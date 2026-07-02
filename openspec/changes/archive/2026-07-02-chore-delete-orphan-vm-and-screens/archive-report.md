# Archive Report: chore-delete-orphan-vm-and-screens

> **STATUS: ARCHIVED 2026-07-02** — chore landed on master via two merged PRs:
> PR #10 (slice 1, merge: `8631c16`; underlying dedupe commit: `deb3c31`) and
> PR #11 (slice 2, merge: `e5eeff5`; underlying cleanup commit: `d619644`).
> Mini-SDD lite: no `spec.md`, no `design.md`, no `verify-report.md`.
> The user explicitly opted out of `sdd-verify` because there is zero behavior change.

## Summary

Closed post-`1ca2fe3` orphan-cleanup debt. Removed `ChildViewModel` (an
orphaned plain `ViewModel()` not in the Hilt graph and not reachable from any
nav route) plus its two orphan consumers `HomeScreen.kt` and `BlockScreen.kt`,
along with the `UsageStatsSheet` + `UsageStatRow` Composables in
`ChildComponents.kt` that only `HomeScreen.kt` was calling. Folded the buggy
parallel `calculateTimeRemaining()` formula in `ChildStatusViewModel` into the
single reactive `combine` path that already produced the correct
`maxOf(0, limit - used + grantsMinutes)` value. Cleaned up the now-orphaned
`<file>` entries in `app/config/ktlint/baseline.xml` so `ktlintCheck` stays
green.

## Why this was a mini-SDD lite chore

The change is purely deletion + a 12-net-line behavior-preserving refactor
inside one private function. The reactive `combine` path in
`startObserving()` was already correct before this chore — we deleted the
buggy parallel formula, did not introduce new behavior. Acceptance scenarios
or architecture tradeoffs would have added ceremony without review value, so
the user approved skipping `sdd-spec` and `sdd-design`. `sdd-verify` was
intentionally skipped because there is no spec to verify against.

## Change

Code (5 files touched, ~698 LoC of dead code removed plus a 12-net-line dedupe):

- **Deleted** `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ChildViewModel.kt` (~182 lines, including the trailing `data class AppUsage` / `data class ActiveGrant` that became unused once `HomeScreen` was gone).
- **Deleted** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/HomeScreen.kt` (~297 lines).
- **Deleted** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/BlockScreen.kt` (~219 lines).
- **Deleted** empty package directory `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/`.
- **Modified (scope-exception, Option 2)** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` — removed `UsageStatsSheet` Composable + its `@OptIn(ExperimentalMaterial3Api::class)` annotation, the private `UsageStatRow` Composable, and the `import com.tudominio.parentalcontrol.viewmodel.AppUsage` line. `RequestTimeDialog`, `AppLimitCard`, and every other import stayed byte-identical. No "while-we're-here" formatting.
- **Modified (dedupe)** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt` — folded the `_nextBlockTime.value` write into the existing 3-way `combine(_timeUsedToday, _dailyLimit, grantsMinutes)` block at lines 128-138 and deleted `calculateTimeRemaining()` plus its two call sites (in `loadInitialState()` line 101 and `updateFromRealtime()` line 278). `_timeRemaining` and `_nextBlockTime` are now written from a single code path.
- **Modified** `app/config/ktlint/baseline.xml` — stripped the 3 `<file>` blocks at lines 1034-1049 (`BlockScreen.kt`), 1050-1058 (`HomeScreen.kt`), and 1291-1296 (`ChildViewModel.kt`). Without this, `ktlintCheck` would have reported dangling baseline entries.

No DB migration. No Hilt module change. No manifest change. No nav-graph
change. No proguard change. No `build.gradle.kts` change. No API-surface
change (`ChildViewModel` was never exposed).

## Discovery during apply

Slice 2 attempt 1 hit `assembleDebug` with 19 unresolved-`AppUsage` errors.
The original Phase 1 grep gate only matched class names matching the file
names; it missed `data class AppUsage` declared at the bottom of
`ChildViewModel.kt` (lines 170-176) and consumed by `UsageStatsSheet` +
`UsageStatRow` Composables inside `ChildComponents.kt`. The user approved
**Option 2**: delete those Composables + the `AppUsage` import immediately,
since the only caller (`HomeScreen.kt`) was also being deleted in the same
PR. Phase 1 was strengthened with tasks 1.8-1.10 (enumerate every top-level
type per target file, then re-grep each symbol repo-wide) so future
"delete orphan files" SDD chores don't repeat the blind spot.

