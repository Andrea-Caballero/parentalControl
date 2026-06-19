# Design: bump-agp-fix-r8-metadata-warnings

## Approach Summary

Single-PR toolchain bump of AGP `8.9.2 → 8.13.2` (primary) or `9.2.1` (fallback), preceded by a **bytecode-inspection verification gate** that proves the target AGP's bundled R8 supports Kotlin metadata ≥ `2.3.0` BEFORE any build file is edited. Because `kotlin-metadata-jvm` is shaded into the R8 jar (relocated under `com.android.tools.r8.kotlin.*`) and AGP/R8 versions are decoupled, a Maven Central POM grep does not work — the gate inspects bytecode constants instead. The verification transcript is captured to a sibling evidence file inside the change folder (`verification-evidence.md`) and referenced from the apply commit message, satisfying the spec scenario "Verification command is reproducible." After the bump, the full quality gate (`assembleDebug testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8`) is the GREEN signal. No app code, no new unit tests, no spec delta for `proguard-keep-alignment` unless R8 8.13 actually rejects an existing keep rule.

## Decisions

### D1. Evidence storage: **Option B — file in change folder + one-line pointer in commit message**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| A. Commit message only | Lost in `git log --oneline`; no audit-trail artifact | Reject |
| **B. Evidence file + one-line commit pointer** | Durable, archived with change, grep-able, satisfies spec scenario "raw stdout SHALL land in (commit message or sibling evidence file)" | **Pick** |
| C. File + full paste in commit message | Redundant; doubles diff if the file changes | Reject |

**Rationale:** `verification-evidence.md` survives `git log`, is archived with the change, and is one click from the PR. Commit subject carries the chosen version; commit body references the file.

### D2. Verification gate commit structure: **Option C — local verify, one combined commit on apply**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| A. Two commits (evidence then bump) | Repo still broken (1171 warnings) after evidence-only commit — violates work-unit rule | Reject |
| B. One commit, no prior local verify | Loses fail-closed semantics | Reject |
| **C. Local verify first (no commit), then one commit with evidence file + bump edit** | Single work unit; reviewer reads evidence in the same diff; orchestrator sees gate-pass via the file content | **Pick** |

**Rationale:** `verification-evidence.md` landing in the same commit as `gradle/libs.versions.toml` proves the gate ran first (file content predates the version edit) without leaving the repo broken mid-chain.

### D3. Branch and PR shape

