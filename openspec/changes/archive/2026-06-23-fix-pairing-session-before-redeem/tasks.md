# Tasks: fix-pairing-session-before-redeem

## Goal

On first launch (no stored session), `PairingManager.pairWithCode()` short-circuits at `pairing/PairingManager.kt:74-80` to `PairingErrorType.SESSION_ERROR` because `authManager.getAccessToken()` returns `null` before any anonymous session has been minted — logcat 2026-06-23 17:10:10 surfaced this after PR #6 (mock pairing route) and PR #7 (WorkManager init) cleared the masking bugs. The fix replaces the `getAccessToken() == null` short-circuit with a call to `DeviceAuthManager.authenticateOrCreate()` (the same seam `SupabaseClientProvider.initializeAndAuthenticate()` already uses, sealed `AuthResult` at `auth/DeviceAuthManager.kt:33-48`); on `Success` the existing HTTP path runs unchanged with the freshly acquired token, on `Error`/`NeedsPairing` the function returns `NETWORK_ERROR` with the existing "Error de conexión. Verifica tu conexión a internet." copy. Success looks like: the 2 new unit tests pass GREEN, the full suite is green at 641/641 (640 prior − 1 removed + 2 new), detekt + ktlintCheck + assembleDebug stay clean on the 2 changed files, and `SESSION_ERROR` no longer surfaces for first-launch child devices.

## Phase 1: RED — failing tests

- [x] 1.1 In `app/src/test/java/com/tudominio/parentalcontrol/pairing/PairingManagerTest.kt`, REPLACE the existing `pairWithCode_returns_session_error_when_no_token` test (lines 113-122) with TWO tests in the same MockK + `MockEngine` + `runTest` style as the surrounding `pairWithCode_real_supabase_returns_success` (lines 89-111). Reuse `setUp()` (lines 45-87) which already wires `mockkObject(DeviceAuthManager.Companion)` and `DeviceAuthManager.getInstance(any()) returns mockAuthManager`; no setup change needed. (a) `pairWithCode_obtains_session_on_demand_when_no_token`: override the line-68 stub with `every { mockAuthManager.getAccessToken() } returnsMany listOf(null, "test-jwt-token")` (first call before `authenticateOrCreate()` returns null, second call after returns the freshly acquired token — `authenticateOrCreate()` mutates `currentAccessToken` via `DeviceAuthManager` internals, so on a real mock this requires the second value). Stub `coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Success(deviceId = "anonymous", accessToken = "test-jwt-token", refreshToken = "", expiresAt = 0L)`. Hit the existing `MockEngine` returning 200 `{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}`; assert `result is PairingResult.Success` and `coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }`. (b) `pairWithCode_returns_network_error_when_authenticateOrCreate_fails`: stub `every { mockAuthManager.getAccessToken() } returns null` and `coEvery { mockAuthManager.authenticateOrCreate() } returns AuthResult.Error("network down")`; assert `result is PairingResult.Error` with `type == PairingErrorType.NETWORK_ERROR` and `message == "Error de conexión. Verifica tu conexión a internet."`, and that the `MockEngine` was NOT invoked (capture via a counter `AtomicInteger` on the engine lambda, then assert `counter.get() == 0`). Done when both tests are on disk and the file still compiles. Prerequisite: read `AuthResult` shape on disk ... (line truncated to 2000 chars)
- [x] 1.2 Run `./gradlew testDebugUnitTest --tests "*PairingManagerTest*" --rerun-tasks` and confirm both new tests FAIL with a clear assertion. Test (a) fails on `assertTrue(..., result is PairingResult.Success)` (today's code returns `SESSION_ERROR`); test (b) fails on `assertEquals(PairingErrorType.NETWORK_ERROR, ...)` (today's code returns `SESSION_ERROR`). The failure mode MUST be an assertion failure, NOT a compile error. Prerequisite: 1.1.
- [x] 1.3 Commit: `test(pairing): replace SESSION_ERROR stub with session-on-demand tests`. Prerequisite: 1.2 RED confirmed on disk.

## Phase 2: GREEN — implementation

