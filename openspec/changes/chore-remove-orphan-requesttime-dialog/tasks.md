# Tasks: Delete orphan `RequestTimeDialog` Composable

> Mini-SDD lite chore (no `spec.md`, no `design.md`). One Composable + dynamic import cleanup, single `chore(...)` commit. No behavior change — verification is the only gate.

---

## Phase 1 — Verification (BLOCKING)

Run all of the following from the repo root. Each command must return **zero matches outside `ChildComponents.kt` itself** and the immutable archive `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/`. Any unexpected match = STOP and surface to the user.

- [x] **1.1 — Grep the symbol name across Kotlin sources.**
  ```bash
  grep -rn "RequestTimeDialog" app/src
  ```
  Expected: exactly one match — the declaration at `ChildComponents.kt:17`. No matches in `app/src/test/` or `app/src/androidTest/`.

- [x] **1.2 — Grep the symbol name in Gradle / manifest / resource files.**
  ```bash
  grep -rn "RequestTimeDialog" \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/build.gradle.kts \
    build.gradle.kts \
    settings.gradle.kts \
    app/proguard-rules.pro \
    app/src/main/res
  ```
  Expected: zero matches.

- [x] **1.3 — Grep the symbol name in DI / nav-graph directories.**
  ```bash
  grep -rn "RequestTimeDialog" \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation
  ```
  Expected: zero matches.

- [x] **1.4 — Verify no Compose `@Preview` references.**
  ```bash
  grep -rn "import androidx.compose.ui.tooling.preview.Preview\|@Preview" app/src
  ```
  Expected: zero matches (the codebase has no previews anywhere).

- [x] **1.5 — Verify the symbol isn't called by the surviving `AppLimitCard` Composable in the same file.**
  ```bash
  grep -nE "RequestTimeDialog" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected: exactly one match (the declaration at line 17). The symbol is **not** referenced inside `AppLimitCard` (lines 108-178). No re-entrant usage.

- [x] **1.6 — Enumerate every top-level declaration in `ChildComponents.kt` (PR #11 lesson).**
  ```bash
  grep -nE "^(class|data class|object|interface|sealed|enum|fun)\s+\w+" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected yield: `RequestTimeDialog` and `AppLimitCard`. Anything not on this list = STOP.

- [x] **1.7 — Repo-wide grep on every top-level declaration from 1.6.**
  ```bash
  grep -rn "\bRequestTimeDialog\b\|\bAppLimitCard\b" app/src
  grep -rn "\bRequestTimeDialog\b\|\bAppLimitCard\b" app/src/test app/src/androidTest 2>/dev/null
  ```
  Expected: `RequestTimeDialog` matches ONLY the declaration in `ChildComponents.kt`. `AppLimitCard` may match in `ChildStatusScreen.kt` (or wherever it is consumed) — that is fine, we are not deleting `AppLimitCard`. No matches in test files. Any unexpected match = STOP and return blocked.

- [x] **1.8 — Confirm no Hilt / nav / manifest / ktlint-baseline references to the symbol.**
  ```bash
  grep -rn "\bRequestTimeDialog\b" \
    app/src/main/java/com/tudominio/parentalcontrol/di \
    app/src/main/java/com/tudominio/parentalcontrol/ui/navigation \
    app/src/main/AndroidManifest.xml \
    app/src/debug/AndroidManifest.xml \
    app/src/main/res \
    app/config/ktlint/baseline.xml
  ```
  Expected: zero matches.

Commit gate (do not commit yet — verification is a precondition, not an artifact):
> If all checks pass, proceed to Phase 2. If any check fails, STOP and report.

---

## Phase 2 — Identify transitive import cleanup

The file uses these imports today (lines 1-11):

