# Archive Report: chore-remove-orphan-requesttime-dialog

> **STATUS: ARCHIVED 2026-07-02** — chore landed on master via merged PR #12
> (merge: `eb2c772`; underlying chore commit: `9514a51`).
> Mini-SDD lite: no `spec.md`, no `design.md`, no `verify-report.md`.
> The user explicitly opted out of `sdd-verify` because there is zero behavior change
> and no spec to verify against (the orphan-cleanup mini-SDD lite pattern).

## Summary

Closed the residual orphan-cleanup debt left over by `chore-delete-orphan-vm-and-screens`
(PR #11). Deleted the `RequestTimeDialog` Composable + its KDoc
(`app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt`,
lines 13-106, 94 lines removed). After PR #11 deleted `HomeScreen.kt` and `BlockScreen.kt`
(the two callers), `RequestTimeDialog` was the last orphan in the file: zero callers,
zero tests, zero nav-graph or DI references. `AppLimitCard` and every other declaration
in `ChildComponents.kt` stayed byte-identical.

## Why this was a mini-SDD lite chore

Pure deletion of dead code that has zero callers and zero observable behavior. No
acceptance scenarios or architecture tradeoffs would have added review value. The
user approved skipping `sdd-spec` and `sdd-design`; `sdd-verify` was intentionally
skipped because there is no spec to verify against.

## Change

Code (1 file touched, ~94 LoC of dead Kotlin removed, 0 added):

- **Modified** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt`
  — removed the `RequestTimeDialog` Composable and its 3-line KDoc (lines 13-106).
  Phase 2 import-cleanup decision: **no imports became unused** — all 9 imports
  (line 1-11) are still referenced by the surviving `AppLimitCard` (`Modifier.fillMaxWidth/padding`,
  `Arrangement.SpaceBetween/spacedBy`, `Icons.Default.Star`, `Card/CardDefaults/MaterialTheme`,
  `@Composable`, `Alignment.CenterVertically/End`, `FontWeight.Bold`, `dp`).

No DB migration. No Hilt module change. No manifest change. No nav-graph change.
No proguard change. No `build.gradle.kts` change. No API-surface change
(`RequestTimeDialog` was not module-public). No ktlint baseline change.

## Discovery during apply

The Phase 1 verification gate was strengthened up front with tasks 1.7-1.8, applying
the lesson from `chore-delete-orphan-vm-and-screens` (PR #11 attempt 1): enumerate
every top-level declaration in the target file FIRST, then re-grep each symbol
repo-wide as a whole-word regex. The original name-only grep had missed
`data class AppUsage` declared at the bottom of `ChildViewModel.kt`. Here the
strengthened gate confirmed `RequestTimeDialog` was the only top-level symbol
worth deleting, and `AppLimitCard` was the only surviving one — no surprises,
no re-do.

## Tasks

All 18 implementation tasks across Phases 1, 2, and 3 are marked complete
(`[x]`) in `tasks.md`. The apply log records the single PR with its merge
commit and underlying chore commit.

| Phase | Outcome |
|-------|---------|
| 1 — Verification (BLOCKING) | ✅ done — comprehensive top-level-type scan (1.6-1.8) caught every reference; only the declaration in `ChildComponents.kt:17` matched |
| 2 — Identify transitive import cleanup | ✅ done — no imports became unused after the Composable removal |
| 3 — Delete + verify (single commit) | ✅ done — `9514a51` deleted the body + KDoc; build/lint gates green |

## Verification performed

PR #12 body records the verification gates run before the chore commit landed
(per `tasks.md` Phase 3):

- `./gradlew :app:assembleDebug` — green.
- `./gradlew testDebugUnitTest` — green. Three pre-existing failures persist
  (`NetworkModuleTest`, `BootReceiverTest` x2). `NavGraphTest` passes
  (10 tests, 0 failures). No new failures introduced.
- `./gradlew :app:ktlintCheck` — green. The 9 pre-existing `WorkersTest.kt`
  drift items persist; no new violations introduced.

Instrumented tests (`connectedDebugAndroidTest`) were not run locally — the dev
machine has no `adb` and no emulator per `openspec/config.yaml` "testing.gotchas".
Instrumented tests run only in CI on API 28/31/35 runners. CI ran PR #12 and
merged it; that is the visual-regression gate.

## Source of truth

No delta spec was authored for this change (mini-SDD lite decision). Confirmed
there is nothing to merge into `openspec/specs/{domain}/spec.md`:

- The change folder contained only `proposal.md` and `tasks.md`. No
  `specs/` subdirectory, no `spec.md`, no `design.md`.
- Repo-wide grep for `RequestTimeDialog` across `openspec/specs/` returns
  zero matches. The two capability specs that reference the broader
  child-side UI surface (`app-block-policy/spec.md`,
  `time-request-approval/spec.md`) describe app-block enforcement and
  parent approval flows respectively; neither names `RequestTimeDialog`
  because the Composable was orphaned UI scaffolding, not behavior.

## Relationship to sibling change

This archive closes the explicit follow-up that `chore-delete-orphan-vm-and-screens`
deferred to a separate change. The sibling change
`openspec/changes/chore-remove-orphan-app-limit-card-and-child-components/`
(PR #13, still pending user merge) is its own SDD cycle targeting the next
layer of orphan cleanup in the same file (`AppLimitCard` Composable +
`ChildComponents.kt` itself once `AppLimitCard` loses its caller). That
sibling is untouched here and will be archived separately after the user merges.

## Notes for the next session

- The archived
  `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`
  and `openspec/changes/archive/2026-07-02-chore-delete-orphan-vm-and-screens/`
  are immutable audit trails. Both reference the historical investigation
  context for the original orphan-cleanup work; leave them untouched.
- The active sibling
  `openspec/changes/chore-remove-orphan-app-limit-card-and-child-components/`
  must NOT be touched by anyone except its own SDD cycle (PR #13 awaiting merge).
- If a future chore ever needs to delete another orphan Composable or file,
  the strengthened verification gate from `tasks.md` tasks 1.6-1.8 continues
  to apply: enumerate every top-level type per target file FIRST, then
  re-grep each symbol repo-wide as a whole-word regex. Two consecutive
  orphan-cleanup chores (`chore-delete-orphan-vm-and-screens`,
  `chore-remove-orphan-requesttime-dialog`) have now executed this gate
  without surprises.