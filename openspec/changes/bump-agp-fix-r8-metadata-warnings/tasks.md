# Tasks: bump-agp-fix-r8-metadata-warnings

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~150-200 (1-2 in `gradle/libs.versions.toml` + ~20-50 in `verification-evidence.md` + 0-1 in `gradle-wrapper.properties` only on fallback path) |
| 400-line budget risk | Low (single PR, well under 400) |
| Chained PRs recommended | No (single work unit — verification gate + bump + gates collapse atomically) |
| Suggested split | None — single PR |
| Delivery strategy | single-pr |
| Chain strategy | N/A |
| DataStore deviation | N/A |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: N/A
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Base branch | Notes |
|------|------|-----------|-------------|-------|
| 1 | Verify + bump AGP + prove zero warnings | PR 1 | `master` | Verification gate blocks bump; no app code touched |

---

## Task 1: Create working branch from master

- [x] Step 1.1: `git checkout master && git pull --ff-only`
- [x] Step 1.2: `git checkout -b feature/bump-agp-fix-r8-metadata-warnings`

**Acceptance**: branch exists at `e2c1000 + 0` (no commits yet), points to master tip.

## Task 2: Verify primary candidate (AGP 8.13.2) bundles kotlin-metadata-jvm ≥ 2.3.0

- [x] Step 2.1: Run `curl -s https://repo1.maven.org/maven2/com/android/tools/r8/8.13.2/r8-8.13.2.pom | grep -A2 kotlin-metadata-jvm`. Capture stdout. **Outcome: HTTP 404 — `r8/8.13.2` does not exist on Maven Central (R8 lives on Google Maven). The proposal's curl-grep approach is fundamentally broken (R8 POM has zero deps; kotlin-metadata-jvm is shaded into the R8 jar). Pivoted to bytecode-inspection verification — see `verification-evidence.md` for the working mechanism.**
- [x] Step 2.2: Create `openspec/changes/bump-agp-fix-r8-metadata-warnings/verification-evidence.md` with the header `# Verification — primary path` and paste the curl stdout in a fenced bash block.
- [x] Step 2.3: IF the stdout shows `kotlin-metadata-jvm` version ≥ 2.3.0 → mark Task 2 complete, proceed to Task 4 with `chosen = "8.13.2"`. **Outcome: Verified via bytecode — AGP 8.13.2 ships R8 8.13.19; R8 8.13.19's `gc2.h = {2, 3, 0}` = max Kotlin metadata v2.3.0. PASS, proceed with `chosen = "8.13.2"`.**

**Acceptance**: `verification-evidence.md` exists with the primary-path section; outcome (pass/fail) recorded in the file.

## Task 3: Verify fallback candidate (AGP 9.2.1) — ONLY IF Task 2 failed

- [ ] Step 3.1: Run `curl -s https://repo1.maven.org/maven2/com/android/tools/r8/9.2.1/r8-9.2.1.pom | grep -A2 kotlin-metadata-jvm`. Capture stdout.
- [ ] Step 3.2: Append a section to `verification-evidence.md` with header `# Verification — fallback path` and paste the curl stdout.
- [ ] Step 3.3: IF the stdout shows `kotlin-metadata-jvm` version ≥ 2.3.0 → `chosen = "9.2.1"`, proceed to Task 4 (Task 5.2 wrapper sub-task also runs). ELSE → abort the change: report both curl outputs, mark Task 3 failed, do not proceed.

**Outcome: Skipped — Task 2 passed with `chosen = "8.13.2"`; fallback path not invoked. Marked not applicable per Task 2.3 branch ("ELSE → leave Task 2 incomplete, proceed to Task 3").**

**Acceptance**: `verification-evidence.md` has both primary and fallback sections if fallback was attempted; outcome recorded.

## Task 4: Sanity-check KSP / Hilt / Compose resolve with the chosen AGP

- [x] Step 4.1: Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath | head -50`. Capture output.
- [x] Step 4.2: Confirm KSP, Hilt, and Compose plugin coordinates are still resolved (no `Could not resolve` errors). **Outcome: resolved cleanly.**
- [x] Step 4.3: If KSP / Hilt / Compose fail to resolve with the new AGP → STOP and escalate; this is out-of-scope for the AGP bump. **Outcome: not triggered.**

**Acceptance**: no unresolved plugin / dependency errors in the runtime classpath.

## Task 5: Edit `gradle/libs.versions.toml` — bump agp version

- [x] Step 5.1: At `gradle/libs.versions.toml` line 2, change `agp = "8.9.2"` → `agp = "<chosen-version>"` (either `8.13.2` from Task 2 pass, or `9.2.1` from Task 3 pass). **Outcome: `agp = "8.13.2"` written.**
- [ ] Step 5.2 (ONLY if Task 3 path chosen): bump `gradle/wrapper/gradle-wrapper.properties` `distributionUrl` to whatever AGP 9.2.1 requires. Check AGP 9.2.1 release notes; current wrapper `gradle-9.4.1-bin.zip` is likely sufficient but verify.

**Step 5.2 not applicable — primary path taken; wrapper at 9.4.1 already exceeds AGP 8.13.2's Gradle 8.11.1+ minimum.**

**Acceptance**: `gradle/libs.versions.toml` has the new agp version; wrapper bumped only if fallback path was used.

## Task 6: Run full quality gate (with R8 enabled)

- [x] Step 6.1: `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8` (single invocation). Capture full output.
- [x] Step 6.2: Confirm all four tasks exit 0. **Outcome: BUILD SUCCESSFUL in 8m 56s, 99 tasks executed.**
- [x] Step 6.3: Confirm `testDebugUnitTest` reports ≥ 604 passing tests. **Outcome: 604 tests, 0 failures, 0 errors, 0 skipped.**
- [x] Step 6.4: Confirm zero new detekt or ktlint findings. **Outcome: detekt clean; ktlint clean (only Kotlin compiler warnings unrelated to the bump).**

**Acceptance**: all gates exit 0; tests ≥ 604; no new findings.

## Task 7: Assert zero R8 metadata warnings post-bump

- [x] Step 7.1: From the Task 6 build log, run `grep -cE "malformed kotlin\.Metadata|kotlin metadata version is not supported"`. **Outcome: `0` (down from 1171 pre-bump).**
- [x] Step 7.2: If count > 0 → STOP and escalate. **Outcome: not triggered.**
- [x] Step 7.3: Append the grep result to `verification-evidence.md` as `# Post-bump assertion`.

