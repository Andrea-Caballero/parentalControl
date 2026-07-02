package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the cold-start session-restore path in [DeviceAuthManager].
 *
 * Bug surface: [DeviceAuthManager.loadPersistedState] only restores
 * `_deviceId` and `_sessionState`; it never decrypts the
 * `encrypted_session` blob. After process death, `currentAccessToken` is
 * null until [DeviceAuthManager.authenticateOrCreate] runs, so any
 * consumer that reads `getAccessToken()` between `Application.onCreate`
 * and `DeviceAuthService.start()` throws
 * `IllegalStateException("no access token")` (the throwing site is
 * `SyncManager.kt:491-494` in the OutboxDrainer).
 *
 * The 4 cases below pin the cold-start invariant:
 *  - **1.1 RED** (`init_with_valid_encrypted_session_populates_accessToken`):
 *    a valid `encrypted_session` blob must survive a manager-instance
 *    reset; the restored token must equal the token the first manager
 *    issued. Today it does not — `loadPersistedState` doesn't decrypt.
 *  - **1.2 RED** (`init_with_isPaired_but_missing_deviceId_sets_PAIRED_when_role_persisted`):
 *    OPPO edge case where `is_paired=true` + `role=PARENT` are persisted
 *    but `device_id` is missing. Today the `when` falls through to
 *    `SessionState.NONE`; the bugfix surfaces `SessionState.PAIRED`.
 *  - **1.3 GREEN pin** (`init_without_encrypted_session_leaves_token_null`):
 *    fresh-install path — token must stay null, no exception.
 *  - **1.4 GREEN pin** (`init_with_undecryptable_encrypted_session_leaves_token_null`):
 *    malformed/expired blob path — token must stay null, no exception.
 *    Pinned via the undecryptable-blob path because there is no public
 *    test seam to write an `encrypted_session` whose decrypt succeeds
 *    but carries an expired `expiresAt`. `restoreSession` returns null
 *    in both cases; for the cold-start invariant the distinction doesn't
 *    matter — what matters is "token stays null, init does not throw".
 *
 * Sibling to `DeviceAuthManagerRoleTest`, which covers the role-aware
 * `authenticateOrCreate(role: Role)` overload and does NOT exercise the
 * cold-start path. Both files share the `device_auth_prefs` SharedPreferences
 * namespace; `setUp` clears both the prefs and the
 * [DeviceAuthManager] singleton so each test sees a true cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerColdStartTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe leftover prefs (clean slate).
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Reset the [DeviceAuthManager] companion-level `instance` field so
        // each test sees a fresh manager. The field is private; reflection
        // is the only way to clear it without exposing a test-only API on
        // production code.
        resetManagerInstance()
    }

    private fun freshManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    private fun resetManagerInstance() {
        // The companion-level `instance` field is compiled as a
        // `private static volatile` field on `DeviceAuthManager` itself
        // (the companion methods access it via synthetic accessors), so
        // we reflect on the outer class rather than `DeviceAuthManager.Companion`.
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    /**
     * RED on `master = 7cd7092`: cold start must restore the encrypted token.
     *
     * Uses the no-arg [DeviceAuthManager.authenticateOrCreate] (which goes
     * through `MockSupabaseEngine` under `USE_MOCK_SUPABASE=true` in debug
     * and writes the `encrypted_session` blob via `persistSession`). The
     * role-aware overload writes `role` only — not the blob — and would
     * leave test 1.1 always-GREEN, which defeats the RED gate.
     */
    @Test
    fun `init with valid encrypted session populates accessToken`() = runTest {
        val writer = freshManager()
        writer.authenticateOrCreate()
        val tokenAfterAuth = writer.getAccessToken()
        assertNotNull("seed: token must be set after authenticateOrCreate", tokenAfterAuth)

        // Simulate process death: clear the in-memory singleton. The next
        // getInstance creates a fresh manager whose `init {}` runs
        // `loadPersistedState`. On master, `loadPersistedState` does NOT
        // decrypt `encrypted_session`; this assertion is RED.
        resetManagerInstance()
        val coldStart = freshManager()
        assertEquals(
            "cold start must restore the same access token the first manager issued",
            tokenAfterAuth,
            coldStart.getAccessToken()
        )
    }

    /**
     * RED on `master = 7cd7092`: OPPO edge case where `is_paired=true` +
     * `role=PARENT` are persisted but `device_id` is missing. Today the
     * `when` block at `loadPersistedState` lines 481-485 falls through to
     * `SessionState.NONE`.
     */
    @Test
    fun `init with isPaired but missing deviceId sets PAIRED when role persisted`() = runTest {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("role", "PARENT")
            .putBoolean("is_paired", true)
            // device_id intentionally NOT written (OPPO simulated eviction)
            .commit()

        val coldStart = freshManager()
        assertEquals(
            "OPPO edge case: PAIRED with role must not fall to NONE",
            SessionState.PAIRED,
            coldStart.sessionState.value
        )
    }

    /**
     * CONTROL GREEN (must stay green): fresh-install path. No blob, no
     * throw, token stays null, state stays NONE.
     */
    @Test
    fun `init without encrypted session leaves token null and does not throw`() = runTest {
        val coldStart = freshManager()
        assertNull("No blob → token must remain null", coldStart.getAccessToken())
        assertEquals(SessionState.NONE, coldStart.sessionState.value)
    }

    /**
     * CONTROL GREEN (must stay green): malformed blob path. The decrypt
     * helper throws, [DeviceAuthManager.restoreSession] returns null via
     * the `catch` block, and `loadPersistedState` propagates that null to
     * `currentAccessToken`. Pinned here so the fix in Phase 3 does not
     * regress this path (e.g. by adding a crashy `.also { throw }`).
     */
    @Test
    fun `init with undecryptable encrypted session does not throw and leaves token null`() = runTest {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("encrypted_session", "not_valid_base64!@#")
            .commit()

        val coldStart = freshManager()
        assertNull("Malformed blob → token must remain null", coldStart.getAccessToken())
    }
}
