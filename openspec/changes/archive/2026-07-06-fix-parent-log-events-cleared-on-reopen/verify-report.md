## Verification Report

**Status**: done

**Change**: `fix-parent-log-events-cleared-on-reopen`
**Branch**: `fix/solicitudes-cache-on-cold-start`
**Base**: `master @ f5c9c66`
**PR**: https://github.com/Andrea-Caballero/parentalControl/pull/20
**Mode**: Strict TDD (RED → GREEN → chore); 3 commits
**Date**: 2026-07-07

### Verdict: PASS WITH WARNINGS

All 6 RED → GREEN transformations are real, no new regressions, scope is contained to the data layer. Two minor warnings are documented below; neither blocks the merge.

### Quality gates

| Gate | Result | Evidence |
|------|--------|----------|
| `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryColdStartTest*" --tests "*ParentRepositoryMergeTest*" --tests "*ParentRepositoryCacheRoundTripTest*"` | ✅ PASS | BUILD SUCCESSFUL — T1.1, T1.2, T1.3, T1.4, T1.5, T1.6 all GREEN. |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` (full suite) | ✅ PASS for the change | 709 tests, 3 failed (all pre-existing). NavGraphTest flake is intermittent on both branches — not a regression. |
| `./gradlew :app:assembleDebug` | ✅ PASS | Per the GREEN commit's CI run. |
| `./gradlew :app:detekt` | ✅ PASS | BUILD SUCCESSFUL on touched files (`PendingRequestsCache.kt`, `ParentRepository.kt`). |
| `./gradlew :app:ktlintCheck` | ⚠️ PASS WITH WARNINGS | 2 violations in `ParentRepositoryColdStartTest.kt`: missing trailing newline (`:1`), unused `io.mockk.every` import (`:10`). Pre-existing in the test-spike file the apply phase added. Production files (`PendingRequestsCache.kt`, `ParentRepository.kt`) clean. |
| Spec/design conformance | ✅ PASS | Data-layer only; DataStore Preferences 1.2.0; `init {}` hydration via coroutine; merge-on-publish preserves optimistic local updates. |
| Strict TDD adherence | ✅ PASS | 3 commits in correct order: `test(repo): add RED coverage` (`4a852f8`) → `feat(data): persist Solicitudes cache` (`aea5ba5`) → `chore(openspec): mark apply tasks complete` (`c32e5ee`). T1.1/T1.2/T1.4/T1.5 RED before GREEN. T1.3 was the GREEN-pin from the start. T1.6 was added in the GREEN commit because the `PendingRequestsCache` class did not exist yet — documented deviation in `tasks.md` apply log. |
| No scope creep | ✅ PASS | Only the 7 expected files changed (2 production, 3 test, 2 openspec). No edits to `DashboardScreen.kt`, `SolicitudesPollingWorker.kt`, network fetch (`getPendingRequests`), Hilt modules, `gradle/libs.versions.toml`, or `app/build.gradle.kts`. |
| Merge tie-breaker correctness | ✅ PASS | `mergeTimeRequests` + `isNewer` (lines 191-221) implement the `(m) merge` decision from engram #245: row with non-null `respondedAt` wins; if both have one, lexicographically later timestamp wins; fallback `createdAt`. Cache write-through happens AFTER the in-memory merge. |

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 19 (Phase 1: 7, Phase 2: 5, Phase 3: 5, Phase 4: 5 — some are multi-item) |
| Tasks complete | 19/19 |
| Commits in scope | 3 |
| Files changed by this change | 7: `app/src/main/java/com/tudominio/parentalcontrol/data/local/PendingRequestsCache.kt` (NEW, 191 LoC), `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` (MODIFIED, +145/-7), `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryColdStartTest.kt` (NEW, 226 LoC), `ParentRepositoryMergeTest.kt` (NEW, 195 LoC), `ParentRepositoryCacheRoundTripTest.kt` (NEW, 98 LoC), `openspec/changes/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (NEW, 94 LoC), `tasks.md` (NEW, 216 LoC). |
| RED → GREEN cases | 6/6 |
| Test totals | 709 tests, 3 pre-existing failures (NetworkModuleTest + 2× BootReceiverTest), no new regressions. |

### Spec compliance matrix (6 RED → GREEN cases)

| Test ID | Scenario | File | Result |
|---------|----------|------|--------|
| T1.1 | `pendingRequestsFlow hydrates from disk on cold start` | `ParentRepositoryColdStartTest` | ✅ COMPLIANT — passes with `assertEquals(fixture, coldRepo.pendingRequestsFlow.value)` after warm → cold cycle. |
| T1.2 | `pendingRequestsFlow on cold start is not empty after warm session` | `ParentRepositoryColdStartTest` | ✅ COMPLIANT — sharper form of T1.1 via `assertNotEquals(emptyList(), ...)`. |
| T1.3 | `pendingRequestsFlow on cold start without prior session stays empty` | `ParentRepositoryColdStartTest` | ✅ COMPLIANT — GREEN-pin; correctly asserts empty on fresh install. Companion to T1.1 (non-empty after warm) — triangulated. |
| T1.4 | `publishPendingRequests merges with cache preserving local optimistic updates` | `ParentRepositoryMergeTest` | ✅ COMPLIANT — multi-step merge: PENDING → APPROVED (local) → PENDING (stale server). Asserts APPROVED survives via the `respondedAt` tie-break. |
| T1.5 | `publishPendingRequests local newer status wins over stale cache` | `ParentRepositoryMergeTest` | ✅ COMPLIANT — variant of T1.4: APPROVED (with `respondedAt`) → stale PENDING (no `respondedAt`). Asserts APPROVED wins on `respondedAt` non-null rule. |
| T1.6 | `PendingRequestsCache round-trip survives fresh instance` | `ParentRepositoryCacheRoundTripTest` | ✅ COMPLIANT — `write(fixture)` then `read()` from a fresh `PendingRequestsCache.get(context)` returns the same list. |