**Acceptance**: grep count = 0; assertion recorded in the evidence file.

## Task 8: Single commit (per D2 — combined evidence + bump)

- [x] Step 8.1: `git add gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties openspec/changes/bump-agp-fix-r8-metadata-warnings/verification-evidence.md` (wrapper not present — primary path). **Outcome: libs.versions.toml + verification-evidence.md staged.**
- [x] Step 8.2: Commit with subject `build(agp): bump AGP to <chosen-version> — kotlin-metadata-jvm verified at <observed-version>`. **Outcome: commit `48233b8`.**
- [x] Step 8.3: Commit body follows the template in `design.md` §"Commit plan" — paste the verification evidence file's fenced bash block, list changes, list gate results, list rollback command + Engram #46 reference. **Outcome: body includes evidence paste + changes + gates + rollback.**
- [x] Step 8.4: NO `Co-Authored-By` trailer. NO AI attribution. Conventional Commit format only. **Outcome: clean commit.**

**Acceptance**: single commit on the feature branch with all files staged together; commit message references the verification evidence and is free of AI-attribution trailers.

## Task 9: Push branch and open PR

- [x] Step 9.1: `git push -u origin feature/bump-agp-fix-r8-metadata-warnings`. **Outcome: pushed; remote tracking set.**
- [x] Step 9.2: `gh pr create` (no PR template in repo) with:
  - Title: `build(agp): bump AGP to fix R8 kotlin-metadata warnings (<version>)`
  - Body: Summary → Changes table → Verification → Test plan → Rollback
  - Label: `type:chore` **DEVIATION: repo lacks `type:chore` label; only default labels exist. PR created without label. Documented in PR body.**
- [x] Step 9.3: Do NOT merge. Hand off to orchestrator for `sdd-verify`. **Outcome: PR #3 open at https://github.com/Andrea-Caballero/parentalControl/pull/3; no merge performed.**

**Acceptance**: PR exists, targets `master`, has `type:chore` label, body matches the design template, no merge performed.

## Task 10: Mark tasks complete in this file

- [x] Step 10.1: Mark all 10 tasks above as `[x]`.
- [x] Step 10.2: Keep the file at `openspec/changes/bump-agp-fix-r8-metadata-warnings/tasks.md` (it will be archived with the change).

**Acceptance**: every checkbox is `[x]`; file saved.

## Task 11: Persist apply-progress to Engram

- [ ] Step 11.1: `mem_save` with:
  - `topic_key`: `sdd/bump-agp-fix-r8-metadata-warnings/apply-progress`
  - `type`: `architecture`
  - `capture_prompt`: `false`
  - `content`: chosen AGP version, verification evidence summary, commit SHA, PR URL, gate results, any deviations.

**Acceptance**: Engram observation exists with the topic_key above.

---

## Sequencing Concerns

1. **Verification-first ordering is mandatory**: Tasks 2-3 MUST complete (with recorded outcome) before Task 5 (the bump edit). The evidence file MUST land in the same commit as the bump (D2 Option C).
2. **Abort conditions**: If both Task 2 and Task 3 verification fail, STOP and report. Do not commit anything; do not open a PR.
3. **Gradle wrapper check is conditional**: Only Task 5.2 runs if Task 3 (fallback) was the chosen path.
4. **Smoke test on device is out of scope**: The change is a build-config toolchain bump; release-APK device smoke flow is out of scope for `sdd-apply` (mirrors PR 4 of the previous `align-with-guia-fedora44` change; manual flow deferred to a separate validation task).
5. **No app code touched**: All edits are in `gradle/`, `gradle/wrapper/`, and `openspec/changes/`. Any edit outside these roots should STOP and escalate.

## Notes for the apply phase

- The apply agent runs in fresh context; it MUST RE-READ `proposal.md`, `specs/r8-kotlin-metadata-version/spec.md`, `design.md`, AND this `tasks.md` before editing anything.
- `openspec/config.yaml` has `strict_tdd: true` and `apply.tdd: true`. Per design D6, shell commands ARE the tests for this change: curl-grep is the RED/GREEN for the verification gate, and `:app:minifyReleaseWithR8` plus the post-bump `grep -cE` is the GREEN for the warning-free assertion. No new unit tests are written (no new code surface).
- The orchestrator routes to `sdd-verify` after apply completes; the verify agent will re-run the gate and the zero-warning grep on the post-bump tree.