- [x] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/pairing/PairingManager.kt`, modify `pairWithCode()` (lines 70-102): replace the `val token = authManager.getAccessToken(); if (token == null) { return@withContext PairingResult.Error(PairingErrorType.SESSION_ERROR, "No autenticado") }` block at lines 74-80 with `var token = authManager.getAccessToken(); if (token == null) { token = when (val authResult = authManager.authenticateOrCreate()) { is AuthResult.Success -> authResult.accessToken; is AuthResult.Error; is AuthResult.NeedsPairing -> return@withContext PairingResult.Error(PairingErrorType.NETWORK_ERROR, "Error de conexión. Verifica tu conexión a internet.") } }`. The rest of the function (HTTP call at lines 85-92, `parsePairingResponse` at line 93) is unchanged. `AuthResult` is already imported at line 6. Prerequisite: 1.3.
- [x] 2.2 Re-run the 2 new tests from 1.1 with `--rerun-tasks` and confirm BOTH pass GREEN. The existing `pairWithCode_real_supabase_returns_success` and `pairWithCode_returns_invalid_code_on_404` must still pass (the token-non-null branch is unchanged). Prerequisite: 2.1.
- [x] 2.3 Run the full unit suite `./gradlew testDebugUnitTest --rerun-tasks`. All tests must be green at 641/641 (640 prior − 1 removed `pairWithCode_returns_session_error_when_no_token` + 2 new). Prerequisite: 2.2.
- [x] 2.4 Commit: `fix(pairing): obtain session on demand before redeeming code`. Prerequisite: 2.3 full-suite GREEN.

## Phase 3: Quality gates

- [x] 3.1 Run `./gradlew detekt --rerun-tasks`. Must pass — 0 new violations on the 2 changed files. Prerequisite: 2.4.
- [x] 3.2 Run `./gradlew ktlintCheck --rerun-tasks` on the 2 changed files. Must pass. The pre-existing ktlint failures on `DeviceComponents.kt` are NOT in scope and may keep failing (WARNING, not blocking). Prerequisite: 3.1.
- [x] 3.3 Run `./gradlew assembleDebug --rerun-tasks`. Must succeed. Prerequisite: 3.2.
- [x] 3.4 CRITICAL DISCIPLINE: every quality gate MUST be re-run with `--rerun-tasks` to avoid stale `UP-TO-DATE` cache masking real state. The 2026-06-23 verify sub-agent discovered this discipline gap (apply sub-agent's Engram obs #99 was inaccurate because of stale cache).

## Phase 4: Optional — PR / review

- [ ] 4.1 (Out of scope unless the user explicitly requests PR creation. The orchestrator owns the PR after apply.) Open a PR titled `fix(pairing): obtain session on demand before redeeming code` referencing `openspec/changes/fix-pairing-session-before-redeem/proposal.md` and `design.md`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~+40 / −10 (PairingManager +~10 / −7, PairingManagerTest +~30 / −10 with the removed ~10-line SESSION_ERROR test replaced by ~30 lines of inverted + new tests) |
| 400-line budget risk | Low (~40 lines net, well under 400) |
| Chained PRs recommended | No |
| Suggested split | Single PR — 2 commits (Phase 1 RED then Phase 2 GREEN atomic) |
| Delivery strategy | single-pr |
| Chain strategy | n/a |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | RED tests + GREEN (preflight gate) + quality gates | PR 1 | Base = `master`. Phase 1 commit leaves the build temporarily with the new failing tests; Phase 2 commit flips them GREEN. Manual smoke in success criteria is post-merge (CI instrumented runner on API 28/31/35). |

Files touched (2 total, exact paths):

- `app/src/main/java/com/tudominio/parentalcontrol/pairing/PairingManager.kt` — modified, ~+10 / −7 lines (replace the `getAccessToken() == null` short-circuit at lines 74-80 with the `authenticateOrCreate()` preflight gate).
- `app/src/test/java/com/tudominio/parentalcontrol/pairing/PairingManagerTest.kt` — modified, ~+30 / −10 lines (replace `pairWithCode_returns_session_error_when_no_token` at lines 113-122 with `pairWithCode_obtains_session_on_demand_when_no_token` and append `pairWithCode_returns_network_error_when_authenticateOrCreate_fails`).

## Out of scope (explicit)

- No new `PairingErrorType` enum value (Decision B — re-use `NETWORK_ERROR`, the existing `PairingViewModel.kt:159-166` UX branch already maps it to "Error de conexión. Verifica tu conexión a internet.").
- No `savePairedSession` API change (Decision C — `parsePairingResponse` at `PairingManager.kt:219` already calls `savePairedSession(deviceId, parentId)`; the implementation at `DeviceAuthManager.kt:353-377` re-persists `currentAccessToken` with the new `deviceId`).
- No instrumented tests (`openspec/config.yaml:57` — dev box has no `adb`/emulator).
- No `MockSupabaseEngine` mock for `/auth/v1/token?grant_type=password` (`DeviceAuthManager.httpClient` at line 109 binds to OkHttp directly, not the mock engine). Structurally identical follow-up to PR #6, separate change. In debug builds with placeholder `SUPABASE_URL`, pairing will surface `NETWORK_ERROR` after this lands — correct UX per the existing ViewModel branch.
- No `routesKnownByMockEngine` guard test (separate backlog item).
- No `DeviceComponents.kt` ktlint refactor (pre-existing on master, separate change).
- No `WorkScheduler`, `SyncManager`, or manifest changes.

## Rollback

Revert the 2 commits (Phase 1 RED + Phase 2 GREEN). No data migration. No feature flag. No compatibility concern. The change reverts the `authenticateOrCreate()` preflight gate and restores the `getAccessToken() == null` short-circuit; falls back to today's behavior (first-launch child devices hit `SESSION_ERROR`). Production pairing flows with a pre-existing token are unaffected by either direction of the change (the `getAccessToken()` non-null branch is unchanged).

## Success criteria

- The 2 new unit tests pass GREEN (`pairWithCode_obtains_session_on_demand_when_no_token` and `pairWithCode_returns_network_error_when_authenticateOrCreate_fails`); the old `pairWithCode_returns_session_error_when_no_token` is removed.
- The full unit suite is green: `./gradlew testDebugUnitTest --rerun-tasks` → 641/641.
- `./gradlew detekt --rerun-tasks`, `./gradlew ktlintCheck --rerun-tasks` (on the 2 changed files), and `./gradlew assembleDebug --rerun-tasks` are all green.
- Manual smoke (out of apply scope, validated in CI instrumented runner on API 28/31/35): in a release build with a real `SUPABASE_URL`, first-launch child device enters code manually and lands on `PairingUiState.Success` instead of `PairingUiState.Error("Error de conexión. Verifica tu conexión a internet.")`.