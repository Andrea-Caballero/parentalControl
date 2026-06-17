# Spec: parent-device-list

## Purpose
Lets the parent app list every child device paired under their account, fetched live from Supabase through a new RLS-aware edge function, and rendered in the parent dashboard.

## ADDED Requirements

### Requirement: Edge function returns devices for the authenticated parent
A new `get-devices-for-parent` Supabase edge function (under `supabase/functions/get-devices-for-parent/index.ts`) SHALL return every `devices` row where `parent_id` equals the caller's `auth.uid()`, respecting the RLS policies in `002_rls_policies.sql`.

#### Scenario: Authenticated parent gets their own devices
- **WHEN** an authenticated parent invokes `POST /functions/v1/get-devices-for-parent` with a valid bearer token,
- **THEN** the response SHALL be an HTTP 200 JSON array of `devices` rows (`id`, `device_name`, `device_model`, `os_version`, `app_version`, `device_state`, `last_seen_at`) filtered by `parent_id = auth.uid()`.

#### Scenario: Unauthenticated or non-parent caller is rejected
- **WHEN** the bearer token is missing, expired, or RLS denies access,
- **THEN** the edge function SHALL return 401/403 and the parent app SHALL treat it as an empty list and surface a non-blocking error toast.

#### Scenario: Parent with no devices gets an empty array
- **WHEN** the parent has no paired devices,
- **THEN** the response SHALL be `200 OK` with body `[]` (not 404).

### Requirement: ParentRepository.getDevices returns the live list
`ParentRepository.getDevices()` SHALL call the `get-devices-for-parent` edge function and SHALL NOT read device state from local SharedPreferences (replaces the existing mock).

#### Scenario: Repository returns a typed Result
- **WHEN** `ParentViewModel` calls `ParentRepository.getDevices()`,
- **THEN** the call SHALL return `Result<List<Device>>` and SHALL emit a `Loading` state to the UI while in flight.

#### Scenario: Offline call surfaces a retryable error
- **WHEN** the network call fails because the device is offline,
- **THEN** the repository SHALL return `Result.failure(NetworkError)` and the UI SHALL show a retry button — the previous mock fallback SHALL NOT run.

#### Scenario: Pull-to-refresh re-invokes the edge function
- **WHEN** the user triggers pull-to-refresh on the dashboard,
- **THEN** the repository SHALL bypass any local cache and SHALL re-invoke the edge function unconditionally.

### Requirement: DashboardScreen renders the real device list
`DashboardScreen` SHALL display the devices returned by `ParentRepository.getDevices()`, ordered by `last_seen_at` DESC, with one device per row showing name, model, and a relative "last seen" string.

#### Scenario: Non-empty list renders DeviceCard rows
- **WHEN** the list is non-empty,
- **THEN** the dashboard SHALL render one `DeviceCard` per device, ordered by `last_seen_at` DESC, each showing `device_name`, `device_model`, and a relative timestamp (e.g., "2 min ago").

#### Scenario: Empty list shows a pair-new-device CTA
- **WHEN** the list is empty,
- **THEN** the dashboard SHALL display an empty-state illustration with a "Pair a new device" call-to-action that opens `PairingBottomSheet`.

#### Scenario: Loading and error states have dedicated UI
- **WHEN** the call is in flight, the dashboard SHALL show a centered progress indicator; **WHEN** the call fails, an inline error banner with a "Retry" action SHALL appear above the list area.

## Out of scope
- Initiating a new pairing from the dashboard (covered by `pairing-flow`).
- Showing per-device live policy or grant state (covered by `app-block-policy` / `time-request-approval`).
- Pagination — expected device count per parent is small (< 50).
- Manual sort controls (only `last_seen_at` DESC for now).
