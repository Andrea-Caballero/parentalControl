# Archive Report: chore-remove-orphan-app-limit-card-and-child-components

> **STATUS: ARCHIVED 2026-07-02** — chore landed on master via merged PR #13
> (merge: `c06762c`; underlying chore commit: `54e3c38`).
> Mini-SDD lite: no `spec.md`, no `design.md`, no `verify-report.md`.
> The user explicitly opted out of `sdd-verify` because there is zero behavior change
> and no spec to verify against (the orphan-cleanup mini-SDD lite pattern).
>
> **Proposal was authored post-merge** (after PR #13 landed). The orchestrator
> skipped `sdd-propose` before merge to avoid blocking the orchestration, and
> the propose phase ran after the implementation PR landed so the audit trail
> still records the intent, scope, and risks. The `proposal.md` was therefore
> never committed on its own — it lands in this cumulative archive chore
> commit along with the rest of the orphan-cleanup epic audit trail.

## Summary

Closed the final orphan-cleanup debt in `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt`.
After `chore-remove-orphan-requesttime-dialog` (PR #12) deleted `RequestTimeDialog`,
the only remaining top-level declaration in the file was `AppLimitCard`
(line 14). Repo-wide grep on `\bAppLimitCard\b` returned only the
self-declaration: zero callers in `app/src/`, zero Compose-preview references,
zero Hilt / nav / manifest references. Deleting the Composable body made the
file import-only (one `package` line + 9 imports), and deleting the empty
file removed the whole file. Stripped the matching `<file>` block from
`app/config/ktlint/baseline.xml` so `ktlintCheck` stays green. The package
directory `ui/child/components/` was intentionally kept — `DegradedAlertDialog.kt`,
`ExtraTimeComponents.kt`, and `RewardComponents.kt` remain live and are
consumed by `ChildStatusScreen.kt`.

This is the third and final iteration of the deletion-chore pattern:
- PR #10/#11 surfaced `ChildViewModel` + `HomeScreen` + `BlockScreen` + `UsageStatsSheet` + `UsageStatRow` + `calculateTimeRemaining()`.
- PR #12 surfaced `RequestTimeDialog`.
- PR #13 (this change) closed out `AppLimitCard` + `ChildComponents.kt`.

After this archive, `ChildComponents.kt` and every Composables / ViewModels /
Screens inside it are gone. The package directory stays because three siblings
are still live.

## Why this was a mini-SDD lite chore

Pure deletion of dead code that has zero callers and zero observable behavior.
No acceptance scenarios or architecture tradeoffs would have added review
value. The user approved skipping `sdd-spec` and `sdd-design`; `sdd-verify`
was intentionally skipped because there is no spec to verify against.

## Change

Code (2 files touched, ~88 LoC of dead Kotlin removed, 0 added):

- **Deleted** `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt`
  (~83 lines: package decl + 9 imports + blank lines + the `AppLimitCard`
  Composable with its `@Composable` annotation).
- **Modified** `app/config/ktlint/baseline.xml` — stripped the
  `<file name="src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt">`
  block (lines 929-934, four `no-wildcard-imports` entries). Without this,
  `ktlintCheck` would have reported dangling baseline entries.

No DB migration. No Hilt module change. No manifest change. No nav-graph
change. No proguard change. No `build.gradle.kts` change. No API-surface
change (`AppLimitCard` and `ChildComponents.kt` were not exposed via any
module API). No DI-graph change. The `ui/child/components/` package
directory was intentionally NOT deleted — it still holds `DegradedAlertDialog.kt`,
`ExtraTimeComponents.kt`, and `RewardComponents.kt`.

## Discovery during apply

The Phase 1 verification gate was strengthened up front with tasks 1.1-1.8,
applying the lesson from `chore-delete-orphan-vm-and-screens` (PR #11 attempt
1): enumerate every top-level declaration in the target file FIRST, then
re-grep each symbol repo-wide as a whole-word regex. The strengthened gate
confirmed `AppLimitCard` was the only top-level symbol in `ChildComponents.kt`
(task 1.3 returned exactly `14:fun AppLimitCard(`), that no other file
imported the file-specific FQCN (task 1.5 returned zero matches), and that
no Hilt / nav / manifest / Compose-preview references existed (tasks 1.2 + 1.8
returned zero matches). No surprises, no re-do.

The package directory gate (task 1.6) was the non-obvious one: it verified
that after the file deletion, three sibling files would remain. The proposal
explicitly excluded directory deletion to avoid regressing siblings; the
apply phase re-checked via `ls` after `rm` and confirmed `DegradedAlertDialog.kt`,
`ExtraTimeComponents.kt`, and `RewardComponents.kt` were untouched.

## Tasks

All 25 implementation tasks across Phases 1, 2, 3, 4, and 5 are marked
complete (`[x]`) in `tasks.md`. The apply log records the single PR with
its merge commit and underlying chore commit.

| Phase | Outcome |
|-------|---------|
| 1 — Verification (BLOCKING) | ✅ done — comprehensive top-level-type scan (1.1-1.8) caught every reference; only the declaration in `ChildComponents.kt:14` matched |
| 2 — Delete `AppLimitCard` Composable body | ✅ done — file retained only `package` + 9 imports + blanks; re-grep confirmed zero top-level declarations survive |
| 3 — Delete the file | ✅ done — `git rm` deleted `ChildComponents.kt`; `ls` confirmed the three siblings remain |
| 4 — Strip the ktlint baseline entry | ✅ done — `<file>` block for `ChildComponents.kt` (lines 929-934) removed; XML still parses |
| 5 — Final reference sweep + verification gates | ✅ done — final sweep clean; `assembleDebug` green; `testDebugUnitTest` shows the same 4 pre-existing failures from master (no new failures); `ktlintCheck` green for the new state (no new violations) |

## Verification performed

PR #13 body records the verification gates run before the chore commit landed
(per `tasks.md` Phase 5). `tasks.md` apply log captures the full transcript:

- `./gradlew :app:assembleDebug` — green (compileDebugKotlin ran fresh, no errors).
- `./gradlew testDebugUnitTest` — green for the new state. The 4 pre-existing
  failures persist (`NetworkModuleTest` 1/2, `BootReceiverTest` 2/7, `NavGraphTest`
  1/10). The `NavGraphTest` pollution from `BootReceiverTest`'s leaked coroutines
  was re-verified as identical on master — **no new failures introduced**.
- `./gradlew :app:ktlintCheck` — green for the new state. The 9 pre-existing
  `WorkersTest.kt` test-source-set drift items persist, plus pre-existing
  main-source-set drift in `SupabaseClientProvider.kt`, `PairingViewModel.kt`,
  `SyncManager.kt`, `ChildStatusViewModel.kt`, `TimeExtraViewModel.kt` —
  identical to master, NOT introduced by this change. **No new violations**.

Final repo-wide grep on `AppLimitCard|ChildComponents\.kt` returned zero
matches outside the audit-trail matches inside the historical
`openspec/changes/chore-remove-orphan-requesttime-dialog/{proposal.md,tasks.md}`
(references inside past change folders are legitimate history, not active code).

Instrumented tests (`connectedDebugAndroidTest`) were not run locally — the dev
machine has no `adb` and no emulator per `openspec/config.yaml` "testing.gotchas".
Instrumented tests run only in CI on API 28/31/35 runners. CI ran PR #13 and
merged it; that is the visual-regression gate.

## Source of truth

No delta spec was authored for this change (mini-SDD lite decision). Confirmed
there is nothing to merge into `openspec/specs/{domain}/spec.md`:

- The change folder contained only `proposal.md` and `tasks.md`. No
  `specs/` subdirectory, no `spec.md`, no `design.md`.
- Repo-wide grep for `AppLimitCard` across `openspec/specs/` returns zero
  matches. The two capability specs that reference the broader child-side UI
  surface (`app-block-policy/spec.md`, `time-request-approval/spec.md`)
  describe app-block enforcement and parent approval flows respectively;
  neither names `AppLimitCard` because the Composable was orphaned UI
  scaffolding, not behavior.

## Relationship to sibling changes

This archive closes the **final** follow-up in the orphan-cleanup epic. The
arc:

1. `chore-delete-orphan-vm-and-screens` (PR #10 + #11) — removed `ChildViewModel`,
   `HomeScreen`, `BlockScreen`, `UsageStatsSheet`, `UsageStatRow`, and deduped
   `calculateTimeRemaining()` in `ChildStatusViewModel`.
2. `chore-remove-orphan-requesttime-dialog` (PR #12) — removed the `RequestTimeDialog`
   Composable after `HomeScreen` (its only caller) was deleted in step 1.
3. `chore-remove-orphan-app-limit-card-and-child-components` (PR #13, **this
   change**) — removed `AppLimitCard` and the now-orphan `ChildComponents.kt`
   file after `RequestTimeDialog` (the only other top-level declaration in
   the file) was deleted in step 2.

After PR #13, `ChildComponents.kt` and every Composable / ViewModel / Screen
inside it are gone. The package directory `ui/child/components/` remains —
it still holds `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, and
`RewardComponents.kt`, which are consumed by `ChildStatusScreen.kt`. None of
those siblings were touched by any of the three archived changes.

## Out-of-scope follow-ups (deferred, not blocking)

These pre-existed on master before this epic and were explicitly out of scope
for all three archived changes:

- The 4 pre-existing unit test failures (`NetworkModuleTest` 1/2,
  `BootReceiverTest` 2/7, `NavGraphTest` 1/10) — `NavGraphTest` pollution is
  caused by `BootReceiverTest`'s leaked coroutines, not by anything in the
  orphan-cleanup scope.
- The 9 pre-existing `WorkersTest.kt` ktlint drift items.
- The pre-existing main-source-set ktlint drift in `SupabaseClientProvider.kt`,
  `PairingViewModel.kt`, `SyncManager.kt`, `ChildStatusViewModel.kt`,
  `TimeExtraViewModel.kt`.
- Wiring `ChildRepository` (still a stub) to real data.

## Notes for the next session

- The archived
  `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`,
  `openspec/changes/archive/2026-07-02-chore-delete-orphan-vm-and-screens/`, and
  `openspec/changes/archive/2026-07-02-chore-remove-orphan-requesttime-dialog/`
  are immutable audit trails. All three reference the historical investigation
  context for the original orphan-cleanup work; leave them untouched.
- The orphan-cleanup epic is now **fully closed locally**. The three archive
  folders live under `openspec/changes/archive/2026-07-02-…` and capture the
  full audit trail. The user can decide whether to push the cumulative
  chore commit (`chore(openspec): archive orphan-cleanup epic (3 changes) and
  create cumulative audit trail`) or absorb it into the next PR.
- If a future chore ever needs to delete another orphan Composable or file,
  the strengthened verification gate from `tasks.md` tasks 1.1-1.8 (and the
  prior archived chores' 1.6-1.8 / 1.7-1.8) continues to apply: enumerate
  every top-level declaration in the target file FIRST, then re-grep each
  symbol repo-wide as a whole-word regex. Three consecutive orphan-cleanup
  chores (`chore-delete-orphan-vm-and-screens`,
  `chore-remove-orphan-requesttime-dialog`,
  `chore-remove-orphan-app-limit-card-and-child-components`) have now executed
  this gate without surprises after the initial PR #11 lesson.