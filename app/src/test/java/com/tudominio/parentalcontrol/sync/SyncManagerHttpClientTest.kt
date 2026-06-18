package com.tudominio.parentalcontrol.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.OutboxEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * PR 3: verifies that [SyncManager.sendOutboxItem] returns
 * [OutboxSendResult.RetryableFailure] when the HTTP client has not been
 * initialized, instead of silently no-op'ing (the previous behavior was a
 * `httpClient?.post(...)` chain that returned `null`, which the boolean
 * overload collapsed to `false` — indistinguishable from a real network
 * drop).
 *
 * Lives in its own file so the `SyncManagerTest.kt` line numbers stay
 * stable for the ktlint baseline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncManagerHttpClientTest {

    private lateinit var context: Context
    private lateinit var db: ParentalDatabase

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val fresh = Room.inMemoryDatabaseBuilder(context, ParentalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runCatching {
            val field = ParentalDatabase::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            field.get(null)?.let { (it as ParentalDatabase).close() }
            field.set(null, fresh)
        }
        db = fresh
    }

    @After
    fun teardown() {
        runCatching { db.close() }
        runCatching {
            val field = ParentalDatabase::class.java.getDeclaredField("INSTANCE")
            field.isAccessible = true
            field.set(null, null)
        }
    }

    @Test
    fun sendOutboxItem_returns_retryable_failure_when_http_client_is_null() = runBlocking {
        val syncManager = newSyncManagerViaReflection(context, db)
        // httpClient is null by default — we never call setHttpClient.

        val item = OutboxEntity(
            id = UUID.randomUUID(),
            tipo = "TIME_REQUEST",
            payload_json = "{}",
            dedup_key = null,
            retries = 0,
            created_at = "2026-06-04T12:00:00Z",
            server_date = "2026-06-04"
        )

        val result = syncManager.sendOutboxItem(item)

        assertTrue(
            "sendOutboxItem must return RetryableFailure when httpClient is null, got $result",
            result is OutboxSendResult.RetryableFailure
        )
    }

    /**
     * The real [SyncManager] constructor is private. Tests that need to
     * exercise its public surface use reflection to instantiate it with
     * the in-memory [ParentalDatabase] from [setup].
     */
    private fun newSyncManagerViaReflection(context: Context, db: ParentalDatabase): SyncManager {
        val constructor = SyncManager::class.java
            .getDeclaredConstructor(Context::class.java, ParentalDatabase::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(context, db)
    }
}
