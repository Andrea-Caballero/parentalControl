# Proposal: Fix `SupabaseClientProvider` legacy `getInstance()` to honor `BuildConfig.USE_MOCK_SUPABASE`

## Problem Statement

With `USE_MOCK_SUPABASE=true` in `local.properties`, `PairingManager.pairWithCode()` calls `SupabaseClientProvider.getInstance(context)`, which builds a provider via the `internal constructor` whose `httpClient` lazy initializer creates a real OkHttp client pointed at placeholder `https://your-project.supabase.co`. The Hilt `@SupabaseClient` binding in `NetworkModule.kt:44-46` respects the flag, but 5 non-Hilt callers (`PairingManager`, `FcmPushService`, `RealtimeManager`, `SyncManager`, `PlayIntegrityManager`) bypass Hilt, surfacing as `NETWORK_ERROR`. Root cause: `hotfix-parent-auth-session` migrated only `ParentRepository` to Hilt; the 5 managers stayed in the legacy path.

## Intent

Make the legacy `getInstance()` honor `BuildConfig.USE_MOCK_SUPABASE` so all callers behave identically under the flag. Release builds (flag hardcoded `false`) are unaffected.

## Scope

### In Scope
- Modify `SupabaseClientProvider.getInstance()` (or the `internal constructor`) to read the flag: flag-true → `MockSupabaseEngine(context).httpClient`; flag-false → existing real-client path preserved.
- One new unit test (`SupabaseClientProviderTest`) covering both flag states.

### Out of Scope
- Hilt-injection refactor of the 5 non-Hilt managers (follow-up change).
- New mock fixtures, production wire-shape work, instrumented testing.

## Capabilities

### New Capabilities
- `mock-supabase-legacy-gate`: The legacy `SupabaseClientProvider.getInstance(context)` accessor SHALL build its `HttpClient` from `MockSupabaseEngine` when `BuildConfig.USE_MOCK_SUPABASE == true`, otherwise from the real OkHttp engine. Applies to all 5 non-Hilt managers.

### Modified Capabilities
- None. `outbox-drain` / `parent-auth-session` cover work-scheduling and session-lifecycle contracts; this is a transport fix, not a spec change. `supabase-backend-integration` is Hilt-bound and already respects the flag.

## Approach

| Step | Change | File |
|------|--------|------|
| 1 | `getInstance()` (or `internal constructor`) reads the flag; flag-true → inject `MockSupabaseEngine(context).httpClient`; flag-false → existing lazy real-client branch. ~10 lines. | `network/SupabaseClientProvider.kt` |
| 2 | RED: `SupabaseClientProviderTest` asserts flag-true → mock, flag-false → real. GREEN: minimal impl to pass. | `test/.../network/SupabaseClientProviderTest.kt` (new) |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `network/SupabaseClientProvider.kt` | Modified | `getInstance()` / `internal constructor` reads flag, selects mock vs. real. ~10 lines. |
| `test/.../network/SupabaseClientProviderTest.kt` | New | One test class, two cases. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Regression in `getInstance()` callers (5 managers) | Low | Hilt path untouched; release hardcodes `false`; existing `ParentRepositoryTest` + `PairingManagerTest` gate via `./gradlew testDebugUnitTest`. |
| Mock route mismatch for non-paired endpoints | Low | `MockSupabaseEngine` 404s unknown routes, surfacing bugs immediately. |

## Rollback Plan

`git revert` of the merge commit. The legacy real-client path is preserved on the `false` branch, so revert restores prior behavior.

## Dependencies

None: `MockSupabaseEngine` and `BuildConfig.USE_MOCK_SUPABASE` exist.

## Success Criteria

- [ ] Debug build with `USE_MOCK_SUPABASE=true`: `PairingManager.pairWithCode()` does NOT init an OkHttp engine (logcat check).
- [ ] `SupabaseClientProviderTest` covers both flag states and passes.
- [ ] `./gradlew testDebugUnitTest detekt ktlintCheck` all green.
- [ ] `ParentRepositoryTest` + `PairingManagerTest` still pass.
