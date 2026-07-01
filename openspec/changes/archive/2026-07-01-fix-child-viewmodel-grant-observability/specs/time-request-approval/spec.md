# Spec delta: time-request-approval (ChildViewModel grant observability)

> This spec delta extends `openspec/specs/time-request-approval/spec.md` for the change `2026-07-01-fix-child-viewmodel-grant-observability`. The base spec defines the data path; this delta adds the requirement that the child home screen reacts to a new grant without an app restart.

## ADDED Requirements

### Requirement: Child home screen reflects approved time without an app restart
The child's `ChildViewModel.remainingMinutes` SHALL observe `grantDao` so that a new `GrantEntity` row created by the post-boot `pullApprovedRequests` (or by the FCM/Realtime `processApproval` path) updates the home screen "Xh · minutos restantes" number on the next composition cycle. No app restart SHALL be required.

#### Scenario: +15 min grant updates the number after the next pull
- **WHEN** `processApproval` inserts a new `GrantEntity` with `minutes = 15` and `expires_at > now`,
- **THEN** `ChildViewModel.remainingMinutes` SHALL recompute as `dailyLimit - dailyUsage + sum(active_grants.minutes)` and the home screen SHALL re-render with the updated total.

#### Scenario: Cold boot with no device id stays at zero
- **WHEN** the ViewModel inits and `authManager.deviceId.value == null` (unpaired, pre-pairing),
- **THEN** `ChildViewModel._activeGrants` SHALL emit `emptyList()` and `remainingMinutes` SHALL stay at `0` (regression: the previous behavior is preserved when unpaired).

#### Scenario: Expired grant does not contribute
- **WHEN** a `GrantEntity` has `expires_at < now`,
- **THEN** the reactive filter SHALL drop it and `remainingMinutes` SHALL NOT include its `minutes` (regression: the existing `expires_at > now` filter used by `TimeExtraViewModel` is the same one used here).

#### Scenario: Formatter handles values > 120 min
- **WHEN** `remainingMinutes > 120` (e.g., `dailyLimit = 120` + 15 min grant = `135`),
- **THEN** the home screen SHALL render the number as `2h 15m`, not `2h` or `2h 0m` (regression: the formatter must handle >120 min, the same edge case the formatHoursMinutes helper already passes in unit tests).
