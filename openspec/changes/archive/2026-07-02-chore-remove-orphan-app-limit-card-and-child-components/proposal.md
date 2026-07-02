# Proposal: Delete orphan `AppLimitCard` Composable and `ChildComponents.kt`

## Why

After `chore-remove-orphan-requesttime-dialog` (PR #12) landed, the orphan-cleanup sweep in `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` is one deletion away from being fully clean. The surviving `AppLimitCard` Composable (the only top-level declaration in the file, line 14) has zero callers in `app/src/` — verified via `grep -rn "\bAppLimitCard\b" app/src` returning only the self-declaration. The file is therefore entirely orphan: removing the Composable body allows the file deletion, closing the orphan-cleanup sweep for `ChildComponents.kt`.

This is the third and final iteration of the deletion chore pattern: PR #10/#11 surfaced `RequestTimeDialog`, PR #12 surfaced `AppLimitCard`, this change finishes the sweep. Leaving `AppLimitCard` in place would recreate the exact debt the previous chores removed — a Composable with zero callers whose only reason to exist is "it compiled once".

## What changes

### Deletions

- `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` — delete the `AppLimitCard` Composable (lines 13-83, including the `@Composable` annotation). Then delete the now-empty file (lines 1-12 are package declaration + 9 imports). After removal, the `ui/child/components/` package directory is NOT empty (it still holds `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`, all consumed by `ChildStatusScreen.kt`) — do NOT delete the directory.

### Ktlint baseline strip

- `app/config/ktlint/baseline.xml` — drop the `<file name=".../ChildComponents.kt">` block (currently lines 929-934, four `no-wildcard-imports` entries on the file's wildcard imports).

### Import cleanup (dynamic, same commit)

After removing `AppLimitCard`, no other file can import from `com.tudominio.parentalcontrol.ui.child.components.ChildComponents` (the file-specific FQCN), because the only top-level symbol was `AppLimitCard` and it has zero callers. Verified pre-flight: `grep "from com.tudominio.parentalcontrol.ui.child.components.ChildComponents"` returns zero matches in the repo. If the apply phase finds a stray downstream import that became unused, drop it in the **same commit**.

## Affected areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` | Removed | Whole file deleted (~83 lines). Package directory stays — siblings remain live. |
| `app/config/ktlint/baseline.xml` | Modified | Drop the `<file>` block for `ChildComponents.kt` (lines 929-934). |
| `ui/child/components/` package directory | Unchanged | Still holds `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`. |
| Downstream files | Maybe modified | Drop any stale `com.tudominio.parentalcontrol.ui.child.components.ChildComponents` import if one surfaces during apply (expected: zero). |

## Impact

- **Behavior**: zero change. `AppLimitCard` had no callers in compiled code.
- **API surface**: zero change. It was not exposed via any module API.
- **DB schema**: zero change.
- **DI graph**: zero change. No Hilt binding references it.
- **Build size**: drops ~83 lines of dead Kotlin + the ktlint-baseline block.
- **CI**: nothing changes — pre-existing 3 test failures + 9 `WorkersTest.kt` ktlint drift items persist; no new ones expected.

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Missed reference to `AppLimitCard` (test, preview, nav graph, DI binding) silently breaks the build | Low | Phase 1 of `tasks.md` is a blocking verification pass. Repo-wide grep for the symbol AND for `ChildComponents` (the file path), plus the PR #11 lesson: enumerate every top-level type in the file first, then re-grep each as a whole-word symbol. |
| File-path reference (DI binding, manifest entry, Gradle source-set, test preview) survives the file deletion | Low | Phase 1.2 greps the file path itself across manifests, Gradle scripts, DI modules, navigation, ktlint baseline, res, and tests. |
| Deleting the file orphans an unused import in another file the static review didn't catch | Very Low | Verified pre-flight: zero imports of `ChildComponents` (file-specific FQCN) anywhere in the repo. Phase 1.5 reconfirms. |
| Ktlint baseline keeps stale entries and `ktlintCheck` reports "unused baseline entries" | Low | Phase 1.4 confirms the `<file>` block exists and lines 929-934 are the exact range. Phase 5 strips them in the same commit. |
| Wrong package-directory deletion breaks the build because siblings still live there | Mitigated | The proposal explicitly excludes directory deletion; tasks.md Phase 4 only removes `ChildComponents.kt`. |

## Rollback plan

Pure deletion. `git revert` the chore commit restores `ChildComponents.kt` verbatim (no other commit will have touched it — it was orphaned). Single commit expected. The ktlint-baseline block revert is bundled in the same commit.

## Out of scope

- Deleting the `ui/child/components/` package directory — `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, and `RewardComponents.kt` remain live and are consumed by `ChildStatusScreen.kt`.
- Any refactor of `ChildStatusScreen`, `ChildStatusViewModel`, or the surviving sibling components.
- Any "while-we're-here" formatting, wildcard-import cleanup, or stylistic edits elsewhere.
- Removing the archived change folders under `openspec/changes/archive/` — they are an immutable audit trail.
- Fixing the pre-existing 3 unit test failures (`NetworkModuleTest`, `BootReceiverTest` x2) and 9 pre-existing `WorkersTest.kt` ktlint violations that exist on master today.

## Success criteria

- [ ] Repo-wide grep confirms zero non-self references to `AppLimitCard` and zero references to the `ChildComponents.kt` file path outside `openspec/changes/archive/`.
- [ ] `ChildComponents.kt` is deleted; the package directory `ui/child/components/` remains (with `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt` untouched).
- [ ] The `<file>` block for `ChildComponents.kt` is stripped from `app/config/ktlint/baseline.xml`.
- [ ] Any import of `com.tudominio.parentalcontrol.ui.child.components.ChildComponents` that became unused is dropped in the same commit (expected: zero).
- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` succeeds — same 3 pre-existing failures from master persist; **no new failures**.
- [ ] `./gradlew :app:ktlintCheck` succeeds — same 9 pre-existing `WorkersTest.kt` drift items persist; **no new violations**.