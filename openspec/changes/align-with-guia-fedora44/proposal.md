# Proposal: align-with-guia-fedora44

## Intent

The project is a working Android (Kotlin/Compose/Hilt/Room) parental-control app, but it has drifted from the canonical guide `guia-android-control-parental-fedora44.md` (repo root) in three dimensions:

1. **Toolchain versions** — Kotlin, AGP, Compose BOM, Hilt, Room, Navigation, Lifecycle, DataStore, Work, and KSP are pinned to older releases than the guide specifies.
2. **File and package structure** — class names, package layout, and DI module boundaries diverge from the guide (e.g., `ForegroundAppService` instead of `AppMonitorService`, `data/local/AppDatabase.kt` instead of `data/db/ParentalDatabase.kt`).
3. **Namespace and Firebase wiring** — application id is `com.example.parentalcontrol` and the manifest is not aligned with the guide's reference layout.

The change brings the project into full compliance with the guide so future tasks can be specified against the canonical structure, not the drifted one.

## Scope

### In Scope — 4 chained PRs, each ≤400 lines

| PR | Title | Files | Lines | Deliverable |
|----|-------|-------|-------|-------------|
| 1 | Versiones y SDK | 2 | ~50 | Bump `gradle/libs.versions.toml` (Kotlin 2.3.0, AGP 8.9.2, Compose BOM 2026.05.00, Hilt 2.56, Room 2.7.1, Navigation 2.9.0, Lifecycle 2.9.0, DataStore 1.1.2, Work 2.10.1, KSP 2.3.0-1.0.24). `compileSdk` 35→36, `targetSdk` 35→36. JVM target stays 17. |
| 2 | Estructura de archivos | ~8 | ~250 | Rename receivers/services to spec names; move `boot/BootReceiver.kt` → `receiver/BootReceiver.kt`; split `data/local/AppDatabase.kt` → `data/db/ParentalDatabase.kt` + entities to `data/model/`; split `di/AppModule.kt` into `DatabaseModule` + `RepositoryModule`; remove orphaned `LockScreenReceiver`; update `AndroidManifest.xml`. |
| 3 | Namespace y Firebase | ~80 | ~200 | Change Kotlin package and `applicationId` from `com.example.parentalcontrol` → `com.tudominio.parentalcontrol` across all source files and manifest. Regenerate `google-services.json` from Firebase Console (**manual user step**). |
| 4 | NavGraph y limpieza | 3 | ~150 | Extract `ui/navigation/NavGraph.kt` from the `when` block in `MainActivity.kt`. Remove duplicate `AppDatabase` provider (companion in `AppDatabase.kt` + Hilt provider in `AppModule.kt`). |

### Out of Scope

- **JVM 17 → 21**: CI pins Java 17; cannot bump without breaking the CI matrix.
- **Manifest entries already correct**: `device_admin_policies.xml`, `accessibility_service_config.xml`, and the 4 permission helpers in `PermissionHelper.kt` are already aligned — no change.
- **`google-services.json` content**: only regenerated in PR 3 because of the new package; otherwise untouched.
- **Guide §0.x policy contracts**: already implemented in prior sessions; this change is structural only.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
None.

This is a pure refactor + version-alignment change. No spec-level behavior changes; existing requirements (enforcement levels §0.2, JSON policy contract §0.3, RLS, outbox sync) remain semantically identical.

## Approach

