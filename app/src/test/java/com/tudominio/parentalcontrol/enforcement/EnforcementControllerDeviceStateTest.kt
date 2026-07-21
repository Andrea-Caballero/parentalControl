package com.tudominio.parentalcontrol.enforcement

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.data.db.ParentalDatabase
import com.tudominio.parentalcontrol.data.model.PolicyEntity
import com.tudominio.parentalcontrol.time.DefaultTimeProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F2b — RED→GREEN tests for the child `device_state` propagation
 * fix. Pre-fix: the three Room query sites in `EnforcementController`
 * were hardcoded to `"default"` — they never observed the
 * `device_state = "LOCKED"` row written by `SyncManager.applyPolicy`
 * under the real `deviceId` from `authManager.deviceId.value`.
 *
 * Post-fix contract (pinned here):
 *  - `loadCurrentPolicy` / `loadAppPolicies` / `loadGrants` observe
 *    the row for the REAL device id from `authManager.deviceId.value`
 *    (or `"default"` if null).
 *  - When `deviceState = "LOCKED"` for the real device id,
 *    `lockManager.lockNow()` fires.
 *  - When `deviceId` changes from null to a real id, the Flow
 *    re-subscribes and the new row is observed.
 *  - `lastAppliedDeviceState` flips — a LOCK after ACTIVE still
 *    fires.
 *
 * The test wires a real `EnforcementController` via the test seam
 * constructor (test-only `authManager`/`lockManager` injection).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EnforcementControllerDeviceStateTest {

    private lateinit var database: ParentalDatabase
    private lateinit var context: Context
    private lateinit var authManager: DeviceAuthManager
    private lateinit var lockManager: com.tudominio.parentalcontrol.admin.LockManager

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context, ParentalDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        runCatching { database.close() }
        Dispatchers.resetMain()
    }

    /**
     * Test seam — `EnforcementController` exposes a test-only
     * secondary constructor (added with F2b) that injects the
     * `authManager` and `lockManager` mocks. The production
     * `getInstance(Context, ParentalDatabase)` factory is unaffected.
     */
    private fun controllerForDeviceId(
        deviceId: MutableStateFlow<String?>
    ): EnforcementController {
        val am = mockk<DeviceAuthManager>(relaxed = true)
        every { am.deviceId } returns deviceId
        every { am.isPaired() } returns (deviceId.value != null)
        val lm = mockk<com.tudominio.parentalcontrol.admin.LockManager>(relaxed = true)
        every { lm.isAdminActive() } returns true
        authManager = am
        lockManager = lm
        return EnforcementController(
            context = context,
            database = database,
            timeProvider = DefaultTimeProvider(context),
            authManager = am,
            lockManager = lm,
        )
    }

    @Test
    fun `lockNow fires when deviceState is LOCKED for the real device id`() = runTest {
        val deviceId = MutableStateFlow<String?>(null)
        // Seed the LOCKED row for the real device id BEFORE the
        // controller starts observing.
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 6L,
                category_assignments = emptyMap(),
                device_state = "LOCKED"
            )
        )
        val controller = controllerForDeviceId(deviceId)
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        verify(atLeast = 1) { lockManager.lockNow() }
    }

    @Test
    fun `lockNow does NOT fire for ACTIVE state`() = runTest {
        val deviceId = MutableStateFlow<String?>(null)
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 1L,
                category_assignments = emptyMap(),
                device_state = "ACTIVE"
            )
        )
        controllerForDeviceId(deviceId)
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        verify(exactly = 0) { lockManager.lockNow() }
    }

    @Test
    fun `lastAppliedDeviceState flips — subsequent LOCK still fires after ACTIVE`() = runTest {
        val deviceId = MutableStateFlow<String?>(null)
        val controller = controllerForDeviceId(deviceId)
        // LOCK first.
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 1L,
                category_assignments = emptyMap(),
                device_state = "LOCKED"
            )
        )
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        verify(atLeast = 1) { lockManager.lockNow() }
        // Now ACTIVE.
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 2L,
                category_assignments = emptyMap(),
                device_state = "ACTIVE"
            )
        )
        testScheduler.advanceUntilIdle()
        // Now LOCK again — must fire (lastAppliedDeviceState flipped
        // to ACTIVE so the new LOCK is not deduplicated).
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 3L,
                category_assignments = emptyMap(),
                device_state = "LOCKED"
            )
        )
        testScheduler.advanceUntilIdle()
        verify(exactly = 2) { lockManager.lockNow() }
    }

    @Test
    fun `lockNow does not fire for default row when real device id has LOCKED`() = runTest {
        // Defensive: if the controller still queried the legacy
        // "default" id (the pre-fix bug), the LOCKED row for
        // "dev-real" would not be observed and lockNow() would not
        // fire. After the fix, the controller queries the REAL id.
        val deviceId = MutableStateFlow<String?>(null)
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "default",
                version = 99L,
                category_assignments = emptyMap(),
                device_state = "ACTIVE"
            )
        )
        database.policyDao().insertPolicy(
            PolicyEntity(
                device_id = "dev-real",
                version = 1L,
                category_assignments = emptyMap(),
                device_state = "LOCKED"
            )
        )
        controllerForDeviceId(deviceId)
        deviceId.value = "dev-real"
        testScheduler.advanceUntilIdle()
        // The controller must observe the dev-real row, not the
        // default row. So lockNow() fires once.
        verify(exactly = 1) { lockManager.lockNow() }
    }
}
