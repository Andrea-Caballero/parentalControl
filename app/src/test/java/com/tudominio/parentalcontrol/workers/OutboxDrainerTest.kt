package com.tudominio.parentalcontrol.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import com.tudominio.parentalcontrol.outbox.OutboxManager
import com.tudominio.parentalcontrol.sync.OutboxSendResult
import com.tudominio.parentalcontrol.sync.SyncManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Unit tests for [OutboxDrainer].
 *
 * The drainer is the new periodic worker that pulls pending `outbox` rows,
 * hands them to [SyncManager.sendOutboxItem], and marks the row state
 * according to the result (processed / retries++ / processed-on-permanent).
 *
 * Mocks: [SyncManager] (its sealed-result `sendOutboxItem` and the
 * `httpClient` field) is stubbed via mockk so each branch is driven
 * deterministically. A real in-memory Room database backs the outbox table
 * so the DAO queries the worker triggers see realistic data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutboxDrainerTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase
    private lateinit var outboxManager: OutboxManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Fresh in-memory DB per test, so the rows from one test never leak
        // into the next. We bypass ParentalDatabase.getInstance by setting the
        // private INSTANCE field directly via reflection.
        val fresh = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runCatching {
            val field = ParentalDatabase::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            val old = field.get(null) as? ParentalDatabase
            old?.close()
            field.set(null, fresh)
        }
        // Reset OutboxManager singleton so it picks up the new in-memory DB.
        runCatching {
            val field = OutboxManager::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        }
        db = fresh
        outboxManager = OutboxManager.getInstance(context)
    }

    @After
    fun teardown() {
        runCatching { db.close() }
        runCatching {
            val appDbField = ParentalDatabase::class.java.getDeclaredField("INSTANCE")
            appDbField.isAccessible = true
            appDbField.set(null, null)
        }
        runCatching {
            val outboxField = OutboxManager::class.java.getDeclaredField("instance")
            outboxField.isAccessible = true
            outboxField.set(null, null)
        }
    }

    private fun newWorker(syncManager: SyncManager): OutboxDrainer {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? {
                return OutboxDrainer(appContext, workerParameters, outboxManager, syncManager)
            }
        }
        return TestListenableWorkerBuilder
            .from(context, OutboxDrainer::class.java)
            .setWorkerFactory(factory)
            .build() as OutboxDrainer
    }

    private fun insertOutboxItem(
        tipo: String = "TIME_REQUEST",
        payload: String = "{}",
        retries: Int = 0
    ): OutboxEntity {
        val item = OutboxEntity(
            id = UUID.randomUUID(),
            tipo = tipo,
            payload_json = payload,
            dedup_key = null,
            retries = retries,
            created_at = "2026-06-04T12:00:00Z",
            server_date = "2026-06-04"
        )
        runBlocking { db.outboxDao().insertOutboxItem(item) }
        return item
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    fun drainOutbox_marks_items_as_processed_on_success() = runBlocking {
        insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.httpClient } returns mockk(relaxed = true)
        coEvery { syncManager.sendOutboxItem(any()) } returns OutboxSendResult.Success

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        // Item is no longer pending (processed = 1). getPendingItems filters processed = 0.
        val updated = db.outboxDao().getPendingItems(10, 50)
        assertEquals(0, updated.size)
    }

    @Test
    fun drainOutbox_increments_retries_on_retryable_failure() = runBlocking {
        insertOutboxItem(retries = 0)
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.httpClient } returns mockk(relaxed = true)
        coEvery { syncManager.sendOutboxItem(any()) } returns
            OutboxSendResult.RetryableFailure(IllegalStateException("network down"))

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        val pending = db.outboxDao().getPendingItems(10, 50)
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].retries)
        assertFalse("Item should not be marked processed on retryable failure", pending[0].processed)
    }

    @Test
    fun drainOutbox_marks_items_as_processed_on_permanent_failure() = runBlocking {
        insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.httpClient } returns mockk(relaxed = true)
        coEvery { syncManager.sendOutboxItem(any()) } returns
            OutboxSendResult.PermanentFailure(statusCode = 400)

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val pending = db.outboxDao().getPendingItems(10, 50)
        assertEquals(0, pending.size)
    }

    @Test
    fun drainOutbox_throws_retryable_failure_when_http_client_is_null() = runBlocking {
        insertOutboxItem()
        val syncManager: SyncManager = mockk(relaxed = false)
        coEvery { syncManager.httpClient } returns null

        val worker = newWorker(syncManager)
        val result = worker.doWork()

        // The worker must NOT silently succeed when the HTTP client is null;
        // it must return Result.retry() so WorkManager backs off and retries.
        assertEquals(ListenableWorker.Result.retry(), result)
    }
}
