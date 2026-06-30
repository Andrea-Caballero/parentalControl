# Design: Fix parent "Solicitudes" tab auto-refresh

## Technical Approach

Two additive fixes wired through the existing parent dashboard surface:

1. **`LaunchedEffect(selectedTab)` in `DashboardScreen.DashboardScaffold`** — fires `ParentViewModel.loadPendingRequests()` whenever `selectedTab` becomes `1` (Solicitudes). The cheapest path: visible-tab = fresh-tab, no manual pull required.
2. **`SolicitudesPollingWorker` (5-min, periodic)** — mirrors `HeartbeatWorker` (`workers/Workers.kt:19-73`) and shares `ParentRepository.getPendingRequests()` (already correct per `time-request-approval`). Enqueued from `WorkScheduler.scheduleAllPeriodicWork()` alongside the existing three periodic jobs. Surfaces successful fetches to `ParentViewModel.pendingRequests` via a new singleton-level `pendingRequestsFlow` on `ParentRepository` that the VM collects.

Both reuse the same repository call — no backend change, no RLS change, no schema change. The proposal's option 2 (defense-in-depth) is in scope; option 3 (delete `RealtimeViewModel.kt` / `RealtimeManager.kt`) is **out of scope** per the user-gated scope decision.

## Architecture Decisions

### D1. Tab-tap refresh trigger — **`LaunchedEffect(selectedTab)` in `DashboardScaffold`**

| Choice | Alternatives | Rationale |
|---|---|---|
| **`LaunchedEffect(selectedTab)` keyed on the tab index, body gated to `selectedTab == 1`** | (a) `LaunchedEffect(Unit)` + `snapshotFlow { selectedTab }.collect` (b) `produceState` driven by `selectedTab` (c) attach the trigger to the `RequestsTab` composable itself | `LaunchedEffect(key)` is the idiomatic Compose primitive for "do something when this key changes" and is the lowest-friction path for ~5 lines. Keying on the tab index (a plain `Int`) means the effect is cancelled and re-launched on every tab change — the spec's "re-tap triggers a fresh fetch" requirement is satisfied for free. Gating the body to `selectedTab == 1` keeps the other tabs free of Solicitudes fetch traffic (spec scenario "Other tabs do not trigger a Solicitudes fetch"). |

### D2. Cross-process data propagation — **`pendingRequestsFlow` on `ParentRepository`**

| Choice | Alternatives | Rationale |
|---|---|---|
| **Add `pendingRequestsFlow: StateFlow<List<TimeRequest>>` to `ParentRepository` (already `@Singleton`); the worker calls a new `publishPendingRequests(list)` to update it; `ParentViewModel` collects it in `init` and mirrors into `_pendingRequests`.** | (a) Singleton event bus (Hilt `@Singleton` holder class with a `SharedFlow`). (b) Worker writes to Room; `ParentViewModel` reads from Room. (c) Pass `ParentViewModel` into the worker via Hilt (not possible — VM scope ≠ worker scope). | `ParentRepository` is already a `@Singleton` (`data/repository/ParentRepository.kt:39`) and is the canonical source of truth for parent-side reads; lifting the cache one level up keeps the worker's write path and the VM's read path talking through the same object without introducing a new holder class. A `StateFlow` (vs `SharedFlow`) matches the spec's "post via `StateFlow.update {}`" wording and lets `ParentViewModel` mirror via a single `launch { repository.pendingRequestsFlow.collect { _pendingRequests.value = it } }` in `init`. |

### D3. Worker skeleton — **mirror `HeartbeatWorker` exactly**

| Choice | Alternatives | Rationale |
|---|---|---|
| **`@HiltWorker class SolicitudesPollingWorker` colocated in `workers/SolicitudesPollingWorker.kt`, `Dispatchers.IO`, `ExistingPeriodicWorkPolicy.KEEP`, `setRequiredNetworkType(CONNECTED)`, exponential backoff at `WorkRequest.MIN_BACKOFF_MILLIS`.** | (a) Reuse `HeartbeatWorker` and parameterize. (b) Use a `ListenableWorker` instead of `CoroutineWorker`. | `HeartbeatWorker` (`workers/Workers.kt:19-73`) is the established shape: same Hilt graph, same dispatcher, same retry policy. Mirroring it keeps the new worker discoverable and reduces cognitive load for the next reader. `KEEP` (vs `UPDATE` on Heartbeat) is what the spec explicitly asks for (`specs/time-request-approval/spec.md:67`) — the worker is idempotent, so replacing its schedule on every `scheduleAllPeriodicWork` call is wasteful. |

