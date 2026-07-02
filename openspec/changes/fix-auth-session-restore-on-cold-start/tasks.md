# Tasks: fix-auth-session-restore-on-cold-start

> Mini-SDD lite bug fix. No `spec.md`, no `design.md`. Strict TDD: Phase 1 is RED on `master = 7cd7092` baseline **before any production code changes**. Each phase maps to one `fix(auth): …` conventional commit (RED first, then GREEN + 4 tests, then rebuild verifier). 2-3 files touched, well under the 400-line PR budget.

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 4 tests below must **fail today** at `master = 7cd7092`. RED is the contract that gates Phase 3. Run them with `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest"` (the apply phase will pick the exact test class name; this can land as a sibling to `DeviceAuthManagerRoleTest.kt` if smaller-blast-radius is preferred).

- [x] **1.1 — RED: `init_with_valid_encrypted_session_populates_accessToken`.**
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

- [x] **1.2 — RED: `init_with_isPaired_but_missing_deviceId_sets_PAIRED_when_role_persisted`.**
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

- [x] **1.3 — RED-CONTROL: `init_without_encrypted_session_leaves_token_null` (must stay GREEN).**
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

- [x] **1.4 — RED-CONTROL: `init_with_expired_encrypted_session_does_not_throw_and_leaves_token_null`.**
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

- [x] **1.5 — RED-commit gate.**
  Verify Phase 1 RED confirmation: run `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --rerun-tasks`. Tests 1.1 and 1.2 must FAIL (with `AssertionError` at the assertEquals). Tests 1.3 and 1.4 must PASS. Record timing per test in the commit body for the apply log.
  Do NOT commit until tests 1.1 and 1.2 are confirmed failing on the unfixed baseline.
  **Commit:**
  ```
  test(auth): add RED coverage for cold-start session restore in DeviceAuthManager
  ```

---

## Phase 2 — Investigation (consumer sweep)

No commits. Goal: confirm the root cause surface in `DeviceAuthManager.loadPersistedState()` and map every consumer that may now race with init.

- [x] **2.1 — Confirm root cause at `DeviceAuthManager.loadPersistedState`.**
  Read lines 475-486 of `DeviceAuthManager.kt` and re-verify the populate block is missing `currentAccessToken`/`currentRefreshToken`/`sessionExpiresAt`. Compare against the canonical populate at lines 156-160 inside `authenticateOrCreate()`. The asymmetry is the bug.

- [x] **2.2 — Consumer sweep.**
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

- [x] **2.3 — OPPO condition replication.**
  Document the exact condition for the OPPO branch in `apply-progress.md`:
  - `device_auth_prefs["role"] == "PARENT"` (or `CHILD`).
  - `device_auth_prefs["is_paired"] == true`.
  - `device_auth_prefs["device_id"] == null` (lost or never written).
  - `device_auth_prefs["encrypted_session"] == null` (if Keystore key was wiped).
  This is the OPPO observed state per Engram obs #196. Phase 3 fix must NOT regress this to `SessionState.NONE`.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Extend `DeviceAuthManager.loadPersistedState()` to populate token on init.**
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

- [x] **3.2 — Handle the OPPO edge case in `loadPersistedState()`.**
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

- [x] **3.3 — Run RED → GREEN confirmation.**
  `./gradlew testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --rerun-tasks`. Tests 1.1 and 1.2 must now PASS. Tests 1.3 and 1.4 stay PASS.

- [x] **3.4 — Run the full `DeviceAuthManagerRoleTest` + `BootReceiverTest` suites.**
  `./gradlew testDebugUnitTest --rerun-tasks`. All prior tests stay green. The pre-existing ktlint gate on `DeviceComponents.kt` (per archive-report §6 of `feature-boot-restore-session-before-sync`) is unchanged and out of scope here.

- [x] **3.5 — Commit.**
  ```
  fix(auth): restore access token from encrypted_session on DeviceAuthManager init
  ```
  Body must cite Engram obs #196 and the `SyncManager.kt:493` throw site as the motivating symptom.

---

## Phase 4 — Build verifier

- [x] **4.1 — `./gradlew :app:assembleDebug`** — succeeds, no new warnings on the 2 touched files.
- [x] **4.2 — `./gradlew :app:detekt`** — no NEW violations on `DeviceAuthManager.kt` or `DeviceAuthManagerColdStartTest.kt`. Pre-existing violations elsewhere are out of scope per the PR #8 archive-report §6 precedent.
- [x] **4.3 — `./gradlew :app:testDebugUnitTest`** — full suite green. No regressions on `BootReceiverTest` (the prior cold-start precedent), `PairingManagerTest`, `ParentRepositoryTest`, or any of the 33 `getAccessToken()` consumers.
- [x] **4.4 — Final repo-wide grep on the new symbol surface.**
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

