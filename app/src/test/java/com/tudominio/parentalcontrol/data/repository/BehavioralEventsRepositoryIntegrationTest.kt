package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import androidx.room.Room
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.auth.Role
import com.tudominio.parentalcontrol.data.db.BehavioralEventDao
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import com.tudominio.parentalcontrol.viewmodel.BehaviorLogViewModel
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * RED→GREEN coverage for the integration gap discovered during the 4th live
 * test session (2026-07-08): after the parent taps "Soy el padre" on
 * OnboardingScreen, the synthetic parent auth path
 * (`DeviceAuthManager.authenticateOrCreate(Role.PARENT)`) writes `role` +
 * `synthetic_access_token` to `device_auth_prefs` but **does NOT** write
 * `parent_id`. The downstream `BehaviorLogViewModel` reads
 * `authManager.getParentId().orEmpty()` → empty string → the DAO read filter
 * `WHERE parent_id = :parentId` never matches the fixture's
 * `parent_id = "parent-demo"` rows. The UI shows "Sin eventos" instead of
 * the 5 seeded events.
 *
 * PR A's 4 unit tests in [BehavioralEventsRepositoryTest] covered the
 * wire-shape contract in isolation (they inject `parentId = "parent-demo"`
 * directly into `repository.refresh(...)`, bypassing the auth-manager
 * read). PR B's 8 Compose tests seeded the DAO directly via
 * `dao.insertAll(...)`. Neither covered the
 * `authenticateOrCreate(Role.PARENT)` → `vm.events` end-to-end path. This
 * test is the seam that was missing.
 *
 * **RED TODAY**: `vm.events.first()` returns an empty list because the DAO
 * filter is `WHERE parent_id = ''` (no row has empty parent_id), even
 * though the mock wrote 5 rows with `parent_id = "parent-demo"`. The test
 * fails on `assertEquals(5, events.size)`.
 *
 * **GREEN AFTER FIX**: a 1-line change in
 * `DeviceAuthManager.authenticateOrCreate(Role.PARENT)` writes
 * `parent_id = "parent-demo"` (or a constant from
 * [MockSupabaseEngine.MOCK_PARENT_ID]) alongside the existing `role` +
 * `synthetic_access_token` writes. The VM then reads the correct parentId,
 * the refresh URL carries `parent_id=eq.parent-demo`, the mock returns the
 * 5 events, the DAO upserts them with `parentId = "parent-demo"`, and the
 * DAO flow emits 5 rows to the VM.
 *
 * Robolectric + real `MockSupabaseEngine` (serves the real
 * `behavioral_events.json` fixture) + real Room in-memory DB. Mirrors the
 * `BehavioralEventsRepositoryTest` setup but swaps the hand-rolled
 * `MockEngine` for the production `MockSupabaseEngine.httpClient` so the
 * fixture-parsing path is exercised end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class BehavioralEventsRepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: ParentalDatabase
    private lateinit var dao: BehavioralEventDao
    private lateinit var authManager: DeviceAuthManager
    private val openClients = mutableListOf<HttpClient>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = RuntimeEnvironment.getApplication()
        authManager = DeviceAuthManager.getInstance(context)
        database = Room.inMemoryDatabaseBuilder(
            context,
            ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.behavioralEventDao()
    }

    @After
    fun tearDown() {
        // Wipe the synthetic parent prefs the test wrote so sibling tests
        // start from a clean slate (DeviceAuthManager is a process-wide
        // singleton).
        authManager.clearSession()
        Dispatchers.resetMain()
        openClients.forEach { runCatching { it.close() } }
        database.close()
    }

    /**
     * End-to-end RED test: simulate the parent's live auth path
     * (`authenticateOrCreate(Role.PARENT)` — the one the live test's
     * "Soy el padre" CTA actually triggers), wire the real
     * [MockSupabaseEngine] as the HTTP client, build the VM, and assert
     * the 5 fixture events surface in `vm.events`.
     *
     * Fails today on `assertEquals(5, events.size)` because
     * `getParentId()` returns null → VM uses empty string → DAO filter
     * `WHERE parent_id = ''` returns 0 rows even though the mock wrote
     * 5 rows with `parent_id = "parent-demo"`.
     */
    @Test
    fun fixture_events_surface_in_viewmodel_after_synthetic_parent_auth() = runTest {
        // Arrange: simulate the live-device parent auth path exactly.
        val authResult = authManager.authenticateOrCreate(Role.PARENT)
        assertTrue(
            "Synthetic parent auth must succeed, got $authResult",
            authResult.isSuccess
        )

        // The repo requires a non-null access token; the synthetic path
        // already sets one in `currentAccessToken` (verified by the
        // authResult.isSuccess assert).
        assertNotNull(
            "After authenticateOrCreate(Role.PARENT), getAccessToken() must be non-null",
            authManager.getAccessToken()
        )

        // Wire the real MockSupabaseEngine — serves the real
        // behavioral_events.json fixture (5 events, parent_id="parent-demo").
        val realMockClient: HttpClient = MockSupabaseEngine(context).httpClient
        openClients.add(realMockClient)
        val provider = SupabaseClientProvider(context, injectedClient = realMockClient)
        val repository = BehavioralEventsRepository(provider, dao, authManager)

        // Act: construct the VM (init { refresh() } fires automatically).
        val vm = BehaviorLogViewModel(repository, authManager)

        // Assert: wait for the events flow to emit (refresh + DAO upsert +
        // flowByParent re-emission is async). 5s is generous — typical
        // execution is <500 ms under UnconfinedTestDispatcher.
        val events = withTimeout(5_000) { vm.events.first() }

        // RED today: events.size == 0 because parentId="" filters out
        // the fixture's parent_id="parent-demo" rows.
        // GREEN after fix: events.size == 5.
        assertEquals(
            "VM must surface the 5 fixture events after synthetic parent auth; " +
                "got $events (parentId='${authManager.getParentId()}')",
            5,
            events.size
        )
    }
}