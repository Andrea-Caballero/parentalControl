package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the role-aware `authenticateOrCreate(role: Role)` overload (T3 of
 * `hotfix-parent-auth-session`).
 *
 * The synthetic hotfix issues a local JWT-shaped token of the form
 * `anon-${role}-${uuid}` and persists the `role` next to `encrypted_session`
 * in `device_auth_prefs` (per design §D4). The existing no-arg
 * `authenticateOrCreate()` is kept for the callers in
 * [com.tudominio.parentalcontrol.network.SupabaseClientProvider] and
 * [com.tudominio.parentalcontrol.auth.DeviceAuthService] — only the new
 * `Role`-aware overload is exercised here.
 *
 * The test uses Robolectric + a real `Context` because the persistence path
 * goes through `Context.getSharedPreferences` (Keystore-backed
 * `encrypted_session` is the no-arg path; the new overload writes a plain
 * `role` key alongside it, which SharedPreferences handles).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerRoleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any leftover prefs from a previous test run. Use both
        // `clear()` (removes all keys) and `remove("role")` (defensive) and
        // `commit()` (synchronous). Robolectric's `apply()` can be deferred
        // to the main looper; `commit()` flushes immediately so the next
        // `getString()` in the test body sees a clean slate.
        val prefs = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().remove("role").commit()
        prefs.edit().clear().commit()
    }

    private fun newManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    @Test
    fun `authenticateOrCreate with PARENT returns success and persists role PARENT`() = runTest {
        val manager = newManager()

        val result = manager.authenticateOrCreate(Role.PARENT)

        assertTrue("Expected success, got $result", result.isSuccess)
        assertNotNull("getAccessToken() must be non-null after auth", manager.getAccessToken())
        assertEquals("Role must be persisted as PARENT", Role.PARENT, manager.getRole())
    }

    @Test
    fun `authenticateOrCreate with CHILD returns success and persists role CHILD`() = runTest {
        val manager = newManager()

        val result = manager.authenticateOrCreate(Role.CHILD)

        assertTrue("Expected success, got $result", result.isSuccess)
        assertNotNull("getAccessToken() must be non-null after auth", manager.getAccessToken())
        assertEquals("Role must be persisted as CHILD", Role.CHILD, manager.getRole())
    }

    @Test
    fun `synthetic token starts with anon and embeds role name`() = runTest {
        val manager = newManager()

        manager.authenticateOrCreate(Role.PARENT)

        val token = manager.getAccessToken()
        assertNotNull("Token must be set", token)
        assertTrue(
            "Token must start with 'anon-', got $token",
            token!!.startsWith("anon-")
        )
        assertTrue(
            "Token must embed the role, got $token",
            token.contains("PARENT")
        )
    }

    @Test
    fun `role is read back from device_auth_prefs after a fresh manager instance`() = runTest {
        val firstManager = newManager()
        firstManager.authenticateOrCreate(Role.PARENT)

        // The shared prefs are persisted; reading the role back through a
        // fresh manager (the one that the next process start would get) must
        // return PARENT.
        val secondManager = newManager()
        assertEquals(
            "Persisted role must survive a manager instance boundary",
            Role.PARENT,
            secondManager.getRole()
        )
    }

    @Test
    fun `getRole returns the role persisted by the last authenticateOrCreate call`() = runTest {
        val manager = newManager()

        // First auth as CHILD, then re-auth as PARENT — getRole must reflect
        // the most recent call (not the first one). This pins the
        // "last-write-wins" semantics of the persistence path.
        manager.authenticateOrCreate(Role.CHILD)
        assertEquals(Role.CHILD, manager.getRole())

        manager.authenticateOrCreate(Role.PARENT)
        assertEquals(
            "Re-auth must overwrite the persisted role",
            Role.PARENT,
            manager.getRole()
        )
    }
}
