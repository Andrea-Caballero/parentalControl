# Design: align-with-guia-fedora44

## Technical Approach

Four sequential PRs. PR 1 is the risk anchor (Kotlin 2.0→2.3, AGP 8.5→8.9, Compose BOM 2024→2026) and ships first to surface deprecations in isolation. PRs 2–4 layer pure refactors on top of a stable toolchain. No app-code change accompanies PR 1; PR 2 is the only PR that moves files on disk; PR 3 is text-only (package declarations); PR 4 is extraction. CI is the contract: every PR runs `detekt ktlintCheck lint testDebugUnitTest assembleDebug` on Java 17. JVM target stays 17 (CI matrix, out of scope).

## PR 1 — Toolchain y SDK (detailed)

### File actions

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Modify — version bumps, add missing `[versions]` refs |
| `app/build.gradle.kts` | Modify — `compileSdk`/`targetSdk` 35→36 |

### `libs.versions.toml` edits (in order)

The current TOML mixes refs and inline strings. PR 1 normalizes to refs for every dependency the guide touches, then bumps.

1. **Add to `[versions]`** (currently missing): `agp = "8.9.2"`, `lifecycle = "2.9.0"`, `hilt = "2.56"`, `room = "2.7.1"`, `datastore = "1.2.0"` (DEVIATION — see §6), `work = "2.10.1"`.
2. **Bump existing refs**: `kotlin 2.0.21→2.3.0`, `ksp 2.0.21-1.0.28→2.3.0-1.0.24`, `compose-bom 2024.10.01→2026.05.00`, `navigation 2.8.0→2.9.0`.
3. **Plugin bumps**: `agp 8.5.2→8.9.2` (uses new `agp` ref), `hilt 2.52→2.56` (uses new `hilt` ref).
4. **Inline → ref conversion** (for libs the guide names but are currently inline): `core-ktx` 1.13.0, `lifecycle-runtime-ktx` 2.7.0→ref, `lifecycle-viewmodel-compose` 2.7.0→ref, `activity-compose` 1.8.0, `room-runtime/-ktx/-compiler` 2.6.0→ref, `work-runtime-ktx` 2.8.1→ref, all `hilt-*` 2.52→ref, `hilt-work`/`hilt-compiler`/`hilt-navigation-compose` 1.2.0 (keep — guide uses `hiltExt` 1.2.0).

**Why this order**: version refs first, then bumps, then conversions. A failed refactor in step 4 has a tight blast radius (build error pointing at the TOML), whereas a half-applied bump in step 2 may produce a confusing transitive-resolution error.

### `app/build.gradle.kts` edits

- `compileSdk = 35` → `36`
- `targetSdk = 35` → `36`
- JVM target, `minSdk = 26`, `namespace`, `applicationId` — **untouched** (PR 3 owns them).
- No `compose-bom` import change needed; it already references `libs.compose.bom`.

### Dependencies & ordering

`libs.versions.toml` must be edited **before** Gradle sync. `app/build.gradle.kts` can be edited in the same commit; Gradle does not enforce an order at the file level.

### Validation (post-edit, in order)

1. `./gradlew help` — confirms TOML parses and version catalog resolves. **Fail-fast on syntax.**
2. `./gradlew :app:dependencies --configuration debugRuntimeClasspath | head -100` — confirms new versions resolve from network/Maven cache.
3. `./gradlew :app:assembleDebug` — **the critical gate**. Kotlin 2.3 may surface deprecation warnings as errors if the project has `-Werror`; resolve call-site deprecations in this PR (acceptable) or split into PR 1a (versions) + PR 1b (deprecation fixes) if the diff balloons past 80 lines.
4. `./gradlew :app:testDebugUnitTest` — confirms Hilt/Room KSP processors regenerated correctly.
5. `./gradlew detekt ktlintCheck lint` — gates. Kotlin 2.3 may enable new detekt rules; if so, fix in PR 1.

### Rollback

