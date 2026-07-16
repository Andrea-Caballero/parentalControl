# Tasks: feat-cross-device-pairing-and-approval

## Change: feat-cross-device-pairing-and-approval

> Regenerated 2026-07-10 with markdown checkbox compliance. Reconciled against `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`, `git log master..feat/cross-device-pairing-and-approval-slice-a --oneline --stat`, and the current OpenSpec proposal/spec/design artifacts. Original A → B1 → B2 → C → D chained plan preserved.

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: High

## Reconciliation notes

- Slice A, Continuation #1, Continuation #2, and T3 prep are marked complete because they are present in the shipped branch history.
- `openspec/changes/feat-cross-device-pairing-and-apply-progress.md` is authoritative for deviations from the first-pass tasks file.
- A.1.7 and A.1.8 are marked complete as scaffold-only tasks; their assertion bodies remain deferred to Slice C / CI per Q2=B.
- A.2.7 was implemented in `supabase/functions/approve-request/index.ts` instead of Android polling; tasks below match shipped code.
- The original UI task was completed through Continuation #1, and the verification round-trip was completed through Continuation #2.
- Apply-progress lists an early branch spelling of `feature/...`; branch reconciliation uses the actual shipped branch `feat/cross-device-pairing-and-approval-slice-a`.

## Slice A — Magic-link + hook parent_id + RLS + 1h auto-DENY

**Budget:** ~230-400 LoC planned. **Status:** shipped on PR #29 branch (10 base commits).  
**Risk:** Medium — auth and RLS path; child path must stay intact.

### Phase 1 — RED tests (all complete)

- [x] **Task A.1.1** — `signInWithMagicLink_happyPath`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMagicLinkTest.kt`
  - Test method: `signInWithMagicLink_happyPath()`
  - Commit: `dbaa272`
  - Acceptance: mocked Supabase magic-link request returns a successful `MagicLinkSent`.

- [x] **Task A.1.2** — `signInWithMagicLink_invalidEmail`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMagicLinkTest.kt`
  - Test method: `signInWithMagicLink_invalidEmail()`
  - Commit: `dbaa272`
  - Acceptance: invalid email fails without persisting or enqueueing state.

- [x] **Task A.1.3** — `verifyMagicLinkOtp_validToken`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMagicLinkTest.kt`
  - Test method: `verifyMagicLinkOtp_validToken()`
  - Commit: `dbaa272`
  - Acceptance: valid token persists a parent session with `parent_id`.

- [x] **Task A.1.4** — `cleanCutover_staleParentIdWiped`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMagicLinkTest.kt`, `DeviceAuthManagerCleanCutoverTest.kt`
  - Test method: `cleanCutover_staleParentIdWiped()` plus the six-case clean-cutover suite.
  - Commit: `dbaa272`
  - Acceptance: legacy `parent-demo` or non-UUID parent state is wiped; real UUID state survives.

- [x] **Task A.1.5** — `custom-access-token-hook` injects `parent_id`
  - File: `supabase/functions/custom-access-token-hook/index_test.ts`
  - Test method: `Deno.test("A.1.5 — injectParentIdClaim...")`
  - Commit: `6eb9935`
  - Acceptance: parent `app_metadata.parent_id` appears as a first-level JWT claim.

- [x] **Task A.1.6** — child and empty metadata omit `parent_id`
  - File: `supabase/functions/custom-access-token-hook/index_test.ts`
  - Test method: `Deno.test("A.1.6 — noParentIdForChild...")` and `Deno.test("A.1.5b — emptyAppMetadata...")`
  - Commit: `6eb9935`
  - Acceptance: child JWTs and empty metadata do not crash and do not get a parent claim.

- [x] **Task A.1.7** — `BehavioralEventsRlsIT.parentCanReadOwnEvents`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/integration/BehavioralEventsRlsIT.kt`
  - Test method: `parentCanReadOwnEvents()`
  - Commit: `772e07a`
  - Acceptance: scaffold only, body deferred to CI per Q2=B; contract documents parent-A sees own events and parent-B sees none.

- [x] **Task A.1.8** — `DevicePushTokensRlsIT.parentCanReadOwnTokens`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/integration/DevicePushTokensRlsIT.kt`
  - Test method: `parentCanReadOwnTokens()`
  - Commit: `772e07a`
  - Acceptance: scaffold only, body deferred to CI per Q2=B; contract documents sibling-parent token isolation.

- [x] **Task A.1.9** — `approve-request` 1h auto-DENY tests
  - File: `supabase/functions/approve-request/index_test.ts`
  - Test method: `Deno.test("A.1.9 — autoDenyAfterOneHour...")` and `Deno.test("A.1.9b — autoDeny does NOT touch fresh...")`
  - Commit: `d8a4596`
  - Acceptance: stale PENDING rows auto-DENY; fresh PENDING rows remain untouched.

### Phase 2 — GREEN implementations (all complete)

- [x] **Task A.2.1** — `DeviceAuthManager.signInWithMagicLink(email)`
  - File: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt`
  - Change: add real Supabase magic-link send path.
  - Commit: `10eb08d`

- [x] **Task A.2.2** — `DeviceAuthManager.verifyMagicLinkOtp(token, email)` + `ParentSession`
  - File: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt`
  - Change: verify OTP, parse parent claim, and persist `ParentSession` atomically.
  - Commit: `10eb08d`

