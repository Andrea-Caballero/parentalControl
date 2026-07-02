# Tasks: fix-auth-session-restore-on-cold-start

> Mini-SDD lite bug fix. No `spec.md`, no `design.md`. Strict TDD: Phase 1 is RED on `master = 7cd7092` baseline **before any production code changes**. Each phase maps to one `fix(auth): …` conventional commit (RED first, then GREEN + 4 tests, then rebuild verifier). 2-3 files touched, well under the 400-line PR budget.

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 4 tests below must **fail today** at `master = 7cd7092`. RED is the contract that gates Phase 3. Run them with `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest"` (the apply phase will pick the exact test class name; this can land as a sibling to `DeviceAuthManagerRoleTest.kt` if smaller-blast-radius is preferred).

- [ ] **1.1 — RED: `init_with_valid_encrypted_session_populates_accessToken`.**
  ```kotlin
  @Test
  fun `init with valid encrypted session populates accessToken`() = runTest {
      val writer = DeviceAuthManager.getInstance(context)
      writer.authenticateOrCreate(Role.PARENT) // writes encrypted_session + role
      val tokenAfterAuth = writer.getAccessToken()
      assertNotNull(tokenAfterAuth)

      // Simulate process death: build a fresh manager on the same Context.
      val coldStart = DeviceAuthManager.getInstance(context)
      assertEquals(
          "Cold start must restore the same access token the first manager issued",
          tokenAfterAuth,
          coldStart.getAccessToken()
      )
  }
  ```
  **Expected TODAY (RED):** FAILS at the `assertEquals` because `loadPersistedState()` (line 475-486) does not call `restoreSession()`.
  Status: RED on `master = 7cd7092` baseline.

- [ ] **1.2 — RED: `init_with_isPaired_but_missing_deviceId_sets_PAIRED_when_role_persisted`.**
  ```kotlin
  @Test
  fun `init with isPaired but missing deviceId sets PAIRED when role persisted`() = runTest {
      context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
          .edit()
          .putString("role", "PARENT")
          .putBoolean("is_paired", true)
          // Note: device_id intentionally NOT written (OPPO simulated eviction)
          .commit()

      val coldStart = DeviceAuthManager.getInstance(context)
      assertEquals(
          "OPPO edge case: PAIRED with role must not fall to NONE",
          SessionState.PAIRED,
          coldStart.sessionState.value
      )
  }
  ```
  **Expected TODAY (RED):** FAILS at the `assertEquals` because the `when` at lines 481-485 falls through to `SessionState.NONE` when `device_id == null` regardless of `is_paired`.
  Status: RED on `master = 7cd7092` baseline.

- [ ] **1.3 — RED-CONTROL: `init_without_encrypted_session_leaves_token_null` (must stay GREEN).**
  ```kotlin
  @Test
  fun `init without encrypted session leaves token null and does not throw`() = runTest {
      context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
          .edit().clear().commit()

      val coldStart = DeviceAuthManager.getInstance(context)
      assertNull("No blob → token must remain null", coldStart.getAccessToken())
      assertEquals(SessionState.NONE, coldStart.sessionState.value)
  }
  ```
  **Expected TODAY:** GREEN. Pins the no-blob path so Phase 3 does not regress it.

- [ ] **1.4 — RED-CONTROL: `init_with_expired_encrypted_session_does_not_throw_and_leaves_token_null`.**
  ```kotlin
  @Test
  fun `init with expired encrypted session does not throw and leaves token null`() = runTest {
      // Write a StoredSession with expiresAt = 1 (1970); restoreSession() returns null on expiry.
      val writer = DeviceAuthManager.getInstance(context)
      writer.authenticateOrCreate(Role.PARENT)
      // No public test seam for StoredSession writes; the apply phase will add one
      // (e.g., a `internal fun seedEncryptedSessionForTest(json: String)` helper)
      // OR construct the blob by hand via SharedPreferences.edit().putString("encrypted_session", encryptWithKeystore(...))
      // — the precise helper is decided in Phase 3 if needed.

      // Build a fresh manager; expect no exception, token null.
      val coldStart = DeviceAuthManager.getInstance(context)
      assertNull(coldStart.getAccessToken())
  }
  ```
  **Expected TODAY:** GREEN. Pins the expiry path so Phase 3 does not regress it.

