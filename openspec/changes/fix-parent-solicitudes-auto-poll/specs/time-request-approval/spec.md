# Delta for `time-request-approval`

## ADDED Requirements

### Requirement: Solicitudes tab auto-refreshes when visible
The parent dashboard SHALL re-fetch pending time requests whenever the user switches to the Solicitudes tab, so the list is never stale relative to the last tap.

#### Scenario: First tap on the Solicitudes tab triggers a fetch
- **WHEN** the parent dashboard's `selectedTab` becomes the Solicitudes tab for the first time in the session,
- **THEN** a `LaunchedEffect(selectedTab)` keyed on tab 1 SHALL invoke `loadPendingRequests()` on `ParentViewModel`, which SHALL call `ParentRepository.getPendingRequests()` against `time_requests`.

#### Scenario: Re-tapping the Solicitudes tab triggers a fresh fetch
- **WHEN** the parent switches away from the Solicitudes tab and back to it,
- **THEN** the `LaunchedEffect(selectedTab)` SHALL re-invoke `loadPendingRequests()` and SHALL NOT rely on cached `StateFlow` data alone.

#### Scenario: In-flight fetch is deduped
- **WHEN** `loadPendingRequests()` is already running,
- **THEN** a second tab tap SHALL NOT issue a parallel fetch (gated by the view model's `isLoading` flag) and SHALL keep the spinner visible.

#### Scenario: Other tabs do not trigger a Solicitudes fetch
- **WHEN** `selectedTab` is any tab other than the Solicitudes tab,
- **THEN** `loadPendingRequests()` SHALL NOT be invoked by the `LaunchedEffect`.

### Requirement: SolicitudesPollingWorker runs on a 5-minute cadence
The system SHALL run a periodic `SolicitudesPollingWorker` that mirrors `HeartbeatWorker`'s lifecycle and fetches pending time requests for the currently-authenticated parent, so the UI is fresh even when the parent never opens the tab.

#### Scenario: Worker is scheduled with a 5-minute repeat interval
- **WHEN** `WorkScheduler.scheduleAllPeriodicWork(context)` runs,
- **THEN** `SolicitudesPollingWorker` SHALL be enqueued via `PeriodicWorkRequestBuilder<SolicitudesPollingWorker>(5.minutes)` with the same constraints and unique work name pattern used by `HeartbeatWorker`.

#### Scenario: Worker fetches pending requests for the signed-in parent
- **WHEN** the worker executes,
- **THEN** it SHALL resolve the current parent session and SHALL call `ParentRepository.getPendingRequests()`, which queries `time_requests` filtered by `status = "PENDING"` with RLS enforcing `parent_id = auth.uid()`.

#### Scenario: Worker skips when offline
- **WHEN** `SyncManager.connectionState` is anything other than `CONNECTED` at worker start,
- **THEN** the worker SHALL return `Result.success()` without performing the network call (mirrors `SyncManager.sync()`'s connectivity gate).

#### Scenario: Worker retries on transient failure
- **WHEN** `ParentRepository.getPendingRequests()` throws a transient network or server error,
- **THEN** the worker SHALL return `Result.retry()` so WorkManager reschedules with backoff, and SHALL NOT swallow the error silently.

#### Scenario: Worker is idempotent across overlapping runs
- **WHEN** a worker run overlaps with the previous run that has not yet completed,
- **THEN** WorkManager's unique-work enqueue SHALL keep only one active instance and SHALL NOT enqueue duplicate fetches.

### Requirement: Successful worker fetch updates the pending-requests StateFlow
The system SHALL propagate a successful worker fetch into `ParentViewModel.pendingRequests` so the next time the parent opens the Solicitudes tab, the UI reflects newly-arrived requests without a manual reload.

#### Scenario: Worker success pushes fresh data to the StateFlow
- **WHEN** `SolicitudesPollingWorker` completes its fetch and the parent is currently signed in,
- **THEN** it SHALL post the resulting list to `ParentViewModel.pendingRequests` via `StateFlow.update {}` (or equivalent) so any collector on the Solicitudes tab sees the new list.

#### Scenario: Worker with no signed-in parent is a no-op
- **WHEN** the worker runs and no parent session exists,
- **THEN** the worker SHALL return `Result.success()` and SHALL NOT mutate `ParentViewModel.pendingRequests`.

#### Scenario: UI reflects new requests without manual reload
- **WHEN** the parent opens the Solicitudes tab after the worker has posted new data,
- **THEN** `RequestCard` components SHALL render the freshly-polled rows on first composition, with no pull-to-refresh required.

### Requirement: SolicitudesPollingWorker is wired into scheduleAllPeriodicWork
The system SHALL enqueue `SolicitudesPollingWorker` alongside `HeartbeatWorker`, `OutboxDrainer`, and `Reconciliation` so it is registered exactly once on app start.

#### Scenario: scheduleAllPeriodicWork enqueues all four periodic workers
- **WHEN** `WorkScheduler.scheduleAllPeriodicWork(context)` is invoked at app start or login,
- **THEN** it SHALL enqueue `SolicitudesPollingWorker` in addition to the existing `HeartbeatWorker`, `OutboxDrainer`, and `Reconciliation` jobs, each with its own unique work name and `KEEP` existing-work policy.

#### Scenario: Worker survives process restart
- **WHEN** the parent app process is killed and later relaunched,
- **THEN** WorkManager SHALL resume `SolicitudesPollingWorker` on its 5-minute cadence without the user opening the dashboard.

#### Scenario: Existing periodic jobs are not duplicated
- **WHEN** `scheduleAllPeriodicWork` is invoked a second time,
- **THEN** the existing `solicitudes_polling` unique work SHALL be kept (not replaced) and SHALL NOT produce a second concurrent fetcher.