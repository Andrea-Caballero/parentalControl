# Tasks: align-with-guia-fedora44

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~650 total across 4 PRs (~50 + ~250 + ~200 + ~150) |
| 400-line budget risk | Low (each PR independently ≤400) |
| Chained PRs recommended | Yes (already decided) |
| Suggested split | PR 1 → PR 2 → PR 3 → PR 4 (feature-branch-chain) |
| Delivery strategy | auto-chain (4-PR chain accepted by user) |
| Chain strategy | feature-branch-chain (PR #N base = PR #N-1 branch tip) |
| DataStore deviation | keep 1.2.0 (do not downgrade to 1.1.2) — call out in PR 1 body |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: feature-branch-chain
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Base branch | Notes |
|------|------|-----------|-------------|-------|
| 1 | Bump toolchain (Kotlin/AGP/Compose BOM/Hilt/Room/etc.) + compileSdk/targetSdk 35→36 | PR 1 | `master` | No app code touched; risks = deprecation warnings |
| 2 | Rename receivers/services, move BootReceiver, split AppDatabase + DI modules, update manifest | PR 2 | `feature/align-pr1-toolchain` | Touches 4 directories + manifest; refactor only |
| 3 | Rename package `com.example.parentalcontrol` → `com.tudominio.parentalcontrol` (~95 .kt files) + namespace + applicationId + manual `google-services.json` | PR 3 | `feature/align-pr2-structure` | Manual Firebase step is on the critical path |
| 4 | Extract `NavGraph.kt` from `MainActivity.kt`; remove duplicate `AppDatabase` provider | PR 4 | `feature/align-pr3-namespace` | Pure extraction + dedup |

---

## PR 1 — Toolchain y SDK

Risk anchor. No app code changes; this PR exists to surface Kotlin 2.3 / AGP 8.9 / Compose BOM 2026 deprecations in isolation before structural changes layer on top.

## 1. Bump `[versions]` refs in `gradle/libs.versions.toml`
- [ ] Step 1: In `gradle/libs.versions.toml` lines 1-6, add new refs: `agp = "8.9.2"`, `lifecycle = "2.9.0"`, `hilt = "2.56"`, `room = "2.7.1"`, `datastore = "1.2.0"` (deviation — keep current), `work = "2.10.1"`, `coroutines = "1.10.2"`.
- [ ] Step 2: Bump existing refs at lines 2-5: `kotlin` 2.0.21→2.3.0, `ksp` 2.0.21-1.0.28→2.3.0-1.0.24, `compose-bom` 2024.10.01→2026.05.00, `navigation` 2.8.0→2.9.0.
- [ ] Step 3: Run `./gradlew help` — confirms TOML parses and catalog resolves. **Fail-fast gate.**

## 2. Bump `[plugins]` in `gradle/libs.versions.toml`
- [ ] Step 1: At line 9, change `android-application` from inline `version = "8.5.2"` to `version.ref = "agp"` (now 8.9.2 via the new ref).
- [ ] Step 2: At line 15, change `hilt` from inline `version = "2.52"` to `version.ref = "hilt"` (now 2.56).
- [ ] Step 3: Run `./gradlew :app:dependencies --configuration debugRuntimeClasspath | head -100` — confirms new versions resolve.

## 3. Convert inline versions to refs in `[libraries]`
- [ ] Step 1: In `gradle/libs.versions.toml` lines 22-25, add refs to `core-ktx` (new `coreKtx` ref, value `"1.16.0"`), `lifecycle-runtime-ktx` (`lifecycle` ref), `lifecycle-viewmodel-compose` (`lifecycle` ref), `activity-compose` (new `activityCompose` ref, value `"1.10.1"`).
- [ ] Step 2: At lines 39-41, convert `room-runtime`, `room-ktx`, `room-compiler` to `version.ref = "room"`.
- [ ] Step 3: At line 42, leave `datastore-preferences` as inline `"1.2.0"` (deviation — see header); do NOT change to ref.
- [ ] Step 4: At line 43, convert `work-runtime-ktx` to `version.ref = "work"`.
- [ ] Step 5: At lines 47-49, convert `hilt-android`, `hilt-android-compiler`, `hilt-android-testing` to `version.ref = "hilt"`.
- [ ] Step 6: At lines 50-52, leave `hilt-work`, `hilt-compiler`, `hilt-navigation-compose` as inline `"1.2.0"` (Hilt Ext 1.2.0 stays; matches guide).
- [ ] Step 7: At line 100, `kotlinx-coroutines-test` already refs `kotlinx-coroutines` — no change.

## 4. Bump `compileSdk` and `targetSdk` in `app/build.gradle.kts`
- [ ] Step 1: At `app/build.gradle.kts:14`, change `compileSdk = 35` → `36`.
- [ ] Step 2: At `app/build.gradle.kts:19`, change `targetSdk = 35` → `36`.
- [ ] Step 3: Leave `jvmTarget = "17"` (line 31) and `JavaVersion.VERSION_17` (lines 35-36) untouched — CI runs Java 17.

## 5. Critical compile gate
- [ ] Step 1: Run `./gradlew :app:assembleDebug` — **this is the gate that surfaces Kotlin 2.3 deprecations**. Capture full output.
- [ ] Step 2: If compile fails with deprecation errors (likely candidates: `kotlinOptions` block (line 29-32 already has `@Suppress("DEPRECATION")`), lifecycle 2.7→2.9 API changes, navigation 2.8→2.9 route DSL, KSP 2.3.0-1.0.24 incompatibility), fix call-sites in this PR **only if total diff stays ≤80 lines added**.
- [ ] Step 3: If deprecation fixes would push PR 1 over 80 lines, **stop and split**: open PR 1a (versions + SDK only, no app code) + PR 1b (deprecation fixes), notify user, and resume chain from PR 1b.
- [ ] Step 4: Run `./gradlew :app:testDebugUnitTest` — confirms Hilt 2.56 / Room 2.7.1 KSP processors regenerate cleanly.
- [ ] Step 5: Run `./gradlew detekt ktlintCheck lint` — Kotlin 2.3 may enable new detekt rules; fix in PR 1 if so.

## 6. PR 1 — Definition of Done
- [ ] All 9 version bumps applied in `gradle/libs.versions.toml` (kotlin, agp, ksp, compose-bom, hilt, room, navigation, lifecycle, work); coroutines bumped to 1.10.2; DataStore stays 1.2.0.
- [ ] `compileSdk = 36` and `targetSdk = 36` in `app/build.gradle.kts`.
- [ ] `./gradlew assembleDebug` green.
- [ ] `./gradlew testDebugUnitTest` green.
- [ ] `./gradlew detekt ktlintCheck lint` green.
- [ ] Branch `feature/align-pr1-toolchain` pushed; PR body calls out DataStore 1.2.0 deviation explicitly.

---

## PR 2 — Estructura de archivos

Refactor only — no behavior change. Net file count goes up (split) but total lines per PR stay ≤400.

## 1. Rename receiver and service classes
- [ ] Step 1: `git mv app/src/main/java/com/example/parentalcontrol/admin/DeviceAdminReceiver.kt app/src/main/java/com/example/parentalcontrol/admin/ParentalDeviceAdminReceiver.kt` (preserves history).
- [ ] Step 2: Inside the renamed file, change `class DeviceAdminReceiver` → `class ParentalDeviceAdminReceiver` (line 12) and `const val TAG = "DeviceAdminReceiver"` → `"ParentalDeviceAdminReceiver"` (line 15).
- [ ] Step 3: `git mv app/src/main/java/com/example/parentalcontrol/accessibility/ForegroundAppService.kt app/src/main/java/com/example/parentalcontrol/accessibility/AppMonitorService.kt`.
- [ ] Step 4: Inside the renamed file, change `class ForegroundAppService` → `class AppMonitorService` (line 9) and update the `companion object` identifier `_appInForeground`/`appInForeground` names if they reference the old class name (lines 12-13).
- [ ] Step 5: `git mv app/src/main/java/com/example/parentalcontrol/service/UsageTrackingService.kt app/src/main/java/com/example/parentalcontrol/service/MonitorForegroundService.kt`.
- [ ] Step 6: Inside the renamed file, change `class UsageTrackingService` → `class MonitorForegroundService` (line ~31). Update self-references in `BootReceiver.kt:67,78` and `UsageTrackingService.kt:15` import + internal usage.

## 2. Move `BootReceiver` and delete `LockScreenReceiver`
- [ ] Step 1: `git mv app/src/main/java/com/example/parentalcontrol/boot/BootReceiver.kt app/src/main/java/com/example/parentalcontrol/receiver/BootReceiver.kt` (creates the `receiver/` directory).
- [ ] Step 2: Update the `package` declaration at line 1 from `com.example.parentalcontrol.boot` → `com.example.parentalcontrol.receiver`.
- [ ] Step 3: Update the `import com.example.parentalcontrol.service.UsageTrackingService` at line 8 → `…service.MonitorForegroundService` (renamed in PR 2 task 1).
- [ ] Step 4: Update the `import com.example.parentalcontrol.workers.WorkScheduler` and `WorkerInitializer` (lines 9-10) — package stays the same, no change.
- [ ] Step 5: **Delete the nested `LockScreenReceiver` class** at lines 92-121 of the moved file (the orphan class, NOT a separate file). Keep the file's outer `BootReceiver` class (lines 20-90) intact.
- [ ] Step 6: Delete the now-empty `app/src/main/java/com/example/parentalcontrol/boot/` directory (`rmdir` if empty after the `git mv`).

## 3. Split `data/local/AppDatabase.kt` into `data/db/ParentalDatabase.kt` + `data/model/*` + `data/db/*` (DAOs)
- [ ] Step 1: Create `app/src/main/java/com/example/parentalcontrol/data/model/` directory. Move the 7 entity classes currently embedded in `AppDatabase.kt:11-87` into 7 new files: `PolicyEntity.kt`, `AppPolicyEntity.kt`, `WindowEntity.kt` (non-Entity, line 28-32), `GrantEntity.kt`, `UsageTodayEntity.kt`, `OutboxEntity.kt`, `TimeRequestEntity.kt`, `BehavioralEventEntity.kt`. Each file gets `package com.example.parentalcontrol.data.model` + its class.
- [ ] Step 2: Create `app/src/main/java/com/example/parentalcontrol/data/db/` directory. Move the 7 DAO interfaces from `AppDatabase.kt:93-251` into separate files: `PolicyDao.kt`, `AppPolicyDao.kt`, `GrantDao.kt`, `UsageDao.kt`, `OutboxDao.kt`, `TimeRequestDao.kt`. The 8th DAO (`BehavioralEventDao`) is already at `data/local/BehavioralEventDao.kt` — `git mv` it to `data/db/BehavioralEventDao.kt` and update its package.
- [ ] Step 3: Move `data/local/Converters.kt` → `data/db/Converters.kt` (ParentalDatabase needs the `@TypeConverters` class).
- [ ] Step 4: Leave `data/local/LocalDataSource.kt` and `data/local/PairedDevicesStore.kt` in `data/local/` (design does not move them).
- [ ] Step 5: Create `app/src/main/java/com/example/parentalcontrol/data/db/ParentalDatabase.kt` containing ONLY the `@Database(entities = [PolicyEntity::class, AppPolicyEntity::class, GrantEntity::class, UsageTodayEntity::class, OutboxEntity::class, TimeRequestEntity::class, BehavioralEventEntity::class], version = 4, exportSchema = true)` + `@TypeConverters(Converters::class)` + `abstract class ParentalDatabase : RoomDatabase()` with the 7 abstract DAO getters. Import entities from `data.model.*` and DAOs/`Converters` from same package.
- [ ] Step 6: Delete the original `app/src/main/java/com/example/parentalcontrol/data/local/AppDatabase.kt`.
- [ ] Step 7: Update all 14 files that import `com.example.parentalcontrol.data.local.AppDatabase` (per pre-PR grep) to import `com.example.parentalcontrol.data.db.ParentalDatabase` instead.

## 4. Split `di/AppModule.kt` into `DatabaseModule` + `RepositoryModule`
- [ ] Step 1: Create `app/src/main/java/com/example/parentalcontrol/di/DatabaseModule.kt` containing only the `provideAppDatabase` provider (lines 21-28 of current `AppModule.kt`). Rename function to `provideDatabase` and return type to `ParentalDatabase`. Update import to `data.db.ParentalDatabase`.
- [ ] Step 2: Add DAO providers in `DatabaseModule.kt` (per guide §9.6): `providePolicyDao`, `provideAppPolicyDao`, `provideGrantDao`, `provideUsageDao`, `provideOutboxDao`, `provideTimeRequestDao`, `provideBehavioralEventDao` (each takes `ParentalDatabase` and returns the DAO).
- [ ] Step 3: Create `app/src/main/java/com/example/parentalcontrol/di/RepositoryModule.kt` containing the remaining providers from `AppModule.kt:30-44`: `provideTimeProvider`, `provideSyncManager`, `provideHealthMonitor` (no change in logic, just re-home them).
- [ ] Step 4: Delete the original `app/src/main/java/com/example/parentalcontrol/di/AppModule.kt`.

## 5. Update `AndroidManifest.xml` with new class names
- [ ] Step 1: At line 46, change `.accessibility.ForegroundAppService` → `.accessibility.AppMonitorService`.
- [ ] Step 2: At line 55, change `.service.UsageTrackingService` → `.service.MonitorForegroundService`.
- [ ] Step 3: At line 69, change `.admin.DeviceAdminReceiver` → `.admin.ParentalDeviceAdminReceiver`.
- [ ] Step 4: At line 81, change `.boot.BootReceiver` → `.receiver.BootReceiver`.
- [ ] Step 5: Confirm there is NO `<receiver … android:name=".boot.LockScreenReceiver">` block to remove — the class is nested in the moved file (deleted in task 2). Manifest never declared it.

## 6. Validation
- [ ] Step 1: Run `./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck lint`.
- [ ] Step 2: Run `grep -rn "com.example.parentalcontrol.boot" app/src/main/` — must return empty.
- [ ] Step 3: Run `grep -rn "LockScreenReceiver" app/src/main/` — must return empty.
- [ ] Step 4: Run `grep -rn "AppDatabase\b" app/src/main/java/com/example/parentalcontrol/data/` — must show `ParentalDatabase` only.

## 7. PR 2 — Definition of Done
- [ ] All 3 class renames applied (DeviceAdminReceiver, ForegroundAppService, UsageTrackingService).
- [ ] `BootReceiver.kt` moved to `receiver/` package; `LockScreenReceiver` nested class deleted; `boot/` directory removed.
- [ ] `AppDatabase.kt` split into `ParentalDatabase.kt` + 8 entity files + 7 DAO files + `Converters.kt` under `data/db/` and `data/model/`.
- [ ] `AppModule.kt` split into `DatabaseModule.kt` + `RepositoryModule.kt`.
- [ ] `AndroidManifest.xml` references updated.
- [ ] Quality gate green: `assembleDebug testDebugUnitTest detekt ktlintCheck lint`.
- [ ] Branch `feature/align-pr2-structure` rebased onto `feature/align-pr1-toolchain` tip before opening PR.

---

## PR 3 — Namespace y Firebase

Text-only refactor (package declarations + 2 gradle properties + manifest references) + one **manual** user step (Firebase Console).

## 1. Update `namespace` and `applicationId` in `app/build.gradle.kts`
- [ ] Step 1: At `app/build.gradle.kts:13`, change `namespace = "com.example.parentalcontrol"` → `namespace = "com.tudominio.parentalcontrol"`.
- [ ] Step 2: At `app/build.gradle.kts:17`, change `applicationId = "com.example.parentalcontrol"` → `applicationId = "com.tudominio.parentalcontrol"`.
- [ ] Step 3: At `app/build.gradle.kts:22`, change `testInstrumentationRunner = "com.example.parentalcontrol.MyHiltTestRunner"` → `"com.tudominio.parentalcontrol.MyHiltTestRunner"`.
- [ ] Step 4: Run `./gradlew :app:assembleDebug` to confirm Gradle picks up the new namespace root before bulk-editing Kotlin files.

## 2. Bulk-rewrite `package` declarations across all ~95 Kotlin source files
- [ ] Step 1: Run a scripted rewrite (e.g., `find app/src/main/java -name "*.kt" -exec sed -i 's|^package com\.example\.parentalcontrol$|package com.tudominio.parentalcontrol|' {} +`).
- [ ] Step 2: Run a follow-up scripted rewrite on `import` lines: `find … -exec sed -i 's|^import com\.example\.parentalcontrol\.|import com.tudominio.parentalcontrol.|' {} +`.
- [ ] Step 3: For non-package, non-import references (e.g., string literals in `ParentalControlApp.kt` line 26, `MyHiltTestRunner` references, fully-qualified class names in DI providers or `BootReceiver.kt` `UsageTrackingService::class.java` lookups), manually inspect `grep -rn "com\.example\.parentalcontrol" app/src/main/java` and rewrite.
- [ ] Step 4: Run `./gradlew :app:assembleDebug` again. Resolve any remaining fully-qualified reference errors one file at a time.

## 3. Update `AndroidManifest.xml` package references
- [ ] Step 1: At `AndroidManifest.xml:6-7`, change the `<instrumentation>` block to `android:name="com.tudominio.parentalcontrol.MyHiltTestRunner"` and `android:targetPackage="com.tudominio.parentalcontrol"`.
- [ ] Step 2: At `AndroidManifest.xml:26`, change `android:name="com.example.parentalcontrol.ParentalControlApp"` → `android:name="com.tudominio.parentalcontrol.ParentalControlApp"`.
- [ ] Step 3: All `.X` relative refs (e.g., `.MainActivity` at line 34, `.accessibility.AppMonitorService`, `.service.MonitorForegroundService`, `.admin.ParentalDeviceAdminReceiver`, `.receiver.BootReceiver`, `.overlay.BlockOverlayService`, `.push.FcmPushService`) resolve automatically against the new namespace — no edits required.

## 4. Manual Firebase step (user)
- [ ] Step 1: User opens Firebase Console → Project Settings → Android app `com.example.parentalcontrol` → **Edit package name** → enter `com.tudominio.parentalcontrol` → download new `google-services.json`.
- [ ] Step 2: User **backs up** the current `app/google-services.json` to a local safe location.
- [ ] Step 3: User replaces `app/google-services.json` with the new file.
- [ ] Step 4: User runs `./gradlew :app:assembleDebug` and a smoke run on device to confirm Firebase init logs show the new package.

## 5. Validation
- [ ] Step 1: Run `grep -rn "com\.example\.parentalcontrol" app/ openspec/ 2>/dev/null` — must return empty (excluding historical proposal/design if needed).
- [ ] Step 2: Run `./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck lint` — must pass.
- [ ] Step 3: Confirm Firebase init log on smoke run shows `com.tudominio.parentalcontrol`.

## 6. PR 3 — Definition of Done
- [ ] `namespace` and `applicationId` are `com.tudominio.parentalcontrol` in `app/build.gradle.kts`.
- [ ] All ~95 Kotlin files use `package com.tudominio.parentalcontrol.…`; all `import` lines updated.
- [ ] `AndroidManifest.xml` `<instrumentation>` and `<application android:name=…>` reference new package.
- [ ] User has regenerated `app/google-services.json` with the new package and smoke-tested.
- [ ] Quality gate green.
- [ ] Branch `feature/align-pr3-namespace` rebased onto `feature/align-pr2-structure` tip.

---

## PR 4 — NavGraph y limpieza

Pure extraction + dedup. No new versions, no new packages. Depends on PR 3 being merged so the imports use the final package name.

## 1. Extract `ui/navigation/NavGraph.kt` from `MainActivity.kt`
- [x] Step 1: Create `app/src/main/java/com/tudominio/parentalcontrol/ui/navigation/NavGraph.kt` with `package com.tudominio.parentalcontrol.ui.navigation`.
- [x] Step 2: Define `@Composable fun NavGraph()` containing the full `when { … }` block currently at `MainActivity.kt:38-95`. Move all imports needed by the body (lines 9-20) to `NavGraph.kt`.
- [x] Step 3: Hoist the `isPaired` / `isChildDevice` decision out of the `when` if needed by passing them as parameters, OR pass `navController` so internal `var selectedMode by remember` and `var showExtraTime by remember` (lines 40, 67) move with the composable.
- [x] Step 4: Move the `private fun restartActivity()` (lines 100-102) into `NavGraph.kt` as a top-level private function, OR delete it if the new structure doesn't need it.

## 2. Wire `MainActivity.kt` to delegate
- [x] Step 1: In `MainActivity.kt:36-97`, replace the entire `setContent { ParentalControlTheme { when { … } } }` block with `setContent { ParentalControlTheme { AppNavHost(...) } }`. (AppNavHost bridges to NavGraph and resolves the auth state + Hilt VMs.)
- [x] Step 2: Remove now-unused imports from `MainActivity.kt:9-20` (keep only `Bundle`, `ComponentActivity`, `setContent`, `enableEdgeToEdge`, `ParentalControlTheme`, `AppNavHost`, `AndroidEntryPoint`).
- [x] Step 3: `MainActivity.kt` is now thin: 65 lines including KDoc, with a 5-line `setContent` body. The wiring details live in `ui/navigation/AppNavHost.kt` (75 lines).

## 3. Remove duplicate `AppDatabase` provider
- [x] Step 1: Companion singleton in `ParentalDatabase.kt` removed (commit `8132502`); Hilt `DatabaseModule.provideDatabase` is the sole constructor.
- [x] Step 2: No `companion object` `getInstance(context)` or `INSTANCE` field remains. Migration constants (`DATABASE_NAME`, `MIGRATION_4_5`, `MIGRATION_5_6`) stay on the companion because the migration tests reference them directly.
- [x] Step 3: `DatabaseInitializationTest.no_static_getInstance_call_sites_remain_in_main_source` + `DatabaseCallerMigrationTest` pin the contract; both green.
- [x] Step 4: `DatabaseModule.kt` references `ParentalDatabase.DATABASE_NAME` (single source of truth).

## 4. Validation
- [x] Step 1: Run `./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck`. (Manual smoke run deferred to the orchestrator / device — dev box has no emulator per `openspec/config.yaml` testing.gotchas.)
- [ ] Step 2: Smoke run on device: parent flow lands on `DashboardScreen`, child flow on `PairingScreen` → `ChildStatusScreen` → `ExtraTimeScreen`, unpaired flow on `OnboardingScreen`. *(Manual — outside apply phase scope.)*
- [x] Step 3: `MainActivityRoutingTest` confirms no legacy routing `when` block in `MainActivity.kt` and that `setContent` body is ≤ 5 lines. `NavGraphTest` confirms the 3 spec routes render correctly.

## 5. PR 4 — Definition of Done
- [x] `ui/navigation/NavGraph.kt` exists; `@Composable fun NavGraph(...)` holds the full routing logic.
- [x] `MainActivity.kt` reduced to 65 lines (with KDoc); delegates to `AppNavHost` which delegates to `NavGraph`.
- [x] Duplicate `AppDatabase` companion singleton removed (commit `8132502`); Hilt module is the only provider.
- [x] Quality gate green (assembleDebug + testDebugUnitTest + detekt + ktlintCheck).
- [ ] Branch `feature/align-pr4-navgraph` rebased onto `feature/align-pr3-namespace` tip. *(Base is `master` per orchestrator's chain-strategy update — PR 3 is already merged, so the PR targets `master` directly.)*

---

## Sequencing Concerns Discovered

1. **DataStore deviation**: guide pins 1.1.2; project uses 1.2.0. **Keep 1.2.0** (per user + design §6). Do NOT touch line 42 of `libs.versions.toml`.
2. **Empty `data/local/entity/` directory**: design says "move entities under `data/local/` to `data/model/`" but the subdir is empty — entities are inlined in `AppDatabase.kt:11-87`. PR 2 task 3 explicitly extracts them, doesn't move a non-existent file.
3. **`LockScreenReceiver` is a nested class** (BootReceiver.kt:96-121), not a separate file. PR 2 task 2 deletes the class block, NOT a file. The "manifest removal" step is a no-op because the manifest never declared it.
4. **`hiltExt = 1.2.0` stays** — guide uses the `hiltExt` ref for `hilt-work`/`hilt-compiler`/`hilt-navigation-compose`. The current TOML already inlines them. PR 1 task 3 step 6 leaves them inline (matches current state and guide value).
5. **Core-ktx and activity-compose** are bumped in the guide (`coreKtx 1.16.0`, `activityCompose 1.10.1`) but the user did NOT list them in PR 1's bump list. **Out of scope for this change** — defer to a future guide-correction PR.
6. **Manual Firebase step in PR 3 is on the critical path**: PR 3 cannot merge until the user regenerates `google-services.json` and smoke-tests. Flag this in the PR 3 body so reviewers don't block on user availability.
7. **PR 1 deprecation risk**: Kotlin 2.3 may surface deprecations in `kotlinOptions`, lifecycle 2.7→2.9, or navigation 2.8→2.9. PR 1 task 5 includes a hard split gate (PR 1a versions / PR 1b deprecations) if the diff exceeds 80 lines.
8. **PR 4 final size**: `MainActivity.kt` is 103 lines today; the extraction alone is ~80 lines moved + ~25 in MainActivity. Duplicate `AppDatabase` removal is ~20 lines deleted. Net diff for PR 4 ≈ 100 lines (well under 400).
9. **PR 3 file count**: proposal says "~80" but actual count is 95 Kotlin files. The scripted `sed` approach scales; no per-file tasks needed.
10. **PR 1 risk anchor: pinned KSP version**: `ksp = "2.3.0-1.0.24"` must match Kotlin `2.3.0` exactly. If KSP `1.0.24` is not yet published for Kotlin 2.3.0 on the build date, fall back to the latest matching `2.3.0-1.0.x` and document the chosen version in the PR body.
