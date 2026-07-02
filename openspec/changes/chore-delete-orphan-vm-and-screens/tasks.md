# Tasks: Delete orphan child VM + screens, dedupe time-remaining formula

> Mini-SDD lite chore. No `spec.md`, no `design.md`. Each task maps 1:1 to a `chore(...): ...` commit. No behavior change — verification is the only gate.

---

## Phase 1 — Verification (BLOCKING)

Run all of the following from the repo root. Each command must return **zero matches** outside of the 3 target files themselves (`ChildViewModel.kt`, `HomeScreen.kt`, `BlockScreen.kt`) and the archived change folder `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`. The archive is an immutable audit trail — do NOT modify it. Any unexpected match = STOP and surface to the user.

- [x] **1.1 — Grep class names across Kotlin sources.**
  ```bash
  grep -rn "ChildViewModel\|HomeScreen\|BlockScreen" app/src
  ```
  Expected: matches only inside the 3 target files. The screens directory contains `HomeScreen.kt` and `BlockScreen.kt`; the viewmodel directory contains `ChildViewModel.kt`. No `app/src/test/` or `app/src/androidTest/` matches allowed.

- [x] **1.2 — Grep class names in Gradle / manifest / resource files.**
  ```bash
  grep -rn "ChildViewModel\|HomeScreen\|BlockScreen\|child\.screens\|viewmodel\.Child" \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/build.gradle.kts \
    build.gradle.kts \
    settings.gradle.kts \
    app/proguard-rules.pro \
    app/src/main/res
  ```
  Expected: zero matches.

- [x] **1.3 — Grep DI / nav-graph directories.**
  ```bash
  grep -rn "ChildViewModel\|HomeScreen\|BlockScreen\|child\.screens\|viewmodel\.Child" \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation
  ```
  Expected: zero matches.

- [x] **1.4 — Verify no Compose `@Preview` references.**
  ```bash
  grep -rn "import androidx.compose.ui.tooling.preview.Preview\|@Preview" app/src
  ```
  Expected: zero matches (the codebase has no previews anywhere).

- [x] **1.5 — Verify no Hilt `@Provides` or `@Binds` for `ChildViewModel`.**
  ```bash
  grep -rn "@Provides\|@Binds" app/src/main/java/com/tudominio/parentalcontrol/di \
    | grep -i "ChildViewModel"
  ```
  Expected: zero matches.

- [x] **1.6 — Verify ktlint baseline coverage of the 3 files (for cleanup later).**
  ```bash
  grep -n "ChildViewModel\|HomeScreen\|BlockScreen\|child/screens\|viewmodel/Child" \
    app/config/ktlint/baseline.xml
  ```
  Expected: 3 `<file name="...">` blocks at lines 1034 (BlockScreen), 1050 (HomeScreen), 1291 (ChildViewModel). Record the line ranges for Phase 3.

- [x] **1.7 — Record verification result in commit body.**
  If all checks pass, proceed. If any check fails unexpectedly, STOP and report.

- [x] **1.8 — Enumerate every top-level type in each target file.**
  Slice 2 attempt 1 was BLOCKED at `assembleDebug` because the gate above only grep'd `ChildViewModel|HomeScreen|BlockScreen` — it missed `data class AppUsage` declared at the bottom of `ChildViewModel.kt`. The fix is to enumerate every top-level declaration per file FIRST, then re-grep each as a whole-word symbol:
  ```bash
  for f in \
    app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ChildViewModel.kt \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/HomeScreen.kt \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/BlockScreen.kt \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt ; do
    echo "=== $f ==="
    grep -nE "^(class|data class|object|interface|sealed|enum)\s+\w+" "$f"
  done
  ```
  Record every name. For slice 2 the expected yield is `ChildViewModel`, `AppUsage`, `ActiveGrant`, `HomeScreen`, `BlockScreen`, plus (in `ChildComponents.kt`) `RequestTimeDialog`, `UsageStatsSheet`, `UsageStatRow`, `AppLimitCard`, and any data classes inside. Anything not on this list = STOP.

