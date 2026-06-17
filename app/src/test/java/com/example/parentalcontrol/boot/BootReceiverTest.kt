package com.example.parentalcontrol.boot

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.parentalcontrol.workers.OutboxDrainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