- [ ] **1.5 — RED-commit gate.**
  Verify Phase 1 RED confirmation: run `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --rerun-tasks`. Tests 1.1 and 1.2 must FAIL (with `AssertionError` at the assertEquals). Tests 1.3 and 1.4 must PASS. Record timing per test in the commit body for the apply log.
  Do NOT commit until tests 1.1 and 1.2 are confirmed failing on the unfixed baseline.
  **Commit:**
  ```
  test(auth): add RED coverage for cold-start session restore in DeviceAuthManager
  ```

---

## Phase 2 — Investigation (consumer sweep)

No commits. Goal: confirm the root cause surface in `DeviceAuthManager.loadPersistedState()` and map every consumer that may now race with init.

- [ ] **2.1 — Confirm root cause at `DeviceAuthManager.loadPersistedState`.**
  Read lines 475-486 of `DeviceAuthManager.kt` and re-verify the populate block is missing `currentAccessToken`/`currentRefreshToken`/`sessionExpiresAt`. Compare against the canonical populate at lines 156-160 inside `authenticateOrCreate()`. The asymmetry is the bug.

- [ ] **2.2 — Consumer sweep.**
  ```bash
  grep -rn "authManager\.getAccessToken\|authManager\.getInstance" \
    app/src/main/java/com/tudominio/parentalcontrol \
    | grep -v "\.getInstance" \
    | sort
  ```
  Expected hits (already known, reconfirm):
  - `sync/SyncManager.kt:136,233,293,357,425,491` (incl. the throw site at 491-494).
  - `network/SupabaseClientProvider.kt:78,232,259,278,299,322,354`.
  - `data/repository/ParentRepository.kt:103,154,231,281,323`.
  - `pairing/PairingManager.kt:50,76`.
  - `realtime/RealtimeManager.kt:50,126`.
  - `security/integrity/PlayIntegrityManager.kt:48,153`.
  - `ui/navigation/AppNavHost.kt:54`.
  - `di/RepositoryModule.kt:49`.
  - `ui/child/status/ChildStatusViewModel.kt:33`.

- [ ] **2.3 — OPPO condition replication.**
  Document the exact condition for the OPPO branch in `apply-progress.md`:
  - `device_auth_prefs["role"] == "PARENT"` (or `CHILD`).
  - `device_auth_prefs["is_paired"] == true`.
  - `device_auth_prefs["device_id"] == null` (lost or never written).
  - `device_auth_prefs["encrypted_session"] == null` (if Keystore key was wiped).
  This is the OPPO observed state per Engram obs #196. Phase 3 fix must NOT regress this to `SessionState.NONE`.

---

## Phase 3 — Fix (GREEN)