### D4. Worker offline gate — **mirror `SyncManager.sync()` line 164**

| Choice | Alternatives | Rationale |
|---|---|---|
| **At the top of `doWork()`, return `Result.success()` if `SupabaseClientProvider.connectionState.value != ConnectionState.CONNECTED`.** | (a) Rely solely on `setRequiredNetworkType(CONNECTED)` and skip the explicit gate. (b) Throw and return `Result.retry()`. | `SyncManager.sync()` (`sync/SyncManager.kt:164`) uses this exact pattern — `connectionState` can lag the constraint check (e.g., Supabase reachable but our app's reachability probe hasn't refreshed). Returning `Result.success()` is the spec scenario "Worker skips when offline": it's a clean no-op, not a retryable failure, because the constraint will re-fire the worker when connectivity returns. |

### D5. In-flight dedup — **set `isLoading` in `loadPendingRequests()` and early-return**

| Choice | Alternatives | Rationale |
|---|---|---|
| **At the top of `loadPendingRequests()`, return immediately if `_isLoading.value == true`. Set `_isLoading.value = true` on entry, `false` in `finally`.** | (a) Introduce a separate `_pendingRequestsLoading` flag. (b) Use a per-call `Mutex` inside the VM. (c) Use a counter / debounce window. | `loadDevices()` (`viewmodel/ParentViewModel.kt:108-136`) already toggles the existing `_isLoading` flow — applying the same pattern to `loadPendingRequests()` keeps the loading-flag model uniform and reuses the spinner logic in `RequestsTab` (line 357-363). A separate flag would split the loading model across two booleans for no gain. `Mutex` adds machinery without behavioral improvement because tab taps are bounded by human speed, not by contention. |

## Data Flow

**Tab-tap path (interactive refresh):**

```
Parent taps "Solicitudes" tab
  → DashboardScreen onSelectTab(1)
    → selectedTab = 1
      → LaunchedEffect(selectedTab) re-keys
        → if (selectedTab == 1) viewModel.loadPendingRequests()
          → if (_isLoading.value) return            // D5 dedup
          → _isLoading.value = true
            → ParentRepository.getPendingRequests()
              → GET /rest/v1/time_requests?status=eq.PENDING&order=created_at.desc
              → returns Result<List<TimeRequest>>
            → publishPendingRequests(list)         // updates repository.pendingRequestsFlow
              → ParentViewModel collector in init
                → _pendingRequests.value = list
                  → DashboardScreen RequestsTab recomposes
          → _isLoading.value = false (in finally)
```

**Worker path (background refresh):**

```
WorkManager (5-min tick from enqueueUniquePeriodicWork)
  → SolicitudesPollingWorker.doWork()            // Dispatchers.IO
    → if (clientProvider.connectionState.value != ConnectionState.CONNECTED)    // D4
        return Result.success()                  // no-op, spec scenario
    → val session = authManager.getAccessToken()
      if (null) return Result.success()          // spec scenario "no signed-in parent"
    → ParentRepository.getPendingRequests()
      → GET /rest/v1/time_requests?status=eq.PENDING
    → onSuccess: publishPendingRequests(list)    // D2
    → onTransientFailure: Result.retry()         // exponential backoff
    → onPermanentAuthMissing: Result.success()   // no retry loop
```