Apply the 4 PRs sequentially. Each PR is independently mergeable, runs the full quality gate (`./gradlew detekt ktlintCheck lint testDebugUnitTest assembleDebug`), and targets the previous PR's branch (feature-branch chain) so reviewers see a clean ≤400-line diff per slice. PR 1 is the highest risk; ship it first behind the CI matrix on Java 17 so version drift surfaces before structural changes layer on top.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `gradle/libs.versions.toml` | Modified | Version bumps (PR 1) |
| `app/build.gradle.kts` | Modified | `compileSdk`/`targetSdk` (PR 1), `namespace`/`applicationId` (PR 3) |
| `app/src/main/AndroidManifest.xml` | Modified | New receiver/service class names (PR 2), new package (PR 3) |
| `app/src/main/java/.../admin/` | Modified | `DeviceAdminReceiver` → `ParentalDeviceAdminReceiver` (PR 2) |
| `app/src/main/java/.../service/` | Modified | `ForegroundAppService` → `AppMonitorService`, `UsageTrackingService` → `MonitorForegroundService` (PR 2) |
| `app/src/main/java/.../boot/` | Removed | Moved to `receiver/` (PR 2) |
| `app/src/main/java/.../receiver/` | New | `BootReceiver.kt` and `ParentalDeviceAdminReceiver.kt` (PR 2) |
| `app/src/main/java/.../data/local/` | Modified | Split into `data/db/ParentalDatabase.kt` + `data/model/` (PR 2) |
| `app/src/main/java/.../di/AppModule.kt` | Split | `DatabaseModule.kt` + `RepositoryModule.kt` (PR 2) |
| `app/src/main/java/.../ui/` | Modified | New `navigation/NavGraph.kt` (PR 4) |
| `app/src/main/java/.../MainActivity.kt` | Modified | Delegates routing to `NavGraph` (PR 4) |
| `app/google-services.json` | Regenerated | New package id (PR 3, **manual**) |
| All ~80 Kotlin source files | Modified | Package declaration (PR 3) |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Kotlin 2.0→2.3 and Compose BOM 2024→2026 deprecations break compilation | High | PR 1 ships first; CI runs full test + lint suite; no app code change in same PR. |
| `dataStore` 1.2.0 → 1.1.2 is a downgrade | Low | Guide pins 1.1.2; verify call sites are 1.1.2-compatible before bumping down. |
| Hilt 2.56 KSP support requires exact KSP version match | Med | Pin KSP to `2.3.0-1.0.24` alongside Hilt 2.56; CI catches mismatch. |
| `google-services.json` regenerated with wrong package breaks Firebase init | Med | User runs manual verification (`./gradlew assembleDebug` + smoke run) before PR 3 merge. |
| Renaming 80+ files in PR 3 produces merge conflicts with concurrent work | Med | PR 3 is the third in the chain; PRs 1–2 do not move files, so diff is text-only package edits. |
| Removing `LockScreenReceiver` (orphaned) breaks a hidden call site | Low | Grep confirms no references in code or manifest; only declared in `BootReceiver.kt`. |
| Chained PR diffs show prior slices in GitHub view | Med | Each child PR rebases onto the previous branch tip before opening; verify clean diff in PR description. |

## Rollback Plan

- **Per PR**: each PR is a single atomic commit on a feature branch off master. Revert is `git revert <sha>` on master; CI re-runs the full quality gate.
- **Full rollback**: `git revert` PRs 4→3→2→1 in reverse merge order. The Firebase `google-services.json` reversion must be the original (pre-PR 3) file; user keeps a local backup before PR 3.
- **Feature branch chain**: if a mid-chain PR fails, only that PR and its successors are dropped; earlier merged PRs stand.

## Dependencies

- **Manual (PR 3)**: user must regenerate `google-services.json` from Firebase Console with package `com.tudominio.parentalcontrol` and place it in `app/`.
- **CI**: Java 17 must stay the CI JDK (do not bump to 21 — out of scope and breaks the matrix).
- **Firebase project**: must be configured to accept the new application id; if not, PR 3 cannot complete.

## Success Criteria

- [ ] `./gradlew detekt ktlintCheck lint testDebugUnitTest assembleDebug` passes on every PR.
- [ ] App boots, signs in via Supabase, and runs smoke flow (pairing + foreground monitor) on each merged PR.
- [ ] `app/build.gradle.kts` `namespace` and `applicationId` equal `com.tudominio.parentalcontrol`.
- [ ] All Kotlin source files use the new package; no `com.example.parentalcontrol` references remain (grep clean).
- [ ] `AndroidManifest.xml` references the renamed receivers/services; `LockScreenReceiver` is gone.
- [ ] `NavGraph.kt` exists and `MainActivity` delegates routing to it; duplicate `AppDatabase` provider is removed.
- [ ] CI instrumented tests pass on API 28/31/35 (no local emulator required).