- [x] **Task A.2.3** — clean cutover deletes legacy migration helper
  - File: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt`
  - Change: wipe stale/non-UUID parent prefs and remove `migrateStaleParentId`; obsolete migration tests deleted.
  - Commit: `10eb08d`

- [x] **Task A.2.4** — inject `parent_id` in custom access token hook
  - File: `supabase/functions/custom-access-token-hook/index.ts`
  - Change: copy `app_metadata.parent_id` into first-level JWT claims while preserving `device_id`.
  - Commit: `30f7b9d`

- [x] **Task A.2.5** — migration `007_behavioral_events_parent_select.sql`
  - File: `supabase/migrations/007_behavioral_events_parent_select.sql`
  - Change: rename/re-issue behavioral events parent SELECT policy.
  - Commit: `0da65e3`

- [x] **Task A.2.6** — migration `008_device_push_tokens_parent_id.sql`
  - File: `supabase/migrations/008_device_push_tokens_parent_id.sql`
  - Change: add nullable `device_push_tokens.parent_id`, index, and parent RLS policies.
  - Commit: `0da65e3`

- [x] **Task A.2.7** — server-side 1h auto-DENY sweep
  - File: `supabase/functions/approve-request/index.ts`
  - Change: run idempotent stale-PENDING auto-DENY sweep before approval/denial handling.
  - Commit: `8dfb91a`

### Phase 3 — Refactor (complete)

- [x] **Task A.3.1** — extract magic-link email validation helper
  - File: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt`
  - Change: centralize magic-link email validation without changing behavior.
  - Commit: `3dbd091`

### Phase 4 — Build verifier + slice smoke (complete with documented warnings)

- [x] **Task A.4.1** — run JVM unit verifier
  - File: `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
  - Change: recorded `./gradlew testDebugUnitTest` evidence; slice-owned tests are GREEN, master baseline failures remain pre-existing.
  - Commit hash: `f0bd90b`

- [x] **Task A.4.2** — record RLS integration deferral
  - File: `app/src/test/java/com/tudominio/parentalcontrol/integration/BehavioralEventsRlsIT.kt`, `DevicePushTokensRlsIT.kt`
  - Change: opt-in scaffolds skip locally unless `-PrunIntegration=true`; body deferred to Slice C / CI.
  - Commit hash: `772e07a`

- [x] **Task A.4.3** — build and quality verifier
  - File: `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
  - Change: `assembleDebug` green; `ktlintCheck` green for new code; detekt remains a pre-existing infra failure.
  - Commit hash: `f0bd90b`

- [x] **Task A.4.4** — local Slice A smoke evidence
  - File: `openspec/changes/feat-cross-device-pairing-and-apply-progress.md`
  - Change: Deno hook and approve-request tests green; JWT round-trip and full RLS body left for verify/Slice C.
  - Commit hash: `8dfb91a`

## Slice A.5 — Magic-link UI + verification round-trip (continuations #1 + #2)

**Budget:** planned ~150-200 LoC; actual PR diff exceeded the 400-line review budget once tests/UI/deep-link were included. **Status:** shipped on top of Slice A base (4 commits).  
**Risk:** Medium — auth UI and app-entry deep-link path.

### Continuation #1 — MagicLinkSignInScreen UI

- [x] **Task cont-1.1** — add RED MagicLinkSignInScreen and OnboardingScreen tests
  - File: `app/src/test/java/com/tudominio/parentalcontrol/ui/auth/MagicLinkSignInScreenTest.kt`, `app/src/test/java/com/tudominio/parentalcontrol/ui/screen/OnboardingScreenTest.kt`
  - Test method: six `magic_link_*` cases plus four rewritten onboarding parent-card cases.
  - Commit hash: `3bfa3b1`, `ea49293`

- [x] **Task cont-1.2** — implement MagicLink UI, VM, DI, onboarding, and route wiring
  - File: `MagicLinkSignInScreen.kt`, `MagicLinkViewModel.kt`, `EmailValidator.kt`, `MagicLinkModule.kt`, `OnboardingScreen.kt`, `NavGraph.kt`
  - Change: parent card routes to magic-link sign-in; UI sends magic-link through a MockK-free `MagicLinkSender` seam.
  - Commit hash: `ea49293`

### Continuation #2 — MagicLinkDeepLinkHandler

- [x] **Task cont-2.1** — add RED MagicLinkDeepLinkHandler tests
  - File: `app/src/test/java/com/tudominio/parentalcontrol/auth/MagicLinkDeepLinkHandlerTest.kt`
  - Test method: valid token, invalid token, missing query param, and non-magic-link pass-through cases.
  - Commit hash: `3a65298`

- [x] **Task cont-2.2** — implement deep-link handler, manifest, and navigation verification
  - File: `MagicLinkDeepLinkHandler.kt`, `AndroidManifest.xml`, `MainActivity.kt`, `AppNavHost.kt`, `NavGraph.kt`
  - Change: parse `parentalcontrol://magic-link`, verify OTP through `MagicLinkVerifier`, and route to dashboard on success.
  - Commit hash: `b56cc9c`

