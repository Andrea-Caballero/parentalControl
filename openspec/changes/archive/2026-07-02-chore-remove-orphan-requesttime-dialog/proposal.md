# Proposal: Delete orphan `RequestTimeDialog` Composable

## Why

After `chore-delete-orphan-vm-and-screens` (PR #11) landed, `RequestTimeDialog` Composable in `ChildComponents.kt` lost its only callers (`HomeScreen.kt:213` and `BlockScreen.kt:191`, both now deleted). The original chore's "Out of scope" rule for slice 2 prevented including it; this change closes the loop.

A deletion chore that leaves new orphan code behind recreates the very debt it set out to remove. `RequestTimeDialog` is dead: zero callers, zero tests, zero nav-graph or DI references. Removing it now keeps the codebase honest and matches the user's stated intent (delete the dead screen surface end-to-end, not most of it).

## What changes

### Deletions (1 Composable in `ChildComponents.kt`)

- `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` — delete the `RequestTimeDialog` Composable (lines 16-106), including the KDoc above it (lines 13-15). Everything else in the file (`AppLimitCard`, package declaration, all imports) stays untouched.

### Import cleanup (dynamic, same commit)

After removing the Composable body, scan the surviving file for any import that became unused. If any are found, drop them in the **same commit** as the Composable deletion. Static review suggests **no imports become unused** (all imports are shared with `AppLimitCard` via wildcards or shared symbols like `Modifier`, `dp`, `FontWeight`, `Alignment`), but the apply phase must verify this empirically. Identification happens AFTER the body is removed, not before.

## Affected areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt` | Modified | Drop `RequestTimeDialog` Composable + KDoc (~94 lines removed). Drop any imports that become unused as a result (expected: none). |

## Impact

- **Behavior**: zero change. The Composable had no callers in compiled code.
- **API surface**: zero change. `RequestTimeDialog` was not exposed via any module API.
- **DB schema**: zero change.
- **DI graph**: zero change. No Hilt binding references it.
- **Build size**: drops ~94 lines of dead Kotlin.
- **CI**: nothing changes. The ktlint baseline does not reference this file.

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Missed reference to `RequestTimeDialog` (test, preview, nav graph, DI binding) silently breaks the build | Low | Phase 1 of `tasks.md` is a blocking verification pass. Repo-wide grep for the symbol, plus the PR #11 lesson: enumerate every top-level type in the file first, then re-grep each as a whole-word symbol (file-name greps miss symbols declared in the same file). |
| Removing the Composable orphans an import the static review didn't catch | Low | Phase 2 inspects the file after the body is removed and drops any import whose only remaining reference is gone. Drop is bundled in the same commit. |
| Ktlint reports a new violation on the modified file | Low | Phase 3 runs `:app:ktlintCheck`; any new failure means a stylistic drift, not a build break, and is fixed in the same commit. |

## Rollback plan

Pure deletion. `git revert` the chore commit restores the Composable verbatim (no other commit will have touched it — it was orphaned). Single commit expected.

## Out of scope

- Any change to `AppLimitCard` or any other Composable in `ChildComponents.kt`.
- Deleting `ChildComponents.kt` itself — `AppLimitCard` is still consumed (see `ChildStatusScreen.kt`).
- Any refactor of `ChildStatusScreen` or `ChildStatusViewModel`.
- Adding tests, comments, or KDoc to surviving code.
- Removing the archived `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/` change — it is an immutable audit trail.
- Any "while-we're-here" formatting, wildcard-import cleanup, or stylistic edits in `ChildComponents.kt`.

## Success criteria

- [ ] `grep` across the repo confirms zero non-self references to `RequestTimeDialog` before deletion.
- [ ] `RequestTimeDialog` Composable + its KDoc are removed from `ChildComponents.kt`.
- [ ] Any import that became unused after the deletion is removed in the same commit.
- [ ] `AppLimitCard` and every other declaration in `ChildComponents.kt` is byte-identical otherwise.
- [ ] `./gradlew :app:assembleDebug` succeeds.
- [ ] `./gradlew testDebugUnitTest` succeeds (same 4 pre-existing failures from master persist; NO NEW ones).
- [ ] `./gradlew :app:ktlintCheck` succeeds (same 9 pre-existing drift items in `WorkersTest.kt` persist; NO NEW ones).
