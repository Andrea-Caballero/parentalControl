# Proposal: Delete orphan child VM + screens, dedupe time-remaining formula

## Why

Closes leftover debt from the post-`1ca2fe3` audit. Three orphan files in `viewmodel/` + `ui/child/screens/` (HomeScreen, BlockScreen) were reachable only via unused VM `ChildViewModel`. The investigation that produced commit `1ca2fe3` already confirmed `ChildViewModel` is not instantiated anywhere in the active navigation graph; the wired child home screen is `ChildStatusScreen` driven by `ChildStatusViewModel` (a `@HiltViewModel`).

The orphan files have already wasted investigation time once. Deleting them makes the codebase reflect current reality.

There is also a small live bug-shaped redundancy: `ChildStatusViewModel.calculateTimeRemaining()` at `ui/child/status/ChildStatusViewModel.kt:192-203` still computes `maxOf(0, _dailyLimit.value - _timeUsedToday.value)` **without** adding grant minutes. It is invoked from `loadInitialState()` (initial boot) and `updateFromRealtime()` (realtime push). The reactive `startObserving()` 3-way combine at lines 112-140 already handles the correct formula `maxOf(0, limit - used + grantsMinutes)`. Two parallel formulas = foot-gun where someone updates one path and forgets the other.

This chore is mini-SDD lite: no `spec.md`, no `design.md`. Zero behavior change. No acceptance scenarios, no architecture decisions to compare.

## What changes

### Deletions (4 files + empty package dir)

- `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ChildViewModel.kt` — class never instantiated (plain `ViewModel()`, no Hilt, no nav graph reference). **Also declares** `data class AppUsage` (lines 170-176) and `data class ActiveGrant` (lines 178-183) at the bottom of the file; both become unused once HomeScreen goes.
- `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/HomeScreen.kt` — only consumer of `ChildViewModel`; itself unused. Line 223 also calls `UsageStatsSheet` (in `ChildComponents.kt`).
- `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/BlockScreen.kt` — same; line 19 carries an unused `import …UsageStatsSheet` that drops for free once the file is gone.
- `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` — **scope-exception edit (Option 2)**: remove the `UsageStatsSheet` Composable (lines 111-214, including the `@OptIn(ExperimentalMaterial3Api::class)` annotation above it), the private `UsageStatRow` Composable (lines 216-280, consumed only by `UsageStatsSheet`), and the now-unused `import com.tudominio.parentalcontrol.viewmodel.AppUsage` (line 14). The rest of the file (`RequestTimeDialog`, `AppLimitCard`, every other import) stays untouched. Rationale: their only consumer (`HomeScreen.kt:223`) is being deleted in this same PR, so they become orphan Composables post-merge if left in place.

After deletion, the empty `ui/child/screens/` package directory is removed (it has no other contents).

### Dedupe (1 function in `ChildStatusViewModel`)

Collapse `calculateTimeRemaining()` (lines 192-203) into the reactive `startObserving()` path. Recommended approach: fold the `_nextBlockTime.value` write into the existing 3-way `combine` block (lines 128-138), then delete `calculateTimeRemaining()` and its 2 call sites (lines 101 in `loadInitialState()` and 278 in `updateFromRealtime()`). The reactive path becomes the single source of truth for both `_timeRemaining` and `_nextBlockTime`.

Acceptable alternative if the apply phase hits a complication: extract a `private fun recomputeTimeAndNextBlock(grantsMinutes: Long)` helper called from both `startObserving()` and the two imperative sites. Either way the goal is **one** formula, not two.

### Collateral cleanup

- `app/config/ktlint/baseline.xml` — remove the three `<file name="...">` blocks at lines 1034-1049 (`BlockScreen.kt`), 1050-1058 (`HomeScreen.kt`), and 1291-1296 (`ChildViewModel.kt`). Without this, the baseline points at files that no longer exist.

## Affected areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ChildViewModel.kt` | Removed | Orphan VM, 182 lines (incl. `AppUsage` + `ActiveGrant` data classes at bottom). |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/HomeScreen.kt` | Removed | Orphan screen, 297 lines. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/BlockScreen.kt` | Removed | Orphan screen, 219 lines. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/` | Removed | Empty package directory. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` | Modified (scope-exception) | Drop `UsageStatsSheet` + `UsageStatRow` Composables + `AppUsage` import (~170 lines removed). Rest of file untouched. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt` | Modified | `calculateTimeRemaining()` dedupe (~12 lines net change). |
| `app/config/ktlint/baseline.xml` | Modified | Drop 3 `<file>` blocks (~23 lines). |

