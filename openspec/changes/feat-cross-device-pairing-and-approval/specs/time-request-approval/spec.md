---
delta:
  spec: time-request-approval
  type: ADDED
  ref: openspec/specs/time-request-approval/spec.md
---

# Delta for time-request-approval

Adds an auto-DENY contract for time requests the parent never answers. Per decision Q3, unanswered `PENDING` requests SHALL auto-deny after 1 hour. This is independent of the cross-device slice structure (not bundled with any specific slice); it is included here so the behavior is spec-pinned before the cross-device harness can exercise it end-to-end.

## ADDED Requirements

### Requirement: Unanswered time requests auto-DENY after 1 hour

A `time_request` with `status = "PENDING"` and `created_at < NOW() - 1 hour` SHALL auto-deny on the next parent poll (`SolicitudesPollingWorker` or `ParentViewModel.loadPendingRequests()`) OR via a server-side job, whichever fires first. Auto-denial writes `denied_at = NOW()` and `response_text = "Auto-denied: no parent response within 1h"`. The `time_requests` row is updated to `status = "DENIED"`; no `grants` row is created.

#### Scenario: Stale PENDING request is auto-denied on next poll

- **GIVEN** a `time_requests` row with `status = "PENDING"`, `created_at = NOW() - 70 minutes`,
- **WHEN** `SolicitudesPollingWorker` (or `ParentViewModel.loadPendingRequests()`) executes the polling query,
- **THEN** the row SHALL be updated to `status = "DENIED"`, `denied_at = NOW()`, `response_text = "Auto-denied: no parent response within 1h"`,
- **AND** the parent UI SHALL NOT render the row in the Solicitudes tab on the next render.

#### Scenario: Auto-denial does not create a grants row

- **WHEN** a `time_request` auto-denies per the prior scenario,
- **THEN** no row SHALL be inserted into `grants`,
- **AND** the child's `EnforcementController` SHALL NOT receive any grant update for this request.

#### Scenario: Recently created PENDING request is not auto-denied

- **GIVEN** a `time_requests` row with `status = "PENDING"`, `created_at = NOW() - 30 minutes`,
- **WHEN** the parent polls,
- **THEN** the row SHALL remain `status = "PENDING"` and SHALL appear in the Solicitudes tab.