Single atomic commit on `feature/align-pr1-toolchain`. Revert is `git revert <sha>` on master. The PR touches only the version catalog and two `compileSdk`/`targetSdk` integers — a revert restores the exact pre-PR state. No data, no Firebase, no DB schema involved.

## PR 2 — Estructura de archivos (summary)

### File actions

| Action | Path |
|--------|------|
| Rename file | `admin/DeviceAdminReceiver.kt` → `admin/ParentalDeviceAdminReceiver.kt` (class rename inside) |
| Rename file | `accessibility/ForegroundAppService.kt` → `accessibility/AppMonitorService.kt` |
| Rename file | `service/UsageTrackingService.kt` → `service/MonitorForegroundService.kt` |
| Move | `boot/BootReceiver.kt` → `receiver/BootReceiver.kt` (and strip nested `LockScreenReceiver` class at line 96) |
| Move + split | `data/local/AppDatabase.kt` → `data/db/ParentalDatabase.kt`; move entities under `data/local/` to `data/model/` |
| Move | DAOs under `data/local/` → `data/db/` |
| Split | `di/AppModule.kt` → `di/DatabaseModule.kt` + `di/RepositoryModule.kt` |
| Modify | `AndroidManifest.xml` (receiver/service `.name` attributes + remove LockScreenReceiver) |

### Edit order

1. Move files (git mv where possible to preserve history). 2. Update `package` declarations. 3. Update internal `import` statements. 4. Update manifest. 5. Run Hilt graph check. 6. Detekt/Ktlint.

### Validation

`./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck lint`. Confirm no `BootReceiver` reference to `LockScreenReceiver` survives; grep `com.example.parentalcontrol.boot` returns empty.

### Rollback

`git revert`. If Hilt DI fails, the failure surfaces as `:app:checkDebugAarMetadata` or KSP error citing the missing provider — revert the split commit and re-merge after fixing the module boundary.

## PR 3 — Namespace y Firebase (summary)

### File actions

| File | Action |
|------|--------|
| All ~80 Kotlin files | Modify `package com.example.parentalcontrol` → `com.tudominio.parentalcontrol` |
| `app/build.gradle.kts` | `namespace` + `applicationId` → `com.tudominio.parentalcontrol` |
| `app/src/main/AndroidManifest.xml` | All `.name=".X"` relative refs resolve via new namespace |
| `app/google-services.json` | **Manual** — user regenerates in Firebase Console |

### Edit order

1. Update `app/build.gradle.kts` `namespace` first. 2. Run `./gradlew :app:assembleDebug` to confirm Gradle picks up the new package root. 3. Scripted `sed` across `app/src/main/java/**.kt` for `package` + `import` lines. 4. `./gradlew :app:assembleDebug` again. 5. User replaces `google-services.json`. 6. Final `assembleDebug` + smoke run.

### Validation

`./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck lint`. Grep `com.example.parentalcontrol` returns empty across the repo. Firebase init logs show the new package.

### Rollback

`git revert` reverts code only; user must also restore the pre-PR 3 `google-services.json` from their local backup. If the namespace is half-applied (some files migrated, some not), grep is the source of truth: any remaining `com.example.parentalcontrol` is the rollback target.

## PR 4 — NavGraph y limpieza (summary)

### File actions

| File | Action |
|------|--------|
| `ui/navigation/NavGraph.kt` | Create — extract `when` block from `MainActivity` |
| `MainActivity.kt` | Modify — replace inline `when` with `NavGraph()` call |
| `data/local/AppDatabase.kt` (or `data/db/ParentalDatabase.kt` after PR 2) | Modify — remove the companion-object `getDatabase()` provider (duplicate of Hilt module) |

### Edit order

1. Create `NavGraph.kt` (pure addition). 2. Wire `MainActivity` to call it. 3. Build, confirm no route regression. 4. Remove duplicate `AppDatabase` companion provider in a separate commit so the diff is reviewable. 5. Detekt/Ktlint.

### Validation