## T3 prep — BuildConfig wiring (pre-merge infra patch)

- [x] **Task t3.0** — wire real Supabase URL and anon key into BuildConfig
  - File: `app/build.gradle.kts`, `DeviceAuthManager.kt`, `SupabaseClientProvider.kt`
  - Change: expose `SUPABASE_URL` and `SUPABASE_ANON_KEY` to real-Supabase debug builds.
  - Commit hash: `e8447c1`
  - Note: outside original Slice A scope; user picked option v3 at pre-merge review.

## Slice B1 — FCM v1 OAuth rewrite (edge fn)

**Outcome**: Re-baselined `supabase/functions/fcm-send/index.ts` to master (the legacy 142-line `key=` path) and split the FCM v1 OAuth rewrite into three sub-slices **B1a → B1b → B1c** (stacked-to-main, ≤400 changed lines each). Each sub-slice is additive against its predecessor until B1c wires the cutover. Audit obs #374 explicitly called for this split (re-baseline + responsibility-aligned units).

**Per-slice dependency:** B1a (additive OAuth foundation) → B1b (additive FCM v1 transport, depends on B1a) → B1c (handler cutover/integration, depends on B1a + B1b).

**Risk:** Medium — service-account OAuth and edge runtime.

### Slice B1a — additive OAuth foundation — split into B1a1 + B1a2 (PREPARED in working tree, awaiting commit/push/PR authorization)

