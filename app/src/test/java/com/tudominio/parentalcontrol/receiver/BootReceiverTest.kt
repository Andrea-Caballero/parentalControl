package com.tudominio.parentalcontrol.receiver

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.StoredSession
import com.tudominio.parentalcontrol.workers.OutboxDrainer
import com.tudominio.parentalcontrol.workers.ReconciliationWorker
import com.tudominio.parentalcontrol.workers.SyncWorker
import com.tudominio.parentalcontrol.workers.WorkScheduler
import com.tudominio.parentalcontrol.workers.WorkerInitializer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Unit tests for [BootReceiver].
 *
 * Focus of `fix-boot-workers-respect-session-gate`: every WorkManager
 * work scheduled from [BootReceiver.onBootCompleted] MUST be gated on
 * a restored session. With a non-null session the boot path enqueues
 * the periodic OutboxDrainer re-arm and the after-boot sync chain;
 * with a null session it cancels the three persisted unique works
 * (sync_work_after_boot, reconciliation_work, outbox_drain_periodic).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Pins the invariant from
     * `openspec/changes/feature-boot-restore-session-before-sync`:
     * when the boot path finds a valid [StoredSession] on disk, the
     * `sync_work_after_boot` unique-work chain must be enqueued. The
     * [WorkerInitializer] branch is what gates this; the new
     * `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`
     * test below additionally pins the OutboxDrainer re-arm.
     */
    @Test
    fun onBootCompleted_with_restored_session_enqueues_sync_after_boot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        val storedSession = StoredSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresAt = 0L,
            deviceId = "test-device",
            userId = "test-user"
        )

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns storedSession

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // WorkerInitializer.initialize(...) is invoked from inside a
        // GlobalScope coroutine; wait for the coroutine to enqueue work
        // before querying WorkManager.
        Thread.sleep(1000L)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_boot")
            .get()

        // WorkScheduler.scheduleSyncAfterBoot enqueues a 3-step chain
        // (SyncWorker -> HeartbeatWorker -> OutboxDrainer) under the
        // unique-work name "sync_work_after_boot"; getWorkInfosForUniqueWork
        // returns one WorkInfo per chain step.
        assertEquals(
            "Expected the sync_work_after_boot chain (3 steps) to be enqueued when restoreSession returns a session",
            3,
            infos.size
        )
    }

    /**
     * Pins the inverse invariant: when restoreSession returns null,
     * no sync chain is enqueued and the receiver logs a warning. This
     * prevents the pre-PR#7 failure mode where SyncWorker would
     * short-circuit to Offline against a DISCONNECTED SupabaseClient.
     */
    @Test
    fun onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // Wait for the GlobalScope coroutine to finish (it either calls
        // WorkerInitializer.initialize or Log.w) before asserting.
        Thread.sleep(1000L)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_boot")
            .get()

        assertEquals(
            "Expected NO sync_work_after_boot work to be enqueued when restoreSession returns null",
            0,
            infos.size
        )

        val warnings = ShadowLog.getLogs()
            .filter { it.tag == "BootReceiver" && it.type == android.util.Log.WARN }
        assertTrue(
            "Expected a Log.w from BootReceiver containing 'no stored session' but got: $warnings",
            warnings.any { it.msg.contains("no stored session") }
        )
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "Session present
     * schedules the sync chain" plus the `outbox-drain` delta scenario
     * "Session restored at boot re-arms the chain".
     *
     * GIVEN a session is restored at boot
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.scheduleOutboxDrainer MUST be invoked (so the
     *      periodic drain survives the reboot)
     * AND WorkerInitializer.initialize MUST be invoked (so the
     *      sync_work_after_boot chain runs)
     * AND no WorkScheduler.cancelWork calls SHALL be issued.
     *
     * The `verifyOrder` assertion pins the gate contract: scheduleOutboxDrainer
     * MUST be invoked AFTER restoreSession returns a non-null session.
     * Before the fix, scheduleOutboxDrainer was called UNCONDITIONALLY (before
     * the session check), so this verifyOrder assertion fails — RED.
     */
    @Test
    fun onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()
        val storedSession = StoredSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresAt = 0L,
            deviceId = "test-device",
            userId = "test-user"
        )

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns storedSession

        // Mock WorkScheduler and WorkerInitializer so the test asserts the
        // exact call surface from BootReceiver, not the WorkManager database
        // contents (already covered by the pre-existing tests). Stubbing
        // WorkerInitializer.initialize as a no-op prevents its body from
        // re-calling WorkScheduler.scheduleOutboxDrainer via
        // scheduleAllPeriodicWork, so the `verify(exactly = 1)` below
        // asserts the direct BootReceiver call only.
        mockkObject(WorkScheduler)
        mockkObject(WorkerInitializer)
        every { WorkerInitializer.initialize(any(), any()) } returns Unit

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // WorkerInitializer.initialize(...) and the gated scheduleOutboxDrainer
        // are dispatched from inside a GlobalScope coroutine; wait for it to
        // finish before verifying the recorded call order.
        Thread.sleep(1000L)

        // 1. OutboxDrainer re-arm MUST be enqueued exactly once.
        verify(exactly = 1) { WorkScheduler.scheduleOutboxDrainer(context) }
        // 2. After-boot sync chain MUST be initialized exactly once with
        //    isAfterBoot=true (which inside WorkerInitializer schedules the
        //    sync_work_after_boot unique-work chain).
        verify(exactly = 1) { WorkerInitializer.initialize(context, true) }
        // 3. Gate contract: scheduleOutboxDrainer MUST be invoked AFTER
        //    restoreSession returns a non-null session. Before the fix,
        //    scheduleOutboxDrainer ran UNCONDITIONALLY (line 57 in the
        //    receiver), so this verifyOrder assertion fails.
        verifyOrder {
            mockAuthManager.restoreSession()
            WorkScheduler.scheduleOutboxDrainer(context)
            WorkerInitializer.initialize(context, true)
        }
        // 4. No cancelWork calls SHALL be issued when a session is restored.
        verify(exactly = 0) { WorkScheduler.cancelWork(any(), any()) }
    }

    /**
     * Pins the `boot-worker-lifecycle` spec scenario "No session cancels
     * persisted works and skips scheduling" plus the `outbox-drain` delta
     * scenarios "No session at boot skips the OutboxDrainer re-arm" and
     * "Cancellation at boot with no session".
     *
     * GIVEN the device has just emitted BOOT_COMPLETED
     * AND DeviceAuthManager.restoreSession() returns null
     * WHEN BootReceiver.onBootCompleted runs
     * THEN WorkScheduler.cancelWork MUST be called for the three persisted
     *      unique works: `${SyncWorker.WORK_NAME}_after_boot`,
     *      ReconciliationWorker.WORK_NAME, OutboxDrainer.WORK_NAME.
     *
     * Before the fix, the else branch only logged a warning — cancelWork
     * was never invoked — so this test fails — RED.
     */
    @Test
    fun onBootCompleted_without_session_cancels_three_boot_unique_works() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()
        val mockAuthManager: DeviceAuthManager = mockk()

        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        every { mockAuthManager.restoreSession() } returns null

        mockkObject(WorkScheduler)

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        // The else branch runs inside a GlobalScope coroutine; wait for it
        // before verifying.
        Thread.sleep(1000L)

        // The boot path MUST cancel the three persisted unique works.
        // Names match WorkScheduler.scheduleSyncAfterBoot
        // (${SyncWorker.WORK_NAME}_after_boot), WorkScheduler.scheduleReconciliation
        // (ReconciliationWorker.WORK_NAME), and WorkScheduler.scheduleOutboxDrainer
        // (OutboxDrainer.WORK_NAME).
        verify(exactly = 1) {
            WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")
        }
        verify(exactly = 1) {
            WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME)
        }
        verify(exactly = 1) {
            WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)
        }
    }
}
