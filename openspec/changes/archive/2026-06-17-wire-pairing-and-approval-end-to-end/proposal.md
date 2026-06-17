# Proposal: wire-pairing-and-approval-end-to-end

> **STATUS: ARCHIVED on 2026-06-17** — 9 commits merged to master (5 chained PRs + ktlint baseline + docs marker + DENY fix + per-device isolation fix), 579 tests passing, all 4 validation gates green. See `archive-report.md` in the archive folder for the full summary, known follow-ups, and reconciliation notes.

## Intent

The parent-child pairing, time-request, and app-blocking flows currently fall back to local mocks (`ParentRepository.createPairingCode`, `pairWithCode`, `getDevices`, `getPendingRequests`, `approveRequest`, `denyRequest`). The Supabase backend is already provisioned — edge functions `create-pairing-code`, `pairing`, `approve-request` exist; tables `devices`, `time_requests`, `app_policies`, `outbox` exist; RLS policies from migration `002_rls_policies.sql` are live — but the parent app never calls it. Four pre-SDD hotfixes (Engram `#15`) unblocked the most critical wiring bugs.

This change replaces the mocks with real HTTP calls, finishes the missing UI surfaces (QR rendering, parent-side device list, app-block affordance), and adds an `OutboxDrainer` worker so offline events eventually reach Supabase. After it lands, the child app can scan a parent's 8-char code (or QR), the parent can see the child device, approve or deny time requests, and curate an app block list — all end-to-end against the real backend.

## Scope

### In Scope — 5 chained PRs, each ≤400 lines (pre-approved plan)

| PR | Title | Files | Lines | Deliverable |
|----|-------|-------|-------|-------------|
| 1 | Wire pairing backend + QR | 4 | ~300 | Replace `ParentRepository.createPairingCode` and `PairingManager.pairWithCode` mocks with real calls to `supabase/functions/v1/create-pairing-code` and `pairing`. Add `io.github.alexzhirkevich:qrose` to `gradle/libs.versions.toml` (pure Kotlin, no native deps). Render QR in `PairingBottomSheet` step 2 (`ui/parent/components/DeviceComponents.kt:445-508`). Wire `parentalcontrol://pair?code=...` deeplink. |
| 2 | Cross-device device list | 1 new + 1 | ~150 | New edge function `supabase/functions/get-devices-for-parent/index.ts` (queries `devices` where `parent_id = auth.uid()` under RLS). Replace `ParentRepository.getDevices()` mock. Update `DashboardScreen` to consume real data. |
| 3 | Outbox drainer | 2 + 1 new | ~200 | New `OutboxDrainer` WorkManager worker pulling `outbox` rows and calling `SyncManager.sendOutboxItem`. `@HiltWorker` + `WorkerFactory` wiring, register in `AndroidManifest.xml` if needed, schedule from `ParentalControlApp.onCreate`. |
| 4 | Wire parent approval | 1 | ~150 | Replace `getPendingRequests()` mock with REST query to `time_requests`. Replace `approveRequest()` / `denyRequest()` mocks with calls to `approve-request` edge function. `RequestCard` UI in `DeviceComponents.kt:213-351` needs no change. |
| 5 | Parent app-block UI | 3 | ~300 | Flesh out `ui/screen/apps/AppsScreen.kt` (currently empty stub) with `PackageManager.queryIntentActivities`. Add `AppsViewModel`. Add "Add to block list" affordance to `DeviceDetailScreen.kt` Policy tab. UI calls existing `appPolicyDao.upsertAppPolicy`. |

### Out of Scope

- **End-to-end testing on two emulators** — PR 6 / `sdd-verify` phase, separate from this change.
- **Schema changes** — all tables, columns, RLS policies, and edge functions already exist.
- **4 permission helper functions** in `PermissionHelper.kt` — untouched.
- **JVM 17 → 21 bump** — separate refactor change (`align-with-guia-fedora44` will keep JVM 17).
- **Parent-side FCM token registration** — needed for `pairing` index.ts FCM TODO to work, but flagged as a follow-up risk, not blocking.

## Capabilities

### New Capabilities
- `pairing-flow`: 8-char code generation, QR rendering, deeplink intake (`parentalcontrol://pair?code=...`).
- `parent-device-list`: parent queries and renders their own devices via the `get-devices-for-parent` edge function.
- `outbox-drain`: WorkManager worker that periodically flushes the `outbox` table to Supabase.
- `time-request-approval`: parent approves or denies `time_requests` rows via the `approve-request` edge function.
- `app-block-policy`: parent curates the per-device app block list; `EnforcementController` already reads `app_policies` post-hotfix.

### Modified Capabilities
None — `openspec/specs/` is empty; no existing spec is being edited, only new spec files created.

## Approach