## Tasks

All 28 implementation tasks across Phases 1, 2, 2.5, 4, and 5 are marked
complete (`[x]`) in `tasks.md`. The apply log records both merged PRs with
their merge commits and underlying chore commits.

| Phase | Slice / PR | Outcome |
|-------|------------|---------|
| 1 — Verification (BLOCKING) | both slices | ✅ done — comprehensive top-level-type scan (1.8-1.10) caught `AppUsage`/`UsageStatsSheet` references that the original name-only grep missed |
| 2 — Dedupe `calculateTimeRemaining()` | PR #10 (`deb3c31`) | ✅ done — single commit, 3 files, +22 / -48 |
| 2.5 — Scope expansion (Option 2) | PR #11 (`d619644`) | ✅ done — `UsageStatsSheet` + `UsageStatRow` deleted from `ChildComponents.kt` after slice 2 attempt 1 hit `assembleDebug` |
| 4 — Delete orphan files + ktlint baseline cleanup | PR #11 (`d619644`) | ✅ done — 3 files deleted, baseline stripped |
| 5 — Build + lint verification | PR #10 + PR #11 | ✅ `assembleDebug`, `testDebugUnitTest`, `ktlintCheck`, `detekt` all green |

## Verification performed

PR #11 body records the verification gates run before the cleanup commit
landed (per `tasks.md` Phase 5):

- `./gradlew :app:assembleDebug` — green.
- `./gradlew testDebugUnitTest` — green (pre-existing test/ktlint state
  pinned in PR #10 commit body; no new tests added).
- `./gradlew ktlintCheck` — green after baseline strip.
- `./gradlew detekt` — green.
- Final repo-wide grep on `ChildViewModel|HomeScreen|BlockScreen|child.screens|viewmodel.Child` returns zero matches outside the immutable audit trail at `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`.

Instrumented tests (`connectedDebugAndroidTest`) were not run locally —
the dev machine has no `adb` and no emulator per `openspec/config.yaml`
"testing.gotchas". Instrumented tests run only in CI on API 28/31/35
runners. CI ran PR #11 and merged it; that is the visual-regression gate.

## Source of truth

No delta spec was authored for this change (mini-SDD lite decision).
Confirmed there is nothing to merge into `openspec/specs/{domain}/spec.md`:

- The change folder contains only `proposal.md` and `tasks.md`. No
  `specs/` subdirectory, no `spec.md`, no `design.md`.
- The two `openspec/specs/*/spec.md` files that mention
  `ChildStatusViewModel` (`app-entry-routing/spec.md:39` and
  `database-initialization/spec.md:23,31`) describe VM wiring that this
  chore did not alter.
- The orphan `ChildViewModel`, `HomeScreen`, `BlockScreen`,
  `UsageStatsSheet`, and `UsageStatRow` were not referenced by any
  capability spec — by definition, they were orphaned.

## Follow-up that remained in scope at archive time

The `RequestTimeDialog` Composable in `ChildComponents.kt` is intentionally
**not** removed here. The user opened a separate sibling change
`openspec/changes/chore-remove-orphan-requesttime-dialog/` to consume the
residual orphan cleanup (the dialog has no remaining caller after PR #11
lands). That sibling change is its own SDD cycle, not part of this
archive.

## Out-of-scope follow-ups (deferred, not blocking)

- Test scaffolding for the reactive `combine` path in
  `ChildStatusViewModel.startObserving()` (`openspec/specs/...` does not
  specify it; deferred per the change's scope guard).
- Wiring `ChildRepository` (still a stub) to real data.
- Replacing `ChildComponents.kt` `RequestTimeDialog` once the sibling
  change lands.

## Notes for the next session

- The archived `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`
  is an immutable audit trail that documented the historical investigation
  which first confirmed `ChildViewModel` was orphaned. Its body still
  references `ChildViewModel` — leave it untouched.
- The sibling change `openspec/changes/chore-remove-orphan-requesttime-dialog/`
  is the active follow-up. Its `apply` phase is running in parallel.
- If a future chore ever needs to delete an "orphan" Kotlin file again,
  start with the strengthened verification gate from
  `tasks.md` tasks 1.8-1.10 (enumerate every top-level type per target
  file FIRST, then re-grep each symbol repo-wide) — the original
  name-only grep missed `data class AppUsage` and cost this chore one
  re-do.
