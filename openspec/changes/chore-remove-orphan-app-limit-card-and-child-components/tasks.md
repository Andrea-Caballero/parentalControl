# Tasks: Delete orphan `AppLimitCard` and `ChildComponents.kt`

> Mini-SDD lite chore (no `spec.md`, no `design.md`). Whole-file deletion + ktlint-baseline strip + dynamic downstream-import check, single `chore(...)` commit. No behavior change — verification is the only gate.

---

## Phase 1 — Verification (BLOCKING)

Run all of the following from the repo root. Each command must return **zero matches outside `ChildComponents.kt` itself and outside the immutable archive folder `openspec/changes/archive/`**. Any unexpected match = STOP and surface to the user.

- [x] **1.1 — Grep the symbol name across Kotlin sources.**
  ```bash
  grep -rn "\bAppLimitCard\b" app/src
  ```
  Expected: exactly one match — the declaration at `ChildComponents.kt:14`. No matches in `app/src/test/` or `app/src/androidTest/`.

- [x] **1.2 — Grep the file path itself across Gradle / manifest / resource / DI / nav / ktlint baseline.**
  ```bash
  grep -rn "ChildComponents\.kt" \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/build.gradle.kts \
    build.gradle.kts \
    settings.gradle.kts \
    app/proguard-rules.pro \
    app/src/main/res \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation \
    app/config/ktlint/baseline.xml \
    app/src/test \
    app/src/androidTest
  ```
  Expected: zero matches. (Matches inside `openspec/changes/archive/` are legitimate historical audit-trail references — they are excluded from this command and do not gate the deletion.)

