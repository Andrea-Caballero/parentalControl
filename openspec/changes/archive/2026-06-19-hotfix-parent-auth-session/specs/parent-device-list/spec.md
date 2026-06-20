# Delta for parent-device-list

## MODIFIED Requirements

### Requirement: Error banner CTAs adapt to error type

The parent device list's error banner SHALL present an "Iniciar sesión como padre" CTA when the underlying error indicates missing authentication (error message contains "not authenticated"), and SHALL fall back to the standard retry + back CTAs for transient errors.
(Previously: the error banner unconditionally rendered "Reintentar" and "Volver" CTAs.)

#### Scenario: Auth-missing error shows the sign-in CTA
- **WHEN** `DeviceListUiState.Error("not authenticated")` is observed,
- **THEN** the rendered error banner SHALL contain a "Iniciar sesión como padre" CTA,
- **AND** the banner SHALL NOT show the "Reintentar" or "Volver" CTAs.

#### Scenario: Transient error shows retry + back
- **WHEN** `DeviceListUiState.Error(<anything not containing "not authenticated">)` is observed,
- **THEN** the rendered error banner SHALL contain "Reintentar" and "Volver" CTAs as before.