PR 1 ships first (highest blast radius — new dependency, new code path on both sides of the network). PRs 2-5 each add a self-contained surface and are independent once the real Supabase client is established in PR 1. The repository pattern stays: `ParentRepository` is the single seam between ViewModels and the network; tests can swap a fake `KtorClient` without touching the UI. The outbox drainer (PR 3) is a pure infrastructure change with no UI impact and can merge in parallel to PRs 2/4/5 if branches are kept independent.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/java/.../data/parent/ParentRepository.kt` | Modified | Mock methods replaced with real HTTP (PR 1, 2, 4) |
| `app/src/main/java/.../data/pairing/PairingManager.kt` | Modified | `pairWithCode` real HTTP (PR 1) |
| `app/src/main/java/.../ui/parent/components/DeviceComponents.kt` | Modified | QR rendered at line 445-508 (PR 1) |
| `app/src/main/java/.../ui/parent/dashboard/DashboardScreen.kt` | Modified | Real device list (PR 2) |
| `app/src/main/java/.../ui/screen/apps/AppsScreen.kt` | Filled | Real apps list UI (PR 5) |
| `app/src/main/java/.../ui/parent/devices/DeviceDetailScreen.kt` | Modified | "Add to block list" affordance (PR 5) |
| `app/src/main/java/.../workers/OutboxDrainer.kt` | New | `@HiltWorker` class (PR 3) |
| `app/src/main/AndroidManifest.xml` | Modified | WorkManager init if needed (PR 3) |
| `gradle/libs.versions.toml` | Modified | `qrose` dependency (PR 1) |
| `supabase/functions/get-devices-for-parent/index.ts` | New | Edge function (PR 2) |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| `qrose` version incompatible with current Compose BOM 2024.10.01 | Med | Pick latest `qrose` 1.x compatible with Compose 1.7; verify with `./gradlew assembleDebug` in PR 1. |
| Edge function not deployed to Supabase | Med | User runs `supabase functions deploy get-devices-for-parent` after PR 2 merges; documented as manual step. |
| Outbox drainer races with `SyncManager` | Med | Worker uses `getUniqueWork` with `KEEP` policy; reads `outbox` via single-writer gate (existing pattern). |
| FCM not delivered to parent on successful pairing | Med | Pre-existing TODO in `pairing/index.ts:237`; flagged but not blocking — pairing succeeds regardless. |
| `PackageManager.queryIntentActivities` is heavy on `AppsScreen` open | Low | Use `remember` + lazy `LaunchedEffect`; paginate if list > 200. |
| Chained PR diffs show prior slices in GitHub view | Med | Each child PR rebases onto previous tip before opening; verify clean diff in description. |

## Rollback Plan

- **Per PR**: each PR is a single atomic commit on a feature branch off `master`. Revert is `git revert <sha>` on `master`; CI re-runs the full quality gate.
- **PR 1** can be reverted independently by swapping the new HTTP calls back to the old mock body — the diff is contained to `ParentRepository` and `PairingManager`.
- **PR 3** (outbox) is a worker add — disabling the schedule in `ParentalControlApp.onCreate` and removing the manifest entry reverts cleanly.
- **Full rollback**: `git revert` PRs 5→4→3→2→1 in reverse merge order. The new edge function stays deployed in Supabase; it is harmless if the app stops calling it.
- **Feature branch chain**: if a mid-chain PR fails, only that PR and its successors are dropped; earlier merged PRs stand.

## Dependencies

- **External lib** (PR 1): `io.github.alexzhirkevich:qrose` — pure Kotlin QR renderer. User confirmed preferred lib; verify Compose BOM compatibility before merge.
- **Manual (PR 2)**: user must run `supabase functions deploy get-devices-for-parent` after merge.
- **Manual (PR 1, follow-up)**: parent-side FCM token registration against `device_push_tokens` keyed by `device_id` (migration `001_initial_schema.sql:123-133`) — flagged as a follow-up change, not part of this PR set.
- **Supabase project**: parent app and child app must target the same project; both must be authenticated.

## Success Criteria

- [ ] `./gradlew detekt ktlintCheck lint testDebugUnitTest assembleDebug` passes on every PR.
- [ ] Parent can create an 8-char pairing code with a scannable QR; child can scan or paste it and complete pairing.
- [ ] `parentalcontrol://pair?code=...` deeplink from the backend response opens the child pairing screen.
- [ ] Parent dashboard lists the child device(s) fetched from `get-devices-for-parent`.
- [ ] Time requests from child appear in the parent UI and can be approved or denied; the child app reflects the verdict.
- [ ] Parent can add a package to the block list from `DeviceDetailScreen`; `EnforcementController` enforces it (validated by unit test, not E2E).
- [ ] `OutboxDrainer` runs at least once in a foreground session, drains pending `outbox` rows, and does not duplicate-send on retry.
- [ ] All 4 hotfixes from Engram `#15` remain intact (no regression in `ExtraTimeScreen.kt:216`, `EnforcementController.kt:321`, `create-pairing-code/index.ts`, `pairing/index.ts:237`).
