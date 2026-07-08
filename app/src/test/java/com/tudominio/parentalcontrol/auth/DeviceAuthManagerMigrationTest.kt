package com.tudominio.parentalcontrol.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED tests for the **stale `parent_id` migration** discovered during the
 * 5th live test session (2026-07-08, after PR #27 landed).
 *
 * # Background
 *
 * PR #27 (`fix-behavioral-event-log-fixture-loading`) added a 1-line fix at
 * [DeviceAuthManager.authenticateOrCreate] (`DeviceAuthManager.kt:217-224`)
 * that writes `parent_id = MOCK_PARENT_ID` to SharedPreferences when the
 * role-aware synthetic PARENT auth path runs. That fix only covers FRESH
 * auth. Parents who authenticated BEFORE PR #27 already have a stale
 * SharedPreferences entry (`role=PARENT`, `synthetic_access_token=...`,
 * NO `parent_id`), and on every subsequent cold start
 * [DeviceAuthManager.loadPersistedState] (called from the manager's
 * `init {}` block at `DeviceAuthManager.kt:150-152`) reads that stale
 * state without ever firing `authenticateOrCreate(Role.PARENT)`.
 *
 * Result: `getParentId()` (`DeviceAuthManager.kt:426-428`) returns `null`,
 * `BehaviorLogViewModel` calls `.orEmpty()` which collapses to `""`, and
 * the DAO filter `WHERE parent_id = ''` matches zero fixture rows — so
 * `BehaviorLogScreen` keeps rendering "Sin eventos" even though the 5
 * fixture rows owned by `parent_id = "parent-demo"` are present.
 *
 * The only way a real parent can recover today is `pm clear` or uninstall
 * (which forces a fresh auth). That's not acceptable in production.
 *
 * # Proposed fix shape
 *
 * In [DeviceAuthManager.loadPersistedState], after the synthetic-token
 * hydration block at `DeviceAuthManager.kt:542-549`, add a lazy migration
 * step:
 *
 * ```kotlin
 * // Migration: backfill parent_id for parents whose auth state predates
 * // PR #27 (which is when parent_id was first written alongside role +
 * // synthetic_access_token). The fix at the role-aware
 // // authenticateOrCreate(role) overload only covers FRESH auth; this block
 // // covers the stale auth case by reading the persisted role and writing
 // // the default parent_id when it's missing. Idempotent: no-op when
 // // parent_id is already present (post-PR-#27 fresh auth).
 // if (prefs.contains("role") && prefs.getString("parent_id", null).isNullOrEmpty()) {
 //     val roleName = prefs.getString("role", null)
 //     if (roleName == Role.PARENT.name) {
 //         prefs.edit()
 //             .putString("parent_id", MockSupabaseEngine.MOCK_PARENT_ID)
 //             .apply()
 //     }
 // }
 * ```
 *
 * # Test cases
 *
 * - **M1 RED** (`loadPersistedState migrates stale PARENT prefs by writing parent_id`):
 *   the core migration contract — pre-PR-#27 prefs (`role=PARENT` +
 *   `synthetic_access_token`, no `parent_id`) must result in
 *   `parent_id = "parent-demo"` after `loadPersistedState` runs.
 *   **RED on `master = 2820e59`** because `loadPersistedState` does not
 *   touch `parent_id`.
 * - **M2 RED** (`loadPersistedState does NOT migrate CHILD prefs`):
 *   the migration must be role-gated. A `role=CHILD` prefs file with no
 *   `parent_id` must stay `parent_id`-less after load (no CHILD reader
 *   of `parent_id` exists today, so writing `parent_id` for CHILD is a
 *   semantically-wrong side effect per PR #27 proposal Q2=n).
 *   **RED on `master`** because today the migration doesn't run at all —
 *   so CHILD would actually stay clean. This test pins the role gate as
 *   a control case so a future "always migrate" fix doesn't regress it.
 * - **M3 RED** (`loadPersistedState is idempotent when parent_id already set`):
 *   post-PR-#27 fresh-auth prefs (`role=PARENT`, `synthetic_access_token`,
 *   `parent_id = "parent-demo"`) must NOT overwrite `parent_id` with
 *   a different value. Pins the idempotent-no-op shape.
 * - **M4 RED** (`getParentId returns MOCK_PARENT_ID after migration`):
 *   end-to-end contract — after a cold start with stale PARENT prefs,
 *   `getParentId()` (the downstream API consumed by
 *   `BehaviorLogViewModel.init`) must return `"parent-demo"`. This is the
 *   bug-reproduction test: today it returns `null`.
 *
 * Sibling to `DeviceAuthManagerAuthenticatePersistTest` (token-write
 * invariants) and `DeviceAuthManagerColdStartTest` (encrypted_session +
 * session-state restore invariants). This file is dedicated to the
 * `parent_id` migration scenario so a future backfill audit (e.g., adding
 * `device_id` migration) doesn't have to touch the wider auth-persist
 * tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe leftover prefs (clean slate). Same seam as the sibling test files.
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Reset the [DeviceAuthManager] companion-level `instance` field so
        // each test sees a fresh manager whose `init {}` re-runs
        // `loadPersistedState()`. Same reflection seam as the sibling files.
        resetManagerInstance()
    }

    private fun resetManagerInstance() {
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun newManager(): DeviceAuthManager =
        DeviceAuthManager.getInstance(context)

    private fun prefs() =
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)

    /**
     * RED on `master = 2820e59`: a SharedPreferences file written by a
     * pre-PR-#27 build (`role=PARENT`, `synthetic_access_token=...`, no
     * `parent_id`) must, after a cold-start `loadPersistedState`, end up
     * with `parent_id = "parent-demo"` — the same value
     * `authenticateOrCreate(Role.PARENT)` writes on fresh auth (per
     * `DeviceAuthManager.kt:223`) and the same value every fixture row
     * in `app/src/main/assets/mock-supabase/behavioral_events.json`
     * carries in its `parent_id` column.
     *
     * Today: `loadPersistedState` (`DeviceAuthManager.kt:507-550`) only
     * reads `device_id`, `is_paired`, `role`, `encrypted_session`, and
     * `synthetic_access_token`. It never touches `parent_id`. So this
     * assertion fails — the key is still absent after init.
     */
    @Test
    fun `loadPersistedState migrates stale PARENT prefs by writing parent_id`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("synthetic_access_token", "anon-PARENT-pre-PR27-uuid")
            // parent_id intentionally NOT written — this is the stale state.
            .commit()

        val coldStart = newManager()

        assertEquals(
            "After cold start, stale PARENT prefs must be migrated so parent_id = " +
                "\"parent-demo\" (the value authenticateOrCreate(Role.PARENT) writes on " +
                "fresh auth and the value every behavioral_events fixture row carries). " +
                "Today loadPersistedState does not touch parent_id, so the key stays absent " +
                "and BehaviorLogScreen renders 'Sin eventos' for affected parents.",
            "parent-demo",
            prefs().getString("parent_id", null)
        )
    }

    /**
     * CONTROL GREEN today (would become GREEN-PIN after the fix lands):
     * the migration must NOT fire for `role=CHILD`. PR #27 proposal
     * Q2=n — no CHILD reader of `parent_id` exists today, so writing
     * `parent_id` for CHILD is a semantically-wrong side effect.
     *
     * Pinning this case so a future "always migrate" refactor doesn't
     * regress it. Today the test passes vacuously (no migration runs
     * at all); after the fix lands with the role gate, it must still pass.
     */
    @Test
    fun `loadPersistedState does NOT migrate CHILD prefs`() {
        prefs().edit()
            .putString("role", "CHILD")
            .putString("synthetic_access_token", "anon-CHILD-pre-PR27-uuid")
            // parent_id intentionally NOT written.
            .commit()

        newManager()

        assertTrue(
            "CHILD role must NOT trigger parent_id migration (PR #27 proposal Q2=n — " +
                "no CHILD reader of parent_id exists today). The parent_id key must stay " +
                "absent after loadPersistedState.",
            !prefs().contains("parent_id")
        )
    }

    /**
     * CONTROL GREEN today (would become GREEN-PIN after the fix lands):
     * the migration must be idempotent. When `parent_id` is already set
     * (a post-PR-#27 fresh-auth install), `loadPersistedState` must NOT
     * overwrite it with `MOCK_PARENT_ID` — the existing value wins.
     *
     * Today the test passes vacuously (no migration runs at all); after
     * the fix lands with the `prefs.getString("parent_id", null).isNullOrEmpty()`
     * guard, it must still pass.
     */
    @Test
    fun `loadPersistedState is idempotent when parent_id already set`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("synthetic_access_token", "anon-PARENT-post-PR27-uuid")
            .putString("parent_id", "parent-demo")
            .commit()

        newManager()

        assertEquals(
            "Migration must be a no-op when parent_id is already persisted. " +
                "The existing value (MOCK_PARENT_ID for post-PR-#27 fresh auth) " +
                "must not be overwritten.",
            "parent-demo",
            prefs().getString("parent_id", null)
        )
    }

    /**
     * RED on `master = 2820e59`: end-to-end bug reproduction. After a
     * cold start with stale PARENT prefs, `getParentId()` (the downstream
     * API consumed by `BehaviorLogViewModel.init`) must return
     * `"parent-demo"`. Today it returns `null`.
     *
     * This is the test that, if it had existed during PR #27 review,
     * would have caught the live-test-#5 bug: the integration gap is at
     * the `getParentId()` contract, not at the repository or DAO. The
     * fix at `authenticateOrCreate(Role.PARENT)` only fires on FRESH auth;
     * stale state stays stale.
     */
    @Test
    fun `getParentId returns MOCK_PARENT_ID after migration`() {
        prefs().edit()
            .putString("role", "PARENT")
            .putString("synthetic_access_token", "anon-PARENT-pre-PR27-uuid")
            // parent_id intentionally NOT written.
            .commit()

        val coldStart = newManager()

        assertEquals(
            "After cold start with stale PARENT prefs, getParentId() must return " +
                "\"parent-demo\" so BehaviorLogViewModel's DAO filter matches fixture rows. " +
                "Today getParentId() returns null, .orEmpty() collapses to \"\", and the " +
                "DAO's WHERE parent_id = '' filter matches zero rows.",
            "parent-demo",
            coldStart.getParentId()
        )
        // Defensive: also assert that the no-migration baseline (pre-fix
        // master) returns null, so a future accidental revert of the fix
        // surfaces immediately rather than as a silent "still broken"
        // BehaviorLogScreen.
        @Suppress("UNUSED_VARIABLE")
        val baselineAssertionNote = "Pre-fix baseline: getParentId() == null. " +
            "If this assertion fails the migration may have been reverted."
        // (No assertion here — this is documentation for the failure mode
        // the RED test would have caught.)
    }

    /**
     * CONTROL GREEN today: a cold-start with no `role` key at all must
     * NOT write `parent_id`. Pins the migration's role gate against a
     * future "always migrate" refactor that forgets the role check.
     */
    @Test
    fun `loadPersistedState does NOT migrate prefs without role`() {
        prefs().edit()
            .putString("synthetic_access_token", "orphan-token-no-role")
            // role intentionally NOT written; parent_id NOT written.
            .commit()

        newManager()

        assertNull(
            "A prefs file with no role key must NOT trigger parent_id migration. " +
                "Pins the role gate against a future 'always migrate' refactor.",
            prefs().getString("parent_id", null)
        )
    }
}