```
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

Static review: every import is **also** used by the surviving `AppLimitCard` (via wildcard `*` or shared symbols like `Modifier`, `dp`, `FontWeight`, `Alignment`). The expected outcome is **zero imports become unused** after `RequestTimeDialog` is removed. The apply phase confirms this empirically:

- [x] **2.1 — Apply the deletion in the IDE / editor first, before any commit.**
  Delete the `RequestTimeDialog` Composable and its KDoc (lines 13-106). Do not save yet — proceed to 2.2 in the same editor session so the IDE can flag unused imports.
- [x] **2.2 — Run the IDE "Optimize Imports" or equivalent, then scan the file by hand.**
  Record the result. **Expected: zero imports become unused.** If any import is flagged, drop it in the same edit (still uncommitted). For wildcard imports (`*`), narrow them to explicit symbols if and only if removing the Composable drops the package's last use; otherwise keep them.
- [x] **2.3 — Sanity check: every surviving top-level declaration still compiles.**
  ```bash
  grep -nE "^(class|data class|object|interface|sealed|enum|fun)\s+\w+" \
    app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt
  ```
  Expected: only `AppLimitCard` remains.
- [x] **2.4 — Sanity check: every remaining import line still references at least one surviving symbol in the file.**
  Manual review. The expected outcome is "all imports retained". Record the decision in the commit body.

> If Phase 2 reveals an import that truly becomes unused, the apply phase drops it in the same commit. If everything is retained (the expected case), note "no import cleanup required" in the commit body.

---

## Phase 3 — Delete + verify (single commit)

- [x] **3.1 — Save the deletion from Phase 2.1-2.2 (Composable body + KDoc, plus any unused imports).**
- [x] **3.2 — Final reference sweep across the whole repo.**
  ```bash
  grep -rn "RequestTimeDialog" --exclude-dir=.git --exclude-dir=build .
  ```
  Expected: matches only in the immutable archive `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/` (audit trail, zero matches expected — verified pre-flight) and in this change folder's own `proposal.md` / `tasks.md` (these docs).
- [x] **3.3 — Run `./gradlew :app:assembleDebug`.** Must succeed.
- [x] **3.4 — Run `./gradlew testDebugUnitTest`.** Must succeed. Expected: same 4 pre-existing failures from master persist; **no new failures**.
- [x] **3.5 — Run `./gradlew :app:ktlintCheck`.** Must succeed. Expected: same 9 pre-existing drift items in `WorkersTest.kt` persist; **no new violations**.
- [x] **3.6 — Commit.**
  ```
  chore(child-components): delete orphan RequestTimeDialog Composable
  ```
  Commit body must include:
  - The Phase 1 verification result (e.g., "1.1-1.8 all returned expected counts; 1 zero outside the declaration").
  - The Phase 2 import-cleanup decision (e.g., "no imports became unused after the Composable removal" — or the list of dropped imports if any).

---

## Notes

- `strict_tdd: true` from `openspec/config.yaml` applies. This is a behaviour-preserving refactor: deleting dead code with zero callers preserves all observable behavior. The TDD gate is satisfied by the verification commands in Phase 1 and Phase 3 — no new tests are required because no behavior delta exists. Forward this to the `sdd-apply` phase.
- Instrumented tests (`connectedDebugAndroidTest`) cannot run locally per the testing capabilities in `openspec/config.yaml` (no `adb`, no emulator). They run only in CI on API 28/31/35. CI will catch any UI regression.
- Conventional-commits style: `chore(...)` prefix.
- The archived `openspec/changes/archive/2026-07-01-fix-child-viewmodel-grant-observability/` change does **not** reference `RequestTimeDialog` (verified pre-flight). No archive coupling.

---

## Apply log

- 2026-07-02 — Applied: deleted the `RequestTimeDialog` Composable + KDoc (lines 13-106, 94 lines removed) from `app/src/main/java/com/tudominio/parentalcontrol/ui/child/components/ChildComponents.kt`. Phase 1 verification (1.1-1.8) all returned expected counts; the only `RequestTimeDialog` match outside this change folder was the declaration in `ChildComponents.kt:17`. Phase 2 import-cleanup decision: **no imports became unused** — all 9 imports are still referenced by the surviving `AppLimitCard` (Modifier.fillMaxWidth/padding, Arrangement.SpaceBetween/spacedBy, Icons.Default.Star, Card/CardDefaults/MaterialTheme, @Composable, Alignment.CenterVertically/End, FontWeight.Bold, dp). Verification: `./gradlew :app:assembleDebug` green; `./gradlew testDebugUnitTest` shows 3 pre-existing failures (`NetworkModuleTest`, `BootReceiverTest` x2) — orchestrator pre-flight mentioned 4 incl. `NavGraphTest`, but `NavGraphTest` actually passes (10 tests, 0 failures) on master; no new failures introduced; `./gradlew :app:ktlintCheck` reports the same 9 pre-existing `WorkersTest.kt` drift items, no new violations. Opening PR from `chore/remove-orphan-requesttime-dialog`.
