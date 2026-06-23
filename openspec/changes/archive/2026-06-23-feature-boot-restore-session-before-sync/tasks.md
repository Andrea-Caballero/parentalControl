# Tasks: feature/boot-restore-session-before-sync

## Goal

After PR #7 (2026-06-23, `04b955a`) made `SyncWorker` instantiate correctly on boot, logcat shows `SyncWorker D Offline, reintentando...` with `tags={SyncWorker, after_boot}` on every fresh boot when no user-initiated auth has happened in the current process. Root cause: `BootReceiver.onBootCompleted()` calls `WorkerInitializer.initialize(context, isAfterBoot = true)` before any auth restore, and `SupabaseClientProvider.connectionState` defaults to `DISCONNECTED` (`SupabaseClientProvider.kt:88`), so `SyncManager.sync()` short-circuits at `SyncManager.kt:174` to `SyncResult.Offline` and WorkManager retries — a noisy logcat loop until the user opens the app. The fix wraps `WorkerInitializer.initialize(...)` in `GlobalScope.launch { restoreSession() ... }` inside `BootReceiver.onBootCompleted()`. On a non-null `StoredSession` the receiver enqueues the boot workers; on `null` it logs a warning and `return@launch` — nothing from `WorkerInitializer.initialize` runs (per locked-scope decision 2a: abort the sync). Success: the 2 new `BootReceiverTest` methods pass, the full unit suite stays green (640/640), and `./gradlew detekt && ./gradlew ktlintCheck && ./gradlew assembleDebug` are clean. No other behavior changes.

## Phase 1: RED — failing test

- [x] 1.1 Append 2 test methods to `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt`, immediately after `onBootCompleted_schedules_outbox_drainer_periodically` (line 44). Reuse the existing `@Before setup` (Robolectric + `WorkManagerTestInitHelper`, `@Config(sdk = [33])`) and the existing test-name style. Stub `DeviceAuthManager.restoreSession()` with `mockkObject(DeviceAuthManager.Companion)` + `every { DeviceAuthManager.getInstance(any()).restoreSession() }`. Tests: (a) `onBootCompleted with restored StoredSession enqueues SyncWorker for after_boot` — stub returns a non-null `StoredSession(accessToken="t", refreshToken="r", expiresAt=0, deviceId="d", userId="u")`; fire the `ACTION_BOOT_COMPLETED` intent; assert `WorkManager.getInstance(context).getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_boot").get()` has size 1. (b) `onBootCompleted with null restored session skips WorkerInitializer and logs warning` — stub returns `null`; assert the same unique-work query is empty; assert a `Log.w` was emitted for tag `BootReceiver` whose message contains `"no stored session"` via Robolectric `ShadowLog`. (Note: `AuthResult.Success` / `AuthResult.Error` are NOT the return type of `restoreSession()` — the real return is `StoredSession?`, where non-null = success path and `null` = failure path; the test stub reflects the actual signature.)
- [x] 1.2 Run `./gradlew testDebugUnitTest --tests "*BootReceiverTest*"` and confirm the 2 new tests FAIL. The expected RED signature is a compile error on the `restoreSession()` call in the test (the method is still `private` at `DeviceAuthManager.kt:437`, and MockK's `every { ... }` would not need source-level access, but the test file's `mockkObject(DeviceAuthManager.Companion)` import is fine; the receiver itself does NOT yet call `restoreSession()` so even after 2.1 is applied standalone the `getWorkInfosForUniqueWork` query would be empty). Capture the failure message. Prerequisite: 1.1 on disk.
- [x] 1.3 Commit: `test(receiver): add RED coverage for BootReceiver boot-time session restore`. Prerequisite: 1.2 RED confirmed on disk.

## Phase 2: GREEN — implementation

