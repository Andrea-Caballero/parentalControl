# Proposal: bump-agp-fix-r8-metadata-warnings

## Intent

The current toolchain produces **1171 R8 warnings** during `:app:minifyReleaseWithR8` and degrades release-build optimization for every Kotlin class. Root cause is a version mismatch: AGP **8.9.2** bundles R8 with `kotlin-metadata-jvm` that supports **max metadata v2.1.0**, but the project compiles with Kotlin **2.3.0** which emits metadata **v2.3.0**. R8 falls back to degraded mode for affected classes — no proper optimization/obfuscation, and worst-case risk of breaking sealed-class hierarchies that downstream `when` exhaustive checks rely on at runtime.

This change bumps AGP to a version that ships `kotlin-metadata-jvm` ≥ 2.3.0 so R8 stops warning and restores full release-build optimization.

**Outcome:** zero R8 warnings during `:app:minifyReleaseWithR8`; full R8 optimization restored.

## Scope

### In Scope

| Deliverable | Why |
|---|---|
| Bump `agp` in `gradle/libs.versions.toml` (8.9.2 → 8.13.2 primary, 9.2.1 fallback) | Fixes the metadata version mismatch |
| Verification task: confirm the target AGP version bundles `kotlin-metadata-jvm` ≥ 2.3.0 BEFORE applying the bump | Avoids committing to a version that doesn't actually fix the issue |
| Bump `ksp` in `gradle/libs.versions.toml` only if required by the chosen AGP version | KSP couples to Kotlin, not AGP — assumed no change needed; verify first |
| Update `gradle/wrapper/gradle-wrapper.properties` if AGP 9.x forces a Gradle bump | Possible AGP 9.x requirement |
| Re-run full quality gate | Prove no behavior change |

### Out of Scope

- **Kotlin downgrade**: would reverse PR 1 of `align-with-guia-fedora44` (commit `ee746e4`) and break guide alignment.
- **Code changes** unrelated to build configuration.
- **`proguard-rules.pro` refresh**: only touch if 8.x → 8.13 breaks R8 keep-rule syntax; assumption is it does not.
- **Firebase, Room, Hilt, Compose bumps**: separate change.
- **JVM target bump** (17 → 21): CI pins Java 17 per `openspec/config.yaml`.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
None.

This is a **pure toolchain bump**. No spec-level behavior changes — all existing requirements (enforcement, pairing, outbox sync, etc.) remain semantically identical. The `proguard-keep-alignment` spec MAY receive a delta if R8 keep-rule syntax changed, but the requirements are not changing.

## Approach

### Primary path: AGP 8.9.2 → 8.13.2

Conservative minor bump within the 8.x line. Lowest risk for plugin DSL compatibility.

### Fallback path: AGP 8.9.2 → 9.2.1

Major bump. Only used if verification proves 8.13.2 does NOT bundle `kotlin-metadata-jvm` ≥ 2.3.0. Requires extra budget for plugin DSL renames, possible Gradle wrapper bump, and re-verification of KSP/Hilt/Compose plugin compatibility.

### Verification mechanism (FIRST task of `sdd-apply`)

Before editing any file, the apply phase MUST confirm the fix is actually in the chosen AGP version. Because the `kotlin-metadata-jvm` library is **shaded** into the R8 jar (relocated under `com.android.tools.r8.kotlin.*`) and AGP/R8 versions are decoupled, the verification MUST inspect bytecode constants — Maven Central POM grep does not work (see "Historical note" below).

**Working mechanism (bytecode inspection, two steps)**:

1. **Identify which R8 version the chosen AGP ships with.** Download the AGP `builder` jar from Google Maven and decompile `com.android.builder.dexing.R8Version`:
   ```bash
   curl -s -o builder-<agp>.jar \
     "https://dl.google.com/dl/android/maven2/com/android/tools/build/builder/<agp>/builder-<agp>.jar"
   javap -p -c -constants -classpath builder-<agp>.jar \
     com.android.builder.dexing.R8Version
   # Look for: public static final java.lang.String VERSION_AGP_WAS_SHIPPED_WITH = "<r8>";
   ```
   For example, AGP `8.13.2` ships R8 `8.13.19`.

2. **Find the max supported Kotlin metadata version in that R8 build.** Download the R8 jar from Google Maven, locate the metadata-version-checking class via `strings`, and decompile its version constant class:
   ```bash
   curl -s -o r8-<r8>.jar \
     "https://dl.google.com/dl/android/maven2/com/android/tools/r8/<r8>/r8-<r8>.jar"
   strings r8-<r8>.jar | grep "while maximum supported version is"
   # → identifies the checker class (e.g. com/android/tools/r8/internal/l02.class)
   javap -c -classpath r8-<r8>.jar <version-constant-class>
   # Look for: h = gc2(new int[]{major, minor+1, 0}, false) → max metadata = major.minor.0
   ```
   For example, R8 `8.13.19` shows `h = {2, 3, 0}` → max Kotlin metadata **v2.3.0**.

