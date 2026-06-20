# Spec: parent-auth-session

## Purpose

Gives the parent role a synthetic anonymous auth session and a toggleable Ktor `MockEngine` so the dashboard renders fixture devices without depending on a real Supabase instance. Replaced by `parent-auth-flow` once real parent sign-up/sign-in lands.

## ADDED Requirements

### Requirement: Role-aware authenticateOrCreate issues a session with the correct role flag

`DeviceAuthManager.authenticateOrCreate(role)` SHALL create an auth session and persist `role` to `device_auth_prefs`.

#### Scenario: Parent role flag is persisted
- **WHEN** `authManager.authenticateOrCreate(Role.PARENT)` is called,
- **THEN** a session SHALL be created,
- **AND** the role `PARENT` SHALL be persisted to `device_auth_prefs`.

#### Scenario: Child role flag is persisted
- **WHEN** `authManager.authenticateOrCreate(Role.CHILD)` is called,
- **THEN** a session SHALL be created,
- **AND** the role `CHILD` SHALL be persisted to `device_auth_prefs`.

### Requirement: OnboardingScreen wires authenticateAsParent before navigating to Dashboard

`OnboardingScreen` SHALL call `authenticateAsParent()` and SHALL only navigate to `Dashboard` on success. During auth the parent button SHALL be disabled and a loading indicator SHALL be visible.

#### Scenario: Parent button triggers auth before nav
- **WHEN** the user taps "Soy el padre" in `OnboardingScreen`,
- **THEN** `authenticateAsParent()` SHALL be called and complete,
- **AND THEN** navigation to `Dashboard` SHALL occur.

#### Scenario: Loading state shown during auth
- **WHILE** `authenticateAsParent()` is in progress,
- **THEN** the parent button SHALL be disabled and a loading indicator SHALL be shown.

#### Scenario: Auth failure does not navigate
- **WHEN** `authenticateAsParent()` fails,
- **THEN** navigation to `Dashboard` SHALL NOT occur,
- **AND** an error SHALL be surfaced to the user.

### Requirement: Mock Supabase engine is toggleable via BuildConfig.USE_MOCK_SUPABASE

The Ktor `HttpClient` SHALL bind to `MockEngine` when `BuildConfig.USE_MOCK_SUPABASE == true` and SHALL bind to the real engine otherwise. The mock engine SHALL return fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Mock engine used when flag is true
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == true`,
- **THEN** the Ktor `HttpClient` SHALL use `MockEngine` returning fixture JSON for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Real engine used when flag is false
- **WHEN** `BuildConfig.USE_MOCK_SUPABASE == false`,
- **THEN** the Ktor `HttpClient` SHALL use the real engine (currently unreachable due to placeholder Supabase config, but bound).

### Requirement: DashboardScreen error banner CTAs swap for auth errors

The parent dashboard's error banner SHALL present an "Iniciar sesión como padre" CTA when the underlying error indicates missing authentication, and SHALL fall back to the standard retry + back CTAs for transient errors.

#### Scenario: "Iniciar sesión como padre" CTA for auth errors
- **WHEN** the error message contains "not authenticated",
- **THEN** the error banner SHALL show a single "Iniciar sesión como padre" CTA (not retry/back),
- **AND** tapping it SHALL trigger the parent auth flow.

#### Scenario: Retry + back for transient errors
- **WHEN** the error message does NOT contain "not authenticated",
- **THEN** the error banner SHALL show "Reintentar" and "Volver" CTAs as before.

### Requirement: MockSupabaseFixtures contract returns realistic data

`MockSupabaseFixtures` SHALL expose ready-to-parse JSON payloads for `/devices`, `/pending-requests`, and `/templates`.

#### Scenario: Device list fixture
- **WHEN** the mock engine handles `GET /devices` for an authenticated parent,
- **THEN** it SHALL return JSON with a list of ≥ 2 child devices, each with `deviceId`, `displayName`, `lastSeen`, `status`.

#### Scenario: Templates fixture
- **WHEN** the mock engine handles `GET /templates`,
- **THEN** it SHALL return JSON with a list of ≥ 1 policy templates, each with `templateId`, `name`, `policyJson`.

## Out of scope

- Real parent sign-up/sign-in (formal `parent-auth-flow`).
- Real Supabase backend integration (archived `supabase-backend-integration`).
- Removing the 5 TODOs in `ParentRepository.kt`.
- Token refresh logic; changes to the child pairing flow.

## Verification hooks

| Req | Test |
|---|---|
| 1 | `DeviceAuthManagerTest` — `authenticateOrCreate_persistsParentRole`, `..._persistsChildRole` |
| 2 | `OnboardingViewModelTest` — `parent_tap_triggers_auth_then_navigates`; Compose UI test for loading + failure |
| 3 | `NetworkModuleTest` — `useMockSupabase_true_bindsMockEngine`, `..._false_bindsRealEngine` |
| 4 | `DashboardScreenTest` — `errorBanner_authError_showsSignInCta`, `..._transientError_showsRetryAndBack` |
| 5 | `MockSupabaseFixturesTest` — `devices_returnsAtLeastTwoChildDevices`, `..._templates_returnsAtLeastOne` |