- [x] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt:437`, change `private fun restoreSession(): StoredSession?` to `internal fun restoreSession(): StoredSession?`. No body change. Add a KDoc one-liner: `/** No network, no side effects; returns the stored session if any, null on missing/expired/decryption-failed. */`. Prerequisite: 1.3.
- [x] 2.2 In `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt`, modify `onBootCompleted(context)` to wrap the existing `WorkerInitializer.initialize(context, isAfterBoot = true)` call inside a `GlobalScope.launch { ... }`. Inside the coroutine: call `DeviceAuthManager.getInstance(context).restoreSession()`. On non-null → call `WorkerInitializer.initialize(context, isAfterBoot = true)`. On `null` → `Log.w(TAG, "no stored session, skipping boot workers")` and `return@launch`. The wrap covers the WHOLE `WorkerInitializer.initialize` call (locked-scope 2a: on null, neither `scheduleSyncAfterBoot` nor `scheduleAllPeriodicWork` runs). `GlobalScope` is used (not `BroadcastReceiver.goAsync()`) per the design's `IntegrityResponseHandler.kt:212` precedent — boot is fire-and-forget, receiver lives ms, no app-scoped `CoroutineScope` wired today. `onPackageReplaced(...)` is updated automatically because it delegates to `onBootCompleted(context)`. Prerequisite: 2.1.
- [x] 2.3 Re-run the 2 tests from 1.1 and confirm BOTH pass GREEN. Run the full unit suite `./gradlew testDebugUnitTest` and confirm 640/640 pass (638 prior + 2 new). Prerequisite: 2.2.
- [x] 2.4 **CRITICAL**: commit 2.1 (visibility) and 2.2 (receiver wrap) as ONE commit: `fix(receiver): restore session before enqueuing sync in BootReceiver`. The receiver cannot compile without the visibility change; the test cannot be green without both. Prerequisite: 2.3 GREEN on the full suite.

## Phase 3: Quality gates

- [x] 3.1 Run `./gradlew detekt` — must pass. Prerequisite: 2.4.
- [x] 3.2 Run `./gradlew ktlintCheck` — must pass on the 2 changed source files. The pre-existing ktlint failures on `DeviceComponents.kt` are NOT in scope and may keep failing (WARNING, not blocking). Prerequisite: 3.1.
- [x] 3.3 Run `./gradlew assembleDebug` — must succeed. Prerequisite: 3.2.

## Phase 4: Optional — PR / review

- [ ] 4.1 (Only if the user explicitly requests PR creation — out of scope per orchestrator unless asked.) Open a PR titled `fix(receiver): restore session before enqueuing sync in BootReceiver` referencing `openspec/changes/feature-boot-restore-session-before-sync/proposal.md` and `design.md`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~+57 / −0 (BootReceiver +6 wrap, DeviceAuthManager +2 visibility + KDoc, BootReceiverTest +~50 for 2 tests) |
| Files touched | 3 modified, 0 created, 0 deleted |
| 400-line budget risk | Low (~60 lines, well under 400) |
| Chained PRs recommended | No |
| Suggested split | Single PR — 2 commits (Phase 1 RED + Phase 2 GREEN atomic) |
| Delivery strategy | single-pr |
| Chain strategy | n/a |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | RED tests + GREEN (visibility + receiver wrap) + quality gates | PR 1 | Base = `master`. Phase 1 commit may leave the build temporarily broken (visibility still `private`); Phase 2 commit restores it. Manual smoke in success criteria is post-merge. |

Files touched (3 total, exact paths):

- `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` — modified, +2 lines (visibility modifier `private` → `internal` + KDoc).
- `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` — modified, +~6 lines (GlobalScope.launch block wrapping the existing `WorkerInitializer.initialize` call).
- `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` — modified, +~50 lines for the 2 new tests (MockK stubs + `WorkManager.getInstance` assertions + `ShadowLog` check).

## Out of scope (explicit)

- `HeartbeatWorker` from boot (gated transitively via `scheduleSyncAfterBoot` chain — out of scope to touch).
- Instrumented tests (dev box has no `adb` / emulator per `openspec/config.yaml:57`).
- `SyncManager`, `WorkScheduler.scheduleSyncAfterBoot` internals, `SupabaseClientProvider`, manifest, or Ktor config changes.
- `routesKnownByMockEngine` guard test (separate backlog item, obs #82).
- `DeviceComponents.kt` ktlint refactor or baseline wiring (pre-existing on master, separate change).
- Cold start / Baseline Profiles optimization (separate concern).
- A new `@ApplicationScope CoroutineScope` (out of scope; `GlobalScope` is the codebase precedent for fire-and-forget boot work).

## Rollback

Revert the 2 commits (Phase 1 RED + Phase 2 GREEN). No data migration. No feature flag. No compatibility concern. The change reverts the `private` → `internal` visibility and the receiver's `onBootCompleted` to its prior state; falls back to PR #7's behavior (`SyncWorker` short-circuits to `Offline` until the user opens the app).

## Success criteria

- The 2 new `BootReceiverTest` methods pass GREEN.
- Full unit suite is green: `./gradlew testDebugUnitTest` → 640/640.
- `./gradlew detekt`, `./gradlew ktlintCheck` (on the 2 changed source files), and `./gradlew assembleDebug` are green.
- Manual smoke (post-merge, CI instrumented runner on API 28/31/35): reboot the device with a valid stored session, observe logcat, confirm no `SyncWorker D Offline, reintentando...` for the `after_boot` tag.
