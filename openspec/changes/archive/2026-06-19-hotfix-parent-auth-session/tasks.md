# Tasks: hotfix-parent-auth-session

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~150-250 (Role ~10, DeviceAuthManager ~30, NetworkModule ~40, MockSupabaseFixtures ~50, OnboardingScreen ~20, DashboardScreen ~30, tests ~80, build config ~10) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Delivery strategy | single-pr |
| Chain strategy | N/A |
| Decision needed before apply | No |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: N/A
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Base branch | Depends on |
|------|------|-----------|-------------|------------|
| 1 | Role enum + authenticateOrCreate overload (TDD) | PR 1 | `master` | — |
| 2 | BuildConfig flag + local.properties.template | PR 1 | `master` | — |
| 3 | NetworkModule + @SupabaseClient + fixtures (TDD) | PR 1 | `master` | unit 2 |
| 4 | DeviceListError + ParentViewModel.authenticateAsParent | PR 1 | `master` | unit 1 |
| 5 | OnboardingScreen wires authenticateAsParent (TDD) | PR 1 | `master` | unit 4 |
| 6 | DashboardScreen CTA swap (TDD) | PR 1 | `master` | unit 4 |
| 7 | Regression: Role.CHILD → completePairing | PR 1 | `master` | gates quality |

---

## Task 1: Create working branch

- [x] Step 1.1: `git checkout master && git pull --ff-only`
- [x] Step 1.2: `git checkout -b feature/hotfix-parent-auth-session`

**Acceptance**: branch at `41183c0 + 0`.

## Task 2: Add Role enum (TDD)

- [x] Step 2.1 (RED): Create `app/src/test/java/com/tudominio/parentalcontrol/auth/RoleTest.kt` asserting `Role.values()` contains `PARENT` and `CHILD`; `Role.valueOf("PARENT")` returns `Role.PARENT`.
- [x] Step 2.2: Run; confirm FAIL (no `Role` class yet).
- [x] Step 2.3 (GREEN): Create `app/src/main/java/com/tudominio/parentalcontrol/auth/Role.kt` with `enum class Role { PARENT, CHILD }`.
- [x] Step 2.4: Re-run; confirm PASS.

**Acceptance**: `Role.kt` exists; `RoleTest.kt` passes.

## Task 3: Role-aware authenticateOrCreate (TDD)

