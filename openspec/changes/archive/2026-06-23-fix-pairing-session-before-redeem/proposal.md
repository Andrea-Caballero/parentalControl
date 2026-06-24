# Proposal: Restore session before redeem in `PairingManager.pairWithCode()`

## Why

Logcat 2026-06-23 17:10:10: a child device on first launch entered `ABCDEFGH` manually. `PairingManager.pairWithCode()` short-circuited at `pairing/PairingManager.kt:74-80` to `SESSION_ERROR` because `authManager.getAccessToken()` returned `null` — no anonymous session had been minted yet, and the call never reached `/functions/v1/pairing`. The edge-function 404-mask from PR #6 (`hotfix-child-pairing-mock-redeem-route`) is fixed; PR #7's `WorkManagerInitializer` fix made `SyncWorker` instantiate; both stopped hiding the deeper bug — the pairing call site assumed a token that the first-launch flow has no reason to possess.

`DeviceAuthManager.authenticateOrCreate()` (`auth/DeviceAuthManager.kt:135-155`) is the public, no-arg entry that already restores from `device_auth_prefs.encrypted_session` (cache hit) or falls through to `createAnonymousSession()` (Supabase call). It is the same seam the 2026-06-23 `feature-boot-restore-session-before-sync` proposal flagged for the boot path, applied here to the pairing path. Once `authenticateOrCreate()` succeeds, `currentAccessToken` is populated and the existing `pairWithCode()` HTTP path runs unchanged; `parsePairingResponse` then calls `savePairedSession(deviceId, parentId)` (line 219), which already re-persists the current token with `deviceId` set — no `savePairedSession` API change required for locked-scope option 3a (anonymous token is sufficient to redeem; the upgrade path lives elsewhere).

## What changes

- **Modified** `app/src/main/java/com/tudominio/parentalcontrol/pairing/PairingManager.kt` — replace the `getAccessToken() == null` short-circuit at lines 74-80 with a call to `authManager.authenticateOrCreate()`. On `AuthResult.Success` (or when `getAccessToken()` already non-null) proceed with the existing `clientProvider.httpClient.post(...)`. On `AuthResult.Error` / `NeedsPairing`, return `PairingErrorType.NETWORK_ERROR` with `e.message ?: "Error de red"` — `PairingViewModel.handlePairingResult` (line 159-165) already maps that to "Error de conexión. Verifica tu conexión a internet.", which is the right UX. (~+10 lines, no signature change.)
- **New test** in `app/src/test/java/com/tudominio/parentalcontrol/pairing/PairingManagerTest.kt` — `pairWithCode_calls_authenticateOrCreate_when_no_token_then_proceeds_with_pairing`: stub `getAccessToken()` to return `null` then `"test-jwt-token"` (the second call after `authenticateOrCreate()` succeeds), stub `authenticateOrCreate()` to return `AuthResult.Success(...)`, hit a `MockEngine` returning `200 {"device_id":"...","parent_id":"..."}`, assert `Success` and that `savePairedSession` was invoked. (~+30 lines.)

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- None. `pairing-flow` spec (`openspec/specs/pairing-flow/spec.md`, req "Child completes pairing via code or QR scan", scenario "Manual code entry posts to pairing edge function") already mandates `POST /functions/v1/pairing` with a bearer token; the fix is in IMPLEMENTATION (preflight acquisition of that token), not in spec-level behavior.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `pairing/PairingManager.kt:70-102` | Modified | Replace token-null short-circuit with `authenticateOrCreate()` preflight (+~10 lines) |
| `pairing/PairingManagerTest.kt` | Modified | 1 new RED test next to `pairWithCode_returns_session_error_when_no_token` (line 113-122) — which will now FAIL until the fix lands (deliberate) (~+30 lines) |
| `pairing-flow` spec | Unchanged | No delta spec produced |

## Out of scope

- `savePairedSession` API tweak to persist a pairing-response access token — locked-scope 3a (anonymous token suffices; the edge function returns only `{device_id, parent_id}` per the orchestrator's verification; `savePairedSession` already re-persists the current token).
- New `PairingErrorType` value — `NETWORK_ERROR` re-uses the existing branch the user already sees for transient failures.
- Instrumented / Compose UI tests — dev box has no `adb` / emulator per `openspec/config.yaml:57`.
- Mocking `/auth/v1/token?grant_type=password` in `MockSupabaseEngine` — `DeviceAuthManager.httpClient` is bound directly to OkHttp (line 109-117), not the mock engine. First-launch debug runs will surface `NETWORK_ERROR` to the user (correct UX, per locked-scope 2). A mock for this route is a separate follow-up if the dev workflow demands an offline first-launch.
- `parent-auth-session` spec — unaffected; `authenticateOrCreate()` is unchanged.
- `BootReceiver` boot-time preflight — already covered by archived `feature-boot-restore-session-before-sync`.

## Spec recommendation

**Skip `sdd-spec` for this change.** Mirrors the 2026-06-23 `hotfix-child-pairing-mock-redeem-route` and `feature-boot-restore-session-before-sync` precedents: `pairing-flow` already requires "the child's authenticated bearer token" at the POST step (scenario line 26-28). The bug is in the implementation of getting that token, not in the spec-level behavior. Adding a "Child on first launch obtains anonymous session before pairing" requirement for a 1-file + 1-test change would be more doc than code. **Follow-up**: revisit if a future change adds new error semantics (e.g., distinguishing "no network" from "backend rejected the anonymous sign-in") — at that point a delta spec is justified.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `authenticateOrCreate()` always fails on debug (placeholder `SUPABASE_URL`) | Medium | Acceptable per locked-scope 2: user sees "Error de conexión. Verifica tu conexión a internet." — same UX as the existing try/catch at lines 94-100. Mock for `/auth/v1/token` is a separate follow-up. |
| `AuthResult.NeedsPairing` from a partially restored session | Low | Treat as `Error` and return `NETWORK_ERROR` — the child cannot recover from this state without UI intervention, which is out of scope. |
| Existing `pairWithCode_returns_session_error_when_no_token` test breaks | Expected | The test (line 113-122) encodes the bug; it will be flipped (or deleted) in the GREEN commit. The new RED test replaces its intent. |
| **Risk level** | **Low** | 1 file modified, 1 test added, no API surface change, no schema, no migration. |

## Rollback

Revert the 2 commits (RED + GREEN). Falls back to today's behavior: first-launch child devices hit `SESSION_ERROR`. Production pairing flows with a pre-existing token are unaffected (the `getAccessToken()` non-null branch is unchanged).

## Success criteria

- [ ] New `pairWithCode_calls_authenticateOrCreate_when_no_token_then_proceeds_with_pairing` passes; existing `pairWithCode_real_supabase_returns_success` and `pairWithCode_returns_invalid_code_on_404` still pass.
- [ ] Existing `pairWithCode_returns_session_error_when_no_token` is removed (its scenario is now the success path) OR inverted to assert the new behavior — pick whichever the design phase prefers.
- [ ] `./gradlew testDebugUnitTest && ./gradlew assembleDebug && ./gradlew detekt && ./gradlew ktlintCheck` all green.
- [ ] `pairing-flow` spec unchanged; no delta spec produced.
- [ ] Post-merge manual QA (parent device + child device on a real Supabase instance): child enters code, sees `Success` and lands on home — not `SESSION_ERROR`.