**Compliance summary**: 6/6 scenarios compliant.

### Coherence (design-of-record = `proposal.md`, no `design.md` by user choice)

| Decision | Followed? | Notes |
|----------|-----------|-------|
| Data-layer only | ✅ Yes | No UI, worker, network, Hilt module, or gradle changes. |
| DataStore Preferences (not SharedPreferences) | ✅ Yes | `androidx.datastore:datastore-preferences` via `PreferenceDataStoreFactory.create`. |
| `init {}` hydration on construction | ✅ Yes | `cacheScope.launch { cache.observe().first() ... }` upgrades `_pendingRequestsFlow.value` asynchronously. |
| First-frame invariant (`emptyList()` until hydration) | ✅ Yes | Hydration only assigns when `cached.isNotEmpty()` — race-safety note in the apply log is well-reasoned. |
| Merge on `publishPendingRequests` (preserves optimistic local updates) | ✅ Yes | `mergeTimeRequests` union-by-id, `isNewer` tie-break (non-null `respondedAt` wins, then lexicographic timestamp). |
| Per-application-context DataStore memoization | ✅ Yes | `PendingRequestsCache.get(context)` keyed on `System.identityHashCode(applicationContext)`. Satisfies the `androidx.datastore` "one DataStore per file per process" invariant. |
| Nullable cache for test tolerance | ✅ Yes | `pendingRequestsCache: PendingRequestsCache?` via `runCatching { ... }.getOrNull()` — production is non-null, mocked-context tests degrade gracefully. |

### Implementation deviations from `tasks.md` §3.2 (all documented in `tasks.md` apply log, all reasonable)

1. `publishPendingRequests` is `suspend` (was fire-and-forget) — eliminates the in-memory vs disk race the proposal implicitly accepted. Both call sites (`SolicitudesPollingWorker.kt:76`, `ParentViewModel.kt:217`) are already in suspending contexts.
2. `init {}` hydrates only when `cached.isNotEmpty()` — prevents a race where hydration's first read completes BEFORE a warm `publishPendingRequests` writes the cache.
3. `pendingRequestsCache` is `runCatching { ... }.getOrNull()` — so `ParentRepositoryTest` (which mocks Context) doesn't regress.
4. `PendingRequestsCache` uses a private-constructor + companion `get(context)` factory keyed on `applicationContext` identityHashCode (not the file-level `preferencesDataStore` extension delegate the proposal suggested) — better suited for Robolectric's per-test sandbox while still satisfying the one-DataStore-per-file invariant.

### Issues found

**CRITICAL**: None.

**WARNING**:
- **ktlint violations in `ParentRepositoryColdStartTest.kt` (carryover from RED commit)**: missing trailing newline at `:1`, unused `io.mockk.every` import at `:10`. The PR description claims "ktlintCheck — clean on `PendingRequestsCache.kt`, `ParentRepository.kt`, and the 3 new/modified test files" but this is FALSE — ktlint reports 2 violations. These are trivial fixes (add `\n`, remove unused import) and were introduced by the RED commit `4a852f8` (the test file was untracked on master, brought in by this PR). The apply log incorrectly attributed these to "the upstream untracked file" — there is no upstream master copy. Recommend a 1-line follow-up commit to fix these. **Non-blocking**: functionality is correct, tests pass, build succeeds.
- **Apply log under-counts pre-existing failures**: `tasks.md` apply log claims "3 pre-existing failures" but a fresh full-suite run on master shows 4 (the extra is the intermittent `NavGraphTest > resolveInitialRoute_pairedChildDevice_returnsChildStatus` flake, which appears ~half the runs on both branches). On the fix branch, 4 failures are observed in some runs and 3 in others — the flake is identical between branches. **Not a regression**; just a documentation gap.

**SUGGESTION**:
- The proposal's "(d) DataStore, (m) merge" decisions are honored but the proposal's open question #1 ("Spec delta — yes or defer?") remains "deferred". The proposal recommends `time-request-approval/spec.md` could be updated to document the cold-start hydration invariant. **Non-blocking**; same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/`.
- `pendingRequestsCache` is owned by `ParentRepository` (not Hilt-injected) — keeps the constructor signature stable for existing test fakes. Documented deviation, no functional issue. If/when more state joins the cache, consider promoting to a Hilt module.

### Verdict

**PASS WITH WARNINGS** — the change is correct, the 6 RED → GREEN contract is real, no new regressions, scope contained to data layer. The 2 ktlint warnings in the test file are trivial 1-line fixes and can be addressed in a follow-up commit or before merge.

### Next steps for the operator

1. Optional: add a 1-line follow-up commit to fix the 2 ktlint violations in `ParentRepositoryColdStartTest.kt` (remove unused `io.mockk.every` import on line 10, add trailing newline).
2. Merge PR #20 to master (no squash — preserve the RED → GREEN → chore commit trail for audit).
3. Run `sdd-archive` to close `fix-parent-log-events-cleared-on-reopen` and sync any specs (if open question #1 resolves to "yes, spec delta now").