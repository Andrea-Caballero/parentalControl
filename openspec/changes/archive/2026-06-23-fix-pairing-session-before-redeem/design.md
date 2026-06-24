# Design: Restore session before redeem in `PairingManager.pairWithCode()`

## 1. Architecture overview

This 1-piece change closes the final gap in a chain of three bugs that masked each other on the child pairing path. PR #6 (`hotfix-child-pairing-mock-redeem-route`) wired the mock engine for `/functions/v1/pairing`; PR #7 (`feature-boot-restore-session-before-sync`) made `SyncWorker` instantiate on boot by disabling WorkManager auto-init. With both in place, logcat 2026-06-23 17:10:10 surfaced the deeper bug: on first launch, `PairingManager.pairWithCode()` short-circuits at `pairing/PairingManager.kt:74-80` to `PairingErrorType.SESSION_ERROR` because `authManager.getAccessToken()` returns `null` — no anonymous session has been minted yet, and the call never reaches `/functions/v1/pairing`.

The fix replaces the `getAccessToken() == null` short-circuit with a call to `DeviceAuthManager.authenticateOrCreate()` (the same seam `SupabaseClientProvider.initializeAndAuthenticate()` already uses at line 104, and the same one the boot design flags for the boot path). On `AuthResult.Success` the existing `clientProvider.httpClient.post(...)` runs unchanged; on `AuthResult.Error` or `AuthResult.NeedsPairing` the function returns `PairingErrorType.NETWORK_ERROR` with the message `"Error de conexión. Verifica tu conexión a internet."` — the same `PairingViewModel.kt:159-166` UX branch the user already sees for transient failures. Two unit tests pin the new contract: one inverts the existing `pairWithCode_returns_session_error_when_no_token` (which encodes the bug), one covers the auth-failure branch. No `DeviceAuthManager` change, no `savePairedSession` API change, no mock-engine change.

## 2. Component design

### 2.1 `PairingManager.pairWithCode()` — preflight gate

Current at `pairing/PairingManager.kt:70-102`, the `getAccessToken() == null` branch at lines 74-80 short-circuits to `PairingErrorType.SESSION_ERROR` without calling the edge function. New gate:

```kotlin
suspend fun pairWithCode(code: String): PairingResult = withContext(Dispatchers.IO) {
    Log.d(TAG, "Intentando emparejar con código: ${code.take(4)}...")

    // Preflight: ensure a session exists before attempting the HTTP call.
    // authenticateOrCreate() is offline-tolerant on cache hit (restores from
    // device_auth_prefs.encrypted_session) and falls through to
    // createAnonymousSession() on miss — same seam as
    // SupabaseClientProvider.initializeAndAuthenticate().
    var token = authManager.getAccessToken()
    if (token == null) {
        token = when (val authResult = authManager.authenticateOrCreate()) {
            is AuthResult.Success -> authResult.accessToken
            is AuthResult.Error,
            is AuthResult.NeedsPairing -> return@withContext PairingResult.Error(
                PairingErrorType.NETWORK_ERROR,
                "Error de conexión. Verifica tu conexión a internet."
            )
        }
    }

    // ... existing HTTP call (lines 82-100) unchanged, uses `token` ...
}
```

The `AuthResult` shape is sealed at `auth/DeviceAuthManager.kt:33-48`: `Success(deviceId, accessToken, refreshToken, expiresAt)`, `NeedsPairing(message)`, `Error(message)`. The new `pairWithCode()` body is ~+10 lines; no signature change. `parsePairingResponse` at lines 210-264 already calls `authManager.savePairedSession(deviceId, parentId)` at line 219, which re-persists the current `currentAccessToken` with the new `deviceId` (`DeviceAuthManager.kt:364-376`). No `savePairedSession` API change is required.

### 2.2 `PairingManagerTest.kt` — invert + append 2 tests