Per the user-approved correction (Engram obs #373), the originally-monolithic B1a (514 LoC over budget) is split into two stacked-to-main sub-pr slices, each independently verifiable and under the ≤400 LoC review budget. The working tree on `feat/cross-device-pairing-and-approval-slice-b1` carries the post-split code. **No `size:exception` was authorized.**

### Slice B1a1 — OAuth credential / JWT / cache foundation (additive on master)

**Budget:** +390 changed lines vs master (≤400). **Status:** code + tests in working tree, all 3 strict-TDD tests GREEN sequential and parallel, `deno fmt --check` + `deno lint` clean. **Risk:** Medium.

**Scope:** new files `oauth-foundation.ts` (188 LoC) + `oauth-foundation.test.ts` (202 LoC). Exports: `FcmConfigError`, `FcmServiceAccount`, constants (`MAX_EXPIRES_IN_SEC`, `SAFETY_WINDOW_MS`, `GOOGLE_OAUTH_TOKEN_URL`, `FCM_SCOPE`, `ASSERTION_LIFETIME_SEC`, `NOT_CONFIGURED`), cache primitives (`getCachedOAuthToken`, `setCachedOAuthToken`, `clearCachedOAuthToken`, `clearOAuthCacheForTests`), `decodeServiceAccount`, `pemToBinary`, `mintFcmAssertionJwt`. Purely additive: legacy `key=` send in `index.ts` untouched. No token-endpoint fetch in B1a1.

#### B1a1 Phase 1 — RED tests (complete)

- [x] **Task B1a1.1.1** — `decodeServiceAccount` rejects empty + non-base64
  - File: `supabase/functions/fcm-send/oauth-foundation.test.ts`
  - Test method: `B1a1.1 — decode rejects empty / per-field / invalid-base64` (first two cases)
  - Acceptance: empty secret + non-base64 input → `FcmConfigError("fcm_service_account_not_configured")`.

- [x] **Task B1a1.1.2** — `decodeServiceAccount` per-field rejection (project_id / private_key / client_email)
  - File: same
  - Test method: same `B1a1.1` (per-field loop)
  - Acceptance: each missing required field yields FcmConfigError.

- [x] **Task B1a1.1.3** — mint-stage failure paths normalize to FcmConfigError (WARNING B1A-OAUTH-002)
  - File: same
  - Test method: same `B1a1.1` (mintCases table-driven: garbage PEM + non-PKCS#8)
  - Acceptance: atob failure and importKey failure both → FcmConfigError.

- [x] **Task B1a1.2.1** — `mintFcmAssertionJwt` produces RS256 JWT with absolute iat/exp (CRITICAL B1A-OAUTH-001)
  - File: same
  - Test method: `B1a1.2 — mint: ABSOLUTE iat/exp + signature verifies against pubkey`
  - Acceptance: `payload.iat === Math.round(nowMs/1000)`; `payload.exp === payload.iat + 3600`.

- [x] **Task B1a1.2.2** — `mintFcmAssertionJwt` signature verifies against public key
  - File: same
  - Test method: same `B1a1.2` (`djwt.verify(jwt, PUBLIC_VERIFY_KEY)`)
  - Acceptance: signature verifies; payload.iss / aud match.

- [x] **Task B1a1.3.1** — cache primitives roundtrip with safety-window (WARNING B1A-TEST-001)
  - File: same
  - Test method: `B1a1.3 — cache: set populates, clear roundtrip, safety window evicts`
  - Acceptance: populated → first `clear` returns `true`; cleared → re-`clear` returns `false`; safety window at `setAt + 3_541_000ms` (59s before expiry) evicts.

#### B1a1 Phase 2 — GREEN implementations (complete)

- [x] **Task B1a1.2.1a** — `oauth-foundation.ts` exports FcmConfigError + FcmServiceAccount + cache + decode + mint + PEM
  - File: `supabase/functions/fcm-send/oauth-foundation.ts`
  - Change: introduces all foundation helpers.
  - Acceptance: B1a1.1.1–B1a1.3.1 pass.

#### B1a1 Phase 3 — Build verifier (complete)

- [x] **Task B1a1.V.1** — B1a1 focused Deno test (sequential + parallel)
  - Command: `deno test --allow-all --no-check oauth-foundation.test.ts` AND `deno test --parallel --allow-all --no-check oauth-foundation.test.ts`
  - Acceptance: 3/3 GREEN both modes.

- [x] **Task B1a1.V.2** — B1a1 fmt + lint clean
  - Command: `deno fmt --check oauth-foundation.ts oauth-foundation.test.ts` AND `deno lint oauth-foundation.ts oauth-foundation.test.ts`
  - Acceptance: `Checked 2 files` for both.

---

### Slice B1a2 — OAuth token exchange + strict response validation (additive on B1a1)

**Budget:** +321 changed lines vs post-B1a1 master (≤400). **Status:** code + tests in working tree, all 3 strict-TDD tests GREEN sequential and parallel, `deno fmt --check` + `deno lint` clean. **Risk:** Medium.

**Scope:** new files `oauth.ts` (101 LoC) + `oauth.test.ts` (220 LoC). Imports foundation types/helpers from `./oauth-foundation.ts` and adds: `getFcmAccessToken(rawSecret, fetchImpl, nowMs)`. Re-exports `MAX_EXPIRES_IN_SEC` for B1b stability. The JWT-bearer form request, strict non-2xx / invalid JSON / schema validation, finite-positive `expires_in` validation + cap, and missing-secret / no-fetch behavior all live here. No `index.ts` change. No new dependencies.

#### B1a2 Phase 1 — RED tests (complete)

- [x] **Task B1a2.1.1** — cache miss → exchange → cache hit (AUDIT FIX cap)
  - File: `supabase/functions/fcm-send/oauth.test.ts`
  - Test method: `B1a2.1 — cache miss→exchange→cache hit + JWT-bearer + cap=3600`
  - Acceptance: 1 OAuth fetch on miss; `cached.expiresAtMs === fixedNow + 3600*1000` (cap from upstream 99999); 2nd call → 0 new fetches.

- [x] **Task B1a2.1.2** — JWT-bearer grant_type body shape
  - File: same
  - Test method: same `B1a2.1`
  - Acceptance: form body contains `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` + non-empty `assertion`.

- [x] **Task B1a2.2.1** — every malformed OAuth response → oauth_token_request_failed (WARNING B1A-OAUTH-003)
  - File: same
  - Test method: `B1a2.2 — every malformed OAuth response → oauth_token_request_failed` (table-driven 14 cases)
  - Acceptance: missing/empty/numeric/object access_token + missing/zero/negative/string/null/NaN/Infinity expires_in + unrelated shape + invalid JSON + non-200 → each yields `FcmConfigError("oauth_token_request_failed")` with a generic message that does NOT echo the response body.

- [x] **Task B1a2.3.1** — empty / malformed-PEM secret → FcmConfigError, no fetch
  - File: same
  - Test method: `B1a2.3 — missing / malformed secret → FcmConfigError, no fetch`
  - Acceptance: empty secret + fake-PEM both → `FcmConfigError("fcm_service_account_not_configured")`; `oauthCalls() === 0`.

#### B1a2 Phase 2 — GREEN implementations (complete)

- [x] **Task B1a2.2.1a** — `oauth.ts` exports `getFcmAccessToken` + re-exports `MAX_EXPIRES_IN_SEC`
  - File: `supabase/functions/fcm-send/oauth.ts`
  - Change: imports foundation helpers; implements `getFcmAccessToken` with strict response validation + cap; re-exports `MAX_EXPIRES_IN_SEC` for B1b stability.
  - Acceptance: B1a2.1.1–B1a2.3.1 pass.

#### B1a2 Phase 3 — Build verifier (complete)

- [x] **Task B1a2.V.1** — B1a2 focused Deno test (sequential + parallel)
  - Command: `deno test --allow-all --no-check oauth.test.ts` AND `deno test --parallel --allow-all --no-check oauth.test.ts`
  - Acceptance: 3/3 GREEN both modes.

- [x] **Task B1a2.V.2** — B1a2 fmt + lint clean
  - Command: `deno fmt --check oauth.ts oauth.test.ts` AND `deno lint oauth.ts oauth.test.ts`
  - Acceptance: `Checked 2 files` for both.

- [x] **Task B1a2.V.3** — combined B1a1 + B1a2 suite (sequential + parallel)
  - Command: `deno test --allow-all --no-check` (all `.test.ts` in the directory)
  - Acceptance: **6/6 GREEN** both modes.

- [x] **Task B1a2.V.4** — Slice A Deno regression
  - Command: `cd ../custom-access-token-hook && deno test --allow-all --no-check --sloppy-imports`
  - Acceptance: 3/3 GREEN (no regression on Slice A hook tests).

---

### Slice B1b — additive FCM v1 transport — NOT STARTED

**Budget:** projected ~330 changed lines (≤400). **Status:** pending B1a merge.

**Scope:** new files `transport.ts` + `transport.test.ts`. The transport module consumes `getFcmAccessToken` from B1a's `oauth.ts`. Audit fixes:
- `message_id` derived from `{ type, request_id, device_token }` (audit-found ambiguity — spec wording already clarified in B1a).
- 401 from FCM endpoint clears the cache and retries ONCE with a fresh token.
- Scrubbed structured logging (no tokens / no PII).

The legacy `key=` send path in `index.ts` is **not** yet modified at B1b boundary.

#### Phase 1 — RED tests (pending)

- [ ] **Task B1b.1.1** — `buildMessageId({ type, request_id, device_token })` is stable across calls; `{ type, request_id }` alone yields a *different* id (device_token participates)
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: same triple → same hex; differing device_token → different hex.

- [ ] **Task B1b.1.2** — `buildMessageId` ignores optional payload fields (e.g., `sent_at`, `ui_state`)
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: payload differing only in optional fields → same id.

- [ ] **Task B1b.2.1** — `sendFcmV1` POSTs to the v1 messages:send URL with v1 `Message` shape (token, data, android.priority)
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: captured URL + body match.

- [ ] **Task B1b.2.2** — `sendFcmV1` uses `Authorization: Bearer <oauth-token>`; never `key=`
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: header equals `Bearer <token>`; no `key=` substring.

- [ ] **Task B1b.3.1** — `sendFcmV1` first 401 → clears cache, retries ONCE with a fresh token
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: 2 OAuth fetches (initial + refresh), 2 FCM POSTs (initial 401 + one retry); retry Authorization header uses the new token.

- [ ] **Task B1b.4.1** — `logStructured` emits JSON to stdout; `scrubbedBody` redacts JWT-shaped substrings + Bearer tokens
  - File: `supabase/functions/fcm-send/transport.test.ts`
  - Acceptance: capture of `console.log` line equals `JSON.stringify({level, event, …})` with no `key=`/JWT substrings in the body.

#### Phase 2 — GREEN implementations (pending)

- [ ] **Task B1b.2.1** — `transport.ts` exports `sendFcmV1`, `buildMessageId`, `interpretResponse`, `logStructured`, `scrubbedBody`, types `FcmPayload`, `FcmV1SendResult`
  - File: `supabase/functions/fcm-send/transport.ts`
  - Change: depends on `oauth.ts` for `getFcmAccessToken` + cache helpers.

- [ ] **Task B1b.4.1** — structured-logging scrubber
  - File: `supabase/functions/fcm-send/transport.ts`
  - Change: regex redaction of JWT-shaped (`xxxxx.yyyyy.zzzzz`), Google-shaped (`ya29.…`), and `Bearer …` substrings; capped message length.

#### Phase 3 — Build verifier (pending)

- [ ] **Task B1b.3.1** — Deno tests for transport
  - Command: `cd supabase/functions/fcm-send && deno test --allow-all --no-check`

- [ ] **Task B1b.3.2** — Slice A Deno regression
  - Command: `cd supabase/functions/custom-access-token-hook && deno test --allow-all --no-check --sloppy-imports`

- [ ] **Task B1b.3.3** — Android regression gate
  - Command: `./gradlew :app:testDebugUnitTest`

---

### Slice B1c — handler cutover/integration — NOT STARTED

**Budget:** projected ~140 changed lines (≤400). **Status:** pending B1a + B1b merge.

**Scope:** minimal `index.ts` modification. The legacy 142-line `key=` send path is replaced by `sendFcmV1(...)` (from B1b's `transport.ts`), and `getFcmAccessToken(...)` (from B1a's `oauth.ts`) handles the service-account secret. The diff is bounded because `index.ts` was re-baselined to master in B1a — at this stage B1c only inserts imports + replaces the `sendFcm(...)` call with `sendFcmV1(...)`. Audit hygiene: integration tests for the handler; **no** duplicated OAuth/transport unit scenarios (B1a/B1b cover those).

#### Phase 1 — RED tests (pending)

- [ ] **Task B1c.1.1** — `index.ts` exports a `serve` handler that, on `{ device_id, payload, priority }` input, looks up an FCM token from `device_push_tokens`, calls `sendFcmV1`, and returns a JSON response with `message_id`
  - File: `supabase/functions/fcm-send/integration.test.ts`
  - Acceptance: stub fetch captures the expected v1 POST + Bearer auth; the response body contains the FCM-assigned message id.

- [ ] **Task B1c.1.2** — handler maps `FcmConfigError("fcm_service_account_not_configured")` to a 500 with the matching error code; logs the failure with structured `request_id` and project_id
  - File: `supabase/functions/fcm-send/integration.test.ts`
  - Acceptance: response status 500 + body `{ "error": "fcm_service_account_not_configured" }`; captured log line is JSON with project_id.

- [ ] **Task B1c.1.3** — handler maps legacy `key=` references to `key=` to nothing (regression guard: `key=` substring is absent from the handler response, request, and logs)
  - File: `supabase/functions/fcm-send/integration.test.ts`
  - Acceptance: scrubbing test confirms.

#### Phase 2 — GREEN implementations (pending)

- [ ] **Task B1c.2.1** — `index.ts` imports `sendFcmV1` from `./transport.ts` and `getFcmAccessToken` from `./oauth.ts`
  - File: `supabase/functions/fcm-send/index.ts`
  - Change: replace the legacy `sendFcm(...)` call site with `sendFcmV1(rawSecret, deviceToken, payload, priority)`.

- [ ] **Task B1c.2.2** — `index.ts` catch arms translate `FcmConfigError` to `500` with `{ "error": <code> }`; non-config errors translate to `500` with scrubbed message.
  - File: `supabase/functions/fcm-send/index.ts`

#### Phase 3 — Build verifier (pending)

- [ ] **Task B1c.3.1** — full Deno suite (oauth + transport + integration)
  - Command: `cd supabase/functions/fcm-send && deno test --allow-all --no-check`

- [ ] **Task B1c.3.2** — Slice A Deno regression
  - Command: `cd supabase/functions/custom-access-token-hook && deno test --allow-all --no-check --sloppy-imports`

- [ ] **Task B1c.3.3** — Android regression gate
  - Command: `./gradlew :app:testDebugUnitTest`

- [ ] **Task B1c.3.4** — build + lint baseline
  - Command: `./gradlew ktlintCheck :app:assembleDebug`

---

## Review Workload Forecast (reset)

| Slice | LoC (additions + deletions vs base) | Budget (400) | Status | Notes |
|---|---:|---:|---|---|
| Slice A + continuations + T3 | actual: 2,911 / 724 | 400 | over; user pre-accepted at PR #29 | merged to master as 9c89c0e |
| **Slice B1a1** (this apply) | **actual: +390 changed (188 oauth-foundation.ts + 202 oauth-foundation.test.ts)** | 400 | **UNDER** ✓ | **prepared in working tree** on `feat/cross-device-pairing-and-approval-slice-b1` (uncommitted, untracked); first stacked-to-main sub-pr; commit/push/PR pending user authorization |
| **Slice B1a2** (this apply, stacks on B1a1) | **actual: +400 changed (101 oauth.ts + 299 oauth.test.ts)** vs post-B1a1 master | 400 | **AT CAP** ✓ | **prepared in working tree**; second stacked-to-main sub-pr; B1a1 must merge first; no review-budget headroom remains |
| Slice B1b (projected) | ~330 changed (180 transport.ts + 150 transport.test.ts) | 400 | UNDER (projected) | depends on B1a1 + B1a2 |
| Slice B1c (projected) | ~140 changed (~80 index.ts + ~60 integration.test.ts) | 400 | UNDER (projected) | depends on B1a1 + B1a2 + B1b |
| Slice B2 | 150-250 | 400 | UNDER | pending |
| Slice C | 200-300 | 400 | UNDER | pending |
| Slice D | 80-150 | 400 | UNDER | pending |

**Chained PRs recommended:** Yes. **Chained strategy:** stacked-to-main (A → B1a1 → B1a2 → B1b → B1c → B2 → C → D). B1a's monolithic 514-LoC attempt is split into B1a1 (credential/JWT/cache foundation) and B1a2 (token exchange + strict response validation); B1a1 lands first, B1a2 stacks on top.
**400-line budget risk:** Medium — B1a1 is under budget at 390 changed lines, while B1a2 is exactly at the 400-line cap with no remaining review-budget headroom. Slice A's over-budget precedent is **not** inherited.
**Decision needed before apply:** No — both B1a1 and B1a2 fit the ≤400 LoC cap with reviewer-required coverage preserved; any additional B1a2 change requires compaction or a new slice.

## Slice B2 — Real FirebaseMessagingService — NOT STARTED

**Budget:** ~150-250 LoC. **Status:** pending B1.  
**Risk:** High — fixes the real FCM receiver blocker while preserving static callers.

### Phase 1 — RED tests (pending)

- [ ] **Task B2.1.1** — `FcmPushServiceTest.isFirebaseMessagingServiceSubclass`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `classHierarchy_isFirebaseMessagingServiceSubclass()`
  - Acceptance: `FcmPushService` is assignable to `FirebaseMessagingService`.

- [ ] **Task B2.1.2** — `FcmPushServiceTest.instantiableByReflection`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `reflectiveNewInstance_succeeds()`
  - Acceptance: no-arg reflection construction succeeds.

- [ ] **Task B2.1.3** — `FcmPushServiceTest.onMessageReceived_invokesHandler`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `onMessageReceived_dispatchesToFcmWorkHelper()`
  - Acceptance: data message enqueues high-priority sync.

- [ ] **Task B2.1.4** — `FcmPushServiceTest.getInstance_returnsSingleton`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `getInstance_returnsSameInstanceAcrossCalls()`
  - Acceptance: static accessor returns one application-safe instance.

- [ ] **Task B2.1.5** — `FcmPushServiceTest.staticCallersStillWork`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `staticProcessMessage_delegatesToInstance()`
  - Acceptance: existing `FcmHelper.kt` callers keep working.

- [ ] **Task B2.1.6** — `FcmPushServiceTest.onNewToken_callsProcessNewToken`
  - File: `app/src/test/java/com/tudominio/parentalcontrol/push/FcmPushServiceTest.kt`
  - Test method: `onNewToken_invokesProcessNewToken()`
  - Acceptance: token refresh delegates to registration flow.

### Phase 2 — GREEN implementations (pending)

- [ ] **Task B2.2.1** — make `FcmPushService` extend `FirebaseMessagingService`
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: change class hierarchy and import Firebase messaging types.
  - Acceptance: B2.1.1 passes.

- [ ] **Task B2.2.2** — add `getInstance(context)` accessor
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: provide thread-safe singleton backed by application context.
  - Acceptance: B2.1.4 passes.

- [ ] **Task B2.2.3** — move static processing into instance methods
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: preserve companion-object delegates while routing through instance methods.
  - Acceptance: B2.1.3, B2.1.5, and B2.1.6 pass.

- [ ] **Task B2.2.4** — override `onMessageReceived` and `onNewToken`
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: parse data-only messages and register rotated tokens.
  - Acceptance: real receiver methods dispatch to existing work helpers.

- [ ] **Task B2.2.5** — verify manifest receiver registration
  - File: `app/src/main/AndroidManifest.xml`
  - Change: no production change expected; confirm existing service points to the real receiver.
  - Acceptance: merged manifest contains `com.google.firebase.MESSAGING_EVENT` for `FcmPushService`.

### Phase 3 — Refactor (pending)

- [ ] **Task B2.3.1** — extract message-type dispatch helper
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: route payload types through a private helper for Slice D's `child.paired` branch.
  - Acceptance: current grant sync behavior remains green.

### Phase 4 — Build verifier (pending)

- [ ] **Task B2.4.1** — JVM receiver tests
  - File: project root
  - Command: `./gradlew testDebugUnitTest`
  - Acceptance: B2.1.1-B2.1.6 are GREEN.

- [ ] **Task B2.4.2** — debug APK and manifest dump
  - File: project root
  - Command: `./gradlew assembleDebug`
  - Acceptance: APK builds and manifest contains the FCM receiver service.

- [ ] **Task B2.4.3** — lint gates for touched files
  - File: project root
  - Command: `./gradlew ktlintCheck detekt`
  - Acceptance: no new violations on B2 changes.

- [ ] **Task B2.4.4** — physical-device FCM smoke
  - File: runtime smoke notes
  - Command: trigger data-only FCM to device `XSI7M7XG99F6ZLEI`
  - Acceptance: `FcmPushService` logs receipt and enqueues sync.

## Slice C — Cross-device harness + CI deploy — NOT STARTED

**Budget:** ~200-300 tooling + CI. **Status:** pending A+B1+B2.  
**Risk:** Low — tooling and CI, no production app behavior.

### Phase 1 — RED tests (pending)

- [ ] **Task C.1.1** — `start-stack.sh` shellcheck and dry-run gates
  - File: `tools/cross-device/start-stack.sh`
  - Test method: `shellcheck` plus `--help`/missing-env exit checks.
  - Acceptance: nonexistent script fails before GREEN implementation.

- [ ] **Task C.1.2** — `smoke-test.sh` exit-code gates
  - File: `tools/cross-device/smoke-test.sh`
  - Test method: `shellcheck` plus preflight failure when stack is missing.
  - Acceptance: failures are stage-tagged and non-zero.

- [ ] **Task C.1.3** — `lib/inbucket.sh` magic-link polling helper
  - File: `tools/cross-device/lib/inbucket.sh`
  - Test method: source function with timeout and stubbed `curl` mailbox fixture.
  - Acceptance: returns magic-link URL on fixture and non-zero on timeout.

### Phase 2 — GREEN implementations (pending)

- [ ] **Task C.2.1** — boot local Supabase + Firebase emulator stack
  - File: `tools/cross-device/start-stack.sh`
  - Change: start both stacks, wait for readiness, and apply adb reverse for parent/child devices.
  - Acceptance: ready within 5 minutes or fail with clear stage.

- [ ] **Task C.2.2** — poll Inbucket REST API for magic-link URL
  - File: `tools/cross-device/lib/inbucket.sh`
  - Change: poll `:54324/api/v1/mailbox/{email}` and extract latest magic-link.
  - Acceptance: C.1.3 passes.

- [ ] **Task C.2.3** — structured stderr logging helpers
  - File: `tools/cross-device/lib/log.sh`
  - Change: add `log_info` and `log_error` helpers with stage fields.
  - Acceptance: smoke failures are diagnosable.

- [ ] **Task C.2.4** — end-to-end smoke-test driver
  - File: `tools/cross-device/smoke-test.sh`
  - Change: drive parent sign-in, child pairing, time request, FCM push, approval, and child verdict.
  - Acceptance: exits 0 on the 2-device happy path.

- [ ] **Task C.2.5** — edge-function deploy workflow
  - File: `.github/workflows/deploy-edge-functions.yml`
  - Change: deploy all 11 functions on push to `master`, setting secrets without logging them.
  - Acceptance: workflow parses and `supabase functions list` count is verified.

- [ ] **Task C.2.6** — cross-device smoke workflow
  - File: `.github/workflows/cross-device-smoke.yml`
  - Change: provision ephemeral Supabase project, apply migrations, run smoke, and tear down.
  - Acceptance: workflow exits green on a test PR.

### Phase 3 — Refactor (pending)

- [ ] **Task C.3.1** — extract common adb helpers
  - File: `tools/cross-device/lib/adb.sh`
  - Change: add `adb_shell`, `adb_reverse`, and `adb_logcat_grep` helpers used by harness scripts.
  - Acceptance: shellcheck still passes.

### Phase 4 — Build verifier (pending)

- [ ] **Task C.4.1** — local stack boot smoke
  - File: `tools/cross-device/start-stack.sh`
  - Command: `./tools/cross-device/start-stack.sh`
  - Acceptance: Supabase and Firebase emulators reach ready.

- [ ] **Task C.4.2** — cross-device happy-path smoke
  - File: `tools/cross-device/smoke-test.sh`
  - Command: `./tools/cross-device/smoke-test.sh`
  - Acceptance: all stages pass after A+B1+B2 are merged.

- [ ] **Task C.4.3** — shellcheck harness scripts
  - File: `tools/cross-device/*.sh`, `tools/cross-device/lib/*.sh`
  - Command: `shellcheck tools/cross-device/*.sh tools/cross-device/lib/*.sh`
  - Acceptance: no shellcheck errors.

- [ ] **Task C.4.4** — workflow syntax and secrets propagation dry-run
  - File: `.github/workflows/deploy-edge-functions.yml`, `.github/workflows/cross-device-smoke.yml`
  - Command: `act -j deploy` or push to a test branch.
  - Acceptance: workflow YAML parses and secrets are never echoed.

## Slice D — Pairing FCM-to-parent (optional) — NOT STARTED

**Budget:** ~80-150 LoC. **Status:** pending A+B1+B2+C.  
**Risk:** Low — best-effort parent notification; migration rollback is symmetric.

### Phase 1 — RED tests (pending)

- [ ] **Task D.1.1** — `PairingIndexTest.notifiesParentOnSuccess`
  - File: `supabase/functions/pairing/pairing.test.ts`
  - Test method: `Deno.test("pairing triggers child.paired FCM on success")`
  - Acceptance: successful pairing posts v1 FCM with `type=child.paired`.

- [ ] **Task D.1.2** — `PairingIndexTest.missingParentTokens_logsAndContinues`
  - File: `supabase/functions/pairing/pairing.test.ts`
  - Test method: `Deno.test("missing parent tokens logs info and returns 200")`
  - Acceptance: missing tokens log an info skip and pairing still returns 200.

### Phase 2 — GREEN implementations (pending)

- [ ] **Task D.2.1** — replace pairing FCM stub with real parent fanout
  - File: `supabase/functions/pairing/index.ts`
  - Change: query active parent tokens and call `fcm-send` with `child.paired` data payload.
  - Acceptance: D.1.1 and D.1.2 pass.

- [ ] **Task D.2.2** — surface `child.paired` as a local notification
  - File: `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt`
  - Change: add dispatch branch for `child.paired` and show a local notification.
  - Acceptance: child pairs and parent sees notification within 5 seconds.

### Phase 3 — Refactor (pending)

No refactor task planned; the Slice D production change is intentionally small.

### Phase 4 — Build verifier (pending)

- [ ] **Task D.4.1** — Android JVM regression tests
  - File: project root
  - Command: `./gradlew testDebugUnitTest`
  - Acceptance: no new Android-side failures.

- [ ] **Task D.4.2** — pairing edge-function tests
  - File: `supabase/functions/pairing/`
  - Command: `deno test --allow-all`
  - Acceptance: D.1.1 and D.1.2 are GREEN.

- [ ] **Task D.4.3** — pairing notification manual smoke
  - File: Slice C harness
  - Command: run `smoke-test.sh` with parent token seeded.
  - Acceptance: parent notification appears within 5 seconds of successful pairing.

## Verification gate per slice

- `./gradlew testDebugUnitTest` → slice-owned RED→GREEN tests must pass or be documented as pre-existing baseline failures.
- `./gradlew assembleDebug` → debug APK must build.
- `./gradlew ktlintCheck` → green for touched code; existing violations remain out of scope unless touched.
- `./gradlew detekt` → currently has pre-existing infrastructure failure on master; do not attribute it to this change without re-verification.
- Deno tests for B1/D edge-function work → green before those slices merge.
- RLS scaffold bodies for A.1.7/A.1.8 → implemented by Slice C CI harness.

## Expected next SDD step

Current native status: `nextRecommended=apply`, `applyState=ready` (B1a2 test/evidence hardening was the latest apply round). Current operational next step is the **final fresh-gate review** of B1a2 (after this remediation): the four B1A2-TEST-QUALITY-001 review items are now closed (TS2353 captured-call shape fixed; NaN/Infinity replaced with JSON-representable boolean/array; request contract asserts exact URL + POST + Content-Type; explicit no-response-body-leak sentinel assertion). Once the fresh gate clears, the user will authorize commit + push + open PR for B1a1, then B1a2 stacked on top, then `apply-continuation` for B1b.