- [ ] **3.1 — Extend `DeviceAuthManager.loadPersistedState()` to populate token on init.**
  In `DeviceAuthManager.kt`, after the existing `_sessionState.value = when { ... }` block at line 485, add:
  ```kotlin
  // Cold-start restore: decrypt the persisted session and push it into the
  // in-memory token fields so any `getAccessToken()` consumer that runs
  // before DeviceAuthService.start() sees a non-null token. Mirrors the
  // populate block at lines 156-160 inside `authenticateOrCreate()`.
  restoreSession()?.let { stored ->
      currentAccessToken = stored.accessToken
      currentRefreshToken = stored.refreshToken
      sessionExpiresAt = stored.expiresAt
  }
  ```
  No new API surface: `restoreSession()` is already `internal` (PR #8). `init { loadPersistedState() }` at lines 148-150 is unchanged — the new code lives inside `loadPersistedState()` itself. Confirm `sessionMutex` is NOT needed here (single-threaded Kotlin object init).

- [ ] **3.2 — Handle the OPPO edge case in `loadPersistedState()`.**
  Strengthen the `_sessionState.value = when { ... }` block at lines 481-485 to the prior-`device_id`-loss shape. Wrap the existing branches inside an additional outer guard:
  ```kotlin
  val hasRole = context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
      .contains("role")
  _sessionState.value = when {
      isPaired && (deviceId != null || hasRole) -> SessionState.PAIRED
      deviceId != null -> SessionState.ANONYMOUS
      hasRole -> SessionState.PAIRED // OPPO: role + is_paired without device_id (best-effort, logged below)
      else -> SessionState.NONE
  }
  if (isPaired && deviceId == null) {
      android.util.Log.w(
          "DeviceAuthManager",
          "is_paired=true but device_id missing; falling back to role-aware PAIRED state"
      )
  }
  ```
  Log message is the apply-phase observable; Phase 1 tests 1.2 and 1.3 + 1.4 pin the behavior.

- [ ] **3.3 — Run RED → GREEN confirmation.**
  `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --rerun-tasks`. Tests 1.1 and 1.2 must now PASS. Tests 1.3 and 1.4 stay PASS.

- [ ] **3.4 — Run the full `DeviceAuthManagerRoleTest` + `BootReceiverTest` suites.**
  `./gradlew testDebugUnitTest --rerun-tasks`. All prior tests stay green. The pre-existing ktlint gate on `DeviceComponents.kt` (per archive-report §6 of `feature-boot-restore-session-before-sync`) is unchanged and out of scope here.

- [ ] **3.5 — Commit.**
  ```
  fix(auth): restore access token from encrypted_session on DeviceAuthManager init
  ```
  Body must cite Engram obs #196 and the `SyncManager.kt:493` throw site as the motivating symptom.

---

## Phase 4 — Build verifier

- [ ] **4.1 — `./gradlew :app:assembleDebug`** — succeeds, no new warnings on the 2 touched files.
- [ ] **4.2 — `./gradlew :app:detekt`** — no NEW violations on `DeviceAuthManager.kt` or `DeviceAuthManagerColdStartTest.kt`. Pre-existing violations elsewhere are out of scope per the PR #8 archive-report §6 precedent.
- [ ] **4.3 — `./gradlew :app:testDebugUnitTest`** — full suite green. No regressions on `BootReceiverTest` (the prior cold-start precedent), `PairingManagerTest`, `ParentRepositoryTest`, or any of the 33 `getAccessToken()` consumers.
- [ ] **4.4 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "restoreSession\|loadPersistedState" \
    app/src/main/java/com/tudominio/parentalcontrol/auth
  ```
  Expected: 1 production call to `restoreSession()` inside `authenticateOrCreate()` (unchanged) + 1 new production call inside `loadPersistedState()` (Phase 3.1) + the existing internal helper definition. No other call sites.

---

## Notes

- This change is a 2-file diff in production (modifies `DeviceAuthManager.kt` only; `DeviceAuthManagerColdStartTest.kt` is the new test). Well under the 400-line review budget. If the test class is added as a sibling to `DeviceAuthManagerRoleTest.kt` (apply-phase decision based on ktlint precedence), total touched = 2 production + 1 test.
- **`strict_tdd: true` from `openspec/config.yaml`** is honoured with the 2-commit pattern: test-only Phase 1 commit + production+test Phase 3 commit. Phase 1 RED is the gate.
- **No new capabilities, no spec delta.** Per the proposal §Capabilities section and per the archived `feature-boot-restore-session-before-sync` archive-report §2 precedent, the `parent-auth-session` capability covers UI-driven auth and does not specify init-time restore ordering.
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke for OPPO parity.
- **Reference resolution for the next session**: Engram observation **#196** (`auth/session-restore-cold-start-bug`) is the diagnosis. PR #8 (`feature-boot-restore-session-before-sync`, archive-report §4) is the prior boot-path fix. The OPPO parent-role branch is the novel piece; everything else mirrors PR #8's restore-on-X pattern.
- **If on closer inspection the root cause is different** (e.g., `KeyStore` key rotation making the encrypted blob undecryptable on OPPO specifically, rather than an init-path omission), Phase 3.1/3.2 are the two seams to revisit. The symptom (`getAccessToken()` returns null after cold start with `encrypted_session` blob present) is the agreed starting point.

---

## Apply log

(populated by the apply phase)