Mirror the 23/06 `MockSupabaseEngineTest` precedent: append, do not create a new file. Use the existing MockK + `MockEngine` + `runTest` setup at `pairing/PairingManagerTest.kt:45-87`.

- **Invert** `pairWithCode_returns_session_error_when_no_token` (lines 113-122) → `pairWithCode_obtains_session_on_demand_when_no_token`. Stub `getAccessToken()` → `null` on the first call and `"test-jwt-token"` on the second (after `authenticateOrCreate()` succeeds), stub `coEvery { mockAuthManager.authenticateOrCreate() }` → `AuthResult.Success(deviceId = "anonymous", accessToken = "test-jwt-token", refreshToken = "", expiresAt = 0L)`, hit the existing `MockEngine` returning 200, assert `result is PairingResult.Success`, assert `coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }`. The test FAILS against today's code (result will be `SESSION_ERROR`) and PASSES after GREEN.
- **Append** `pairWithCode_returns_network_error_when_authenticateOrCreate_fails`. Stub `getAccessToken()` → `null`, stub `coEvery { mockAuthManager.authenticateOrCreate() }` → `AuthResult.Error("network down")`, assert `result is PairingResult.Error` with `type == NETWORK_ERROR` and the Spanish copy, and assert the `MockEngine` is NOT invoked. FAILS against today's code (returns `SESSION_ERROR`); PASSES after GREEN.

The `setUp()` (lines 45-80) already wires `mockkObject(DeviceAuthManager.Companion)` and `DeviceAuthManager.getInstance(any()) returns mockAuthManager` — no setup change needed; just add the two `coEvery` stubs inside the new tests.

## 3. Architecture decisions

