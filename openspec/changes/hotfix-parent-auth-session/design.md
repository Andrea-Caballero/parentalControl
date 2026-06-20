# Design: hotfix-parent-auth-session

## Approach Summary

Single-PR hotfix to the parent "devices" screen, anchored on two pieces:

1. **Role-aware synthetic session** — `DeviceAuthManager.authenticateOrCreate(role)` issues a local JWT-shaped token (`anon-{role}-{uuid}`) and persists `role` next to `encrypted_session` in `device_auth_prefs`. No real Supabase call. The synthetic token is acknowledged throwaway; the formal `parent-auth-flow` change will replace it.
2. **Toggleable Ktor `MockEngine`** — A new `NetworkModule.provideHttpClient(@ApplicationContext, buildConfig)` reads `BuildConfig.USE_MOCK_SUPABASE` (propagated from `local.properties` via `buildConfigField`). When `true`, the engine serves `app/src/main/assets/mock-supabase/*.json`; when `false`, the engine falls back to the existing OkHttp+TLS path (currently unreachable against the placeholder URL — same as today).

The `OnboardingScreen.onSelectParent` call wraps in `viewModel.authenticateAsParent()` before `route = Dashboard`, with a loading state. The `DashboardScreen` error banner swaps CTAs (single "Iniciar sesión como padre" vs. retry+back) by pattern-matching a new `DeviceListError` sealed class that the `ParentRepository` populates when its `IllegalStateException("not authenticated")` fires.

## Decisions

### D1. Hilt qualifier name — **`@SupabaseClient`**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| A. `@MockOrReal` | Describes the implementation choice, leaks to consumer site | Reject |
| **C. `@SupabaseClient`** | Names the binding target; consumer reads `@Inject @SupabaseClient httpClient: HttpClient`. Mock/real is a NetworkModule concern, not a consumer concern. | **Pick** |
| B. `@SupabaseEngine` | Names the engine, not the client; less idiomatic | Reject |
| D. Two named bindings | Forces every repo to declare which one; over-engineered for one toggle | Reject |

**Rationale:** The qualifier marks what the consumer wants (a Supabase `HttpClient`), not how it was built. The mock/real decision lives entirely inside `NetworkModule`.

### D2. Role enum placement — **`auth/Role.kt` (separate file, top-level enum)**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| **A. `auth/Role.kt`** | Clean import path; obvious location for future readers; future `Role.ADMIN` lives naturally | **Pick** |
| B. Nested in `DeviceAuthManager` | Saves a file; pollutes the manager namespace with a domain concept | Reject |
| C. String literal | No test mocking surface, no compile-time exhaustiveness | Reject |

### D3. BuildConfig flag propagation

```
local.properties (USE_MOCK_SUPABASE=true)            # gitignored (verified at .gitignore:3,15)
  → gradle.properties / app/build.gradle.kts:
        defaultConfig {
            buildConfigField("boolean", "USE_MOCK_SUPABASE",
                "${project.findProperty("USE_MOCK_SUPABASE") ?: "false"}")
        }
        buildFeatures { compose = true; buildConfig = true }   # AGP 8.13 default-off
  → BuildConfig.USE_MOCK_SUPABASE (auto-generated)
      → NetworkModule.provideHttpClient() reads BuildConfig.USE_MOCK_SUPABASE
        → MockEngine (fixtures) or OkHttp (real)
```

**Build script edits** (Kotlin DSL, current style):

```kotlin
// app/build.gradle.kts — additions only
defaultConfig {
    // …existing…
    buildConfigField(
        "boolean",
        "USE_MOCK_SUPABASE",
        "${project.findProperty("USE_MOCK_SUPABASE") ?: "false"}"
    )
}
buildFeatures {
    compose = true
    buildConfig = true
}
```

**`local.properties.template`** (new, tracked) — documents the flag for new contributors:

```properties
# Demo / dev: serve the Ktor MockEngine for Supabase endpoints.
# Leave as `false` in production. See app/src/main/assets/mock-supabase/.
USE_MOCK_SUPABASE=true
```

`.gitignore` already excludes `local.properties` (lines 3 and 15) — verified.

### D4. Synthetic auth session shape — **Option C: pure local**

- **Token format**: `"anon-${role.name}-${UUID.randomUUID()}"` (e.g., `anon-PARENT-3f2a…`).
- **Persistence**: alongside `encrypted_session`, write `"role"` key (`"PARENT"` / `"CHILD"`) in `device_auth_prefs`.
- **State**: `_sessionState = SessionState.ANONYMOUS` (existing value), `_deviceId = null`.
- **Side effect**: no network call; `createAnonymousSession()` no longer hits Supabase for the synthetic path.
- **Clear path**: `Role.CHILD` keeps the existing `completePairing` flow intact; `Role.PARENT` skips `completePairing` and lands at `Dashboard`.

### D5. ErrorBanner CTA detection — **`DeviceListError` sealed class**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| A. `error.message.contains("not authenticated")` | Matches spec scenario literally, but string-match is fragile and breaks when message i18ns | Reject |
| **B. Sealed `DeviceListError { AuthMissing; Transient(reason); Empty }`** | Pattern-match exhaustively; the repository stamps the variant when it throws `IllegalStateException("not authenticated")` | **Pick** |
| C. `ErrorType` enum on UI state | Less refactor-proof than sealed class (callers can pass wrong enum) | Reject |

**Refactor** (small, additive): `DeviceListUiState.Error` carries `DeviceListError` instead of `String`. The repository wraps:

- `IllegalStateException("not authenticated")` → `DeviceListError.AuthMissing`
- All other failures → `DeviceListError.Transient(e.message ?: "Unknown error")`
- Empty result still maps to `DeviceListUiState.Empty`

The dashboard pattern-matches `DeviceListError.AuthMissing → "Iniciar sesión como padre"` CTA; `Transient → Reintentar + Cerrar` CTAs. `DashboardScreenTest` asserts on which nodes appear (Compose semantics IDs), not on substrings.

### D6. Fixture JSON location — **`app/src/main/assets/mock-supabase/`**

```
app/src/main/assets/mock-supabase/
├── devices.json           # ≥ 2 ChildDevice rows
├── pending-requests.json  # ≥ 1 TimeRequest row (PENDING)
└── templates.json         # ≥ 1 PolicyTemplate row
```

**Rationale:** Spec scenario "mock engine used when flag is true" is build-flavor-agnostic — the flag must work in a production-debug build, not only under test. `test/resources/` (option B) would lock the mock behind unit-test classpath only and break spec. Inline Kotlin constants (option C) work but bloat the engine file; assets keeps the JSON readable and editable by non-Kotlin reviewers. `MockSupabaseEngine` reads via `context.assets.open("mock-supabase/devices.json")` at construction.

### D7. Test mocking strategy — **Mock the `HttpClient` dependency**

Existing `ParentRepositoryTest` already constructs a `MockEngine` and stubs `clientProvider.httpClient` via MockK (see `ParentRepositoryTest.kt:59-86`). No new abstraction; no `SupabaseEngineConfig` wrapper. Unit tests stay build-flavor-agnostic because `BuildConfig.USE_MOCK_SUPABASE` is never read in unit tests — only in `NetworkModule` (production graph).

### D8. Apply task ordering