`./gradlew :app:assembleDebug testDebugUnitTest detekt ktlintCheck lint`. Smoke: app launches and routes to `Dashboard`/`Apps`/`Settings` identically.

### Rollback

Per-commit revert. If NavGraph extraction breaks a route, the failing destination is identified by the route string in the crash — revert the MainActivity commit only; keep `NavGraph.kt` for the next attempt.

## PR Dependencies

```
PR 1 (toolchain) ──green──▶ PR 2 (structure) ──green──▶ PR 3 (namespace) ──green──▶ PR 4 (navgraph)
                                                          │
                                                          └─ parallel OK: PR 4 touches different files
```

PRs 1→2→3 must serialize. PR 4 can land in parallel with PR 3 (different files: `ui/navigation/` and `MainActivity.kt` vs. package declarations and manifest). Recommend sequential anyway — chain keeps the diff story coherent for reviewers.

## Risk Mitigation

| Risk | Mitigation |
|------|------------|
| Kotlin 2.3 deprecations break compile in PR 1 | Run `./gradlew assembleDebug` **immediately** after the TOML bumps. If errors > ~30 lines, split into PR 1a (versions) + PR 1b (deprecations). |
| Hilt 2.56 KSP version mismatch | Pin `ksp = "2.3.0-1.0.24"` alongside Hilt ref; `assembleDebug` fails loudly on mismatch. |
| `dataStore` downgrade per guide | **Deviation** — keep 1.2.0 (see §6). |
| PR 3 namespace half-applied | `grep -rn "com.example.parentalcontrol" app/src/` after sed; gate on empty output. |
| PR 2 split breaks Hilt graph | `assembleDebug` fails at KSP time with missing-binding error; revert the split commit only. |
| `google-services.json` regenerated with wrong package | User smoke-runs `./gradlew assembleDebug` + boots the app before PR 3 merge. |
| `LockScreenReceiver` removal breaks hidden call site | Grep across `app/src/` and `AndroidManifest.xml`; only declared in `BootReceiver.kt:96` (nested class, not separate file). |
| Chained PR diffs show prior slices | Each child PR rebases onto parent's branch tip; verify diff in PR description before requesting review. |
| Detekt/Ktlint surprises at end of chain | Run both gates after **every** PR, not at the chain end. |

## DataStore Deviation Justification

The guide pins `datastore = "1.1.2"`, but the project currently uses `1.2.0` and the call sites (preferences DataStore, no Proto) are compatible with both. Downgrading to 1.1.2 would regress bugfixes (1.2.0 fixed a coroutine cancellation race in `dataStore.edit`) and would be the only downgrade in the entire PR. **Decision**: keep 1.2.0 and explicitly call out the deviation in the PR 1 description. Follow-up: propose a guide correction (bump 1.1.2 → 1.2.0) in a separate, scoped PR so the guide reflects the rationale.

## New Concerns / Gotchas

- **No separate `LockScreenReceiver.kt` file** — the proposal says "remove orphan LockScreenReceiver" but it is a **nested class** at `boot/BootReceiver.kt:96`. PR 2 deletes that class block, not a file. Proposal wording is imprecise; PR 2 description should clarify.
- **`libs.versions.toml` is partially ref'd** — PR 1 also normalizes the catalog to use version refs for everything the guide touches (not just bumps). This expands the diff slightly (~15 extra lines) but prevents drift between ref and inline strings.
- **`hiltExt` 1.2.0 stays** — the guide renames it to `hiltExt`; the current TOML already has it. No change beyond ensuring the ref name matches the guide.
- **Manual Firebase step is on the critical path for PR 3** — if the user is unavailable, PR 3 cannot merge. The PR description should flag this so reviewers don't block on a step the user must perform.
- **`kotlinx-coroutines = 1.7.3` is below the guide's 1.10.2** — the proposal's version table doesn't list this. Two options: (a) include in PR 1 to stay aligned with the guide, (b) defer to a follow-up. Recommend (a) for consistency, even though coroutines 1.7→1.10 has fewer breaking changes than the other bumps.