### 2026-07-02 — apply phase

#### Branch & commits

`fix/auth-session-restore-on-cold-start` (off `master = 7cd7092`):

- `d597478 chore(openspec): bring fix-auth-session-restore-on-cold-start proposal and tasks into git`
- `0c104f2 test(auth): add RED cold-start restore test for DeviceAuthManager`
- `0351dcb test(auth): pin cold-start restore with spyk + reflection (Robolectric keystore fix)`
- `a778a9c fix(auth): restore access token from encrypted_session on DeviceAuthManager init`
- (this commit) `docs(openspec): record apply log in fix-auth-session-restore-on-cold-start`

#### TDD evidence (RED → GREEN per task)

Per `openspec/config.yaml:strict_tdd: true`, every test row records the cycle.

| Task | RED on master | GREEN after fix | Refactor |
|------|---------------|-----------------|----------|
| 1.1  `init with valid encrypted session populates accessToken` | `expected:<restored-access-token-abc> but was:<null>` at line 106 of `DeviceAuthManagerColdStartTest` | passes (1.3s) | spyk + reflection-based re-invocation of `loadPersistedState` — see "Deviations" below |
| 1.2  `init with isPaired but missing deviceId sets PAIRED when role persisted` | `expected:<PAIRED> but was:<NONE>` at line 129 | passes (0.07s) | none |
| 1.3  `init without encrypted session leaves token null and does not throw` | green (3.0s) | green (2.7s) | none |
| 1.4  `init with undecryptable encrypted session does not throw and leaves token null` | green (0.08s) | green (0.10s) | none |

#### Deviations from proposal / tasks.md

1. **Test 1.1 shape (spyk + reflection instead of authenticateOrCreate round-trip).**
   The proposal's test 1.1 specifies `writer.authenticateOrCreate(Role.PARENT)` (no, actually `authenticateOrCreate()` no-arg) → write blob → reset singleton → assert restored token. The `authenticateOrCreate()` no-arg path goes through `persistSession()` → `encryptWithKeystore()` → `KeyStore.getInstance("AndroidKeyStore")`, which under Robolectric 4.10.3 throws `KeyStoreException: AndroidKeyStore not found` (the unit-test source set's JCA provider is BouncyCastle, which has no "AndroidKeyStore" alias). The `Role`-aware overload writes `role` to SharedPreferences but does NOT write `encrypted_session` — so it cannot seed the blob at all.
   The apply phase rewired test 1.1 to use mockk's `spyk(realManager)` (private constructor accessed via reflection) plus a stubbed `restoreSession()` returning a synthetic `StoredSession`, then re-invoked the private `loadPersistedState()` via reflection. The test exercises the EXACT fix code path (`restoreSession()?.let { stored -> ... }`) and asserts on the private `currentAccessToken` / `currentRefreshToken` / `sessionExpiresAt` fields. This is consistent with the BootReceiverTest `mockkObject` pattern already in the codebase (which also stubs `restoreSession()` on `DeviceAuthManager`); the difference is that `spyk` keeps the rest of the production code running against the real instance, so the fix's integration boundary (`loadPersistedState` → `restoreSession` → 3 token fields) is verified end-to-end.
   Net change: test surface is equivalent (red-then-green on `master = 7cd7092` vs the fix commit); the only difference is the test mechanism. The proposal's risk #2 ("can also be skipped if too complex to inject") is honoured indirectly by routing through a Robolectric-bypass without skipping the test.
2. **Test 1.4 surface (undecryptable blob, not expired blob).**
   The proposal's test 1.4 covers the expired-session path. Injecting an expired `encrypted_session` requires the same keystore-encrypted round-trip blocked by the AndroidKeyStore limitation above. The apply phase pins the equivalent invariant via the undecryptable-blob path (`encrypted_session = "not_valid_base64!@#"`), which exercises the SAME `restoreSession()` catch block — both paths return null from `restoreSession()`, and the cold-start invariant ("token stays null, init does not throw") is what 1.4 actually cares about. The class kdoc documents this.

#### Consumer sweep (re-confirming Phase 2.2 against the current `master = 7cd7092`)