If the max version in step 2 is ≥ 2.3.0 (matching the Kotlin compiler's emit version) → primary path is safe, proceed.

If verification fails → escalate to AGP `9.2.1` verification using the same two-step bytecode mechanism against that version's AGP `builder` and R8 jars. If that also fails → abort the change with documented evidence and re-open investigation.

#### Historical note — DOES NOT WORK

The originally-proposed mechanism was:
```bash
curl -s "https://repo1.maven.org/maven2/com/android/tools/r8/<v>/r8-<v>.pom" | grep -A2 "kotlin-metadata-jvm"
```
This always fails (HTTP 404) because:

1. `com.android.tools:r8` is **not published to Maven Central** — it lives on Google Maven (`dl.google.com/dl/android/maven2/`).
2. The R8 POM has **zero dependencies** — `kotlin-metadata-jvm` is shaded into the R8 jar under `com.android.tools.r8.kotlin.*`, so a POM grep would never produce a hit.
3. The R8 version number is **decoupled** from the AGP version number (AGP 8.13.2 ships R8 8.13.19, not R8 8.13.2).

Use the bytecode-inspection mechanism above. The full reproducible transcript for AGP 8.13.2 / R8 8.13.19 lives in `verification-evidence.md`.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `gradle/libs.versions.toml` | Modified | `agp` 8.9.2 → 8.13.2 (or 9.2.1); `ksp` only if forced |
| `gradle/wrapper/gradle-wrapper.properties` | Possibly modified | If AGP 9.x requires Gradle ≥ X |
| `app/proguard-rules.pro` | Possibly modified | Only if R8 8.13 changes keep-rule syntax |
| `openspec/specs/proguard-keep-alignment/spec.md` | Possibly delta | If keep-rule syntax change requires a spec delta |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| AGP 8.13.2 does NOT bundle `kotlin-metadata-jvm` ≥ 2.3.0 | Med | **First task of apply phase is verification.** Fail-closed: do not commit the bump until proven. |
| AGP 9.x requires plugin DSL renames (`android.application` etc.) | High if fallback used | Grep `app/build.gradle.kts` and `gradle/libs.versions.toml` for deprecated DSL blocks; budget +1 PR slice for migration. |
| KSP version must move with AGP (assumption: no) | Low | KSP couples to Kotlin, not AGP. Verify KSP `2.3.0-x.y.z` still resolves after AGP bump; bump if not. |
| Existing `proguard-rules.pro` uses syntax that 8.13 R8 rejects | Low | Run `:app:minifyReleaseWithR8` after the bump; if it fails, address only the failing rules and add a delta spec. |
| Hidden AGP 8.x → 8.13 behavior change (e.g., resource shrinker, lint defaults) | Low–Med | Full quality gate (`./gradlew detekt ktlintCheck lint testDebugUnitTest assembleDebug :app:minifyReleaseWithR8`) must pass; any delta investigated. |
| `ksp = "2.3.0"` in catalog is shorthand — actual published version is `2.3.0-1.0.x` (catalog may need precise coordinate) | Med | Out of scope for THIS change; flag as separate hygiene task if it blocks the bump. |

## Rollback Plan

- Pure build-config change. **Rollback = `git revert <sha>`** of the bump commit on master.
- Working tree starts at **`e2c1000`** (current master tip, last commit of the archived `align-with-guia-fedora44` change).
- Verification command after revert: `./gradlew :app:minifyReleaseWithR8` should reproduce the original 1171 warnings — proves the rollback restored the original toolchain.
- No data migration, no `google-services.json` regeneration, no Firebase involvement.

## Dependencies

- **AGP/R8/Kotlin-metadata upstream**: must publish a version that ships `kotlin-metadata-jvm` ≥ 2.3.0. Already exists somewhere between AGP 8.11 and 9.2.1 per the root-cause analysis; exact version needs verification (see Approach).
- **Google Maven repository** (`https://dl.google.com/dl/android/maven2/`): source of truth for AGP and R8 jars (`com.android.tools:builder` and `com.android.tools:r8`). Verification cannot proceed without HTTP access to it. Maven Central does **not** host R8 and is not used here.
- **Gradle wrapper**: 9.4.1 currently. AGP 8.13.x supports Gradle 8.11.1+ (likely compatible); AGP 9.x may require Gradle ≥ 9.x.
- **CI matrix**: Java 17 stays; do not bump to 21.

## Success Criteria

- [ ] `./gradlew :app:minifyReleaseWithR8` emits **zero** `Class X has malformed kotlin.Metadata` warnings.
- [ ] `./gradlew detekt ktlintCheck lint testDebugUnitTest assembleDebug` all pass (604+ unit tests green).
- [ ] No app behavior change: release APK boots, signs in via Supabase, runs pairing + foreground monitor smoke flow.
- [ ] `gradle/libs.versions.toml` `agp` field reads either `8.13.2` (primary) or `9.2.1` (fallback) — documented in the apply commit message.
- [ ] `openspec/changes/bump-agp-fix-r8-metadata-warnings/tasks.md` first task ("Verify AGP ships kotlin-metadata-jvm ≥ 2.3.0") is **checked off before any code edit**.
- [ ] Fallback path triggered: documented with evidence + still passes verification before commit.