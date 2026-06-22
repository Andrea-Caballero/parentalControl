# Delta: parent-auth-session

## MODIFIED Requirements

### Requirement: Mock Supabase engine is toggleable via BuildConfig.USE_MOCK_SUPABASE

The Ktor `HttpClient` SHALL bind to `MockEngine` when `BuildConfig.USE_MOCK_SUPABASE == true` and SHALL bind to the real engine otherwise. The mock engine SHALL return fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

**AND** under the `debug` build type, the build SHALL read the `USE_MOCK_SUPABASE` flag from `local.properties` (falling back to `gradle.properties`) so the documented developer workflow takes effect.

(Previously: defined only the runtime flag semantics; did not specify the Gradle source for the flag, which caused `USE_MOCK_SUPABASE=true` in `local.properties` to be ignored and the real engine to be bound.)

#### Scenario: Mock engine used when flag is true
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == true`,
- **THEN** the Ktor `HttpClient` SHALL use `MockEngine` returning fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Real engine used when flag is false
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == false`,
- **THEN** the Ktor `HttpClient` SHALL use the real engine (currently unreachable due to placeholder Supabase config, but bound).

#### Scenario: Build reads USE_MOCK_SUPABASE from local.properties under debug
- **GIVEN** the `debug` build type is active,
- **AND** `local.properties` contains `USE_MOCK_SUPABASE=true`,
- **THEN** `BuildConfig.USE_MOCK_SUPABASE` SHALL be `true`,
- **AND** the Ktor `HttpClient` SHALL bind to `MockEngine`.

#### Scenario: Release build does not honor local.properties USE_MOCK_SUPABASE
- **GIVEN** the `release` build type is active,
- **AND** `local.properties` contains `USE_MOCK_SUPABASE=true`,
- **THEN** `BuildConfig.USE_MOCK_SUPABASE` SHALL be `false`,
- **AND** the Ktor `HttpClient` SHALL bind to the real engine.

### Requirement: DashboardScreen error banner CTAs swap for auth errors

The parent dashboard's error banner SHALL present an "Iniciar sesión como padre" CTA when the underlying error indicates missing authentication, and SHALL fall back to the standard retry + back CTAs for transient errors.

**AND THEN**, when the user taps the "Iniciar sesión como padre" CTA and authentication succeeds, `loadDevices()` SHALL be re-invoked automatically so the device list transitions out of the `Error(AuthMissing)` state.

(Previously: defined the CTA swap but did not specify reload behavior after auth, leaving the banner stuck in `Error(AuthMissing)` even when auth completed successfully.)

#### Scenario: "Iniciar sesión como padre" CTA for auth errors
- **WHEN** the error message contains "not authenticated",
- **THEN** the error banner SHALL show a single "Iniciar sesión como padre" CTA (not retry/back),
- **AND** tapping it SHALL trigger the parent auth flow.

#### Scenario: Retry + back for transient errors
- **WHEN** the error message does NOT contain "not authenticated",
- **THEN** the error banner SHALL show "Reintentar" and "Volver" CTAs as before.

#### Scenario: Tapping sign-in CTA reloads devices after successful auth
- **GIVEN** the dashboard is showing `DeviceListUiState.Error(DeviceListError.AuthMissing)`,
- **WHEN** the user taps the "Iniciar sesión como padre" CTA,
- **AND** `authenticateAsParent()` completes with `Result.success(Unit)`,
- **THEN** `loadDevices()` SHALL be invoked,
- **AND THEN** `DeviceListUiState` SHALL transition to `Loading`,
- **AND THEN** `DeviceListUiState` SHALL transition to `Success(devices)` when the fetch completes (mock engine returns ≥ 2 devices per existing fixture contract).

#### Scenario: Tapping sign-in CTA does not reload on auth failure
- **GIVEN** the dashboard is showing `DeviceListUiState.Error(DeviceListError.AuthMissing)`,
- **WHEN** the user taps the "Iniciar sesión como padre" CTA,
- **AND** `authenticateAsParent()` completes with `Result.failure(throwable)`,
- **THEN** `loadDevices()` SHALL NOT be invoked,
- **AND** the error banner SHALL remain visible.

## Verification hooks

| Req | Test |
|---|---|
| 3 (modified) | `NetworkModuleTest` — add `debug_buildtype_reads_useMockSupabase_from_localProperties`, `release_buildtype_ignores_localProperties_useMockSupabase` |
| 4 (modified) | `ParentViewModelTest` — add `authenticateAsParent_success_invokesLoadDevices`, `authenticateAsParent_failure_doesNotInvokeLoadDevices` |