1. Branch `feature/hotfix-parent-auth-session` from `master` (SHA `41183c0`).
2. Create `auth/Role.kt` with `enum class Role { PARENT, CHILD }`.
3. RED: `DeviceAuthManagerRoleTest` — assert `authenticateOrCreate(PARENT)` and `(CHILD)` persist correct role.
4. GREEN: add `authenticateOrCreate(role)` to `DeviceAuthManager`; write `"role"` to `device_auth_prefs`.
5. Add `buildFeatures.buildConfig = true` + `buildConfigField` in `app/build.gradle.kts`; create `local.properties.template`; verify `./gradlew :app:generateDebugBuildConfig`.
6. Add `data/remote/MockSupabaseFixtures.kt` + assets folder; create `MockSupabaseEngine` Ktor `HttpClient` provider.
7. RED: `MockSupabaseEngineTest` — assert `/devices`, `/pending-requests`, `/templates` return fixture JSON.
8. GREEN: implement engine with `context.assets.open(...)` per route.
9. Create `di/NetworkModule.kt` with `@Provides @Singleton @SupabaseClient fun httpClient(...): HttpClient`; switch on `BuildConfig.USE_MOCK_SUPABASE`.
10. RED: `NetworkModuleTest` (Robolectric) — flag `true` → MockEngine; flag `false` → real engine bound.
11. Add `DeviceListError` sealed class; refactor `ParentViewModel.loadDevices` to pattern-match.
12. RED: `ParentViewModelTest` — null-token → `DeviceListUiState.Error(DeviceListError.AuthMissing)`; mock-engine fixture → `Success(devices)`.
13. GREEN: implement.
14. Modify `OnboardingScreen.onSelectParent` → wrap in `viewModel.authenticateAsParent()` + loading state.
15. RED: `OnboardingScreenTest` (Compose) — tap → loading visible → nav fires; failure → no nav.
16. GREEN: implement.
17. Modify `DashboardScreen` `ErrorBanner` to pattern-match `DeviceListError`.
18. RED: `DashboardScreenTest` (Compose, Robolectric) — `AuthMissing` shows "Iniciar sesión como padre"; `Transient` shows retry+back.
19. GREEN: implement.
20. Quality gate: `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8`.
21. Commit + push + PR + archive change (move to `openspec/changes/archive/2026-06-19-hotfix-parent-auth-session/`).

### D9. Commit plan (work-unit commits, single PR)

1. **`feat(auth): add Role enum and role-aware authenticateOrCreate`** — `auth/Role.kt` (new) + `DeviceAuthManager.authenticateOrCreate(role)` + `DeviceAuthManagerRoleTest` (RED → GREEN).
2. **`build(gradle): add BuildConfig.USE_MOCK_SUPABASE flag`** — `app/build.gradle.kts` (buildFeatures + buildConfigField) + `local.properties.template` (new, tracked).
3. **`feat(network): add @SupabaseClient Ktor engine with MockSupabaseFixtures`** — `NetworkModule.kt` (new) + `MockSupabaseEngine.kt` + assets folder + tests.
4. **`feat(viewmodel): introduce DeviceListError sealed class and authenticateAsParent`** — `ParentViewModel.kt` + `DeviceListUiState.Error(reason)` refactor + tests.
5. **`fix(onboarding): wire authenticateAsParent before Dashboard nav with loading`** — `OnboardingScreen.kt` + `NavGraph.kt` plumbing + Compose test.
6. **`fix(dashboard): swap error banner CTAs for auth errors`** — `DashboardScreen.kt` + Compose test.
7. **`docs(openspec): archive hotfix-parent-auth-session; sync parent-auth-session spec`** — archive move + spec delta syncs.

Total diff estimate: 6 implementation commits × ~30-40 lines = **~200 lines code + tests + fixtures + build config**. Well within the 400-line single-PR threshold.

## File-by-file impact