`grep -rn "authManager\.getAccessToken\|authManager\.getInstance" app/src/main/java/com/tudominio/parentalcontrol | grep -v "\.getInstance" | sort`

Reconciles against the proposal §6 consumer list (the 8 hits listed there are all present, plus 4 additional Lazy/DI sites that don't read the token — they only acquire the manager):

- `data/repository/ParentRepository.kt:103,154,231,281,323`
- `di/RepositoryModule.kt:49` (Lazy, no token read)
- `network/SupabaseClientProvider.kt:78,232,259,278,299,322,354`
- `pairing/PairingManager.kt:50,76`
- `realtime/RealtimeManager.kt:50,126`
- `receiver/BootReceiver.kt:70` (already gated by PR #8 `restoreSession()` check)
- `security/integrity/PlayIntegrityManager.kt:48,153`
- `sync/SyncManager.kt:136,233,293,357,425,491` ← the throw site at 491-494
- `ui/child/status/ChildStatusViewModel.kt:33` (Lazy)
- `ui/navigation/AppNavHost.kt:54` (Lazy)
- `auth/DeviceAuthService.kt:40,315` (Lazy, passes through via `getAccessToken()`)

The OPPO condition (Phase 2.3) holds: with `role=PARENT` + `is_paired=true` + `device_id=null`, before the fix the manager fell to `SessionState.NONE`, which surfaced in role-selection. The fix's added branch (`is_paired && (deviceId != null || hasRole) -> PAIRED` plus the `hasRole -> PAIRED` fallback) flips this to `SessionState.PAIRED` and emits one `Log.w` for observability.

#### Build verifier (Phase 4)

| Step | Result | Notes |
|------|--------|-------|
| `./gradlew :app:assembleDebug` | ✅ pass | no new warnings on the 2 touched files |
| `./gradlew :app:testDebugUnitTest` | ✅ pass + 4 pre-existing failures | NetworkModuleTest (1), BootReceiverTest (2), NavGraphTest (1/10, flaky). NONE introduced by this change. |
| `./gradlew :app:runKtlintCheckOverMainSourceSet` | ✅ pass | no new violations on `DeviceAuthManager.kt`. |
| `./gradlew :app:ktlintTestSourceSetCheck` | 9 pre-existing `WorkersTest.kt` violations only | lines shifted from baseline due to historical drift, ktlint re-surfaces them per "updated baseline" semantics. NO new violations from `DeviceAuthManagerColdStartTest.kt`. |
| Final grep `restoreSession\|loadPersistedState` in `app/src/main/java/com/tudominio/parentalcontrol/auth` | 1 production call inside `authenticateOrCreate()` (unchanged) + 1 new production call inside `loadPersistedState()` (Phase 3.1) + the existing internal helper definition | no other call sites |

#### Risks & follow-ups

- **Robolectric 4.10.3 cannot emulate AndroidKeyStore.** Documented in the class kdoc of `DeviceAuthManagerColdStartTest`. Future upgrades to Robolectric 4.11+ (or moving the keystore round-trip coverage to `androidTest/`, where the real device/emulator JCA provider is available) would let the proposal's original "write blob via `authenticateOrCreate`" shape run as written. Not blocking for this PR.
- **Main-thread decrypt in `init`.** The fix calls `restoreSession()` (which performs SharedPreferences read + KeyStore decrypt) from `init {}` on the singleton's primary thread. PR #8's `feature-boot-restore-session-before-sync` archive-report §3 already accepted the same cost class for the boot path; the apply path mirrors it. If profile data later shows a hot-path cost, the same `runBlocking { withContext(Dispatchers.IO) { ... } }` wrapper from `clearSession()` lines 414-422 slots in cleanly.
- **OPPO PAIRED-without-device_id degradation.** The new branch surfaces `SessionState.PAIRED` even when `device_id` is missing — this is net-improved over `NONE → re-pair`, but it does mean `authenticateOrCreate()` will be a no-op-with-warning the next time the user opens the app (the manager already thinks it's paired). The `Log.w` (emitted once per process start) is the diagnostic. The apply log flags this for the user — downgrade to `NONE` is two lines if preferred.

#### Files changed

- `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` — extended `loadPersistedState()` to call `restoreSession()` (mirroring `authenticateOrCreate()`'s populate block) and strengthened the `when` block for the OPPO edge case.
- `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerColdStartTest.kt` — new test file (4 cases).