- [x] **1.9 — Repo-wide grep on every top-level type found in 1.8.**
  ```bash
  grep -rn "\bAppUsage\b\|\bActiveGrant\b\|\bUsageStatsSheet\b\|\bUsageStatRow\b\|\bRequestTimeDialog\b\|\bAppLimitCard\b\|\bHomeScreen\b\|\bBlockScreen\b\|\bChildViewModel\b" \
    app/src
  grep -rn "\bAppUsage\b\|\bActiveGrant\b\|\bUsageStatsSheet\b\|\bUsageStatRow\b\|\bRequestTimeDialog\b\|\bAppLimitCard\b\|\bHomeScreen\b\|\bBlockScreen\b\|\bChildViewModel\b" \
    app/src/test app/src/androidTest 2>/dev/null
  ```
  Expected: matches ONLY inside the 4 target files we are about to edit (ChildViewModel.kt declarations being removed, HomeScreen.kt calling UsageStatsSheet at line 223, BlockScreen.kt's unused import at line 19, ChildComponents.kt declarations + bodies), plus the immutable archive `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`. NO matches in test files, navigation, DI, or other screens. Any unexpected match = STOP and return blocked.

- [x] **1.10 — Confirm no Hilt / nav / manifest references to the symbols from 1.8.**
  ```bash
  grep -rn "\bAppUsage\b\|\bActiveGrant\b\|\bUsageStatsSheet\b\|\bUsageStatRow\b" \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/src/main/res
  ```
  Expected: zero matches.

Commit gate (do not commit yet — proceeds into Phase 2 in the same worktree):
> No commit yet — verification is a precondition, not an artifact.

---

## Phase 2 — Dedupe `calculateTimeRemaining()` in `ChildStatusViewModel.kt`

- [x] **2.1 — Fold `_nextBlockTime` write into the reactive `combine`.**
  In `app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt` (around lines 128-138), extend the `collect { (used, limit, grantsMinutes) -> ... }` block to also set `_nextBlockTime.value`:
  ```kotlin
  _timeRemaining.value = maxOf(0, limit - used + grantsMinutes)
  _nextBlockTime.value = if (_timeRemaining.value > 0) {
      timeProvider.wallInstant().plusSeconds(_timeRemaining.value * 60)
  } else {
      timeProvider.wallInstant()
  }
  updateWarningLevel()
  updateUiState()
  ```

- [x] **2.2 — Delete `calculateTimeRemaining()` (lines 192-203).**
- [x] **2.3 — Remove its 2 call sites.**
  - Line 101 inside `loadInitialState()`.
  - Line 278 inside `updateFromRealtime()`.
- [x] **2.4 — Sanity check: only one place in the file writes `_timeRemaining.value`.**
  ```bash
  grep -n "_timeRemaining\.value\s*=" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt
  ```
  Expected: one match (the `combine.collect` block).
- [x] **2.5 — Sanity check: only one place writes `_nextBlockTime.value`.**
  ```bash
  grep -n "_nextBlockTime\.value\s*=" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt
  ```
  Expected: one match.
- [x] **2.6 — Run `./gradlew :app:assembleDebug`.** Must succeed before committing.
- [x] **2.7 — Commit.**
  ```
  chore(child-status): dedupe calculateTimeRemaining into startObserving combine
  ```

> **Slice 1 outcome**: PR #10 opened on `chore/child-viewmodel-status-dedupe` → `master`. 3 files changed, +22 / -48. Single commit `deb3c31`. Body pinned the pre-existing test/ktlint state. Slice 2 (Phase 3 / now Phase 4 after scope expansion) is paused pending merge, then resumed in Phase 2.5 → Phase 4 → Phase 5.

---

## Phase 2.5 — Delete transitive dead code in `ChildComponents.kt` (scope-exception, Option 2)

Slice 2 attempt 1 hit `assembleDebug` because the original Phase 1 grep only matched class names matching the file names; it missed `data class AppUsage` declared at the bottom of `ChildViewModel.kt` and consumed by `UsageStatsSheet` + `UsageStatRow` in `ChildComponents.kt`. The user approved Option 2: delete those Composables + the `AppUsage` import now, while the only caller (`HomeScreen.kt`) is also being deleted in the same PR. This phase MUST run BEFORE Phase 4 so the orphan path is closed before Phase 4's terminal `assembleDebug`.

- [x] **2.5.1 — Read `ChildComponents.kt` in full.**
  Confirm body extent for `UsageStatsSheet` (annotation + Composable) and `UsageStatRow` (private Composable). Locate the `import com.tudominio.parentalcontrol.viewmodel.AppUsage` line.

- [x] **2.5.2 — Delete exactly three blocks; preserve everything else.**
  - The `import com.tudominio.parentalcontrol.viewmodel.AppUsage` line (line 14 on master).
  - The `@OptIn(ExperimentalMaterial3Api::class)` annotation + `UsageStatsSheet` Composable body (the block immediately above `fun UsageStatsSheet` through its closing `}`).
  - The `UsageStatRow` private Composable body (from `@Composable` above `private fun UsageStatRow` through its closing `}`).
  - Keep `RequestTimeDialog`, `AppLimitCard`, the `package` declaration, every other `import`, every comment, every blank line outside the three removed blocks. No "while-we're-here" formatting.

- [x] **2.5.3 — Confirm no remaining reference to `AppUsage` / `UsageStatsSheet` / `UsageStatRow` survives in the file.**
  ```bash
  grep -nE "AppUsage|UsageStatsSheet|UsageStatRow" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected: zero matches.

---

## Phase 4 — Delete orphan files + clean ktlint baseline

- [x] **4.1 — Delete `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ChildViewModel.kt`.**
- [x] **4.2 — Delete `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/HomeScreen.kt`.**
- [x] **4.3 — Delete `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/BlockScreen.kt`.**
- [x] **4.4 — Remove the now-empty `app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens/` directory.**
  ```bash
  rmdir app/src/main/java/com/tudominio/parentalcontrol/ui/child/screens
  ```
  Expected: `rmdir` succeeds (no other contents).
- [x] **4.5 — Strip the 3 `<file>` blocks from `app/config/ktlint/baseline.xml`.**
  Remove the line ranges recorded in Phase 1 task 1.6 (BlockScreen lines ~1034-1049, HomeScreen lines ~1050-1058, ChildViewModel lines ~1291-1296). Verify with:
  ```bash
  grep -n "ChildViewModel\|HomeScreen\|BlockScreen" app/config/ktlint/baseline.xml
  ```
  Expected: zero matches.
- [x] **4.6 — Final reference sweep across the whole repo.**
  ```bash
  grep -rn "ChildViewModel\|HomeScreen\|BlockScreen\|child\.screens\|viewmodel\.Child" \
    --exclude-dir=.git --exclude-dir=build \
    .
  ```
  Expected: zero matches outside `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`.
- [x] **4.7 — Commit.**
  ```
  chore(child): delete orphan ChildViewModel, HomeScreen, BlockScreen and baseline entries
  ```

> **Phase 2.5 + Phase 4 are bundled into a single chore commit on this slice** (`chore(cleanup): delete orphan ChildViewModel, HomeScreen, BlockScreen, and 4 Composables`). Separate per-phase commits would inflate the PR without adding review value when both phases are tightly coupled.

---

## Phase 5 — Build + lint verification

- [x] **5.1 — Run `./gradlew :app:assembleDebug`.** Must succeed.
- [x] **5.2 — Run `./gradlew testDebugUnitTest`.** Must succeed.
- [x] **5.3 — Run `./gradlew ktlintCheck`.** Must succeed (baseline cleanup keeps it green; deleted files are no longer pinned).
- [x] **5.4 — Run `./gradlew detekt`.** Must succeed.
- [x] **5.5 — Final report.** Write a short summary in the sdd-verify report when the orchestrator launches that phase (no commit here — verification artifacts live in `openspec/changes/chore-delete-orphan-vm-and-screens/verify-report.md`).

---

## Notes

- The archived `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/` change contains references to `ChildViewModel` in its `proposal.md`, `design.md`, `tasks.md`, `specs/...`, `archive-report.md`, and `verify-report.md`. **Do not touch** — it is an immutable audit trail that documents the historical investigation.
- `strict_tdd: true` from `openspec/config.yaml` is honoured by treating Phase 2's dedupe as a behaviour-preserving refactor: the reactive `combine` formula (`maxOf(0, limit - used + grantsMinutes)`) was already correct before this chore; we are removing the buggy parallel formula, not introducing new behavior. No new tests required.
- Instrumented tests (`connectedDebugAndroidTest`) cannot run locally per the testing capabilities in `openspec/config.yaml` (no `adb`, no emulator). They run only in CI on API 28/31/35. CI will catch any UI regression.
- Conventional-commits style: `chore(...)` prefix for both commits.

---

## Apply log

- 2026-07-02 — Slice 1 merged via PR #10 (merge: `8631c16`; underlying dedupe commit: `deb3c31`).
- 2026-07-02 — Slice 2 (with scope expansion) opened via PR #<N> (pending — fill in once user merges) from `chore/delete-orphan-child-vm-and-screens`.
  - Scope expanded after slice 2 attempt 1 hit `assembleDebug` with 19 unresolved-`AppUsage` errors caused by `data class AppUsage` living at the bottom of `ChildViewModel.kt` (lines 170-183). Expansion deletes `UsageStatsSheet` + `UsageStatRow` Composables in `ChildComponents.kt` per user Option-2 approval. Phase 1 grep gate also strengthened (tasks 1.8-1.10) to enumerate every top-level type per target file before re-grepping repo-wide, so future "delete orphan files" SDD chores don't repeat the blind spot.