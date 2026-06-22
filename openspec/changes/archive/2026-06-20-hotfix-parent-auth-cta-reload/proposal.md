# Proposal: Hotfix parent-auth CTA reload + Gradle mock wiring

## Intent

Fix the regression where the parent's "Iniciar sesión como padre" CTA on the Devices tab completes auth but does not re-fetch devices (error banner stays). Also fix the broken Gradle wiring so `USE_MOCK_SUPABASE=true` in `local.properties` actually engages the mock engine.

## Scope

### In Scope
- `viewmodel/ParentViewModel.kt`: `authenticateAsParent()` calls `loadDevices()` on success
- `app/build.gradle.kts`: parse `local.properties` and wire `USE_MOCK_SUPABASE` into `buildConfigField`, gated to `debug` build type
- `gradle.properties`: add `USE_MOCK_SUPABASE=true`
- Add unit test for the reload-on-success path

### Out of Scope
- "Cerrar sesión" in Settings (stubs are empty; separate SDD change later)
- Real parent sign-up/sign-in (formal `parent-auth-flow`)
- Real Supabase backend integration

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `parent-auth-session`:
  - Req 3: add "AND the build SHALL read `USE_MOCK_SUPABASE` from `local.properties` under `debug`, so the documented dev workflow takes effect."
  - Req 4: add "AND THEN `loadDevices()` SHALL be re-invoked so the device list re-renders after auth succeeds."

## Approach

1. `ParentViewModel.authenticateAsParent()` — change from `pass-through` to `Result.onSuccess { loadDevices() }`. One file, ~3 lines.
2. `app/build.gradle.kts` — read `local.properties` explicitly and pass `USE_MOCK_SUPABASE` to `buildConfigField`, scoped to `buildTypes.debug`. ~10 lines.
3. `gradle.properties` — add `USE_MOCK_SUPABASE=true` so the flag is honored even when a developer has not edited `local.properties`.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `viewmodel/ParentViewModel.kt:97-98` | Modified | `authenticateAsParent()` now triggers reload on success |
| `app/build.gradle.kts:32-34` | Modified | Explicit `local.properties` parsing for `USE_MOCK_SUPABASE`, gated to `debug` |
| `gradle.properties` | Modified | Add `USE_MOCK_SUPABASE=true` |
| `app/src/test/.../viewmodel/ParentViewModelTest.kt` | Modified | Add reload-on-success test |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Race between `authenticateAsParent()` and `loadDevices()` on rapid taps | Low | Both run on `viewModelScope`; serialized via state flow |
| Accidental mock in release via stale `local.properties` | Med | Gate parse to `buildTypes.debug` only |
| Onboarding "Soy el padre" path has the same gap | Med | Fixed by VM change; verify manually |

## Rollback Plan

Revert the single PR. Changes are isolated to VM behavior and Gradle wiring; no schema, API, or DB migration.

## Dependencies

None new. Uses existing `DeviceAuthManager`, `ParentRepository`, `MockSupabaseFixtures`.

## Success Criteria

- [ ] CTA on Devices tab triggers auth AND reloads devices (Error(AuthMissing) → Loading → Success with mock engine)
- [ ] `USE_MOCK_SUPABASE=true` in `local.properties`/`gradle.properties` engages mock engine; `BuildConfig.USE_MOCK_SUPABASE == true`
- [ ] Existing `ParentViewModelTest` and `NetworkModuleTest` still pass
- [ ] New test covers reload-on-success
- [ ] No regression in Onboarding or child flow