The two paths converge at `ParentRepository.pendingRequestsFlow`. The UI sees the same `StateFlow<List<TimeRequest>>` regardless of who triggered the refresh.

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `app/src/main/java/com/tudominio/parentalcontrol/workers/SolicitudesPollingWorker.kt` | Create | `@HiltWorker` with `WORK_NAME = "solicitudes_polling"`. Mirrors `HeartbeatWorker` (`workers/Workers.kt:19-73`). Injects `ParentRepository` and `SupabaseClientProvider`. |
| `app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt` | Modify | Add `scheduleSolicitudesPolling(context)` after `scheduleHeartbeat` (current line 47) mirroring its shape but with `KEEP` policy. Wire it into `scheduleAllPeriodicWork` (current lines 12-20) so it is registered once on app start. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt` | Modify | Add `LaunchedEffect(selectedTab)` inside `DashboardScaffold` (after the `TabRow` at line 222, before the `when (selectedTab)` at line 224) that calls `viewModel.loadPendingRequests()` when `selectedTab == 1`. |
| `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` | Modify | Add private `_pendingRequestsFlow: MutableStateFlow<List<TimeRequest>>` + public `pendingRequestsFlow: StateFlow<List<TimeRequest>>` + public `publishPendingRequests(list)`. No change to existing `getPendingRequests()`. |
| `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` | Modify | In `loadPendingRequests()` (current lines 147-167): add the `_isLoading.value` toggle (D5) and the early-return gate. In `init` (current lines 81-84): launch a collector on `repository.pendingRequestsFlow` that mirrors into `_pendingRequests`. |
| `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt` | Modify | Add Compose test: tap Solicitudes tab → assert `loadPendingRequests` invoked (via existing `ParentViewModelTest` mock); tap Devices tab → assert not invoked (spec scenario "Other tabs do not trigger a Solicitudes fetch"). |
| `app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelTest.kt` | Modify | Add test: while `_isLoading = true`, second `loadPendingRequests()` is a no-op (spec scenario "In-flight fetch is deduped"). |
| `app/src/test/java/com/tudominio/parentalcontrol/workers/SolicitudesPollingWorkerTest.kt` | Create | `WorkManagerTestInitHelper` test driver. Three cases: connection offline → `Result.success()` no-op; no session → `Result.success()` no-op; success path → `repository.publishPendingRequests` called. |
| `app/src/test/java/com/tudominio/parentalcontrol/workers/WorkSchedulerTest.kt` | Modify | Extend with `scheduleSolicitudesPolling` assertion: `enqueueUniquePeriodicWork` called with name `"solicitudes_polling"`, `KEEP` policy, 5-min interval, `CONNECTED` constraint. |
| `openspec/changes/fix-parent-solicitudes-auto-poll/design.md` | Create | This document. |

## Interfaces / Contracts

**New worker** — constructor signature, no public method beyond `doWork()`:

```kotlin
@HiltWorker
class SolicitudesPollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val parentRepository: ParentRepository,
    private val clientProvider: SupabaseClientProvider,
) : CoroutineWorker(context, workerParams) {
    companion object {
        const val WORK_NAME = "solicitudes_polling"
    }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { ... }
}
```

**`ParentRepository` additions** (additive, no signature changes to existing methods):

```kotlin
private val _pendingRequestsFlow = MutableStateFlow<List<TimeRequest>>(emptyList())
val pendingRequestsFlow: StateFlow<List<TimeRequest>> = _pendingRequestsFlow.asStateFlow()