- [x] Step 3.1 (RED): Add tests to `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerTest.kt` (create if absent): `authenticateOrCreate(Role.PARENT)` and `(Role.CHILD)` succeed and persist `role=PARENT|CHILD` to `device_auth_prefs`; `getAccessToken()` non-null after either; `getRole()` returns persisted role.
- [x] Step 3.2: Run; confirm FAIL (current overload doesn't take a role).
- [x] Step 3.3 (GREEN): Add `suspend fun authenticateOrCreate(role: Role): Result<Unit>` to `DeviceAuthManager.kt`. Per D4: `currentAccessToken = "anon-${role}-${UUID.randomUUID()}"`; persist `"role"` in `device_auth_prefs`; return `Result.success(Unit)`. Remove no-arg overload only if unused (verify first).
- [x] Step 3.4: Re-run; confirm PASS.

**Acceptance**: `authenticateOrCreate(role)` exists; PARENT and CHILD paths work.

## Task 4: BuildConfig.USE_MOCK_SUPABASE flag

- [x] Step 4.1: In `app/build.gradle.kts` `defaultConfig`: `buildConfigField("boolean", "USE_MOCK_SUPABASE", "${project.findProperty("USE_MOCK_SUPABASE") ?: "false"}")`.
- [x] Step 4.2: Add `buildFeatures { buildConfig = true }` if absent; keep `compose = true`.
- [x] Step 4.3: Create `local.properties.template` at repo root with `USE_MOCK_SUPABASE=true` and demo/dev comment.
- [x] Step 4.4: Verify `.gitignore` excludes `local.properties` (D3 cites lines 3, 15 — verify only).
- [x] Step 4.5: `./gradlew :app:generateDebugBuildConfig` — confirm field generated.

**Acceptance**: BuildConfig field exists; template documents flag.

## Task 5: @SupabaseClient NetworkModule + MockSupabaseFixtures (TDD)

- [x] Step 5.1 (RED): Create `MockSupabaseEngineTest.kt`: `GET /devices` returns ≥ 2 devices; `GET /templates` returns ≥ 1 template; `GET /pending-requests` returns ≥ 1 PENDING. Asserts on JSON parse shape.
- [x] Step 5.2: Run; confirm FAIL.
- [x] Step 5.3 (GREEN): Create `di/SupabaseClient.kt`:
  ```kotlin
  @Qualifier @Retention(AnnotationRetention.BINARY) annotation class SupabaseClient
  ```
  (D1 — qualifier names consumer target, NOT engine choice.)
- [x] Step 5.4: Create/modify `di/NetworkModule.kt`: `@Provides @Singleton @SupabaseClient fun httpClient(@ApplicationContext ctx): HttpClient` — if `BuildConfig.USE_MOCK_SUPABASE`, `MockEngine` reading from `assets/mock-supabase/`; else existing real engine.
- [x] Step 5.5: Create `data/remote/MockSupabaseEngine.kt` — Ktor `HttpClient(MockEngine)` dispatcher keyed by URL path; reads JSON via `context.assets.open("mock-supabase/{name}.json")`.
- [x] Step 5.6: Create `app/src/main/assets/mock-supabase/{devices,pending-requests,templates}.json` per spec Req 5.
- [x] Step 5.7: Re-run; confirm PASS.

**Acceptance**: MockSupabaseEngineTest passes; fixtures load; real engine is else-branch.

## Task 6: DeviceListError + ParentRepository refactor + ParentViewModel.authenticateAsParent

- [x] Step 6.1: Create `data/repository/DeviceListError.kt`:
  ```kotlin
  sealed class DeviceListError {
      object AuthMissing : DeviceListError()
      data class Transient(val reason: String) : DeviceListError()
  }
  ```
- [x] Step 6.2: Modify `ParentRepository.kt`: `getDevices()` wraps `IllegalStateException("not authenticated")` → `DeviceListError.AuthMissing`; other failures → `DeviceListError.Transient(reason)`. Preserve literal exception message internally for log compat (D5).
- [x] Step 6.3: Update `ParentRepositoryTest.kt` to assert on `DeviceListError` variants. `createPairingCode_returns_failure_when_not_authenticated` now asserts on `AuthMissing`.
- [x] Step 6.4: Modify `ParentViewModel.kt`: `DeviceListUiState.Error` carries `DeviceListError` (not `String`); add `suspend fun authenticateAsParent(): Result<Unit>` calling `authManager.authenticateOrCreate(Role.PARENT)`.
- [x] Step 6.5: Create `ParentViewModelTest.kt`: `authenticateAsParent()` happy/failure paths; `loadDevices()` null-token → `Error(AuthMissing)`; mock-engine fixture → `Success(devices)`.

**Acceptance**: `DeviceListError` exists; `ParentRepository.getDevices()` returns typed errors; `authenticateAsParent()` tested.

## Task 7: Wire OnboardingScreen.onSelectParent (TDD)

- [x] Step 7.1 (RED): Create `OnboardingScreenTest.kt` (follow `NavGraphTest` pattern: Robolectric, `@Config(sdk = [33])`, `ParentalControlTheme`): tap "Soy el padre" with mocked `DeviceAuthManager` triggers `authenticateOrCreate(Role.PARENT)` BEFORE nav; tap while in progress shows loading (button disabled, indicator visible); auth failure prevents nav.
- [x] Step 7.2: Run; confirm FAIL.
- [x] Step 7.3 (GREEN): Modify `OnboardingScreen.kt`: `onSelectParent` calls `viewModel.authenticateAsParent()` first; loading during auth (disabled button + `CircularProgressIndicator`); success → `NavRoute.Dashboard`; failure → surface error inline.
- [x] Step 7.4: Modify `NavGraph.kt` plumbing if `onSelectParent` lives there (verify location first).
- [x] Step 7.5: Re-run; confirm PASS.

**Acceptance**: auth-then-nav works; loading shown; failure prevents nav; tests green.

## Task 8: DashboardScreen error banner CTA swap (TDD)

- [x] Step 8.1 (RED): Create `DashboardScreenTest.kt` (Compose + Robolectric): `Error(AuthMissing)` → "Iniciar sesión como padre" CTA only (no retry/back); `Error(Transient)` → "Reintentar" + "Volver" CTAs; tapping sign-in CTA triggers `authenticateAsParent()`.
- [x] Step 8.2: Run; confirm FAIL.
- [x] Step 8.3 (GREEN): Modify `DashboardScreen.kt`: replace string-match CTA detection with `DeviceListError` pattern matching; `AuthMissing` → sign-in CTA only; `Transient` → retry+back; wire sign-in CTA to `viewModel.authenticateAsParent()`.
- [x] Step 8.4: Re-run; confirm PASS.

**Acceptance**: Dashboard CTAs match error type; tests green.

## Task 9: Regression test for child pairing flow

- [x] Step 9.1: Run `ParentRepositoryTest.kt` — confirm `createPairingCode_returns_failure_when_not_authenticated` still passes (now against `AuthMissing`).
- [x] Step 9.2: Add `completePairing_with_role_child_invokes_completePairing` — confirm `authenticateOrCreate(Role.CHILD)` followed by `completePairing(pairingCode)` still hits pairing-completion path (parent_id resolution unchanged).
- [x] Step 9.3: Run all existing tests; confirm zero regressions.

**Acceptance**: 604+ baseline tests pass; new regression test green.

## Task 10: Run full quality gate

- [x] Step 10.1: `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8`
- [x] Step 10.2: All five exit 0.
- [x] Step 10.3: `testDebugUnitTest` reports ≥ 604 + ~80 new = ~684+ passing.
- [x] Step 10.4: Zero `malformed-kotlin-Metadata` warnings (R8 fix from prior change holds).
- [x] Step 10.5: Optional smoke — `USE_MOCK_SUPABASE=true` in `local.properties`, exercise parent flow manually, document result.

**Acceptance**: 5 gates green; ~684+ tests pass; no R8 warnings.

## Task 11: Work-unit commits

Per D9 — 7 commits, single PR:

- [x] Step 11.1: `feat(auth): add Role enum and Role-aware authenticateOrCreate` (Tasks 2 + 3).
- [x] Step 11.2: `build(gradle): add BuildConfig.USE_MOCK_SUPABASE flag` (Task 4).
- [x] Step 11.3: `feat(network): add @SupabaseClient Ktor engine with MockSupabaseFixtures` (Task 5).
- [x] Step 11.4: `feat(viewmodel): introduce DeviceListError sealed class and authenticateAsParent` (Task 6).
- [x] Step 11.5: `feat(onboarding): wire authenticateAsParent before Dashboard nav with loading` (Task 7).
- [x] Step 11.6: `fix(dashboard): swap error banner CTAs for auth errors` (Task 8).
- [x] Step 11.7: `test: regression test for Role.CHILD → completePairing` (Task 9).
- [x] Step 11.8: Conventional Commits only; no `Co-Authored-By` or AI attribution trailers.

**Acceptance**: 7 commits on `feature/hotfix-parent-auth-session`, each reviewable.

## Task 12: Push, open PR, update tasks.md

- [x] Step 12.1: `git push -u origin feature/hotfix-parent-auth-session` *(Reconciled at archive: executed — PR #4 merged as 596f6c9; box left unchecked was a bookkeeping oversight in apply phase.)*
- [x] Step 12.2: `gh pr create` with: Summary (3 bullets) → Changes table → Test plan (5 gates + ~684+ tests) → Rollback (`git revert <sha>`) → Demo (`USE_MOCK_SUPABASE=true` in `local.properties`). *(Reconciled at archive: executed — PR #4 exists at https://github.com/Andrea-Caballero/parentalControl/pull/4; box left unchecked was a bookkeeping oversight.)*
- [x] Step 12.3: Mark all 12 tasks `[x]` in this `tasks.md`.
- [x] Step 12.4: Do NOT merge — orchestrator routes to verify after apply returns.

**Acceptance**: PR exists, targets master, body matches design; tasks.md fully checked.

## Task 13: Persist apply-progress to Engram

- [x] Step 13.1: `mem_save` with `topic_key`: `sdd/hotfix-parent-auth-session/apply-progress`; `type`: `architecture`; `capture_prompt`: `false`; content: chosen path, verification summary, commit SHAs, PR URL, 5 gate results, regression confirmation, deviations. *(Reconciled at archive: executed by apply phase — Engram observation exists with topic_key `sdd/hotfix-parent-auth-session/apply-progress`; box left unchecked was a bookkeeping oversight.)*

**Acceptance**: Engram observation exists.

---

## Sequencing Concerns

1. **T2 (Role enum) → T3 (authenticateOrCreate overload)** — overload signature depends on Role.
2. **T4 (BuildConfig flag) → T5 (NetworkModule binding)** — binding reads BuildConfig.
3. **T6 (DeviceListError) → T8 (DashboardScreen CTA swap)** — UI pattern-matches on the typed error.
4. **RED before GREEN** for every behavior task: 2, 3, 5, 6, 7, 8.
5. **T9 (regression test) gates T10 (quality gate)** — without it, the existing child flow could silently break.
6. **Abort conditions**:
   - If T7.1 reveals the Compose tree is too complex to test cleanly with Robolectric, STOP and flag for orchestrator — do NOT fall back to "skip the test".
   - If T5.7 reveals the fixture JSON shape doesn't match what `ParentRepository` parses, STOP — fixture contract must match parser, not vice versa.

## Notes for the apply phase

- The apply agent runs in fresh context; it MUST re-read proposal, specs, design, AND this tasks.md before editing.
- `openspec/config.yaml` has `strict_tdd: true`; TDD applies to behavior changes. The 5 TODOs in `ParentRepository.kt` from the previous change are unrelated — leave them alone.
- This is a hotfix; speed matters but quality bar is the same.
- The eventual formal `parent-auth-flow` change will replace the synthetic `authenticateOrCreate` with real sign-up/sign-in. This hotfix sets up the integration point — `authenticateOrCreate(role)` is the seam.
- The Hilt qualifier is `@SupabaseClient` (design D1) — NOT `@MockOrReal`. The qualifier marks what the consumer wants, not how it was built.
