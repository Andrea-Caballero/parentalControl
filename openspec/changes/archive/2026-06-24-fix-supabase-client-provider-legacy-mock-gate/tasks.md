# Tasks: Fix `SupabaseClientProvider` legacy `getInstance()` mock gate

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~30–50 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: stacked-to-main
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | RED test + GREEN impl in two commits | PR 1 | base: master; test-only commit then prod-only commit, both in one PR |

## Phase 1: RED — Add failing test for legacy mock gate

- [x] 1.1 Read `MockSupabaseEngine` to confirm class name (`com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine`) and `httpClient` accessor.
- [x] 1.2 Create `app/src/test/java/com/tudominio/parentalcontrol/network/SupabaseClientProviderTest.kt` with class `SupabaseClientProviderTest` (Robolectric runner if needed; follow `OutboxDrainerTest` pattern).
- [x] 1.3 Add `getInstance_with_mock_flag_true_returns_provider_with_mock_engine_client`: assert `httpClient.engine::class` is `MockSupabaseEngine` (read via reflection on `engine` field).
- [ ] 1.4 ➖ **Skipped per orchestrator simplification**: orchestrator scope said ONE test (flag-true only). The Hilt short-circuit test (`getInstance_with_real_engine_when_injectedClient_used`) is covered transitively by `ParentRepositoryTest` + `PairingManagerTest`, which both inject `SupabaseClientProvider` via mockk and never exercise the real lazy branch.
- [x] 1.5 Document BuildConfig-override constraint: flag-false is gated by existing `ParentRepositoryTest` + `PairingManagerTest` regression; do not toggle BuildConfig. *(Documented inline in `SupabaseClientProviderTest` class KDoc.)*
- [x] 1.6 Run `./gradlew testDebugUnitTest` — new tests MUST fail RED.
- [x] 1.7 Run `./gradlew detekt ktlintCheck` — both MUST pass.
- [x] 1.8 Commit: `test(network): add RED coverage for legacy getInstance() mock gate`.

## Phase 2: GREEN — Make `getInstance()` honor `BuildConfig.USE_MOCK_SUPABASE`

- [x] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/network/SupabaseClientProvider.kt`, branch inside the `httpClient` lazy `run { }` block (preserves `injectedClient` short-circuit): flag-true → `MockSupabaseEngine(context).httpClient`; flag-false → existing real OkHttp branch.
- [x] 2.2 Run `./gradlew testDebugUnitTest` — new tests MUST pass GREEN; all pre-existing tests MUST still pass.
- [x] 2.3 Run `./gradlew detekt ktlintCheck assembleDebug` — all MUST pass.
- [x] 2.4 Commit: `fix(network): make legacy getInstance() honor BuildConfig.USE_MOCK_SUPABASE`.
