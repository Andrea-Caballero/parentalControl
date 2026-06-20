# Proposal: hotfix-parent-auth-session

## Intent

Parent "devices" screen renders `error cargando dispositivo - not authenticated`; retry/back do nothing. Two root causes:

1. **Missing parent auth** — `OnboardingScreen.onSelectParent` never calls `authenticateOrCreate()`. Parent has no token; `ParentRepository.getDevices()` returns `Result.failure(IllegalStateException("not authenticated"))` at line 67-68.
2. **Placeholder Supabase config** — `SUPABASE_URL` / `SUPABASE_ANON_KEY` in `local.properties` are placeholders, so even with a valid token the real call fails. That's why retry keeps failing.

Hotfix: **synthetic anonymous parent session** + onboarding wiring + **Ktor `MockEngine`** for demo/dev so the device list returns realistic fixtures without hitting the placeholder URL.

**Outcome**: parent opens app → taps "Soy el padre" → synthetic session → dashboard renders fixture devices → retry works on transient failures.

## Scope

### In Scope

- `Role` enum (`PARENT` / `CHILD`) + `authenticateOrCreate(role)` in `DeviceAuthManager`.
- `authenticateAsParent()` called from `OnboardingScreen.onSelectParent` BEFORE navigation; loading shown.
- Ktor `MockEngine` binding toggled by `BuildConfig.USE_MOCK_SUPABASE` (from `local.properties`).
- `MockSupabaseFixtures.kt` with realistic JSON for `/devices`, templates, pending requests.
- Error banner CTA swap: `Iniciar sesión como padre` when error contains `not authenticated`; `Reintentar` + `Volver` otherwise.
- RED-first tests: `DeviceAuthManagerTest`, `ParentViewModelTest` (null-token + fixture), `MockSupabaseEngineTest`, CTA-swap UI test.

### Out of Scope

- Real parent sign-up/sign-in (eventual `parent-auth-flow`).
- Real Supabase config / RLS / `parent_profiles`.
- Removing the 5 TODOs in `ParentRepository.kt`.
- Token refresh logic; child pairing flow changes.

## Capabilities

### New Capabilities

- `parent-auth-session`: `Role` enum + synthetic anonymous session for parent devices (replaced by `parent-auth-flow` later).
- `supabase-mock-engine`: Ktor `MockEngine` toggle for demo/dev; returns fixtures when `USE_MOCK_SUPABASE=true`.

### Modified Capabilities

- `parent-device-list`: error-state CTA — when error contains `not authenticated`, swap retry/back for `Iniciar sesión como padre`. Existing retry / pull-to-refresh / loading scenarios unchanged.

## Approach

1. `Role` enum is the source of truth for parent vs. child tracking; persisted in `device_auth_prefs` next to the token.
2. `authenticateOrCreate(role)` issues a synthetic JWT-shaped token with `role` claim; persists `role` alongside.
3. `OnboardingScreen` wraps `onSelectParent` → `viewModel.authenticateAsParent()` → `NavRoute.Dashboard`.
4. Hilt `@MockOrReal` qualifier on Ktor `HttpClient`; `NetworkModule` provides `MockEngine` when `USE_MOCK_SUPABASE=true`.
5. `MockSupabaseFixtures.kt` defines device list, templates, pending requests as JSON literals.
6. `DashboardScreen` reads `UiState.error.message`; swaps CTAs when it contains `not authenticated`.
7. **TDD**: RED tests → GREEN impl → REFACTOR.

## Affected Areas

| Area | Impact |
|------|--------|
| `auth/Role.kt` | New — Role enum |
| `auth/DeviceAuthManager.kt` | `authenticateOrCreate(role)` + persist role |
| `ui/screen/OnboardingScreen.kt` | `authenticateAsParent` before nav |
| `viewmodel/ParentViewModel.kt` | `authenticateAsParent()` method |
| `di/NetworkModule.kt` | `@MockOrReal` Ktor engine binding |
| `data/remote/MockSupabaseFixtures.kt` | New — fixture JSON |
| `ui/parent/screens/DashboardScreen.kt` | Error banner CTA swap |
| `app/build.gradle.kts` | `buildConfigField USE_MOCK_SUPABASE` |
| `local.properties` | `USE_MOCK_SUPABASE=true` (gitignored) |
| `app/src/test/.../{auth,viewmodel,data/remote,ui/parent}/` | New — TDD coverage |

## Risks

| Risk | Mitigation |
|------|------------|
| Mock engine leaks to production | Default `USE_MOCK_SUPABASE=false`; document in `local.properties.template` |
| Hilt binding ambiguity (`@MockOrReal`) | Explicit qualifier + KDoc; failing `NetworkModuleTest` |
| Child pairing regresses if role flag breaks `completePairing` | Keep `Role.CHILD` default; add regression test |
| Error UX swap confuses mid-session | Swap CTAs only for auth errors; preserve retry/back for transient |

## Rollback Plan

Single PR. Rollback = `git revert <sha>`. All edits additive. With `USE_MOCK_SUPABASE=false`, behavior matches today.

## Success Criteria

- [ ] `Role` enum at `auth/Role.kt` with `PARENT` / `CHILD`.
- [ ] `DeviceAuthManager.authenticateOrCreate(role)` persists role in prefs.
- [ ] `OnboardingScreen.onSelectParent` calls `authenticateAsParent()` before nav; loading shown.
- [ ] `BuildConfig.USE_MOCK_SUPABASE` toggles Ktor engine; default `false`.
- [ ] RED-then-GREEN tests: null-token → `IllegalStateException`; fixture → `Result.success(devices)`; `authenticateOrCreate(PARENT|CHILD)` persists correct role; CTA swap on auth error.
- [ ] All 5 quality gates green: `assembleDebug`, `testDebugUnitTest` (604+), `detekt`, `ktlintCheck`, `:app:minifyReleaseWithR8`.
- [ ] Zero `malformed kotlin.Metadata` warnings.