| # | Choice | Alternative | Rationale |
|---|---|---|---|
| **A** | Gate at `PairingManager.pairWithCode()` (1a) | UI-level `PairingViewModel` (1b) / lazy in `DeviceAuthManager.getAccessToken()` (1c) | `PairingManager` is the single source of truth for the `/functions/v1/pairing` POST; both `pairWithCode` (manual entry) and `pairWithQr` (QR scan, line 128) call it, so a single gate covers both. UI-level would duplicate the preflight in two call sites. A `getAccessToken()` lazy network call is a footgun — every other consumer (sync, heartbeat, FCM) already assumes a non-null token implies "session ready" and would suddenly hit Supabase on a property read. |
| **B** | Re-use existing `NETWORK_ERROR` enum (2c) | New `AUTH_ERROR` value | `PairingViewModel.kt:159-166` already maps `SESSION_ERROR` and `NETWORK_ERROR` to the same user copy ("Error de conexión. Verifica tu conexión a internet."). Distinguishing them at the enum level would force a UI change in the ViewModel — out of scope for a 1-file + 1-test fix. In debug builds the dominant cause is `createAnonymousSession()` failing on the placeholder `SUPABASE_URL` (per the proposal's Risk table), which IS a network problem from the user's perspective. Adding a new enum value is scope creep. |
| **C** | No `savePairedSession` API change (3a) | Extend to take an access-token argument | The existing `parsePairingResponse` flow already calls `savePairedSession(deviceId, parentId)` (line 219), and the implementation at `DeviceAuthManager.kt:364-376` re-persists the current `currentAccessToken` with the new `deviceId`. The edge function returns only `{device_id, parent_id}` (no new token per the proposal's locked-scope 3a), so the anonymous token is sufficient to redeem. The post-pairing upgrade path is a separate change. |
| **D** | Append 2 tests to existing `PairingManagerTest.kt` (4a) | New `PairingManagerSessionRestoreTest.kt` file | Mirrors the 23/06 `MockSupabaseEngineTest` pattern (append, not create) and the 23/06 `BootReceiverTest` pattern (test file = one class). Keeps review focus tight: 1 test file modified, 1 production file modified. |

## 4. Apply hints

- **Strict TDD, 2 commits:**
  1. **RED** — replace `pairWithCode_returns_session_error_when_no_token` with `pairWithCode_obtains_session_on_demand_when_no_token` AND append `pairWithCode_returns_network_error_when_authenticateOrCreate_fails` in `PairingManagerTest.kt`. Confirm RED: the inverted test fails on the `assertTrue(..., result is PairingResult.Success)` line (today's code returns `SESSION_ERROR`); the new test fails on `assertEquals(PairingErrorType.NETWORK_ERROR, ...)` (today's code returns `SESSION_ERROR`). Run `./gradlew testDebugUnitTest --tests "*.PairingManagerTest"`.
  2. **GREEN** — replace the `getAccessToken() == null` short-circuit at `PairingManager.kt:74-80` with the `authenticateOrCreate()` preflight (see §2.1). Both new tests pass; existing `pairWithCode_real_supabase_returns_success` (line 89-111) and `pairWithCode_returns_invalid_code_on_404` (line 124-154) still pass (the token-non-null branch is unchanged).
- **Quality gates** (no new deps, no new permissions, no new Ktor config):
  `./gradlew testDebugUnitTest && ./gradlew assembleDebug && ./gradlew detekt && ./gradlew ktlintCheck`.
- **Files touched (2 total, exact paths):**
  - `app/src/main/java/com/tudominio/parentalcontrol/pairing/PairingManager.kt` (modify, ~+10 lines added, ~7 lines deleted from the short-circuit)
  - `app/src/test/java/com/tudominio/parentalcontrol/pairing/PairingManagerTest.kt` (modify, ~+30 lines for the inverted + new test; the old `pairWithCode_returns_session_error_when_no_token` is replaced, not added alongside)

## 5. Verification approach

- The 2 new tests are the primary verification — they pin (a) `getAccessToken() == null` triggers `authenticateOrCreate()`, the new token is used in the POST, and `savePairedSession` is invoked; (b) `authenticateOrCreate()` failure surfaces `NETWORK_ERROR` with the Spanish copy and skips the HTTP call.
- The 640 prior unit tests must remain green (regression target — `./gradlew testDebugUnitTest`).
- `./gradlew assembleDebug`, `./gradlew detekt`, `./gradlew ktlintCheck` must all succeed; no new detekt rules, no ktlint violations (4-space indent already used in the file).
- Manual smoke is OUT OF SCOPE for this PR — dev box has no `adb`/emulator. Post-merge, CI's instrumented runner on API 28/31/35 will validate against a real `SUPABASE_URL` that the child pairing flow lands on `PairingUiState.Success` instead of `PairingUiState.Error("Error de conexión. Verifica tu conexión a internet.")`.

## 6. Out of scope

- No new `PairingErrorType` enum value (Decision B; the `NETWORK_ERROR` branch already maps to the right UX).
- No `savePairedSession` API change (Decision C; the existing call re-persists the current `currentAccessToken` with the new `deviceId`).
- No instrumented tests (`openspec/config.yaml:57` — dev box has no `adb`/emulator).
- No `MockSupabaseEngine` mock for `/auth/v1/token?grant_type=password` (the same gap as `DeviceAuthManager.httpClient` at line 109 being bound to OkHttp, not the mock engine). This is a separate mock-engine follow-up, structurally identical to PR #6 — out of scope for this hotfix. **In debug builds with placeholder `SUPABASE_URL`, pairing will surface `NETWORK_ERROR` ("Error de conexión. Verifica tu conexión a internet.") after this change lands; the existing UX branch in `PairingViewModel.kt:159-166` already handles this.**
- No `routesKnownByMockEngine` guard test (separate backlog).
- No `DeviceComponents.kt` ktlint refactor (pre-existing on master, separate change).
- No new `sdd-spec` delta spec — `pairing-flow` already mandates the POST with a bearer token (`openspec/specs/pairing-flow/spec.md`); the bug is in the implementation of acquiring that token, not in spec-level behavior.
