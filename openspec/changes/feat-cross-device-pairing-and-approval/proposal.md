---
change: feat-cross-device-pairing-and-approval
title: Real cross-device pairing and approval flow
status: draft
created: 2026-07-09
author: sdd-propose
---

# Proposal: Real cross-device pairing and approval flow

## 1. Problem statement

The ParentalControl app currently runs end-to-end against an in-memory `MockSupabaseEngine` (`app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt`) with `BuildConfig.USE_MOCK_SUPABASE=true` as the default debug flag (`openspec/config.yaml` `testing.gotchas`). Live validation of the previous cycle (`feat-parent-behavioral-event-log` + `fix-behavioral-event-log-fixture-loading` + `fix-migrate-stale-parent-id-on-load`, archived as PR #28 at `5e392a6`) confirmed that 5 fixture `BehavioralEvent`s render in `BehaviorLogScreen` and `SolicitudesScreen` against the mock engine — but this validation hides every bug that lives at the network boundary: RLS denials, missing JWT claims, FCM failures.

Three concrete blockers prevent real cross-device:

1. `FcmPushService.kt:18` is **not a `FirebaseMessagingService` subclass** — it is a plain class with `companion object` static callbacks. The Android manifest entry at `AndroidManifest.xml:122-128` advertises the intent filter `com.google.firebase.MESSAGING_EVENT`, but no FCM receiver is registered, so real pushes never reach the parent device.
2. `custom-access-token-hook/index.ts:42-56` copies only `device_id` from `app_metadata` into the JWT. RLS policies keyed on `parent_id = auth.uid()` (e.g., `children_parent_select` from migration `005_children_table.sql`, `time_requests_parent_select`, `devices_parent_select`) deny real-Supabase parents because `parent_id` is never injected.
3. `fcm-send/index.ts:97-119` uses the deprecated `Authorization: key=<FCM_SERVER_KEY>` legacy FCM API. Google deprecated the legacy endpoint in June 2024; new FCM projects may not even surface a server key. Cloud FCM calls now fail or silently return mock data.

Three bonus blockers ride along: `behavioral_events` was created in migration `004_parent_outcome_checkins.sql:85-99` with a `parent_events` SELECT policy but the explore's risk surface calls out a missing `behavioral_events_parent_select` for cloud-RLS parity; there is no real parent sign-up/sign-in flow (only `DeviceAuthManager.createAnonymousSession`); and there is no CI path for `supabase functions deploy`, so all 11 edge functions ship by hand.

## 2. Goal

Deliver a real cross-device end-to-end loop on 2 physical devices (or 1 device + 1 emulator) against local Supabase + the Firebase emulator: the parent signs up/signs in via real Supabase Auth, the parent generates a pairing code that expires in 10 minutes, the child pairs via the `pairing` edge function with a device JWT, `app_metadata.parent_id` is injected into the parent JWT so RLS reads work, the child writes a `time_request`, the parent receives an FCM v1 push on its real `FirebaseMessagingService`, the parent approves or denies, the verdict propagates to the child, and `BehaviorLogScreen` renders the parent's events from real cloud data. Validation harness runs against `supabase start` locally and ships as CI that deploys the 11 edge functions on merge to master. Single source of truth for `parent_id` = real `auth.uid()`; clean cutover with no `MOCK_PARENT_ID` migration helper.

## 3. Scope

### In scope

- **Blocker 1 — Real `FirebaseMessagingService`**: rewrite `FcmPushService.kt` as `class FcmPushService : FirebaseMessagingService()` with `onMessageReceived(remoteMessage)` and `onNewToken(token)`. Manifest intent filter stays at `AndroidManifest.xml:122-128`. JVM unit tests pin the class is reflectively instantiable. ~150-250 LoC.
- **Blocker 2 — `parent_id` JWT injection + `behavioral_events` RLS hardening**: `custom-access-token-hook/index.ts` reads `app_metadata.parent_id` and injects it as a first-level claim. New migration `007_behavioral_events_parent_select.sql` adds/renames the `behavioral_events` SELECT policy to the project's `*_parent_select` naming convention with `parent_id = auth.uid()`. Edge function tests cover the hook; JVM RLS regression test pins the policy against a real Supabase local DB. ~80-150 LoC.
- **Blocker 3 — FCM v1 OAuth rewrite**: `fcm-send/index.ts` rewritten to `POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` with a Bearer token derived from the service-account JSON in `supabase secrets`. Service-account JSON is set via `supabase secrets set FCM_SERVICE_ACCOUNT_KEY=...` from CI, never committed. Edge function test mocks the v1 endpoint with `fetch` stub. ~100-200 LoC.
- **Bonus — Parent sign-up/sign-in flow**: `DeviceAuthManager` gains `signUpAsParent(email, password)` and `signInAsParent(email, password)` against real Supabase Auth. `OnboardingScreen` wires the new path; child path stays on the anonymous JWT. JVM tests for happy path + invalid credentials + email-conflict. ~150-250 LoC.
- **Bonus — Edge function CI deploy**: `.github/workflows/deploy-functions.yml` runs `supabase functions deploy <name>` for all 11 functions on merge to master, using `SUPABASE_ACCESS_TOKEN` and `SUPABASE_PROJECT_REF` secrets. ~80-120 LoC YAML.
- **Bonus — Cross-device validation harness**: scripts under `tools/cross-device/` that boot `supabase start` + Firebase emulator + build the APK with `-PuseRealSupabase=true` + adb-reverse two devices + run the end-to-end smoke test documented at `tools/cross-device/README.md`. ~200-300 LoC of tooling.
- **Clean cutover for `parent_id`** (no migration helper): every existing install re-authenticates against real Supabase on next cold start; the previous `MOCK_PARENT_ID` fallback in `DeviceAuthManager.loadPersistedState` is removed.
- **Tests**: JVM unit tests for the parent auth path, JVM unit tests for the FCM receiver contract, edge function tests for `fcm-send` v1 (mocked fetch), JVM RLS regression test for `behavioral_events_parent_select`.
- **`register-token` edge function** (`supabase/functions/register-token/index.ts`): wires the parent's FCM token into a new `parent_push_tokens` table (or extends `device_push_tokens.parent_id`). Required for the cross-device push to land.

### Out of scope (deferred to follow-up changes)

- **Multi-device per child** (Q4 product decision): 1 device per child in this MVP.
- **Web admin for parents**: not on the roadmap.
- **Production FCM**: v1 works against the Firebase emulator + a project with a service account; production rollout with rate limits and dead-letter handling is a separate change.
- **`parent_id = "parent-demo"` migration helper** (Q2=b clean cutover): every install re-authenticates; no backfill path.
- **`shared-mock-server` enhancements** (`register-token` pass-through): user explicitly chose Q1=b (real cloud) over Q1=a (shared-mock).
- **Email verification vs magic-link** (carries to spec).
- **Realtime push of new pending requests into Solicitudes** without tab switch (already deferred in `time-request-approval/spec.md`).

## 4. Approach (sequenced slices — chained PRs)

This change is too large for one PR. Total estimate: **~860-1300 LoC** across production + tests + tooling + CI, well above the **400-line review budget** cached in session preflight (`chained_pr_strategy: ask-always`, `review_budget_lines: 400`). The proposal explicitly requests user approval for the **chained-PR strategy at the tasks phase** before any slice implementation begins.

The slice order is sequenced by **dependency**: auth must land before RLS can be validated against a real parent; RLS must land before FCM has anyone to push to; FCM receiver must exist before the harness can validate end-to-end. Each slice ends with a green `./gradlew testDebugUnitTest` + `./gradlew assembleDebug` + a slice-specific smoke gate.

### Slice 1 — Parent sign-up/sign-in (`DeviceAuthManager` real auth)
**Ships**: `signUpAsParent(email, password)` + `signInAsParent(email, password)` against real Supabase Auth. `OnboardingScreen` gains a parent sign-in form. Child path untouched.
**LoC budget**: ~150-250 production + ~100-150 tests.
**Risk**: Medium. Touches auth; must not break the child anonymous path or the existing `authenticateOrCreate(Role.PARENT)` mock path.
**Verification gate**: (a) `./gradlew testDebugUnitTest` green; (b) `./gradlew :app:assembleDebug` green; (c) manual: build APK with `-PuseRealSupabase=true`, sign up a parent, verify `auth.users` row created and `app_metadata.parent_id` populated; (d) CI gate: `e2e_parent_signup` job (JVM-only smoke; no emulator required).

### Slice 2 — Hook `parent_id` injection + `behavioral_events` RLS
**Ships**: `custom-access-token-hook/index.ts:42-56` rewritten to read `app_metadata.parent_id` and inject it as a top-level JWT claim. New migration `007_behavioral_events_parent_select.sql` (adds or renames the `behavioral_events` SELECT policy to `behavioral_events_parent_select`, keyed on `parent_id = auth.uid()`). Edge function test against the hook with a stubbed Supabase event payload. JVM RLS regression test that runs the migration against `supabase start` (via the cross-device harness from Slice 5) and asserts the policy is present and denies a sibling-parent SELECT.
**LoC budget**: ~80-150 production + ~80-120 tests.
**Risk**: High. Touches every parent-facing RLS read path; if `app_metadata.parent_id` is missing on a real auth.users row, the entire parent dashboard 403s.
**Verification gate**: (a) edge function tests green; (b) JVM RLS regression green; (c) manual: sign in as parent → `BehaviorLogScreen` renders events against real cloud (was previously only mock-validated); (d) sibling-parent test passes (parent A cannot read parent B's events).

### Slice 3 — FCM v1 OAuth rewrite
**Ships**: `fcm-send/index.ts` rewritten to call `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` with an OAuth Bearer token derived from the service-account JSON in `supabase secrets`. Service-account JSON is **never** committed to git; the workflow that sets it lives in Slice 5. Edge function test mocks the v1 endpoint via `globalThis.fetch = stub` and asserts the body shape + auth header.
**LoC budget**: ~100-200 production + ~80-120 tests.
**Risk**: Medium. Only fires on real cloud; hard to test in JVM. Service-account JWT signing requires a tiny JWT lib (e.g., `djwt`) in Deno.
**Verification gate**: (a) edge function tests green; (b) local end-to-end against the Firebase emulator: child pairs, parent receives a high-priority data message; (c) CI: `fcm_v1_smoke` job uses a project-scoped service account and asserts the v1 endpoint returns 200.

### Slice 4 — Real `FirebaseMessagingService`
**Ships**: `FcmPushService.kt:18` rewritten as `class FcmPushService : FirebaseMessagingService()`. `onMessageReceived(remoteMessage)` parses the `data` payload (data-only messages — required for Firebase emulator support), dispatches `FcmWorkHelper.enqueueHighPrioritySync(context)` per the existing static helpers. `onNewToken(token)` calls `FcmPushService.processNewToken(applicationContext, token)`. Manifest entry stays at `AndroidManifest.xml:122-128`. Static `companion object` callers (`FcmHelper.kt:22,36,56,60`) are refactored to call instance methods via a `FcmPushService.getInstance(context)` accessor (preserves the existing public API used by tests and `FcmHelper`). JVM unit test pins the class is instantiable by `Class.forName(...).newInstance()` (mirrors WorkManager's HiltWorker reflection contract).
**LoC budget**: ~150-250 production + ~80-120 tests.
**Risk**: High. This is the showstopper: if it's wrong, no push reaches the parent on real cloud. Coexists with static callers via the refactor.
**Verification gate**: (a) JVM test green; (b) `./gradlew :app:assembleDebug` green with a debug-signed APK; (c) manual on physical device: trigger an FCM via the Firebase console → `FcmPushService.onMessageReceived` fires → `FcmWorkHelper.enqueueHighPrioritySync` runs.

### Slice 5 — Cross-device validation harness + CI edge deploy
**Ships**: `tools/cross-device/start-stack.sh` (boots `supabase start` + Firebase emulator + adb-reverse for 2 devices), `tools/cross-device/smoke-test.sh` (end-to-end: parent signs up, generates pairing code, child scans, child requests time, parent approves via FCM, child reflects verdict), `tools/cross-device/README.md` with the manual runbook. `.github/workflows/deploy-functions.yml` deploys all 11 edge functions on merge to master. `.github/workflows/cross-device-smoke.yml` runs the smoke against an ephemeral Supabase + Firebase project.
**LoC budget**: ~200-300 tooling + ~80-120 CI YAML.
**Risk**: Low. Tooling, not production code.
**Verification gate**: (a) `tools/cross-device/start-stack.sh` boots clean on the dev machine; (b) `tools/cross-device/smoke-test.sh` exits 0 against the local stack; (c) CI: `cross_device_smoke` workflow green on a temporary branch.

### Slice 6 (optional, may fold into Slice 4) — Pairing FCM-to-parent notification
**Ships**: `pairing/index.ts:288-298` no longer skips the parent notification (the explicit "deferred" TODO at `pairing/index.ts:237` from `pairing-flow/spec.md:50`). New migration `008_device_push_tokens_parent_id.sql` adds a `parent_id` column to `device_push_tokens` (or a sibling `parent_push_tokens` table). On successful pairing, `pairing` looks up the parent's push tokens and calls `fcm-send` with payload `{ type: "child.paired", device_id, child_first_name }`.
**LoC budget**: ~80-150 production + ~50-80 tests.
**Risk**: Medium. New column = new RLS policy; sibling-parent test from Slice 2 covers the denials.
**Verification gate**: (a) edge function tests green; (b) manual: child pairs → parent's device receives a "child paired" notification within 5 seconds.

### Slice ordering & dependencies

```
[1 Parent auth] → [2 Hook parent_id + behavioral_events RLS] → [3 FCM v1] → [4 Real FirebaseMessagingService] → [5 Harness + CI] → [6 (optional) Pairing notification]
```

Slices 1, 2, 3, 5 are independently mergeable. Slice 4 is the showstopper and merges last in the production-code sequence (before Slice 5 to validate the receiver with real pushes). Slice 6 folds into Slice 4 if review budget permits, otherwise becomes its own PR.

## 5. Tradeoffs

| Decision | Alternative | Why we picked this |
|---|---|---|
| Validation path = (b) Local Supabase + Firebase emulator | (a) `shared-mock-server` extension; (c) big-bang real cloud | User explicitly chose (b). Shared-mock is faster first PR but skips FCM end-to-end — defeats the purpose of validating cross-device. Big-bang is too much risk at once. Local Supabase mirrors production RLS + JWT + edge function semantics with offline safety. |
| `parent_id` clean cutover (no migration helper) | (a) Lazy backfill like `fix-migrate-stale-parent-id-on-load` | Less code, simpler contract. Existing installs re-pair, which is acceptable for a parental-control app where the parent is the user. Pre-PR-#27 stale `parent_id = "parent-demo"` prefs are wiped on first cold start against real cloud. |
| Full FCM v1 cutover (no legacy parallel) | (a) Legacy `key=` API + v1 fallback; (b) keep legacy only | Cleaner, less surface area. Legacy still works on some accounts but Google deprecated in 2024 and removed the server-key UI from new projects. Parallel mode doubles the secret surface and the test matrix. |
| 1 device per child in MVP | Multi-device per child from day 1 | Slice the risk. Each new device dimension doubles the RLS + UI surface (cross-device policy propagation, per-device grants, per-device FCM fanout). Defer to follow-up change once the 1-device path is stable. |
| No web admin for parents | Build a parent web dashboard in parallel | Out of scope per product decision. Mobile-only is fine for MVP. |
| Chained PRs (~6 slices) | One ~1300 LoC PR | Reviewer burnout, hard to bisect failures, blocks parallel work. 400-line review budget enforced. Slicing by dependency lets each PR land independently. |
| Service-account JSON via `supabase secrets` (not in repo) | Commit a sanitized dev service-account JSON | Production-safer; CI sets it from GitHub secrets. Dev convenience vs. ops safety — we chose ops safety. |
| Data-only FCM messages (not notification messages) | Notification messages with a UI payload | Firebase emulator only supports data messages; cross-device test only works with data. Production can keep data-only (we still control the UI). |
| `behavioral_events_parent_select` as a new migration | Patch migration 004 in place | Migration history is immutable; new file preserves audit trail and lets us drop the older `parent_events` policy cleanly. |
| Email verification deferred to spec | Magic-link from day 1 | Avoids the parent needing to remember a password during smoke tests; magic-link is the follow-up change. |

## 6. Risks

| # | Severity | Risk | Mitigation |
|---|---|---|---|
| R1 | HIGH | FCM v1 OAuth in CI: service-account JSON must be in `supabase secrets`, never in repo. CI needs a secure `supabase secrets set` step. | Slice 5's CI workflow uses GitHub Actions secrets `FCM_SERVICE_ACCOUNT_KEY_B64` (base64-encoded JSON) + `SUPABASE_ACCESS_TOKEN`. Decoded at runtime in the workflow, piped to `supabase secrets set --no-verify`. Never echoed to logs. Documented at `tools/cross-device/README.md`. |
| R2 | HIGH | Hook change can RLS-deny the parent on real cloud if `app_metadata.parent_id` is missing on `auth.users`. | Slice 1's sign-up path sets `app_metadata.parent_id = auth.uid()` **atomically** with the `auth.users` insert (admin API). Slice 2's edge function test pins the hook's behaviour for both "has parent_id" and "missing parent_id" paths. JVM RLS regression in Slice 5 validates a real sibling-parent denial. |
| R3 | HIGH | Real `FirebaseMessagingService` must coexist with the static `companion object` callers (`FcmHelper.kt:22,36,56,60`) — refactor carefully or break JUnit tests that mock the static callbacks. | Slice 4 keeps the `companion object` static entry points for backward compatibility (they delegate to a `FcmPushService.getInstance(context)` accessor + instance method). Existing tests that mock the static calls stay green. New JVM test pins the class is reflectively instantiable. |
| R4 | MEDIUM | Local Supabase + Firebase emulator don't perfectly mirror production: secrets differ, FCM emulator only supports data messages (no notification messages), RLS differences in `auth.uid()` claim shape. | Use data-only messages for cross-device testing (documented at `tools/cross-device/README.md`). Test the JWT claim shape in Slice 2 against `supabase start`. CI uses an ephemeral Supabase project, not prod. |
| R5 | MEDIUM | Parent sign-up flow exposes a real `auth.users` row — needs email verification or magic-link alternative. | Slice 1 ships with email + password for smoke (and confirmed email = false on local Supabase). Magic-link OR email verification is flagged for spec phase (open clarification Q1). |
| R6 | MEDIUM | Edge function CI deploy: needs the Supabase access token in GitHub secrets; token rotation is a manual ops task. | Slice 5 documents the rotation runbook at `tools/cross-device/README.md`. Deploy workflow uses `SUPABASE_ACCESS_TOKEN` from repo secrets + scopes it via the Supabase CLI's project-level token. |
| R7 | MEDIUM | Chained-PR review fatigue: 6 slices is the upper bound for this project; reviewer may push back on the granularity. | Session preflight cached `chained_pr_strategy: ask-always`. Tasks phase will explicitly request user approval for chained-PR strategy + slice granularity before slicing begins. |
| R8 | LOW | Clean cutover invalidates existing mock-mode `BehaviorLog` state — parents will see empty log until paired + on real cloud. | Documented in user-facing release notes. The `BehaviorLogScreen` already handles the empty state gracefully (per `feat-parent-behavioral-event-log` archive). |
| R9 | LOW | Slice 6's optional pairing notification adds `device_push_tokens.parent_id` — schema migration is reversible. | Migration is `ALTER TABLE ... ADD COLUMN IF NOT EXISTS parent_id UUID` + new RLS policy; rollback is `DROP POLICY + DROP COLUMN`. |
| R10 | LOW | Deno JWT lib (`djwt`) for FCM v1 OAuth token signing adds a runtime dependency to the edge function. | Pinned version in `import_map.json`; documented at `supabase/functions/fcm-send/README.md`. Fails loud on import if the dep is missing. |

## 7. Success criteria

- [ ] **Slice 1**: 2 physical devices complete parent sign-up via real Supabase Auth against `supabase start`; `auth.users` row has `app_metadata.parent_id = auth.uid()`.
- [ ] **Slice 2**: Parent JWT contains `parent_id` as a first-level claim (verified via `jwt.io` against the local stack). `behavioral_events_parent_select` RLS policy denies a sibling-parent SELECT (JVM RLS regression test green).
- [ ] **Slice 3**: `fcm-send` calls the v1 endpoint with `Authorization: Bearer <oauth-token>` (verified via `fetch` mock in edge function test). Local Firebase emulator receives the data message.
- [ ] **Slice 4**: Real `FirebaseMessagingService.onMessageReceived` fires on the parent device against the Firebase emulator; `FcmWorkHelper.enqueueHighPrioritySync` runs within 1s of receipt.
- [ ] **Slice 5**: `tools/cross-device/start-stack.sh` boots clean on the dev machine; `tools/cross-device/smoke-test.sh` exits 0 end-to-end (sign-up → pairing → time request → FCM push → approve → child reflects verdict). CI deploy workflow green on merge to master.
- [ ] **Slice 6** (if not folded into 4): Parent receives a "child paired" notification within 5s of successful pairing.
- [ ] **All migrations apply cleanly to a fresh cloud project**: migrations 001-008 (including new 007 + optional 008) replay without error against a `supabase db reset`.
- [ ] **All 11 edge functions deploy from CI**: `supabase functions list` shows every function deployed via `deploy-functions.yml`.
- [ ] **`./gradlew testDebugUnitTest` green**: all JVM unit tests pass (existing + new parent-auth + FCM receiver contract + RLS regression).
- [ ] **`./gradlew assembleDebug` green**: debug APK builds with `-PuseRealSupabase=true`.
- [ ] **Detekt + ktlintCheck green**: no new violations on touched files.
- [ ] **BehaviorLogScreen renders 5 fixture events on parent's device against REAL cloud** (not just mock) — re-validates the live-test gap that `fix-migrate-stale-parent-id-on-load` (PR #28) closed against the mock.

## 8. Alternatives considered

1. **`shared-mock-server` first, real cloud later** (Q1=a): faster first PR — shared-mock gives 2-device smoke in a day. But user explicitly chose (b) — they want validation against real Supabase from day 1, because mock hides bugs that only appear at the network boundary (RLS denials, missing JWT claims, FCM failures). Skipped.
2. **Big bang (one ~1300 LoC PR)**: ships everything in one shot. Reviewer burnout (400-line budget enforced), hard to bisect failures if anything regresses, blocks parallel work. Skipped in favour of chained PRs.
3. **Keep mock mode + add a "demo mode" flag for shared state**: doesn't solve the user's real cross-device goal. The user wants real Supabase + real FCM, not a fancier mock. Skipped.
4. **Production FCM on day 1** (vs Firebase emulator + dev project only): higher blast radius — a real misconfiguration 403s real parents. Emulator + dev project is the right validation surface for MVP. Production rollout deferred to a follow-up change.

## 9. Open clarifications (carry into `sdd-spec`)

1. **Email verification vs magic-link for parent sign-up?** — Slice 1 ships with email + password (no email verification) for local Supabase smoke. The spec phase picks the production path: email verification (Supabase's built-in), magic-link (Supabase's `signInWithOtp`), or keep email + password with a manual "forgot password" flow. Drives the parent sign-up UI surface in `OnboardingScreen`.
2. **Push message format: data-only (works in emulator) vs notification (production-only)?** — Slice 4 uses data-only for the cross-device harness. The spec phase decides whether production pushes should add a notification payload (requires Firebase production config + APNs/FCM dual-stack). Default recommendation: stay data-only for V1.
3. **adb-reverse vs network IP for the 2-device validation harness?** — adb-reverse is cleaner (no IP discovery), but only works while USB is connected. Network IP is wireless-friendly. Spec phase picks based on whether the harness runs in CI (network IP via ephemeral IPs) or only locally (adb-reverse).
4. **CI: separate workflow per slice or one workflow with stages?** — Recommendation: one workflow per slice with shared `tools/cross-device/` scripts, so each slice has its own gate. Spec phase confirms.
5. **Parent FCM token storage: extend `device_push_tokens` with `parent_id` or a sibling `parent_push_tokens` table?** — Slice 6 needs this. The spec phase decides based on whether the token lifecycle is shared with the child (extend) or separate (sibling). Default recommendation: extend with `parent_id` nullable + a new RLS policy.
6. **BehaviorLogScreen "fixture events" rendering**: do we keep the 5 fixture events for cloud-mode too, or are they strictly a mock-mode dev convenience? The previous SDD cycle validated mock-mode only. The spec phase decides whether the cloud-mode `BehaviorLogScreen` shows the parent's real historical events (which would be empty at MVP) or the 5 fixtures as a demo seed.

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/push/FcmPushService.kt` | Modified (Slice 4) | Rewrite as `FirebaseMessagingService` subclass. ~150-250 LoC. |
| `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` | Modified (Slice 1) | Add `signUpAsParent` + `signInAsParent`. ~150-250 LoC. |
| `app/src/main/AndroidManifest.xml:122-128` | Unchanged | FCM service intent filter stays; the class behind it becomes a real receiver. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/onboarding/*` | Modified (Slice 1) | Parent sign-in form. |
| `supabase/functions/custom-access-token-hook/index.ts` | Modified (Slice 2) | Read `app_metadata.parent_id`. |
| `supabase/functions/fcm-send/index.ts` | Modified (Slice 3) | v1 OAuth rewrite. |
| `supabase/functions/pairing/index.ts:288-298` | Modified (Slice 6, optional) | Wire parent notification. |
| `supabase/migrations/007_behavioral_events_parent_select.sql` | New (Slice 2) | Add/rename RLS policy. |
| `supabase/migrations/008_device_push_tokens_parent_id.sql` | New (Slice 6, optional) | `parent_id` column on push tokens. |
| `.github/workflows/deploy-functions.yml` | New (Slice 5) | Edge function CI deploy. |
| `.github/workflows/cross-device-smoke.yml` | New (Slice 5) | End-to-end smoke CI. |
| `tools/cross-device/start-stack.sh` | New (Slice 5) | Boot local Supabase + Firebase emulator. |
| `tools/cross-device/smoke-test.sh` | New (Slice 5) | End-to-end smoke test. |
| `tools/cross-device/README.md` | New (Slice 5) | Manual runbook. |
| `app/src/test/java/.../push/FcmPushServiceTest.kt` | Modified (Slice 4) | Add reflective-instantiation + onMessageReceived contract tests. |
| `app/src/test/java/.../auth/DeviceAuthManagerParentAuthTest.kt` | New (Slice 1) | Sign-up + sign-in + invalid credentials + email conflict. |
| `supabase/functions/fcm-send/fcm-send.test.ts` | New (Slice 3) | Mock v1 endpoint with `globalThis.fetch`. |
| `supabase/functions/custom-access-token-hook/hook.test.ts` | New (Slice 2) | Assert parent_id injection. |
| `openspec/specs/parent-auth-session/spec.md` | Unchanged | This change introduces the real path; the synthetic path stays for tests. Formalizing the delta is a follow-up. |
| `openspec/specs/pairing-flow/spec.md` | Modified (Slice 6, optional) | Adds the "parent receives paired notification" scenario that `pairing-flow/spec.md:50` defers. |
| `openspec/specs/time-request-approval/spec.md` | Unchanged | The child-side receipt + enforcement is already covered by the existing scenarios. |

## Capabilities (contract with `sdd-spec`)

### Modified capabilities
- **`parent-auth-session`**: the synthetic-anonymous path stays as the test/dev surface, but the spec gains an ADDED requirement group covering `signUpAsParent` + `signInAsParent` against real Supabase Auth. `MOCK_PARENT_ID` is removed from production paths.
- **`pairing-flow`** (Slice 6): the "Out of scope" item at `pairing-flow/spec.md:50` (parent-side FCM on successful pairing) becomes an ADDED requirement. New migration `008_device_push_tokens_parent_id.sql` is referenced.
- **`time-request-approval`**: the FCM-receipt scenarios at `time-request-approval/spec.md:56-63` already cover child-side receipt. No delta required at the spec level; the production code change (Slice 4) is the contract.

### New capabilities
- **`fcm-v1-send`**: covers the FCM v1 OAuth rewrite + the Deno JWT signing for service-account tokens. Used by `fcm-send`, `approve-request`, `pairing` (Slice 6).
- **`cross-device-harness`** (Slice 5): covers `tools/cross-device/` scripts + CI workflows. Documents the validation contract.

## Rollback plan

Each slice is independently revertible via `git revert` of the slice's PR. Critical paths:
- **Slice 1**: revert restores the synthetic-anonymous path; existing `BehaviorLogScreen` fixture rendering continues to work (mock engine unchanged).
- **Slice 4**: revert restores the static `companion object` callers; `FirebaseMessagingService` registration is removed from `AndroidManifest.xml`. Push delivery fails silently — same as today's broken state. Mock engine still drives the UI.
- **Slice 2 + Slice 3 + Slice 6**: revert the migration(s) + the edge function deploys. Edge function CLI supports `supabase functions deploy <name> --no-verify` for undeploy. Migrations are down-migrated via the standard `supabase migration repair` + manual SQL.
- **Slice 5**: revert deletes the CI workflow + the tooling scripts. No production surface.

Total rollback cost if all 6 slices merged and need to be reverted: 6 PR reverts + 2 migrations down + 11 edge function redeploys to previous versions. Acceptable for the size of the change.

## Dependencies

- **External**: Supabase CLI (`supabase` ≥ 1.180), Firebase CLI (`firebase` ≥ 13.0) with the emulator suite, `adb` on the dev machine (already installed per `openspec/config.yaml` `testing.gotchas`), `jq` for shell scripts.
- **Project**: pre-existing `MockSupabaseEngine` is **kept** (slice 5 keeps `USE_MOCK_SUPABASE=true` as the default for the debug variant; `-PuseRealSupabase=true` flips it).
- **Schema**: pre-existing migrations `001-006` must apply cleanly. New migration `007_behavioral_events_parent_select.sql` builds on `004_parent_outcome_checkins.sql` (which already creates the `behavioral_events` table at lines 53-99 + the `parent_events` policy at lines 89-99).
- **Secrets**: GitHub repo secrets `SUPABASE_ACCESS_TOKEN`, `SUPABASE_PROJECT_REF`, `FCM_SERVICE_ACCOUNT_KEY_B64` must be set before Slice 5 merges.

## References

- **Explore**: engram observation `#340` `sdd/feat-cross-device-pairing-and-approval/explore` — 8 blockers + 5 decisions captured.
- **Decisions**: engram observation `#339` `sdd/feat-cross-device-pairing-and-approval/decisions` — 3 implementation decisions (Q1=b, Q2=b, Q3=a).
- **Blocker citations** (file:line): `FcmPushService.kt:18` (not a `FirebaseMessagingService` subclass); `custom-access-token-hook/index.ts:42-56` (only `device_id` injected); `fcm-send/index.ts:97-119` (deprecated `Authorization: key=<FCM_SERVER_KEY>`); `AndroidManifest.xml:122-128` (FCM service intent filter); `004_parent_outcome_checkins.sql:53-99` (behavioral_events table + existing `parent_events` policy); `pairing/index.ts:288-298` (deferred parent notification); `pairing-flow/spec.md:50` (explicit "Out of scope" deferral).
- **Previous SDD cycle**: `feat-parent-behavioral-event-log` + `fix-behavioral-event-log-fixture-loading` + `fix-migrate-stale-parent-id-on-load` (PRs #27 + #28, master `5e392a6`) — runtime validation confirmed 5 fixture events render in `BehaviorLogScreen` against the mock engine. The clean cutover in Slice 1 invalidates the `MOCK_PARENT_ID` fallback added by `fix-migrate-stale-parent-id-on-load`.
- **Format precedent**: `archive/2026-07-07-fix-rename-child-dialog/proposal.md` (single-PR ~280 LoC budget) — slice structure is new for this project, but the explicit `file:line` citations + verified test counts + risk table style are inherited.
- **Session preflight (cached)**: `execution_mode=interactive`, `artifact_store=openspec`, `chained_pr_strategy=ask-always`, `review_budget_lines=400` — threads into the tasks phase via explicit user approval for chained PRs.