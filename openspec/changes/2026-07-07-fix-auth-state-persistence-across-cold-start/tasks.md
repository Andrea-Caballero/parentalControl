# Tasks: fix-auth-state-persistence-across-cold-start

> Mini-SDD lite bug fix. No `specs/` (user deferred the spec delta per Q4=d — engram #280; RED test is the contract). No `design.md` (user picked "skip design"; `proposal.md` is the design-of-record). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = f54f2b0` baseline **before any production code changes**. Each phase maps to one conventional commit. Single PR, ~73 LoC (8 production + 65 tests), well under the 400-line review budget.

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~73 LoC (8 production + 65 tests) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-always |
| Chain strategy | n/a (single PR) |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | `synthetic_access_token` cleartext write/read + 2 RED→GREEN + 4 NEW GREEN | PR 1 | base = `master`; 1 modified production file (`auth/DeviceAuthManager.kt`) + 1 modified test file (`auth/DeviceAuthManagerAuthenticatePersistTest.kt`) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 2 tests below must **fail today** at `master = f54f2b0`. They are the existing acceptance contract from the explore phase (engram #278). RED is the gate that conditions Phase 3. No production code may be written until these failures are confirmed on the unfixed baseline.

Run them with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerAuthenticatePersistTest" --rerun-tasks`.

- [ ] **1.1 — RED (existing) `authenticateOrCreate with PARENT persists access token to prefs for cold-start hydration`** at `DeviceAuthManagerAuthenticatePersistTest.kt:121`. Warm `authenticateOrCreate(Role.PARENT)`; assert `device_auth_prefs` contains either `synthetic_access_token` or `encrypted_session`. **Expected TODAY**: FAILS — the role-aware overload at `DeviceAuthManager.kt:193-213` only writes `role=PARENT` (lines 203-206); the token lives only in `currentAccessToken` (line 197), which is reset by process death. The `prefs.all.keys` set is `{"role"}`; the `firstOrNull { key == "synthetic_access_token" || key == "encrypted_session" }` returns null; `assertNotNull` fails. RED on `master = f54f2b0` baseline.

- [ ] **1.2 — RED (existing) `cold start after authenticateOrCreate with PARENT restores accessToken`** at `DeviceAuthManagerAuthenticatePersistTest.kt:175`. First manager calls `authenticateOrCreate(Role.PARENT)`; sanity-asserts `getAccessToken()` non-null. Reset singleton (`resetManagerInstance`); create a second manager (simulates process death + cold start); assert `getAccessToken()` non-null and equal to the first token. **Expected TODAY**: FAILS — `loadPersistedState()` (lines 475-503) reads `device_id` / `is_paired` / `role` for `_sessionState` and calls `restoreSession()` (line 498), but the synthetic path never wrote an `encrypted_session` blob. `restoreSession()` returns null (line 459 short-circuits on missing key). `currentAccessToken` stays null. `assertNotNull` on the cold-start token fails. RED on `master = f54f2b0` baseline.

- [ ] **1.3 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerAuthenticatePersistTest" --rerun-tasks`. T1.1 + T1.2 MUST FAIL. Record timing per test. Do NOT commit until the failures are confirmed on the unfixed baseline.
  **Commit (RED only — no production code):**
  ```
  test(auth): add RED coverage for synthetic-parent cold-start token persistence
  ```
  *(Optional: this RED file was added in the explore phase already — engram #278 / commit landed at the explore stage. Verify the commit exists on `master` before starting Phase 3; skip the commit step if it's already in `git log master`.)*

---

## Phase 2 — Investigation (no commits)

- [ ] **2.1 — Confirm root cause at `authenticateOrCreate(role: Role)` (`DeviceAuthManager.kt:193-213`).** Re-verify that the role-aware overload builds a `token = "anon-${role.name}-${java.util.UUID.randomUUID()}"` (line 196), assigns it to `currentAccessToken` (line 197), and writes only `role` to `device_auth_prefs` (lines 203-206). The `encrypted_session` key is **never** written from this path — only from `handleAuthSuccess` (line 450) and `completePairing` / `savePairedSession` (lines 311-319, 383-391). Confirm: there is no call to `persistSession(StoredSession(...))` in the role-aware overload.

- [ ] **2.2 — Confirm `loadPersistedState()` read path (`DeviceAuthManager.kt:475-503`).** Re-verify the cold-start read at lines 481-486 populates `_sessionState` from `is_paired` / `device_id` / `role` (the `when` block), and the `restoreSession()?.let { stored -> ... }` block at lines 498-502 only reads the `encrypted_session` blob. There is NO branch that reads any synthetic-token key today. `currentAccessToken` is populated only by the encrypted-blob path; the synthetic path leaves it null.

- [ ] **2.3 — Confirm `clearSession()` already clears the new key (`DeviceAuthManager.kt:405-419`).** Re-verify the `.clear()` at line 415 wipes ALL `device_auth_prefs` entries, including the new `synthetic_access_token` (no production change needed; apply phase writes a GREEN test pin to defend the invariant). No code change here.

- [ ] **2.4 — Confirm the SharedPreferences pattern reference.** Read `data/local/PairedDevicesStore.kt:20-25,100` — the `paired_devices` namespace + cleartext JSON write via `prefs.edit().putString(KEY_DEVICES, ...).apply()`. The fix mirrors this style under the existing `device_auth_prefs` namespace with a single `synthetic_access_token` key.

- [ ] **2.5 — Confirm no other call sites of the synthetic path write a token.** Run:
  ```bash
  grep -rn "authenticateOrCreate(" \
    app/src/main/java/com/tudominio/parentalcontrol \
    | grep -v "fun authenticateOrCreate"
  ```
  Expected: only `DeviceAuthService` and the role-aware consumers. The no-arg `authenticateOrCreate()` (line 152) and the role-aware `authenticateOrCreate(role)` (line 193) are the two entry points; the bug is confined to line 193.

- [ ] **2.6 — Consumer sweep on `getAccessToken()`.**
  ```bash
  grep -rn "getAccessToken()" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected hits (already known from proposal §References):
  - `data/repository/ParentRepository.kt:294-295` (returns null → `DeviceListError.AuthMissing`).
  - `app/src/main/java/com/tudominio/parentalcontrol/network/SupabaseClientProvider.kt` (auth header).
  - `app/src/main/java/com/tudominio/parentalcontrol/sync/SyncManager.kt:491-494` (throws `IllegalStateException` if null — same surface the 2026-07-02 fix covered for the encrypted path).
  - The test surface (`DeviceAuthManagerAuthenticatePersistTest`, `DeviceAuthManagerColdStartTest`).

- [ ] **2.7 — Confirm precedent gap.** Re-read `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` — that fix covered `handleAuthSuccess` and `restoreSession()` (`DeviceAuthManager.kt:498-502`) for the device-auth path. The synthetic parent path at lines 193-213 was missed; this change closes the gap with the same pattern (write a recoverable key, read it on cold start).

---

## Phase 3 — Fix (GREEN)

- [ ] **3.1 — Modify `authenticateOrCreate(role: Role)` at `DeviceAuthManager.kt:193-213` to write a cleartext `synthetic_access_token` key (~+3 LoC).** Extend the existing `SharedPreferences.edit()` block at lines 203-206 to add one more `putString`:
  ```kotlin
  context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
      .edit()
      .putString("role", role.name)
      .putString("synthetic_access_token", "synthetic-${role.name.lowercase()}")
      .apply()
  ```
  Place the constant `"synthetic_access_token"` as a private `companion object` const alongside the existing `TAG` (line 92) for the same discoverability and lint pass reasons the existing `TAG` lives there. Value: `"synthetic-${role.name.lowercase()}"` is deterministic per role (`"synthetic-parent"` / `"synthetic-child"`) — cleartext is acceptable per Q1=c (no real credential material; eventual `parent-auth-flow` replaces with real auth). Mirror the cleartext SharedPreferences write style at `PairedDevicesStore.kt:100`.

- [ ] **3.2 — Modify `loadPersistedState()` at `DeviceAuthManager.kt:475-503` to read `synthetic_access_token` (~+5 LoC).** Add a parallel read block AFTER the existing `restoreSession()?.let { stored -> ... }` block (lines 498-502):
  ```kotlin
  // Synthetic hotfix path (Q1=c cleartext SharedPreferences):
  // when `role` is persisted but no `encrypted_session` blob was ever
  // written (e.g. `authenticateOrCreate(role: Role)` synthetic path),
  // hydrate `currentAccessToken` from the cleartext `synthetic_access_token`
  // key. The eventual `parent-auth-flow` change will replace this with
  // real Keystore-encrypted auth tokens.
  if (currentAccessToken == null && prefs.contains("role")) {
      val syntheticToken = prefs.getString("synthetic_access_token", null)
      if (syntheticToken != null) {
          currentAccessToken = syntheticToken
          currentRefreshToken = ""
          sessionExpiresAt = 0
      }
  }
  ```
  Guarded by `currentAccessToken == null` so the existing `encrypted_session` path wins when both are present (per the proposal §Risk 2 mitigation: prefer `encrypted_session`, fall back to `synthetic_access_token` only when absent). Guarded by `prefs.contains("role")` so a stray token without role leaves `currentAccessToken` null (defends the negative-case test T3.7).

- [ ] **3.3 — RED → GREEN confirmation (the 2 existing RED cases).** `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerAuthenticatePersistTest" --rerun-tasks`. T1.1 + T1.2 now PASS. Capture the timing delta vs the Phase 1 baseline.

- [ ] **3.4 — Add 4 NEW GREEN tests to `DeviceAuthManagerAuthenticatePersistTest.kt` (~45 LoC).** Append to the same class — the file already has the SharedPreferences + reflection helper infrastructure in `setUp` / `resetManagerInstance` / `newManager`.

  - **T3.5 — `cold start after authenticateOrCreate with CHILD restores accessToken`** (~15 LoC, Q2=y symmetry). Mirror T1.2 verbatim with `Role.CHILD` instead of `Role.PARENT`. The role-aware overload at line 193 covers both roles; the bug is symmetric. Assert cold-start `getAccessToken()` non-null and equal to the first session's token.

  - **T3.6 — `clearSession() also clears synthetic_access_token`** (~5 LoC, Q3=y defense-in-depth). After `authenticateOrCreate(Role.PARENT)`, assert the `synthetic_access_token` key is present in `device_auth_prefs`. Call `clearSession()`. Assert the key is gone (`prefs.contains("synthetic_access_token") == false`). Pins the `.clear()` invariant at `DeviceAuthManager.kt:415` against future `remove("...")` refactors.

  - **T3.7 — `cold start with synthetic_access_token but missing role leaves token null`** (~10 LoC, negative case). Skip `authenticateOrCreate` entirely. Manually write `synthetic_access_token = "orphan-token"` to `device_auth_prefs` (NO `role` key). Build a fresh manager. Assert `getAccessToken()` is null. Pins the guard `prefs.contains("role")` at T3.2 — defends against a partial-write regression.

  - **T3.8 — `authenticateOrCreate(PARENT) → clearSession() → authenticateOrCreate(PARENT) round-trips without stale-token leak`** (~15 LoC). Sequence: first `authenticateOrCreate(PARENT)` captures token A; `clearSession()`; second `authenticateOrCreate(PARENT)` captures token B (a fresh UUID — line 196 generates a new one). Reset singleton; fresh cold-start manager. Assert cold-start `getAccessToken() == tokenB`, NOT `tokenA`. Defends against ordering bugs between in-memory and on-disk writes (e.g., `clearSession()` clearing in-memory but stale on-disk value being re-hydrated by the second auth's read).

- [ ] **3.9 — Run the full auth test suites.** `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.*" --rerun-tasks`. All 6 RED→GREEN cases pass (T1.1, T1.2, T3.5, T3.6, T3.7, T3.8). `DeviceAuthManagerRoleTest` (5 cases) and `DeviceAuthManagerColdStartTest` (4 cases) stay green.

- [ ] **3.10 — Run the full repo + VM test suites.** `./gradlew :app:testDebugUnitTest --rerun-tasks`. All prior tests stay green. The pre-existing `NetworkModuleTest` / `BootReceiverTest` / `NavGraphTest` failures (per `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:151` precedent) are unchanged and out of scope.

- [ ] **3.11 — Commit**:
  ```
  fix(auth): persist synthetic parent/child access token across cold start
  ```
  Body must cite engram #278 (root cause), #280 (Q1=c + Q2=y + Q3=y decisions), `DeviceAuthManager.kt:193-213` (write path) and `:475-503` (read path), and the live-test 2026-07-07 discovery that surfaced the bug.

---

## Phase 4 — Build verifier (PR gate)

- [ ] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `DeviceAuthManager.kt`.

- [ ] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; pre-existing baseline failures (`NetworkModuleTest` / `BootReceiverTest` / `NavGraphTest`) unchanged.

- [ ] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `DeviceAuthManager.kt` or `DeviceAuthManagerAuthenticatePersistTest.kt`. Pre-existing violations elsewhere are out of scope.

- [ ] **4.4 — `./gradlew :app:detekt`** — no new violations on the 1 touched production file.

- [ ] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "synthetic_access_token" \
    app/src/main/java/com/tudominio/parentalcontrol \
    app/src/test/java/com/tudominio/parentalcontrol
  ```
  Expected: 2 production use sites in `DeviceAuthManager.kt` (3.1 write at lines 193-213 + 3.2 read at lines 475-503) + 1 production constant declaration (the new `companion object` const from T3.1) + 1+ test references in `DeviceAuthManagerAuthenticatePersistTest.kt`. No other call sites.

- [ ] **4.6 — Final cold-start trace sanity check.** Re-read `DeviceAuthManager.loadPersistedState()` post-fix and confirm the 3 outcomes match the proposal §Why trace:
  - Fresh install: no `role`, no `synthetic_access_token` → `_sessionState = NONE`, `currentAccessToken = null`. (Unchanged.)
  - Synthetic parent re-open: `role = PARENT`, `synthetic_access_token = "synthetic-parent"` → `_sessionState = PAIRED` (line 484 `hasRole` branch), `currentAccessToken = "synthetic-parent"` (T3.2 read). (NEW behavior — the fix.)
  - Real auth re-open: `encrypted_session` blob present → existing `restoreSession()?.let { ... }` path wins (T3.2 guard `currentAccessToken == null`), `currentAccessToken` populated from blob. (Unchanged — same as 2026-07-02 fix.)

---

## Out of scope (frozen)

- Keystore encryption of the synthetic token (per Q1=c cleartext decision; eventual `parent-auth-flow` replaces synthetic with real Keystore-encrypted auth).
- `parent-auth-session/spec.md` delta (per Q4=d defer — RED test is the contract).
- Update to the V1 archived `fix-auth-session-restore-on-cold-start` spec (per Q5=l leave).
- Migration of `DeviceAuthService` / `PairingManager` to the new path (no production change beyond the 2-line write + 5-line read).
- Splash / loading state for the cold-start hydration window (synchronous `loadPersistedState()` runs before any consumer; no UI surface required).
- Real `parent-auth-flow` implementation (separate change).
- `selectedChildId` / Solicitudes-cache cold-start (separate `fix-parent-log-events-cleared-on-reopen` change; PR #20 merged).
- Pre-existing `NetworkModuleTest` / `BootReceiverTest` / `NavGraphTest` failures (baseline unchanged, out of scope per `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:151` precedent).

## Notes

- This change is a 1-file production diff (`auth/DeviceAuthManager.kt` MODIFIED) + 1 modified test file (`auth/DeviceAuthManagerAuthenticatePersistTest.kt`, 2 RED→GREEN + 4 NEW GREEN). ~73 LoC total, well under the 400-line review budget.
- **`strict_tdd: true` from `openspec/config.yaml:3`** is honoured with the 2-commit pattern: RED confirmation in Phase 1 (the RED file was already committed at explore time per engram #278; Phase 1 verifies it's still RED on `master = f54f2b0`) + production+test Phase 3 commit. Phase 1 RED is the gate.
- **No new capabilities, no spec delta.** Per the proposal §Capabilities section + Q4=d decision, `parent-auth-session/spec.md` is silent on cold-start hydration of the synthetic path; the existing RED test (T1.1) is the de-facto contract. Same precedent as the 2026-07-02 fix (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21`).
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke.
- **Why cleartext, not `persistSession(StoredSession(...))` for the synthetic path:** `persistSession` uses `encryptWithKeystore` (line 447), which requires a working Android Keystore. Robolectric 4.10.3 does not provide `KeyStore.getInstance("AndroidKeyStore")` (per the `DeviceAuthManagerColdStartTest.kt:30-44` kdoc), so the `persistSession` path is hard to assert GREEN under the unit-test source set. The cleartext SharedPreferences path is the same shape as `PairedDevicesStore.kt:100` and is Robolectric-friendly. The synthetic token has no real security value (Q1=c); eventual `parent-auth-flow` will use real Keystore-encrypted auth tokens.
- **Why `synthetic-${role.name.lowercase()}` and not the random UUID from line 196:** the random UUID lives only in `currentAccessToken` (line 197) and is reset by process death. The cleartext `synthetic_access_token` is the disk-recoverable handle; it can be a deterministic placeholder because the eventual real auth path will overwrite it. The cold-start invariant the test pins is `getAccessToken() != null` after force-stop, not a specific token value (see T1.2: it asserts `tokenAfterFirstAuth == coldStart.getAccessToken()` — same value, which works because the same `authenticateOrCreate` call writes the same `synthetic_access_token` value to disk and the same cleartext value is re-hydrated).
- **Why the `prefs.contains("role")` guard in T3.2:** the synthetic path writes BOTH `role` and `synthetic_access_token` atomically (one `edit().putString(...).putString(...).apply()` block in T3.1). A partial-write regression (e.g., `apply()` interrupted between the two `putString` calls) would leave a stray `synthetic_access_token` without `role`; the guard + T3.7 negative test pin the invariant that BOTH keys must be present for the read to fire.
- **Reference resolution for the next session**: engram #278 (`sdd/fix-auth-state-persistence-across-cold-start/explore` — root cause analysis), #279 (proposal artifact), #280 (`sdd/fix-auth-state-persistence-across-cold-start/decisions` — Q1=c + Q2=y + Q3=y + Q4=d + Q5=l). Precedent: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` is the same cold-start shape for the device-auth path; this change closes the symmetric gap on the synthetic path. Precedent (test-seam + RED→GREEN): `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md` is the closest format mirror (single-PR data-layer cold-start fix, ~50 LoC, 2 RED→GREEN + new GREEN tests).
- **If on closer inspection the root cause is different** (e.g., the synthetic path already writes a recoverable key in a place the explore trace missed, or the read path in T3.2 conflicts with an existing test that asserts a different cold-start behavior), Phase 3.1/3.2 are the seams to revisit. The symptom (`getAccessToken() == null` on a fresh `DeviceAuthManager` after a warm `authenticateOrCreate(Role.PARENT)`) is the agreed starting point.