| File | Action | Why |
|------|--------|-----|
| `app/src/main/java/com/tudominio/parentalcontrol/auth/Role.kt` | Create | `enum class Role { PARENT, CHILD }` — D2 |
| `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` | Modify | Add `authenticateOrCreate(role)`; write `"role"` to prefs — D4 |
| `app/src/main/java/com/tudominio/parentalcontrol/di/NetworkModule.kt` | Create | `@Provides @Singleton @SupabaseClient fun httpClient()` — D1, D3 |
| `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` | Create | Ktor `HttpClient(MockEngine)` reading from `assets/mock-supabase/` — D6 |
| `app/src/main/assets/mock-supabase/{devices,pending-requests,templates}.json` | Create | Fixture JSON for spec scenario "realistic data" — D6 |
| `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` | Modify | `DeviceListError` sealed class; `authenticateAsParent()` method; `Error(reason)` refactor — D5 |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/screen/OnboardingScreen.kt` | Modify | Disable button + loading during auth; only nav on success — Spec req 2 |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/navigation/NavGraph.kt` | Modify | `onSelectParent` triggers `viewModel.authenticateAsParent()` before `route = Dashboard` |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt` | Modify | `ErrorBanner` pattern-matches `DeviceListError`; CTA swap — D5 |
| `app/build.gradle.kts` | Modify | `buildFeatures.buildConfig = true`; `buildConfigField USE_MOCK_SUPABASE` — D3 |
| `local.properties.template` | Create | Document flag for contributors — D3 |
| `app/src/test/.../auth/DeviceAuthManagerRoleTest.kt` | Create | TDD RED → GREEN — D8 step 3 |
| `app/src/test/.../data/remote/MockSupabaseEngineTest.kt` | Create | TDD — D8 step 7 |
| `app/src/test/.../di/NetworkModuleTest.kt` | Create | Hilt binding shape — D8 step 10 |
| `app/src/test/.../viewmodel/ParentViewModelTest.kt` | Create | Error → AuthMissing; fixture → Success — D8 step 12 |
| `app/src/test/.../ui/screen/OnboardingScreenTest.kt` | Create | Compose tap → loading → nav — D8 step 15 |
| `app/src/test/.../ui/parent/screens/DashboardScreenTest.kt` | Create | CTA swap assertion — D8 step 18 |

## Open Questions

- [ ] **`DeviceListError.AuthMissing` vs spec wording** — spec scenario says "error message contains 'not authenticated'"; D5 introduces a typed variant. Resolved at apply-time: the repository converts the existing `IllegalStateException("not authenticated")` into `DeviceListError.AuthMissing`. The literal string remains in the exception for log compatibility; the type carries the UI contract. No spec rewrite needed.
- [ ] **`OnboardingScreen` already-existing tests** — `OnboardingTest.kt` tests `OnboardingStep`/`OnboardingProgress` types but no Compose test for the role buttons. New `OnboardingScreenTest` follows the `NavGraphTest` Compose pattern (Robolectric, `@Config(sdk = [33])`, `ParentalControlTheme`). No conflict.
- [ ] **`completePairing` regression for `Role.CHILD`** — existing flow must still hit `completePairing` after `authenticateOrCreate(CHILD)`. The proposal's risk table flags this; apply-task step 13 includes a regression test (assert `completePairing` is called when role is CHILD and `onPairingComplete` fires).

## References

- Proposal: `openspec/changes/hotfix-parent-auth-session/proposal.md`
- Spec (NEW): `openspec/changes/hotfix-parent-auth-session/specs/parent-auth-session/spec.md`
- Spec delta: `openspec/changes/hotfix-parent-auth-session/specs/parent-device-list/spec.md`
- Existing `DeviceAuthManager`: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt:135-155` (`authenticateOrCreate()` no-arg shape that we extend to a `Role`-aware overload)
- Existing `ParentRepository`: `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt:65-90` (`getDevices()` — the `IllegalStateException("not authenticated")` site that D5 retires)
- Existing test pattern: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryTest.kt:59-86` (MockEngine + MockK stub of `clientProvider.httpClient` — D7's basis)
- Build script: `app/build.gradle.kts` (Kotlin DSL; `buildFeatures { compose = true }` at line 28 — D3 adds `buildConfig = true` and `defaultConfig.buildConfigField`)
- Project config: `openspec/config.yaml` (`strict_tdd: true`, `apply.tdd: true`)