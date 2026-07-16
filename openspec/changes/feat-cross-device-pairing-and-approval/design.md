# Design: Real cross-device pairing and approval

**Outcome**: A real cross-device loop (parent signs in → child pairs → time request → FCM push → verdict → child reflects) running against `supabase start` + the Firebase emulator, replacing the broken `FcmPushService` shim, the missing `parent_id` JWT claim, the legacy `key=` FCM API, and the missing edge-function CI deploy.

**Scope**: 4 chained PR slices (A–D), ~760–1,300 LoC across production + tests + CI + tooling, all against the 400-line review budget.

**Why now**: 5 confirmed decisions (Q1–Q5) close the design forks; 8 explore blockers map to specific slices. The single source of truth for `parent_id` becomes real `auth.uid()` (clean cutover; no `MOCK_PARENT_ID` backfill).

---

## 1. Architecture overview

```text
┌─────────────────────┐                              ┌─────────────────────┐
│  PARENT DEVICE      │                              │  CHILD DEVICE       │
│  (Android phone)    │                              │  (Android phone)    │
│                     │                              │                     │
│  Parent UI          │       Real Supabase           │  Child UI           │
│  DashboardScreen    │◄────────────────────────────►│  ExtraTimeScreen    │
│  SolicitudesScreen  │       Cloud Auth + RLS       │  PairingScreen      │
│                     │       + Realtime             │                     │
│  FcmPushService ◄───┼────── Firebase Cloud ────────┼─► FcmPushService    │
│  (real receiver)    │       Messaging (v1 OAuth)   │  (real receiver)    │
└─────────────────────┘                              └─────────────────────┘
         │                                                     │
         │ HTTPS + JWT (parent_id claim)                       │ HTTPS + JWT (device_id claim)
         ▼                                                     ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│                  SUPABASE CLOUD PROJECT (fbuiwtzybalatpeakdiw)                │
│                                                                                │
│  PostgreSQL (RLS enforced)                                                    │
│    auth.users  ──► custom-access-token-hook ──► JWT (parent_id, device_id)    │
│    devices, app_policies, time_requests, grants, behavioral_events, ...       │
│                                                                                │
│  Edge Functions (Deno)                                                        │
│    pairing, create-pairing-code, approve-request                              │
│    fcm-send (v1 OAuth), register-token, custom-access-token-hook              │
│                                                                                │
│  Firebase Cloud Messaging v1 API (OAuth Bearer)                               │
│    ◄──── service-account JSON via supabase secrets ────►                      │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Flow in one breath**: parent magic-link → Supabase Auth issues JWT with `parent_id` from `app_metadata` → RLS reads work → child pairs via `pairing` edge fn with anonymous device JWT → child writes `time_requests` (RLS by `device_id` claim) → `approve-request` triggers `fcm-send` v1 → FCM delivers data-only message to parent → `FcmPushService.onMessageReceived` enqueues sync → parent approves → Realtime pushes verdict to child.

---

## 2. Key sequence diagrams

### 2.1 Parent magic-link sign-up (Q1=b)

```text
[Parent UI] -> [DeviceAuthManager.signInWithMagicLink(email)]
            -> [Supabase Auth POST /auth/v1/magiclink] -- email sent -->
            -> [Inbucket: http://127.0.0.1:54324 (local) or real inbox]
            -> [Parent clicks link] -> [Supabase Auth verifies token (1h TTL)]
            -> [custom-access-token-hook fires (per §2.4)]
            -> [DeviceAuthManager.verifyMagicLinkOtp persists ParentSession]
            -> [device_auth_prefs: { role: PARENT, parent_id, access_token }]
            -> [Parent dashboard loads]
```

**Why magic-link**: zero password storage, no reset flow, matches the "set up once on a phone" mental model. Email-confirmation deferred to production; local Supabase smoke works because Inbucket catches the email. (See `DeviceAuthManager.kt:155-273` for the existing `createAnonymousSession` path the new methods replace on the parent side.)

### 2.2 Child pairing via 8-char code

```text
[Child PairingScreen] -> [PairingManager.pairWithCode]
                       -> POST /functions/v1/pairing {code, device_name, ...}
                       -> [pairing edge fn: service_role writes devices+children]
                       -> auth.admin.updateUserById sets app_metadata.device_id
                       -> [custom-access-token-hook writes device_id into JWT]
                       -> [child app receives JWT, persists session]
                       -> [Slice D: parent device receives "child.paired" FCM]
```

**Why this works today minus the notification**: `pairing/index.ts:51-189` already creates the `devices` row with `parent_id = pairingRecord.parent_id` (line 162) and writes `app_metadata.device_id` via the admin API (line 178-185). The custom-access-token-hook picks up `app_metadata.device_id` at next JWT mint. The deferred parent-notify TODO at `pairing/index.ts:293-298` becomes live in Slice D.

### 2.3 Time request + FCM push + approval

```text
[Child ExtraTimeScreen] -> POST /rest/v1/time_requests (RLS: device_id claim)
                          -> [SolicitudesPollingWorker on parent pulls PENDING]
                          -> [Parent approves] -> POST /functions/v1/approve-request
                          -> [approve-request calls fcm-send v1 (see §2.4)]
                          -> [fcm-send: OAuth Bearer to FCM v1]
                          -> [FcmPushService.onMessageReceived on parent device]
                          -> [FcmWorkHelper.enqueueHighPrioritySync (per §0.1)]
                          -> [SolicitudesScreen renders verdict]
                          -> [Realtime channel pushes verdict to child]
                          -> [ExtraTimeScreen unlocks apps]
```

**Per §0.1 (FcmPushService.kt:11-13)**: FCM is signal-only. `onMessageReceived` never enforces; it only enqueues `FcmWorkHelper.enqueueHighPrioritySync`. Server is the source of truth for grants.

### 2.4 FCM v1 OAuth token derivation

```text
[fcm-send cold start] -> read FCM_SERVICE_ACCOUNT_KEY (base64 JSON) from secrets
                       -> [decode + parse] -> {client_email, private_key, project_id}
                       -> [assertion JWT signed RS256:
                           iss=client_email, scope=firebase.messaging,
                           aud=oauth2.googleapis.com, iat, exp=+1h]
                       -> POST https://oauth2.googleapis.com/token
                          grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer
                          assertion=<signed_jwt>
                       -> [oauth_access_token cached in memory until exp-60s]
                       -> POST https://fcm.googleapis.com/v1/projects/{project_id}/messages:send
                          Authorization: Bearer <token>
                          { message: { token, data: {type, ...},
                                       android: {priority: "high"} } }
```

**Why v1 OAuth, not legacy `key=`**: Google deprecated the legacy endpoint in 2024 (`fcm-send/index.ts:97-119`); new FCM projects may not even surface a server key. The Deno JWT lib (`djwt`, pinned in `import_map.json`) handles RS256 signing without bundling crypto. Token cache is in-process and ephemeral; no disk writes.

### 2.5 Edge function CI deploy

```text
[git push to master] -> [GitHub Actions: deploy-edge-functions.yml]
                       -> [download FCM_SERVICE_ACCOUNT_KEY_B64 from secrets]
                       -> [decode to tmpfile, never echo] -> [supabase secrets set]
                       -> [supabase functions deploy pairing create-pairing-code ...]
                       -> [supabase functions list -> verify count = 11]
                       -> [run supabase db reset against ephemeral project]
                       -> [deploy-functions exit 0]
```

**Why ephemeral Supabase project in CI**: production secrets differ; CI uses a throwaway project created via `supabase projects create` and torn down on workflow end. The smoke harness (Slice C) targets the local stack (`supabase start`) for adb device tests; the deploy job targets the ephemeral cloud for the v1 OAuth round-trip.

---

## 3. Data model changes

### 3.1 New migration `007_behavioral_events_parent_select.sql`

```sql
-- Add/rename the SELECT policy on behavioral_events to match the project's
-- <table>_parent_select naming convention, keyed on parent_id = auth.uid().
-- (See 002_rls_policies.sql:153-171 for the precedent on time_requests.)
ALTER TABLE behavioral_events ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS parent_events ON behavioral_events;

CREATE POLICY behavioral_events_parent_select ON behavioral_events
    FOR SELECT
    USING (
        parent_id = auth.uid()
        OR
        EXISTS (
            SELECT 1 FROM devices
            WHERE devices.id = behavioral_events.device_id
              AND devices.parent_id = auth.uid()
        )
    );
```

**Structural intent**: same predicate as the prior `parent_events` policy at `004_parent_outcome_checkins.sql:89-99`, renamed to the `*_parent_select` pattern so future RLS lint tooling (proposed in `config.yaml` quality hooks) can auto-discover them. The `parent_id` column already exists on `behavioral_events` (line 58 of 004), so no column changes — only the policy rename + re-issue.

### 3.2 New migration `008_device_push_tokens_parent_id.sql`

```sql
-- Add parent_id to device_push_tokens for parent FCM fanout.
-- Nullable so existing child-device rows stay valid (ON DELETE SET NULL
-- on the parent's auth.users row lets us clean up on parent delete).
ALTER TABLE device_push_tokens
    ADD COLUMN IF NOT EXISTS parent_id UUID
    REFERENCES auth.users(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_push_tokens_parent
    ON device_push_tokens(parent_id) WHERE parent_id IS NOT NULL;

-- Sibling-parent denial: a parent can only see/manage their own push tokens.
CREATE POLICY device_push_tokens_parent_select ON device_push_tokens
    FOR SELECT
    USING (parent_id = auth.uid());

CREATE POLICY device_push_tokens_parent_update ON device_push_tokens
    FOR UPDATE
    USING (parent_id = auth.uid());
```

**Why extend, not sibling table** (Q2=a): one table to maintain, existing RLS pattern, the join is rare (only on FCM fanout). Reversible: `DROP POLICY + DROP COLUMN` is the symmetric rollback.

### 3.3 No change to `time_requests` or `behavioral_events` columns

- `time_requests` table: no schema change. The 1h auto-DENY contract (per `time-request-approval` delta) is enforced at the polling layer (`SolicitudesPollingWorker` / `ParentViewModel.loadPendingRequests`), not at the schema level.
- `behavioral_events`: no column additions. Only the RLS policy rename in §3.1.

---

## 4. Component changes

| File | Change | Rationale |
|---|---|---|
| `app/src/main/java/.../auth/DeviceAuthManager.kt` | Add `signInWithMagicLink(email)` + `verifyMagicLinkOtp(token, email)`. Remove `migrateStaleParentId` helper (Q2=b clean cutover; line 581-590 deleted). Wipe prefs containing `parent_id = "parent-demo"` on cold start under `USE_MOCK_SUPABASE=false`. | Magic-link per Q1=b. Clean cutover invalidates PR #28's lazy backfill. |
| `app/src/main/java/.../push/FcmPushService.kt` | Rewrite as `class FcmPushService : FirebaseMessagingService()`. Add `getInstance(context)` accessor. Move `processMessage` + `processNewToken` (lines 38-60) to instance methods; keep `companion object` static delegates for `FcmHelper.kt:22,36,56,60` backward compat. | Real FCM receiver per blocker at `FcmPushService.kt:18`. Static shim preserves JVM tests + `FcmHelper` callers. |
| `app/src/main/java/.../push/FcmHelper.kt` | No signature change. Lines 22, 36, 56, 60 continue calling `FcmPushService.getStoredToken/isTokenStale/processNewToken` via the static delegate. | Stays green by contract. |
| `app/src/main/AndroidManifest.xml:122-128` | No change. FCM service intent filter stays. The class behind it becomes a real receiver. | The manifest already advertises FCM; the class was the blocker. |
| `app/src/main/java/.../ui/onboarding/*` | Add parent magic-link form: email input + "Enviar enlace" button + "Abrir bandeja" hint (Inbucket URL on local). Child path untouched. | Real parent sign-up surface. |
| `app/src/main/java/.../network/SupabaseClientProvider.kt` | No change. The `loadPersistedState` wipe for legacy `parent-demo` prefs runs inside `DeviceAuthManager.init` (which `SupabaseClientProvider` already triggers via the `authManager` field at line 78). | Q2=b cutover lives in the auth layer, not the client layer. |
| `supabase/functions/custom-access-token-hook/index.ts` | Rewrite the device_id branch (lines 42-52) to also copy `app_metadata.parent_id` to a top-level `parent_id` claim. Empty `app_metadata.parent_id` is omitted, not null. | Closes RLS blocker for parent-scoped reads (line 42-56 cited as blocker 2 in the proposal). |
| `supabase/functions/fcm-send/index.ts` | Replace `sendFcm` (lines 92-142) with v1 OAuth: read `FCM_SERVICE_ACCOUNT_KEY` from `Deno.env`, derive OAuth Bearer via `djwt`, POST to `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`. Body shape = `{ message: { token, data, android: { priority } } }`. Token cached in-process. | Closes blocker 3 (legacy `key=` deprecated). `fcm-send/index.ts:97-119` cited in proposal. |
| `supabase/functions/pairing/index.ts:288-298` | Replace the `sendFcmNotification` stub (currently logs "skipping" and returns) with: read parent's push tokens via `device_push_tokens.parent_id = pairingRecord.parent_id`, then call internal `fcm-send` helper with `{ type: "child.paired", device_id, child_first_name }`. Best-effort: log `info` and continue on missing tokens. | Lifts the `pairing-flow/spec.md:50` deferral. Slice D scope. |
| `supabase/migrations/007_*.sql` | New. Rename `parent_events` → `behavioral_events_parent_select` per §3.1. | RLS hardening for cross-device. |
| `supabase/migrations/008_*.sql` | New. Add `device_push_tokens.parent_id` + RLS per §3.2. | Enables Slice D's `child.paired` notification. |
| `tools/cross-device/start-stack.sh` | New. Boots `supabase start` + `firebase emulators:start --only auth,messaging` in parallel, waits for both `ready` (5min timeout), runs `adb -s $PARENT_DEVICE_ID reverse` and same for child device. | Slice C. Replaces the `shared-mock-server` pattern for cross-device validation (Q1=b real cloud). |
| `tools/cross-device/smoke-test.sh` | New. Drives: parent signs up → child pairs via 8-char code → child requests time → parent approves → child reflects verdict. Exits 0 on success, non-zero on any stage with stderr tag. | Slice C. |
| `tools/cross-device/README.md` | New. Manual runbook: env vars (`PARENT_DEVICE_ID`, `CHILD_DEVICE_ID`), Inbucket URL for magic-link capture, FCM console URL for the emulator. | Slice C. |
| `.github/workflows/deploy-edge-functions.yml` | New. On push to `master`: decode `FCM_SERVICE_ACCOUNT_KEY_B64` to a tmpfile, `supabase secrets set`, then `supabase functions deploy` for all 11 functions. Verifies via `supabase functions list`. | Slice C. Closes "no CI deploy" blocker. |
| `.github/workflows/cross-device-smoke.yml` | New. On PR open: provision ephemeral Supabase project, apply migrations, run `smoke-test.sh` against the stack, tear down. | Slice C. |
| `app/src/test/java/.../auth/DeviceAuthManagerParentAuthTest.kt` | New. JVM tests for `signInWithMagicLink` (mocked HTTP via Ktor `MockEngine`), `verifyMagicLinkOtp`, invalid email, expired token, prefs wipe for legacy `parent-demo`. | Slice A. |
| `app/src/test/java/.../push/FcmPushServiceTest.kt` | New. JVM tests: reflective `Class.forName(...).newInstance()` (mirrors HiltWorker contract), `onMessageReceived` dispatches to `FcmWorkHelper.enqueueHighPrioritySync`, `getInstance(context)` static-compat. | Slice B. |
| `supabase/functions/fcm-send/fcm-send.test.ts` | New. Deno test: mock `globalThis.fetch` to capture v1 POST body + auth header, assert `Authorization: Bearer <token>` and `message: { token, data, android: { priority } }` shape. 401 retry case. | Slice B. |
| `supabase/functions/custom-access-token-hook/hook.test.ts` | New. Deno test: parent_id injection, child omission, empty-app_metadata no-crash. | Slice A. |
| `supabase/functions/fcm-send/import_map.json` | New. Pin `djwt` + Deno std version for RS256 signing. | Slice B. |

---

## 5. Cross-cutting concerns

### 5.1 Security

- **Service-account JSON** lives in `supabase secrets FCM_SERVICE_ACCOUNT_KEY` only. CI decodes `FCM_SERVICE_ACCOUNT_KEY_B64` to a tmpfile, pipes to `supabase secrets set`, deletes tmpfile. Never echoed, never committed, never written to `device_auth_prefs`.
- **Magic-link tokens** are scoped to email + single-use, with Supabase's default 1h expiry. Verify flow uses the same `verifyMagicLinkOtp` path as the existing child anonymous auth — no JWT smuggle.
- **RLS sibling-parent denial**: migration 007 + 008 each add policies that test `parent_id = auth.uid()`. JVM RLS regression test (Slice C) spawns `supabase start`, applies migrations, runs SQL with a synthetic JWT as `parent-A`, asserts a SELECT against `parent-B`'s data returns 0 rows. Mirrors the existing `time_requests_parent_select` precedent at `002_rls_policies.sql:153-161`.
- **FCM body scrubbing**: `FcmPushService.onMessageReceived` (post-Slice B) logs message type + body size only. No PII, no token substrings, no payload content.

### 5.2 Testing strategy

| Layer | Tool | What it covers |
|---|---|---|
| JVM unit | MockK + Ktor `MockEngine` | `DeviceAuthManager.signInWithMagicLink` happy + invalid + expired; `verifyMagicLinkOtp` + prefs wipe |
| JVM unit | Robolectric | `FcmPushService` reflectively instantiable, `getInstance(context)` returns singleton, `onMessageReceived` dispatches to `FcmWorkHelper` |
| JVM unit | MockK static shim | `FcmHelper` callers at lines 22, 36, 56, 60 still compile + pass |
| Edge fn unit | Deno test + `globalThis.fetch` stub | `fcm-send` v1 body shape, Bearer header, 401 retry, missing secret error code |
| Edge fn unit | Deno test | `custom-access-token-hook` injects parent_id, omits when absent, no-crash on empty `app_metadata` |
| Integration | `supabase start` + SQL | RLS regression: parent-A denied parent-B's rows for `behavioral_events` + `device_push_tokens` |
| E2E local | `tools/cross-device/smoke-test.sh` | 2-device happy path against local Supabase + Firebase emulator |
| E2E CI | `.github/workflows/cross-device-smoke.yml` | Ephemeral Supabase project + smoke harness on PR |

All gates wired into `./gradlew testDebugUnitTest` (existing JVM surface) and `supabase functions test` (new edge fn surface).

### 5.3 Observability

- Edge functions: structured JSON to stdout (`{ event, request_id, latency_ms, status, device_id? }`). The existing `fcm-send/index.ts:84` `console.error` becomes a structured `info`/`warn`/`error` with the same fields.
- `FcmPushService.onMessageReceived`: `Log.d(TAG, "msg type=... size=... bytes")`. No body content.
- `custom-access-token-hook`: log `parent_id_injected=true|false` per invocation (no value, just flag).
- CI deploy logs: GitHub Actions step output. `FCM_SERVICE_ACCOUNT_KEY_B64` is `secrets.*` and never expanded into a `run:` step; only into `env:` for the `supabase secrets set` step.

### 5.4 Backward compatibility

- `FcmHelper.kt:22, 36, 56, 60` static callers stay green via `FcmPushService.getInstance(context)` shim. JVM tests that mock the static callbacks (per `firebase-messaging-service-receiver` spec §"Static processMessage entry point still works") pass without modification.
- `MockSupabaseEngine` (`app/src/main/java/.../data/remote/MockSupabaseEngine.kt`) is **kept**. `BuildConfig.USE_MOCK_SUPABASE=true` is the default for the debug variant; `-PuseRealSupabase=true` flips it. The new `signInWithMagicLink` path is only wired under `USE_MOCK_SUPABASE=false`.
- Existing pre-PR-#28 installs with `parent_id = "parent-demo"` in `device_auth_prefs` see a sign-in screen on first cloud cold start (Q2=b cutover; the wipe is `DeviceAuthManager.loadPersistedState` + flag check).
- `signing-report`: not affected. `assembleDebug` produces a debug-signed APK as today.

### 5.5 Migration / cutover plan

- **Slice A ships clean cutover**: `migrateStaleParentId(prefs)` (the helper at `DeviceAuthManager.kt:581-590` from PR #28) becomes a NOP — no `MOCK_PARENT_ID` fallback. Replaced by an explicit wipe in `loadPersistedState` under `USE_MOCK_SUPABASE=false` (lines 552-566 deleted; lines 537-550 simplified).
- **All installs with `parent_id = "parent-demo"`** see the sign-in screen on next cold start against `-PuseRealSupabase=true`. No backfill, by design (Q2=b).
- **No feature flag in production**: the cutover is a one-way ratchet driven by the `USE_MOCK_SUPABASE=false` build flag. Mock-mode debug builds (`USE_MOCK_SUPABASE=true`) keep the legacy `parent-demo` prefs for the existing test fixtures.

---

## 6. Open risks (carry into sdd-tasks)

| ID | Severity | Description | Mitigation | Slice |
|---|---|---|---|---|
| R1 | HIGH | FCM v1 OAuth CI secrets leak | `FCM_SERVICE_ACCOUNT_KEY_B64` in GitHub secrets, decoded to tmpfile at runtime, piped via stdin to `supabase secrets set --no-verify`, never echoed. `secrets.*` only — never in `run:` step output. | C |
| R2 | HIGH | Hook missing `parent_id` causes RLS 403 on every parent read | Sign-up path (Slice A) sets `app_metadata.parent_id = auth.uid()` atomically with `auth.admin.createUser`; Slice A edge fn test pins "has parent_id" + "missing parent_id" branches; JVM RLS regression in Slice C asserts denial for missing claim | A |
| R3 | HIGH | `FcmPushService` refactor breaks static callers at `FcmHelper.kt:22, 36, 56, 60` | `getInstance(context)` accessor + `companion object` delegates preserve the public static API; existing JUnit tests stay green by contract; new reflective-instantiation test pins the HiltWorker contract | B |
| R4 | MED | Local Supabase + Firebase emulator parity gaps (data-only messages, claim shape, secrets) | All cross-device tests use data-only FCM messages; `supabase start` + Inbucket covers auth email; ephemeral Supabase project in CI for v1 OAuth round-trip; documented in `tools/cross-device/README.md` | C |
| R5 | MED | Magic-link needs email inbox (no email in local smoke) | `supabase start` ships Inbucket on `:54324`; `smoke-test.sh` polls Inbucket for the link; `README.md` documents the URL | C |
| R6 | MED | Edge function CI deploy needs Supabase access token in secrets + rotation | `tools/cross-device/README.md` rotation runbook; CI uses `SUPABASE_ACCESS_TOKEN` from repo secrets scoped to the deploy-only project | C |
| R7 | LOW | `device_push_tokens.parent_id` migration reversibility | Migration is `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + 2 `CREATE POLICY`; rollback is `DROP POLICY + DROP COLUMN` (symmetric, single statement) | D |
| R8 | LOW | Drop 5 fixture events in cloud real may surprise parents who expected prior data | Release notes: "BehaviorLogScreen shows real cloud events; legacy mock fixtures do not migrate." `BehaviorLogScreen` already handles empty state (per `feat-parent-behavioral-event-log` archive). | A |

---

## 7. Tradeoffs (REQUIRED — `openspec/config.yaml` `design.require_tradeoffs`)

| Decision | Alternative | Why not |
|---|---|---|
| Magic-link (no password) for parent sign-up | Email + password (current explore default); email confirmation; OAuth Google | Less friction for parents; "set up once on a phone" mental model. Passwords = reset flows + storage concerns. Q1=b confirmed. |
| Extend `device_push_tokens.parent_id` | Sibling `parent_push_tokens` table | One table to maintain; existing RLS pattern (`*_parent_select`); join is rare (only on FCM fanout). Q2=a confirmed. |
| adb-reverse local + network IP for CI | adb-reverse everywhere; network IP everywhere | adb-reverse is dev-machine ergonomic (no IP discovery); network IP is portable to ephemeral CI containers with no USB. Q3=a confirmed. |
| Drop 5 fixture events in cloud real | Seed 5 fixtures in cloud; keep both modes | Real cloud = real data. Fixtures are mock-mode only. `BehaviorLogScreen` empty state is already handled. Q4=a confirmed. |
| One workflow per slice (A/B/C/D) | One staged workflow; one big-bang workflow | Slice independence is the whole point; stages create implicit coupling; 4-slices fit the 400-line review budget (tight on B). |
| FCM v1 OAuth cutover (no legacy parallel) | Legacy `key=` + v1 fallback; keep legacy only | Cleaner, less surface area. Legacy is deprecated; new FCM projects don't surface a server key. Parallel mode doubles the secret surface and test matrix. |
| `behavioral_events_parent_select` as new migration (007) | Patch migration 004 in place | Migration history is immutable; new file preserves audit trail; lets us drop the older `parent_events` policy cleanly. |
| `signing-report`: no new keystore | Generate per-tenant signing key | Out of scope; production signing is a follow-up change. Debug-signed APK is the gate per `openspec/config.yaml` `apply.build_command`. |
| In-process OAuth token cache for `fcm-send` | Re-derive on every call | Cold-start cost (~150ms RS256 sign) is wasteful; cache until 60s before expiry is the FCM-blessed pattern. |
| `DeviceAuthManager.getInstance(context)` shim (Slice B) | Replace all static callers | Touch radius is larger; the shim is the smallest change that preserves `FcmHelper` callers + JVM tests. |
| Drop `MOCK_PARENT_ID` lazy backfill (Q2=b) | Keep `migrateStaleParentId` for backwards compat | Less code, simpler contract. Parental-control app where the parent IS the user — re-pair on first cloud launch is acceptable. |

---

## 8. Glossary

- **App metadata** — Supabase Auth's `auth.users.raw_app_meta_data` JSONB column. Used for non-claim user metadata that the server sets (e.g., `parent_id` after sign-up). Survives token refresh; the `custom-access-token-hook` reads from here to inject into the JWT.
- **Custom access token hook** — A Supabase Edge Function invoked automatically during JWT minting. It receives the `app_metadata` + current claims and returns the augmented claims payload. Registered at Auth → Hooks → Custom Access Token in the Supabase dashboard.
- **Data-only FCM message** — A push message with `notification: null` and a custom `data` payload. The receiver app is responsible for rendering. Required for Firebase emulator support; production rollout can stay data-only (we control the UI).
- **FCM v1 API** — The OAuth-authenticated successor to FCM's legacy `Authorization: key=<FCM_SERVER_KEY>` API. Endpoint: `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send`. Body shape: `{ message: { token, data, android: { priority } } }`.
- **Inbucket** — Local email testing tool bundled with `supabase start`. Serves a web UI on `:54324` (default) for magic-link capture during local development. Production cuts over to real email.
- **Service-account JSON** — A Google Cloud IAM service account credential. Contains `client_email`, `private_key`, `project_id`. Used to mint OAuth tokens for FCM v1. Stored in `supabase secrets FCM_SERVICE_ACCOUNT_KEY`, never in repo.
- **`supabase secrets`** — Per-project encrypted secret store, accessible from Edge Functions via `Deno.env.get()`. Set via `supabase secrets set --no-verify NAME=<value>` or piped via stdin.
- **DJWT** — Deno JWT library (`djwt` on `deno.land/x/djwt`). Used for RS256 signing of the OAuth assertion JWT in `fcm-send`. Pinned in `import_map.json` for reproducibility.
- **Sibling-parent denial** — RLS test pattern: parent-A attempts a SELECT against parent-B's data and must receive 0 rows. Verified by the JVM RLS regression test (Slice C) against a real `supabase start` instance.
- **Clean cutover** — A one-way ratchet migration. No `MOCK_PARENT_ID` fallback; legacy prefs are wiped on first cloud cold start (Q2=b).

---

## 9. Slice ↔ spec ↔ file map (review checklist)

| Slice | Spec deltas | Files touched | LoC budget |
|---|---|---|---|
| **A** | `parent-auth-session`, `supabase-backend-integration` (hook), `time-request-approval` (1h auto-DENY) | `DeviceAuthManager.kt`, `custom-access-token-hook/index.ts`, `OnboardingScreen*`, `migrations/007_*.sql`, `SolicitudesPollingWorker*`, `ParentViewModel*`, `DeviceAuthManagerParentAuthTest.kt`, `hook.test.ts` | 230–400 |
| **B** | `fcm-v1-send`, `firebase-messaging-service-receiver` | `FcmPushService.kt`, `fcm-send/index.ts`, `import_map.json`, `FcmPushServiceTest.kt`, `fcm-send.test.ts` | 250–450 (tight) |
| **C** | `cross-device-harness` | `tools/cross-device/*`, `.github/workflows/deploy-edge-functions.yml`, `.github/workflows/cross-device-smoke.yml`, JVM RLS regression test | 200–300 |
| **D** | `pairing-flow` (REMOVED → ADDED) | `pairing/index.ts:288-298`, `migrations/008_*.sql`, `pairing.test.ts` | 80–150 |

**Review budget enforcement**: each slice's PR diff is targeted to ≤ 400 added lines. Slice B is the tightest — refactoring `FcmPushService` + rewriting `fcm-send` in one PR risks bursting. If it does, split into B1 (FCM v1) + B2 (receiver refactor).

---

## 10. Next phase

`/sdd-tasks` — break each of the 4 slices into reviewable tasks with strict-TDD ordering (failing test → implementation → green test). Explicit user approval for chained-PR strategy at the start, per the proposal's `chained_pr_strategy: ask-always` and `review_budget_lines: 400` preflight.
