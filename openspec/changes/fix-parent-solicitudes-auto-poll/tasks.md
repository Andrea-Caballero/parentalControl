# Tasks: Fix parent "Solicitudes" tab auto-refresh

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~250-320 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: stacked-to-main
400-line budget risk: Low

Reasoning: 1 new worker + 1 new repo flow + ~5-line UI hook + 3 test extensions. Well under 400-line budget.

## Phase 1: RED — failing tests (test-only commit)

- [x] 1.1 `ParentViewModelTest.kt`: add `loadPendingRequests_second_call_while_running_is_noop` (pre-set `_isLoading=true`, assert fetch once) and `init_collects_pendingRequestsFlow_into_state` (Turbine + fake emit).
- [x] 1.2 `workers/WorkersTest.kt` (existing `WorkSchedulerTest` at line 179): add `scheduleSolicitudesPolling_enqueues_unique_periodic_with_keep_and_5min_interval` (enqueueUniquePeriodicWork `"solicitudes_polling"`, KEEP, 5 min, CONNECTED).
- [x] 1.3 Create `workers/SolicitudesPollingWorkerTest.kt` — 3 cases: offline → `Result.success()` no-op; no access token → no-op; success → `publishPendingRequests(list)` called. Pattern: `WorkManagerTestInitHelper` + MockK (follows `OutboxDrainerTest`).
- [x] 1.4 `DashboardScreenTest.kt`: add `tab_tap_to_solicitudes_invokes_loadPendingRequests_other_tabs_do_not` (mock VM; Sol tab triggers fetch, Devices does not).
- [x] 1.5 `./gradlew testDebugUnitTest detekt ktlintCheck` new tests RED, pre-existing pass. Commit: `test(parent): add RED coverage for solicitudes auto-refresh`.

## Phase 2: GREEN — repository flow + VM collector (prod commit)

- [x] 2.1 `data/repository/ParentRepository.kt` (after line 49): private `_pendingRequestsFlow = MutableStateFlow<List<TimeRequest>>(emptyList())`; expose `val pendingRequestsFlow: StateFlow<List<TimeRequest>>`; add `fun publishPendingRequests(list: List<TimeRequest>)`. Import `MutableStateFlow`/`StateFlow`.
- [x] 2.2 `viewmodel/ParentViewModel.kt` `init` (lines 81-84): after existing `loadPendingRequests()`, add `viewModelScope.launch { repository.pendingRequestsFlow.collect { _pendingRequests.value = it } }`.
- [x] 2.3 `ParentViewModel.loadPendingRequests()` (147-167): early-return if `_isLoading.value == true`; set `_isLoading.value = true` after viewModelScope.launch; reset in `finally` (mirrors `loadDevices()` 108-136).
- [x] 2.4 `./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug` tests 1.1 GREEN. Commit: `feat(parent): lift pending-requests cache to repository StateFlow`.

## Phase 3: GREEN — worker + scheduler (prod commit)

- [x] 3.1 Create `workers/SolicitudesPollingWorker.kt`: `@HiltWorker class SolicitudesPollingWorker @AssistedInject constructor(@Assisted context, @Assisted params, parentRepository: ParentRepository, clientProvider: SupabaseClientProvider) : CoroutineWorker(...)`, `WORK_NAME = "solicitudes_polling"`. In `Dispatchers.IO`: `Result.success()` if offline (D4) or no token; else `getPendingRequests()`; on success `publishPendingRequests(list)`+`Result.success()`; transient → `Result.retry()`. Mirrors `HeartbeatWorker` (`Workers.kt:19-73`).
- [x] 3.2 `workers/WorkScheduler.kt`: add `fun scheduleSolicitudesPolling(context)` after `scheduleHeartbeat` (line 47). Periodic 5 min, `KEEP` (spec 67), CONNECTED, exponential backoff at `MIN_BACKOFF_MILLIS`.
- [x] 3.3 `scheduleAllPeriodicWork()` (12-20): call `scheduleSolicitudesPolling(context)` alongside the other three; update Log.d to mention 4 workers.
- [x] 3.4 `./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug` tests 1.2/1.3 GREEN. Commit: `feat(workers): add SolicitudesPollingWorker (5-min, KEEP)`.

## Phase 4: GREEN — dashboard tab-tap hook (prod commit)

- [x] 4.1 `ui/parent/screens/DashboardScreen.kt`: inside `DashboardScaffold`'s `Column`, between `TabRow { }` (line 222) and `when (selectedTab) {` (line 224), add `LaunchedEffect(selectedTab) { if (selectedTab == 1) viewModel.loadPendingRequests() }`. Add `import androidx.compose.runtime.LaunchedEffect`.
- [x] 4.2 `./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug` → test 1.4 GREEN. Commit: `feat(dashboard): refresh solicitudes on tab focus`.

## Phase 5: Manual end-to-end verification

- [ ] 5.1 POCO child POSTs `/rest/v1/time_requests`; OPPO parent on Solicitudes sees row within 5 s (criterion #1). Idle 6 min on Devices, switch to Solicitudes → fresh via worker (criterion #2). Full `./gradlew :app:lintDebug testDebugUnitTest detekt ktlintCheck assembleDebug`; install on POCO + OPPO.