## Impact

- **Behavior**: zero change. The wired path (`ChildStatusViewModel` + `ChildStatusScreen`) already does what the deleted screens would have done, and the dedupe makes the two existing formulas consistent (the reactive path is already correct).
- **API surface**: zero change. `ChildViewModel` was never exposed.
- **DB schema**: zero change.
- **DI graph**: zero change. `ChildViewModel` had no `@Inject` / `@Provides` / Hilt binding.
- **Build size**: drops ~698 lines of dead Kotlin plus ~23 lines of ktlint baseline.
- **CI**: `./gradlew ktlintCheck` will start enforcing on the now-cleaner codebase, since the baseline no longer pins the deleted files. Nothing else changes.

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Missed reference to one of the 4 edited files (test, Compose `@Preview`, nav graph, Hilt module, `tools:` manifest entry, Gradle script) silently breaks the build | Low | Phase 1 of `tasks.md` is a verification pass. The original gate (grep `ChildViewModel|HomeScreen|BlockScreen` across `app/src`) was insufficient because `ChildViewModel.kt` also declares `data class AppUsage`/`ActiveGrant` which are consumed elsewhere. The gate is now **comprehensive**: it enumerates every top-level type per target file (via `grep -nE "^(class|data class|object|interface|sealed|enum)\s+\w+"`) and then re-greps each type name across the whole `app/src` tree before deletion. The new top-level-type scan is the one that caught `AppUsage`/`UsageStatsSheet`; it is captured in `tasks.md` Phase 1 tasks 1.8-1.10 for future re-use. |
| Dedupe changes the `_nextBlockTime` semantics in a way that breaks the countdown chip in `ChildStatusScreen` | Low | Both call sites currently write `_nextBlockTime` to `wallInstant() + remainingMinutes * 60`; the dedupe preserves this exact formula. |
| Ktlint baseline still references deleted files → noisy `ktlintCheck` report | Low | Baseline cleanup is included as a Phase 3 step. |

## Rollback plan

Pure deletion + a small in-place dedupe. To roll back: `git revert` the chore commit (single commit expected). All three files come back verbatim (no other commits will have touched them — they're orphaned). The `ChildStatusViewModel.kt` change is a 12-line diff that restores trivially.

## Out of scope

- Any change to monitoring, sync, policy, time-request flow, or grant observation logic.
- Any change to `ChildStatusScreen.kt` (its display side is already correct).
- Adding tests for the dedupe — `strict_tdd` from `openspec/config.yaml` is honoured by making the dedupe a behaviour-preserving refactor against the existing `ChildStatusTest` placeholder suite. No new tests required because no behavior delta exists.
- Removing the archived `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/` change — it is an immutable audit trail and still documents the historical investigation (including the fact that `ChildViewModel` was found to be orphaned at the time).
- **Do NOT delete or refactor any other Composables or imports in `ChildComponents.kt`.** The only edits allowed there are the `UsageStatsSheet` removal, the `UsageStatRow` removal, and the now-unused `AppUsage` import. `RequestTimeDialog`, `AppLimitCard`, and every other `import` line stay verbatim. No "while-we're-here" formatting or wildcard-import fixes.

## Success criteria

- [ ] `grep` across the repo confirms zero non-self references to `ChildViewModel`, `HomeScreen`, or `BlockScreen` before deletion.
- [ ] All 3 files deleted; `ui/child/screens/` directory removed.
- [ ] `ChildComponents.kt` has `UsageStatsSheet`, `UsageStatRow`, and the `AppUsage` import removed; everything else in the file is byte-identical.
- [ ] `ChildStatusViewModel.calculateTimeRemaining()` no longer exists; its 2 call sites removed. (Satisfied by slice 1.)
- [ ] `_timeRemaining` and `_nextBlockTime` are written from a single code path.
- [ ] `app/config/ktlint/baseline.xml` no longer references the 3 deleted files.
- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` succeeds.
- [ ] `./gradlew ktlintCheck` succeeds (baseline cleanup keeps it green).