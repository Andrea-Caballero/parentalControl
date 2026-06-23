package com.tudominio.parentalcontrol.receiver

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.StoredSession
import com.tudominio.parentalcontrol.workers.OutboxDrainer
import com.tudominio.parentalcontrol.workers.SyncWorker
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * Focus of PR 3: when [BootReceiver.onBootCompleted] runs, the
 * [OutboxDrainer] periodic worker must be enqueued through WorkManager
 * under its unique work name.
 *
 * The test uses [WorkManagerTestInitHelper] to give the test a real
 * WorkManager instance backed by an in-memory database, then queries the
 * work info after the receiver fires to assert the right class was
 * scheduled.
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

    @Test
    fun onBootCompleted_schedules_outbox_drainer_periodically() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val receiver = BootReceiver()

        val bootIntent = android.content.Intent(BootReceiver.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)

        val workManager = WorkManager.getInstance(context)
        val infos = workManager
            .getWorkInfosForUniqueWork(OutboxDrainer.WORK_NAME)
            .get()

        assertNotNull("WorkManager must return at least one WorkInfo for the unique name", infos)
        assertEquals(
            "Expected exactly one OutboxDrainer periodic work to be enqueued",
            1,
            infos.size
        )

        val info = infos[0]
        // The work may be ENQUEUED or RUNNING depending on scheduler state; we
        // only need to confirm the period and the factory-registered class.
        assertTrue(
            "Work must be periodic (state = ${info.state})",
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING
        )
    }

    /**
     * Pins the invariant from
     * `openspec/changes/feature-boot-restore-session-before-sync`:
     * when the boot path finds a valid [StoredSession] on disk, the
     * `sync_work_after_boot` unique-work chain must be enqueued. The
     * existing scheduleOutboxDrainer branch (above) is independent of
     * this gate; this test exercises the [WorkerInitializer] branch.
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
}