fun publishPendingRequests(list: List<TimeRequest>) {
    _pendingRequestsFlow.value = list
}
```

**`WorkScheduler.scheduleSolicitudesPolling`** — mirrors `scheduleHeartbeat`:

```kotlin
fun scheduleSolicitudesPolling(context: Context) {
    val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
    val workRequest = PeriodicWorkRequestBuilder<SolicitudesPollingWorker>(5, TimeUnit.MINUTES)
        .setConstraints(constraints)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
        .addTag(SolicitudesPollingWorker.WORK_NAME)
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SolicitudesPollingWorker.WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
```

## Testing Strategy

Per `openspec/config.yaml` `strict_tdd: true`. Each row follows RED → GREEN.

| Layer | What to Test | Approach |
|---|---|---|
| Unit (Worker) | `SolicitudesPollingWorkerTest`: offline gate, no-session no-op, success path calls `publishPendingRequests`, transient failure → `Result.retry()`. | `WorkManagerTestInitHelper` + MockK for `ParentRepository` / `SupabaseClientProvider`. Pattern follows existing `WorkSchedulerTest`. |
| Unit (Worker scheduling) | `WorkSchedulerTest`: `scheduleSolicitudesPolling` enqueues with correct name/policy/interval/constraint; `scheduleAllPeriodicWork` calls it. | MockK on `WorkManager`; verify enqueue args. |
| Unit (VM) | `ParentViewModelTest`: `loadPendingRequests()` dedupes when `_isLoading = true`; collector on `repository.pendingRequestsFlow` updates `_pendingRequests`. | MockK on `ParentRepository`; Turbine on `pendingRequests`. |
| Compose | `DashboardScreenTest`: tap Solicitudes → `loadPendingRequests` invoked; tap Devices → not invoked. | Robolectric Compose UI test with `createComposeRule()`. Pattern matches existing `OnboardingScreenTest`. |
| Quality gates | detekt / ktlintCheck / assembleDebug | `./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug`. |

No instrumented tests required — all changes are JVM-testable via Robolectric + WorkManager test driver + Compose UI test.

## Failure Modes & Mitigations

| Failure | Likelihood | Mitigation |
|---|---|---|
| Worker fires while device is offline. | Med | (1) `setRequiredNetworkType(CONNECTED)` defers until network returns. (2) Explicit `connectionState != CONNECTED` gate at top of `doWork()` short-circuits with `Result.success()` (D4). |
| Worker fires but network drops mid-call. | Low | Ktor `IOException` → `Result.retry()` → exponential backoff from `WorkRequest.MIN_BACKOFF_MILLIS`. Worker does not swallow the error silently (spec scenario "Worker retries on transient failure"). |
| Parent rapidly taps Solicitudes tab. | Low | `loadPendingRequests()` early-returns if `_isLoading.value == true` (D5). One in-flight fetch + spinner stays visible. |
| Worker and tab-tap fire concurrently. | Low | Both call `getPendingRequests()` (read-only) and converge on `pendingRequestsFlow`. Last write wins. RLS-scoped GET is idempotent under Supabase. |
| **Manual pull-to-refresh** | N/A | **GAP: there is no swipe-to-refresh in `DashboardScreen` / `RequestsTab`.** The worker + `LaunchedEffect` cover the live cases. Adding `androidx.compose.material.pullrefresh.PullRefreshIndicator` is a tracked follow-up. |
| Stale data after a long offline period. | Low | Worker constraints defer until connectivity returns. On reconnect, the next 5-min tick (or the next tab tap) catches up. Worst-case staleness ≈ 5 min + offline duration. |
| Worker fires with no signed-in parent. | Low | `getPendingRequests()` returns `Result.failure(DeviceListError.AuthMissing)`. Worker returns `Result.success()` (no retry, no StateFlow mutation) — spec scenario "Worker with no signed-in parent is a no-op". |
| Overlapping runs of the worker. | Low | `enqueueUniquePeriodicWork(..., ExistingPeriodicWorkPolicy.KEEP, ...)` keeps one active instance — spec scenario "Worker is idempotent across overlapping runs". |
| Process is killed while the worker is scheduled. | Low | WorkManager persists unique work in its internal DB; on process restart, the 5-min cadence resumes (spec scenario "Worker survives process restart"). |

## Migration / Rollout

No migration. Both changes are additive:

- `SolicitudesPollingWorker` is a new `enqueueUniquePeriodicWork` — first launch schedules it; subsequent launches use `KEEP` so it never duplicates.
- `LaunchedEffect(selectedTab)` only fires when the user actually switches tabs — no idle CPU cost.
- `pendingRequestsFlow` initializes to `emptyList()`, matching the existing `_pendingRequests` initial value. No screen sees a different first-frame.

**Rollback:** remove the `scheduleSolicitudesPolling(context)` line from `scheduleAllPeriodicWork` and revert the `LaunchedEffect` addition. No data migration, no config flag to flip.

## Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| `DashboardScreen` change affects unrelated tab interactions. | Low | Low | `LaunchedEffect(selectedTab)` body is gated to `selectedTab == 1`. Compose test asserts Devices tab does NOT invoke `loadPendingRequests` (spec scenario "Other tabs do not trigger a Solicitudes fetch"). |
| New worker shares `ParentRepository.getPendingRequests()` which is already correct. | N/A | N/A | No change to the repository call. RLS scoping already in place per `openspec/specs/time-request-approval/spec.md` (req "Parent lists pending requests"). |
| `KEEP` policy for Solicitudes vs `UPDATE` for Heartbeat. | Low | Low | Drift is on existing code (not introduced by this PR). `KEEP` is correct for both — a tracked follow-up can normalize Heartbeat. Worker behavior identical either way (idempotent read). |
| `loadPendingRequests()` setting `_isLoading` overlaps with `loadDevices()`. | Low | Low | A single shared `_isLoading` flag is the existing VM contract (`loadDevices()` already sets it). The `RequestsTab` spinner only shows when `isLoading && requests.isEmpty()` (line 357), so an in-flight device reload with an empty requests list will briefly show a spinner — acceptable. |
| `connectionState` gate is redundant with `setRequiredNetworkType`. | Low | None | Intentional belt-and-suspenders mirroring `SyncManager.sync()` line 164. The constraint handles "no network at enqueue"; the gate handles "constraint passed but reachability probe stale". |
| Worker's `@AssistedInject` requires `HiltWorkerFactory`. | Low | None | Already wired (`@HiltWorker` workers in `Workers.kt` prove the factory is in place). No DI graph change. |
| Open scope decision was Option 2, not Option 3 (no realtime cleanup). | N/A | None | `realtime/RealtimeViewModel.kt` and `realtime/RealtimeManager.kt` remain untouched in this change. The "RealtimeViewModel deleted or fully wired" success criterion stays gated until a follow-up picks it up. |

## Open Questions

None blocking the design. The "Open scope decision" in the proposal (`proposal.md:63-69`) was resolved by the user: Option 2 (LaunchedEffect + worker + scheduler) is in scope; Option 3 (delete realtime files) is out of scope for this change.