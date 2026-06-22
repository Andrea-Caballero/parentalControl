# Tasks: hotfix-parent-auth-cta-reload

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~50 (3 source files + 2 test files + 1 properties file + 1 template) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | single-pr |
| Chain strategy | n/a |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

## Phase 1: Behavior A — RED tests for VM reload-on-auth-success

- [x] 1.1 Add `authenticateAsParent_success_invokesLoadDevices` test to `app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelTest.kt` — assert `loadDevices()` is invoked when `authManager.authenticateOrCreate(Role.PARENT)` returns `Result.success(Unit)`.
- [x] 1.2 Add `authenticateAsParent_failure_doesNotInvokeLoadDevices` test — assert `loadDevices()` is NOT invoked when auth returns `Result.failure(...)`.
- [x] 1.3 Run `./gradlew :app:testDebugUnitTest --tests "*ParentViewModelTest*"` and confirm both new tests FAIL (RED).

## Phase 2: Behavior A — GREEN implementation

- [x] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` (`authenticateAsParent()`, lines 97–98), chain `.onSuccess { loadDevices() }` on the `Result` returned by `authManager.authenticateOrCreate(Role.PARENT)`.
- [x] 2.2 Run the same test command from 1.3 and confirm both new tests PASS (GREEN). No other tests regressed.

## Phase 3: Behavior B — RED tests for Gradle wiring

- [x] 3.1 Add `debug_buildtype_reads_useMockSupabase_from_localProperties` test to `app/src/test/java/com/tudominio/parentalcontrol/di/NetworkModuleTest.kt` — write temp `local.properties` with `USE_MOCK_SUPABASE=true`, evaluate Gradle `buildConfigField` in JVM (or inspect `BuildConfig` under test), assert `USE_MOCK_SUPABASE == true`.
- [x] 3.2 Add `release_buildtype_ignores_localProperties_useMockSupabase` test — same setup but build as `release`, assert `USE_MOCK_SUPABASE == false`.
- [x] 3.3 Run the same test command from 1.3 and confirm both new tests FAIL (RED).

## Phase 4: Behavior B — GREEN implementation

- [x] 4.1 In `app/build.gradle.kts` (lines 32–34), parse `local.properties` explicitly and expose `USE_MOCK_SUPABASE` as a property scoped to `buildTypes.debug` only; `defaultConfig` defaults to `"false"`; `release` ignores `local.properties`.
- [x] 4.2 In `gradle.properties`, add the line `USE_MOCK_SUPABASE=true`.
- [x] 4.3 In `local.properties.template`, update the comment to state the value is read by Gradle under `debug` builds.
- [x] 4.4 Run the same test command from 1.3 and confirm both new tests PASS (GREEN). No other tests regressed.

## Phase 5: REFACTOR + full verification

- [x] 5.1 Run `./gradlew :app:testDebugUnitTest` and confirm all tests pass (not just the 4 new ones).
- [x] 5.2 Run `./gradlew :app:ktlintCheck` and `:app:detekt` — confirm no NEW violations (existing `app/config/ktlint/baseline.xml` permits 1939 pre-existing entries; new code must not add to it).
- [x] 5.3 Run `./gradlew :app:assembleDebug` to confirm the build is green.
- [ ] 5.4 Manual smoke: install on emulator, navigate to parent dashboard, confirm Devices tab transitions `Error(AuthMissing)` → `Loading` → `Success(devices)` after tapping "Iniciar sesión como padre". (Skipped: no `adb`/emulator per `sdd-init/parentalcontrol` gotcha #1; orchestrator will run smoke later if needed.)

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Single PR: VM reload + Gradle wiring + 4 tests + smoke | PR 1 | All tasks; base = `master` |
