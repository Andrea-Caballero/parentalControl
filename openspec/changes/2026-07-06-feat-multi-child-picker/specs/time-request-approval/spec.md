# Delta for `time-request-approval`

> Change `feat-multi-child-picker` (PR B, stacked-to-main on top of PR A). This delta scopes the Solicitudes tab to the parent's currently-selected child when the picker is visible. The filter is **client-side** for V1; the `ParentRepository.kt:157-163` static-query refactor that would move this to the server is deferred to a later change (decision Q3=ii, R2-V1).

## ADDED Requirements

### Requirement: Solicitudes tab filters pending requests by selectedChildId

The Solicitudes tab on `DashboardScreen` SHALL filter its rendered `RequestCard` list by `ParentViewModel._selectedChildId.value`. When the value is `null` (chip = "Todos", or picker hidden because N ≤ 1), the tab SHALL render every pending request returned by `ParentRepository.getPendingRequests()`. When the value is a specific `childId`, the tab SHALL render only requests whose `deviceId` belongs to a `ChildDevice` whose `child?.id` matches. The filter SHALL be applied at the UI layer in `DashboardScaffold` against the already-fetched list — no new HTTP call SHALL be issued for a chip tap.

#### Scenario: "Todos" shows every pending request
- **WHEN** `_selectedChildId.value == null` and the parent opens the Solicitudes tab,
- **THEN** the tab SHALL render every entry in `_pendingRequests.value` with no filtering, and the badge SHALL match the rendered count.

#### Scenario: A child-specific chip narrows the Solicitudes tab
- **WHEN** the parent taps a per-child chip for childId = "c-123",
- **THEN** the Solicitudes tab SHALL render only requests whose `deviceId` is in the set of devices belonging to that child, and a tap on "Aprobar" / "Denegar" SHALL still hit `ParentRepository.approveRequest` with the original `requestId` (no client-side mutation of the request id).

#### Scenario: Filter is purely client-side
- **WHEN** the parent switches chips,
- **THEN** the repository SHALL NOT receive a new call to `getPendingRequests()` and the existing `_pendingRequests` `StateFlow` SHALL be reused as the source of truth.

### Requirement: V2 server-side filter refactor is deferred

A future change SHALL refactor `ParentRepository.getPendingRequests()` at `ParentRepository.kt:157-163` to accept a `childId: String?` parameter and append `.in("device_id", childDeviceIds)` to the Supabase REST query so the server returns the filtered list directly. Until that change lands, the client-side filter in `DashboardScaffold` is the source of truth.

#### Scenario: Static-query refactor is out of scope for this change
- **WHEN** the PR for `feat-multi-child-picker` is opened,
- **THEN** it SHALL NOT modify the `getPendingRequests()` query string at `ParentRepository.kt:157-163`, and the `time-request-approval` capability SHALL keep its current "fetch every pending request" semantics.

## Out of scope
- Per-child notifications (covered by `app-block-policy`).
- Server-side `childId` filter on `time_requests` (deferred V2 refactor of `ParentRepository.kt:157-163`).
- Realtime push of new pending requests into the Solicitudes tab without a tab switch (acceptable V1 window; covered by `SolicitudesPollingWorker`).