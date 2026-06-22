# Design: Hotfix parent-auth CTA reload + Gradle mock wiring

## Technical Approach

Hotfix for two regressions on `parent-auth-session` (see `parent-auth-regression-2026-06-20`):

1. `authenticateAsParent()` becomes **reload-on-success** via inline `loadDevices()` (~3 lines).
2. Gradle reads `local.properties` explicitly inside `buildTypes.debug` and wires `USE_MOCK_SUPABASE` into `BuildConfig` (~10 lines).
3. `gradle.properties` gains `USE_MOCK_SUPABASE=true` (1 line).

Plus 4 new unit tests. No public API changes. No new types.

## Architecture Decisions

### Decision 1: Inline `loadDevices()` inside `authenticateAsParent()` on success

| Field | Value |
|---|---|
| Choice | Body becomes `authManager.authenticateOrCreate(Role.PARENT).onSuccess { loadDevices() }`. Signature unchanged. |
| Alternatives | (a) `SharedFlow<AuthEvent>` consumed by `DashboardScreen` via `LaunchedEffect` — decoupled but more parts; (b) Move auth into `loadDevices()` itself — mixes responsibilities |
| Rationale | Inline keeps auth + reload atomic. Serialization guaranteed by `viewModelScope`. No new abstractions. Smallest diff. Fixes both the Devices-tab CTA path and the `OnboardingScreen` post-nav path in one place. |

### Decision 2: Gradle reads `local.properties` only inside `buildTypes.debug`

| Field | Value |
|---|---|
| Choice | Inside `buildTypes.debug { ... }`, parse `local.properties` via `Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }`. Fall back to `project.findProperty("USE_MOCK_SUPABASE")` (gradle.properties / CLI). `release` ignores `local.properties`; `defaultConfig` defaults to `"false"`. |
| Alternatives | (a) Read `local.properties` for all build types — stale file silently enables mock fixtures in release (footgun); (b) Read always + assert `!= true` in release — defensive but adds CI coupling |
| Rationale | Gating to `debug` makes the failure mode loud: release ignores `local.properties`, so any release-time `BuildConfig.USE_MOCK_SUPABASE == true` assertion fails. Matches the two new scenarios in `parent-auth-session/spec.md`. |

### Decision 3: Failure path leaves `DeviceListUiState` untouched

| Field | Value |
|---|---|
| Choice | `Result.failure` returns without mutating `_deviceListState`. The existing `Error(reason)` banner stays. |
| Alternatives | (a) New auth-failure UI state — richer UX but adds scope; (b) Auto-retry after delay — magic behavior |
| Rationale | Failure is already visible via `Transient` banner / `clearError()`. Smallest fix; out of scope per proposal. |

## Data Flow

Happy path after the fix:

```
ParentViewModel           ParentRepository       DeviceAuthManager      MockEngine
    │                            │                      │                    │
    │ init { loadDevices() }     │                      │                    │
    ├─loadDevices()─────────────►│                      │                    │
    │                            │ check session ──────►│                    │
    │                            │                      │ (missing)          │
    │ Result.failure(AuthMissing)│                      │                    │
    │◄───────────────────────────┤                      │                    │
    │ state = Error(AuthMissing) │                      │                    │
    │ (UI shows "Iniciar sesión como padre")             │                    │
    │                            │                      │                    │
    │ user taps CTA              │                      │                    │
    │ authenticateAsParent() (suspend, inline)           │                    │
    ├────────────────────────────┼─────────────────────►│                    │
    │                            │                      │ create session     │
    │                            │                      │ anon-PARENT-{uuid} │
    │ Result.success(Unit)       │                      │ persist role       │
    │ .onSuccess { loadDevices() }                      │                    │
    ├─loadDevices()─────────────►│                      │                    │
    │                            │ check session ──────►│                    │
    │                            │                      │ (present)          │
    │                            │ GET /devices ────────────────────────────►│
    │                            │                                       fixture JSON
    │ Result.success(devices)    │                                           │
    │◄───────────────────────────┤                                           │
    │ state = Success(devices)                                               │
```

## File Changes

| File | Action | Description |
|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` | Modify | Wrap `authenticateAsParent()` body so `Result.success` triggers `loadDevices()` (uses existing `viewModelScope.launch` at line 101). |
| `app/build.gradle.kts` | Modify | Add `local.properties` parser inside `buildTypes.debug { ... }`; pass `USE_MOCK_SUPABASE` to `buildConfigField`. `defaultConfig` defaults to `"false"`. |
| `gradle.properties` | Modify | Add `USE_MOCK_SUPABASE=true`. |
| `local.properties.template` | Modify | Update comment to clarify the value is now read by Gradle (debug-only). |
| `app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelTest.kt` | Modify | Add 2 tests: `authenticateAsParent_success_invokesLoadDevices`, `authenticateAsParent_failure_doesNotInvokeLoadDevices`. Mirror existing `runTest` + mockk. |
| `app/src/test/java/com/tudominio/parentalcontrol/di/NetworkModuleTest.kt` | Modify | Add 2 tests: `debug_buildtype_reads_useMockSupabase_from_localProperties`, `release_buildtype_ignores_localProperties_useMockSupabase`. Mirror existing setup. |

## Interfaces / Contracts

No new public APIs. No new types. The change touches existing internals only:
- `ParentViewModel.authenticateAsParent(): Result<Unit>` — same signature, richer body.
- `BuildConfig.USE_MOCK_SUPABASE: Boolean` — same field, different source for debug.

## Testing Strategy

| Layer | What to Test | Approach |
|---|---|---|
| Unit (JVM) | `authenticateAsParent()` calls `loadDevices()` on success, skips on failure | Mock `DeviceAuthManager` returning `Result.success(Unit)` / `Result.failure(...)`; stub `repository.getDevices()`; assert via `DeviceListUiState` transition. Use existing `runTest` + `UnconfinedTestDispatcher` + mockk pattern from `ParentViewModelTest`. |
| Unit (JVM) | Gradle wires `USE_MOCK_SUPABASE` from `local.properties` under `debug` only | Run `./gradlew :app:tasks --quiet` with temp `local.properties` containing `USE_MOCK_SUPABASE=true`; assert `BuildConfig.USE_MOCK_SUPABASE`. If JVM path is hard with the existing `NetworkModuleTest` style, fall back to Robolectric. |
| Manual smoke | Tap "Iniciar sesión como padre" → devices populate | After `./gradlew testDebugUnitTest` passes, build `:app:assembleDebug`, install on emulator, observe `Loading → Success(devices)`. |

## Migration / Rollout

No migration. No schema, permissions, or flavor changes. `local.properties.template` change is documentation only. Revert-the-PR rollback.

## Open Questions

None blocking. Soft follow-up (do NOT add to tasks): the same gap exists in `OnboardingScreen` (commit `401d09f` wired `authenticateAsParent()` before nav, but `init { loadDevices() }` left `Error(AuthMissing)`). VM fix closes both paths; smoke-test Onboarding clears the error post-nav.
