# Spec: time-request-approval

## Purpose
Closes the loop on a child asking for more screen time: child outbox → parent verdict → `grants` row + FCM → child's `EnforcementController` reads the new grant from Room.

## ADDED Requirements

### Requirement: Child enqueues a time request via the outbox
The child app SHALL write a `time_request` payload into local `outbox`; `OutboxDrainer` SHALL deliver it to the `time_requests` table.

#### Scenario: Local insert enqueues the request
- **WHEN** the child taps "Ask for more time" with `minutes = 30`, `reason = "homework"`,
- **THEN** a row SHALL be inserted into `outbox` with `tipo = "time_request"` and payload `{ device_id, minutes_requested: 30, reason: "homework" }`; the UI shows "queued".

#### Scenario: Drained row creates a PENDING time_request
- **WHEN** the `OutboxDrainer` picks the row up,
- **THEN** a row SHALL be created in `time_requests` with `status = "PENDING"` and the local row SHALL be marked `processed = TRUE`.

#### Scenario: Offline request is sent later
- **WHEN** the device is offline at request time,
- **THEN** the local insert still succeeds and the request SHALL be sent automatically once the drainer runs.

### Requirement: Parent lists pending requests
The parent app SHALL fetch `time_requests` rows with `status = "PENDING"` for the parent's devices and render them in `RequestCard` components on `DeviceDetailScreen`.

#### Scenario: RLS-aware query scopes to the parent
- **WHEN** `ParentRepository.getPendingRequests()` is called,
- **THEN** the app SHALL query `time_requests` via Supabase REST filtered by `status = "PENDING"`, with the device join enforcing `parent_id = auth.uid()` via RLS.

#### Scenario: Non-empty list renders RequestCards
- **WHEN** the list is non-empty,
- **THEN** `DeviceDetailScreen` SHALL render one `RequestCard` per request showing device name, minutes, reason, and "Approve" / "Deny" buttons.

#### Scenario: Refresh removes stale PENDING items
- **WHEN** the parent pulls to refresh,
- **THEN** the list SHALL be re-fetched and any request no longer `PENDING` SHALL be removed.

### Requirement: Parent issues approve or deny verdict
Tapping "Approve" / "Deny" on a `RequestCard` SHALL call the `approve-request` edge function with the right `decision`.

#### Scenario: Approve creates a grants row and FCMs the child
- **WHEN** the parent taps "Approve",
- **THEN** `ParentRepository.approveRequest(requestId, minutes)` SHALL `POST /functions/v1/approve-request` with `{ request_id, decision: "APPROVED", minutes }`; the edge function SHALL insert a `grants` row (`status = "APPROVED"`, `source = "MANUAL"`, `expires_at = NOW() + minutes`) and FCM the child's active tokens in `device_push_tokens`.

#### Scenario: Deny closes without a grant
- **WHEN** the parent taps "Deny",
- **THEN** the edge function SHALL set `time_requests.status = "DENIED"`, `responded_at = NOW()`, SHALL NOT insert a `grants` row, and MAY FCM the denial reason.

#### Scenario: UI updates optimistically and rolls back on failure
- **WHEN** the verdict call succeeds,
- **THEN** the parent UI SHALL optimistically remove the `RequestCard`; **WHEN** it fails, the UI SHALL roll back and show a "Retry" snackbar.

### Requirement: Child receives the verdict and enforcement applies it
On FCM receipt the child SHALL sync grants; `EnforcementController` SHALL pick up the new `grants` row from Room without an app restart.

#### Scenario: FCM triggers a grants sync
- **WHEN** the child receives an FCM of type `grant.approved`,
- **THEN** `FirebaseMessagingService` SHALL trigger `SyncManager.syncGrants()` which upserts the new `grants` row into Room.

#### Scenario: New grant is reflected in remaining time
- **WHEN** the next `EnforcementController` poll runs after the sync,
- **THEN** the new `grants` row SHALL be reflected in remaining screen time and the UI SHALL show the new allowance.

#### Scenario: Expired grant reverts
- **WHEN** a grant's `expires_at` passes,
- **THEN** `EnforcementController` SHALL revert to the pre-grant state by reading `expires_at` from Room (server-side `cleanup_expired_grants` is a background concern).

## Out of scope
- Parent-side FCM token registration (not needed for the verdict).
- Modifying `EnforcementController` (already grant-aware from hotfix #2).
- Batch approval, scheduling, or auto-approval rules.
- Editing `time_requests.reason` from the parent UI.