- **Branch**: `feature/bump-agp-fix-r8-metadata-warnings` from `master` (`e2c1000`).
- **PR target**: `master`. **Title**: `build(agp): bump AGP to fix R8 kotlin-metadata warnings (8.13.2)` (or `(9.2.1)`).
- **Body** (no `.github/PULL_REQUEST_TEMPLATE.md` exists): Summary (one paragraph) → Changes table → Verification (paste from `verification-evidence.md`) → Test plan (full gradle gate + zero-warning grep) → Rollback (`git revert <sha>` + Engram #46 reference).
- **Label**: `type:chore`.

### D4. Apply task ordering

1. Create `feature/bump-agp-fix-r8-metadata-warnings` from `master`.
2. **Verify primary (AGP 8.13.2) via bytecode inspection** (two steps — see `proposal.md` §"Verification mechanism" for rationale why Maven Central POM grep does not work):
   1. Download the AGP `builder` jar from Google Maven and decompile `com.android.builder.dexing.R8Version` to find which R8 the AGP ships with:
      ```bash
      curl -s -o builder-8.13.2.jar \
        "https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/8.13.2/builder-8.13.2.jar"
      javap -p -c -constants -classpath builder-8.13.2.jar \
        com.android.builder.dexing.R8Version
      # Expect: public static final java.lang.String VERSION_AGP_WAS_SHIPPED_WITH = "8.13.19";
      ```
   2. Download that R8's jar from Google Maven and decompile its metadata-version constant class to find the max supported Kotlin metadata version:
      ```bash
      curl -s -o r8-8.13.19.jar \
        "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.13.19/r8-8.13.19.jar"
      strings r8-8.13.19.jar | grep "while maximum supported version is"
      # → identifies the checker class (e.g. com/android/tools/r8/internal/l02.class)
      javap -c -classpath r8-8.13.19.jar <version-constant-class>
      # Expect (for R8 8.13.19): h = gc2(new int[]{2, 3, 0}, false) → max metadata v2.3.0
      ```
   Save the full transcript to `verification-evidence.md` under `# Verification — primary path`. If max metadata ≥ `2.3.0` → pass, step 4 with `chosen = "8.13.2"`. If pass condition fails → step 3.
3. **Verify fallback (AGP 9.2.1)** only if step 2 failed: repeat the same two-step bytecode-inspection mechanism against `builder-9.2.1.jar` and the matching `r8-<x>.<y>.<z>.jar`; append to `verification-evidence.md` under `# Verification — fallback path`. If pass → step 4 with `chosen = "9.2.1"`; if fail → abort and report both transcripts.
4. **Sanity**: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | head -50` confirms KSP/Hilt/Compose resolve.
5. **Edit `gradle/libs.versions.toml`**: change `agp = "8.9.2"` → chosen version (line 2).
6. **If 9.x chosen**: bump `gradle/wrapper/gradle-wrapper.properties` distribution to AGP 9.x's required Gradle (verify against AGP release notes; AGP 9.x requires Gradle ≥ 9.x — current wrapper is 9.4.1, likely fine; bump only if release notes require more).
7. **Quality gate**: `./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8`.
8. **Zero-warnings assertion**: `grep -cE "malformed kotlin\.Metadata|kotlin metadata version is not supported" <build-log>` returns `0`; task exits 0.
9. **Single commit** (per D2): `verification-evidence.md` (new) + `gradle/libs.versions.toml` (modified) in one `build(agp): bump AGP to 8.13.2 — kotlin-metadata-jvm verified at 2.3.x` commit.
10. **Push** and open PR per D3.
11. **Update `tasks.md`** with `[x]` marks for all completed steps.
12. **Persist apply-progress** to Engram via `mem_save` with `topic_key: sdd/bump-agp-fix-r8-metadata-warnings/apply-progress`.

### D5. Rollback evidence strategy: **Option A — document mechanical possibility; do not execute**

| Option | Tradeoff | Decision |
|--------|----------|----------|
| **A. Document in PR body + verification-evidence.md; do not execute the cycle** | Git revert is deterministic; pre-bump 1171-warning state already observed (Engram #46) | **Pick** |
| B. Full revert-R8 + revert-the-revert cycle | Runs R8 twice; no new information | Reject |
| C. Worktree cycle | Same cost as B | Reject |

**Rationale:** Spec scenario "Revert restores the warning count" is satisfied by citing the pre-bump observation in the PR body. Recording the cycle means documenting its mechanical availability, not burning CI minutes on a deterministic fact.

### D6. Strict TDD interaction

`openspec/config.yaml` has `strict_tdd: true` and `apply.tdd: true`. Strict TDD applies to **behaviors with unit-test surfaces**. This change has zero new code surface — the only behaviors introduced are:
- Chosen AGP's bundled R8 supports Kotlin metadata ≥ 2.3.0 → **verified by bytecode inspection BEFORE the bump** (download `builder-<agp>.jar` + `r8-<r8>.jar` from Google Maven and decompile the `R8Version` and metadata-version constants; transcript proves it).
- `:app:minifyReleaseWithR8` emits 0 warnings after the bump → **asserted by `grep -cE` AFTER the bump** (existing 1171 → zero).
- Full quality gate stays green → **existing `./gradlew` invocations are the regression suite** (604+ unit tests, detekt, ktlint).

No new unit tests are written because there is no new Kotlin/Java code. Documented here so the orchestrator does not flag "missing tests" at verify. If AGP 9.x forces plugin DSL renames, those edits get unit tests at the call site (Hilt graph, Room schema), not here.

## File-by-file impact

| File | Action | Why |
|------|--------|-----|
| `gradle/libs.versions.toml` | Modify | `agp = "8.9.2"` → `8.13.2` (or `9.2.1`); sole behavior-shaping edit |
| `openspec/changes/bump-agp-fix-r8-metadata-warnings/verification-evidence.md` | Create | Audit-trail artifact; spec scenario "raw stdout SHALL land in the apply commit message (or a sibling evidence file)" |
| `gradle/wrapper/gradle-wrapper.properties` | Modify (only if 9.x chosen) | AGP 9.x may require Gradle ≥ 9.x; current wrapper is 9.4.1 |
| `app/proguard-rules.pro` | Possibly modify | Only if R8 8.13 rejects an existing keep rule; out-of-scope unless triggered |
| `openspec/changes/bump-agp-fix-r8-metadata-warnings/tasks.md` | Update | Mark apply steps `[x]` per `sdd-apply` contract |

## Commit plan (work-unit commits)

**One commit** by design (D2):

```
build(agp): bump AGP to 8.13.2 — kotlin-metadata-jvm verified at 2.3.x

Verification (see openspec/changes/bump-agp-fix-r8-metadata-warnings/verification-evidence.md):
<paste of evidence file's fenced bash block>

Changes:
- gradle/libs.versions.toml: agp 8.9.2 → 8.13.2
- verification-evidence.md: new audit trail

Gate: ./gradlew :app:assembleDebug :app:testDebugUnitTest detekt ktlintCheck :app:minifyReleaseWithR8 → all green; 0 malformed-kotlin-Metadata lines.

Rollback: git revert <sha>; pre-bump 1171-warning state in Engram #46.
```

If fallback (9.2.1) chosen, the commit additionally includes `gradle/wrapper/gradle-wrapper.properties` if a wrapper bump is required.

## Open Questions

- [x] **AGP 8.13.2 metadata support — RESOLVED.** Bytecode inspection (`javap` on `com.android.builder.dexing.R8Version` in `builder-8.13.2.jar`) shows `VERSION_AGP_WAS_SHIPPED_WITH = "8.13.19"`. Bytecode inspection on the corresponding `r8-8.13.19.jar` shows `gc2.h = {2, 3, 0}` → max supported Kotlin metadata **v2.3.0** → matches Kotlin 2.3.0's emit version. Primary path passes. Full transcript in `verification-evidence.md`.
- [ ] AGP 9.x Gradle wrapper minimum — checked against AGP release notes only if fallback triggered; current 9.4.1 likely sufficient.
- [ ] `proguard-rules.pro` interaction with R8 8.13 — only addressed if the quality gate surfaces a keep-rule rejection; no preemptive edit.

## References

- Proposal: `openspec/changes/bump-agp-fix-r8-metadata-warnings/proposal.md`
- Spec: `openspec/changes/bump-agp-fix-r8-metadata-warnings/specs/r8-kotlin-metadata-version/spec.md`
- Engram #46 — D8/R8 root cause (1171-warning evidence)
- Engram #47 — Task #4 closed as documented debt
- Engram #57 — SDD proposal for this change
- Archive ref: `openspec/changes/archive/2026-06-19-align-with-guia-fedora44/design.md` (4-PR chain pattern)
- Project config: `openspec/config.yaml` (`strict_tdd: true`, `apply.tdd: true`)