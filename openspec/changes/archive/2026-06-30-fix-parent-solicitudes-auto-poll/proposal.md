# Proposal: Fix parent "Solicitudes" tab auto-refresh

## Why

The parent "Solicitudes" tab does NOT auto-refresh when a child submits a time request. Live evidence: POCO child posted to `/rest/v1/time_requests`; OPPO parent sat on the dashboard 30 s with no interaction — **zero `GET /rest/v1/time_requests`** in server log; manual tab tap also produced no GET and UI stayed "Sin solicitudes". The parent DID poll `POST /functions/v1/get-devices-for-parent`, so Supabase is reachable. Parents must kill/relaunch to see a new request, breaking the loop promised by `time-request-approval`.

## Scope

### In Scope
- `DashboardScreen.kt` — `LaunchedEffect(selectedTab)` → `loadPendingRequests()` on tab 1.
- (Gated) `SolicitudesPollingWorker` (5-min, parallel to `HeartbeatWorker`) in `scheduleAllPeriodicWork()`.
- (Gated) Cleanup of `RealtimeViewModel.kt` + `RealtimeManager.kt` (never injected; empty `refreshRequests()`).

### Out of Scope
- `approve-request` / `deny` path (covered by `time-request-approval`).
- FCM-driven parent push (would be a new capability).
- RLS query — `ParentRepository.getPendingRequests()` already works.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `time-request-approval` — `ADDED` requirement `Parent Solicitudes tab auto-refreshes when visible`. Existing "Parent lists pending requests" untouched.

## Approach

Cheapest: one `LaunchedEffect(selectedTab)` firing `loadPendingRequests()` on tab 1. Defense-in-depth: mirror `HeartbeatWorker` as a 5-min `SolicitudesPollingWorker`. Both reuse `ParentRepository.getPendingRequests()` — no backend change.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `ui/parent/screens/DashboardScreen.kt:224-241` | Modified | `LaunchedEffect(selectedTab)` → `loadPendingRequests()` on tab 1. |
| `workers/WorkScheduler.kt:12-20` | Modified (gated) | Schedule `SolicitudesPollingWorker`. |
| `workers/SolicitudesPollingWorker.kt` | New (gated) | Mirrors `HeartbeatWorker`. |
| `realtime/RealtimeViewModel.kt` | Removed/wired (gated) | Dead: empty `refreshRequests()` L117-120. |
| `realtime/RealtimeManager.kt` | Removed/wired (gated) | No UI consumer. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Rapid tab taps hammer Supabase | Low | `loadPendingRequests()` idempotent + `isLoading`-gated. |
| Worker + on-tab refresh double-fire | Low (gated) | Worker read-only; duplicate GETs tolerated by RLS. |
| Deleting realtime kills future RT feature | Med | Capture rationale in spec if cleanup chosen. |

## Rollback Plan

Revert the PR. If worker added: `WorkScheduler.cancelWork(context, "solicitudes_polling")`. No data migration.

## Dependencies

None.

## Success Criteria

- [ ] Parent on "Solicitudes" + child submits → `GET /rest/v1/time_requests` within 5 s, no manual pull-to-refresh.
- [ ] (Gated) After 6 min idle, switching to "Solicitudes" shows fresh PENDING list.
- [ ] (Gated) `RealtimeViewModel.kt` deleted or fully wired with non-empty `refreshRequests()`, unit-tested.

## Open scope decision — TODO(sdd-spec)

<!-- TODO(sdd-spec): user MUST pick one before spec phase writes deltas. -->

1. **Cheapest only** — `LaunchedEffect(selectedTab)` in `DashboardScreen.kt`. ~30 lines, 1 file.
2. **+ defense-in-depth worker** — add `SolicitudesPollingWorker` (5-min). ~100-150 lines, 3 files.
3. **Full cleanup** — Option 2 + delete `RealtimeViewModel.kt` + `RealtimeManager.kt`. ~150-250 lines, 5 files.

All three fit the 400-line review budget.