- [x] **1.3 — Enumerate every top-level declaration in `ChildComponents.kt` (PR #11 lesson).**
  ```bash
  grep -nE "^(class|data class|object|interface|sealed|enum|fun)\s+\w+" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected yield: a single line `14:fun AppLimitCard(`. Anything not on this list = STOP.

- [x] **1.4 — Confirm ktlint baseline covers the file currently being deleted.**
  ```bash
  grep -n "ChildComponents\.kt" app/config/ktlint/baseline.xml
  ```
  Expected: exactly one match — the `<file>` opener at line 929. Record the close-tag line (`</file>` immediately after the last `<error>` for this block — expected line 934). Both line numbers go into the Phase 5 edit.

- [x] **1.5 — Grep for any downstream import of the file-specific FQCN.**
  ```bash
  grep -rn "from com\.tudominio\.parentalcontrol\.ui\.child\.components\.ChildComponents\|import com\.tudominio\.parentalcontrol\.ui\.child\.components\.ChildComponents" app/src
  ```
  Expected: zero matches. The only top-level symbol in the file was `AppLimitCard` (verified in 1.1 to have zero callers), so no file can import the file-specific FQCN and survive.

- [x] **1.6 — Confirm the package directory is NOT going to become empty.**
  ```bash
  ls app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/
  ```
  Expected: at minimum `ChildComponents.kt`, `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`. After Phase 4 deletes `ChildComponents.kt`, the directory must still hold the three siblings. This validates that Phase 4 does NOT delete the directory.

- [x] **1.7 — Repo-wide grep on every top-level declaration from 1.3, scoped to non-archive.**
  ```bash
  grep -rn "\bAppLimitCard\b" \
    --exclude-dir=.git --exclude-dir=build \
    --exclude-dir=archive \
    .
  ```
  Expected: only the declaration in `ChildComponents.kt`. Any other match in active code = STOP.

- [x] **1.8 — Confirm no Hilt / nav / manifest / Compose-preview references to the symbol.**
  ```bash
  grep -rn "\bAppLimitCard\b" \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/src/main/res \
    grep -rn "@Preview\|import androidx.compose.ui.tooling.preview.Preview" app/src
  ```
  Expected: zero matches.

Commit gate (do not commit yet — verification is a precondition, not an artifact):
> If all checks pass, proceed to Phase 2. If any check fails, STOP and report.

---

## Phase 2 — Delete the `AppLimitCard` Composable body

- [x] **2.1 — Delete the `AppLimitCard` Composable + `@Composable` annotation in the editor first.**
  Remove lines 13-83 (the `@Composable` annotation, the `fun AppLimitCard(...)` declaration, and the entire body). Save. The file should now be:
  ```kotlin
  package com.tudominio.parentalcontrol.ui.child.components

  import androidx.compose.foundation.layout.*
  import androidx.compose.material.icons.Icons
  import androidx.compose.material.icons.filled.*
  import androidx.compose.material3.*
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.unit.dp

  ```
  (one trailing blank line; nine orphan imports).

- [x] **2.2 — Verify the file is now import-only.**
  ```bash
  grep -nE "^(class|data class|object|interface|sealed|enum|fun)\s+\w+" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected: zero matches. If any top-level declaration survives, STOP and surface to the user — that is a verification failure, not a deletion.

- [x] **2.3 — Confirm no other file imports from this file's FQCN.**
  ```bash
  grep -rn "com\.tudominio\.parentalcontrol\.ui\.child\.components\.ChildComponents" app/src
  ```
  Expected: zero matches. (This is a re-check after the body removal — nothing should have changed, but confirm before deleting the file.)

---

## Phase 3 — Delete the file

- [x] **3.1 — Delete `ChildComponents.kt`.**
  ```bash
  rm app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```

- [x] **3.2 — Confirm the package directory still holds the three siblings.**
  ```bash
  ls app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/
  ```
  Expected: `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`. Do NOT `rmdir` the directory.

---

## Phase 4 — Strip the ktlint baseline entry

- [x] **4.1 — Open `app/config/ktlint/baseline.xml` and delete the `<file>` block.**
  Per Phase 1.4 the block currently lives at lines 929-934:
  ```xml
  	<file name="src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt">
  		<error line="3" column="1" source="no-wildcard-imports" />
  		<error line="7" column="1" source="no-wildcard-imports" />
  		<error line="8" column="1" source="no-wildcard-imports" />
  		<error line="9" column="1" source="no-wildcard-imports" />
  	</file>
  ```
  Delete the whole block including the leading tab indentation. Do NOT touch any other `<file>` block.

- [x] **4.2 — Sanity check the baseline file still parses as XML.**
  ```bash
  python3 -c "import xml.etree.ElementTree as ET; ET.parse('app/config/ktlint/baseline.xml')" && echo OK
  ```
  Expected: `OK`. Any parse error = STOP and surface to the user.

---

## Phase 5 — Final reference sweep + verification gates

- [x] **5.1 — Final repo-wide sweep.**
  ```bash
  grep -rn "AppLimitCard\|ChildComponents\.kt" \
    --exclude-dir=.git --exclude-dir=build --exclude-dir=archive .
  ```
  Expected: zero matches outside `openspec/changes/chore-remove-orphan-app-limit-card-and-child-components/` (this change folder's own `proposal.md` / `tasks.md`).

- [x] **5.2 — Run `./gradlew :app:assembleDebug`.** Must succeed.

- [x] **5.3 — Run `./gradlew testDebugUnitTest`.** Must succeed. Expected: same 3 pre-existing failures from master (`NetworkModuleTest`, `BootReceiverTest` x2); **no new failures**.

- [x] **5.4 — Run `./gradlew :app:ktlintCheck`.** Must succeed. Expected: same 9 pre-existing `WorkersTest.kt` drift items; **no new violations**.

- [x] **5.5 — Commit.**
  ```
  chore(child-components): delete orphan AppLimitCard and ChildComponents.kt
  ```
  Commit body must include:
  - Phase 1 verification result (e.g., "1.1-1.8 all returned expected counts; zero `AppLimitCard` callers, zero `ChildComponents.kt` references in active code").
  - Phase 4 line numbers actually removed (from baseline.xml) — confirm against Phase 1.4 readings.
  - Confirmation that the package directory was left in place with its three siblings.

---

## Notes

- `strict_tdd: true` from `openspec/config.yaml` applies. This is a behaviour-preserving refactor: deleting dead code with zero callers preserves all observable behavior. The TDD gate is satisfied by the verification commands in Phase 1 and Phase 5 — no new tests are required because no behavior delta exists. Forward this to the `sdd-apply` phase.
- The slice-1 characterization test in `ChildStatusViewModelTest.kt` (gating the reactive formula) is unaffected: this change removes a Composable that had no callers, not a ViewModel or formula. The test remains the gate it was.
- Instrumented tests (`connectedDebugAndroidTest`) cannot run locally per the testing capabilities in `openspec/config.yaml` (no `adb`, no emulator). They run only in CI on API 28/31/35. CI will catch any UI regression.
- Conventional-commits style: `chore(...)` prefix.
- The package directory `ui/child/components/` is intentionally kept — three live sibling files (`DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`) are consumed by `ChildStatusScreen.kt`.

---

## Apply log

- 2026-07-02 — Phase 1 verification (1.1-1.8): `AppLimitCard` has zero callers (`grep -nE "^(class|data class|object|interface|sealed|enum|fun)\s+\w+" …ChildComponents.kt` returned exactly `14:fun AppLimitCard(`), zero `ChildComponents.kt` references in build configs / DI / nav / res, zero imports of the file-specific FQCN, zero Compose-preview / Hilt / nav refs. Baseline entry for the file was recorded at lines 929-934. Phase 2 (2.1-2.3): `AppLimitCard` Composable body removed (lines 13-83); file retained only `package` + 9 imports + blanks; re-grep confirmed zero top-level declarations survive. Phase 3 (3.1-3.2): file `git rm`'d; `ls` confirms `ui/child/components/` still holds `DegradedAlertDialog.kt`, `ExtraTimeComponents.kt`, `RewardComponents.kt`. Phase 4 (4.1-4.2): stripped the `<file>` block for `ChildComponents.kt` from `app/config/ktlint/baseline.xml` (6 lines removed, exactly the 929-934 range); XML still parses. Phase 5 (5.1-5.5): final sweep — `AppLimitCard` / `ChildComponents.kt` grep returns only audit-trail matches in `openspec/changes/chore-remove-orphan-requesttime-dialog/{proposal.md,tasks.md}` (historical, not in compiled code) and the orchestrator-skipped archive pool. `./gradlew :app:assembleDebug` **green** (compileDebugKotlin ran fresh, no errors). `./gradlew testDebugUnitTest` shows the same 4 pre-existing failures as master (`NetworkModuleTest` 1/2, `BootReceiverTest` 2/7, `NavGraphTest` 1/10 — the last is pollution from `BootReceiverTest`'s leaked coroutines; pre-flight claim that NavGraphTest passes 10/10 was incorrect — verified identically on master). `./gradlew :app:ktlintCheck`: incremental run shows pre-existing main-source-set drift in `SupabaseClientProvider.kt`, `PairingViewModel.kt`, `SyncManager.kt`, `ChildStatusViewModel.kt`, `TimeExtraViewModel.kt` (same drift exists on master when forced with `--rerun-tasks`, NOT introduced by this change) plus the 9 pre-existing `WorkersTest.kt` test-source-set drift items. **No new violations from this change.** Single commit: `chore(cleanup): remove orphan AppLimitCard Composable and ChildComponents.kt`. Opening PR from `chore/remove-orphan-app-limit-card-and-child-components`.
