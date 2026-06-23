# Proposal: Restore session in BootReceiver before scheduling SyncWorker

## Why

After the 2026-06-23 PR #7 (`04b955a`, "fix(work): disable WorkManager auto-init to honor HiltWorkerFactory") made `SyncWorker` instantiate correctly, logcat shows `SyncWorker D Offline, reintentando...` with `tags={SyncWorker, after_boot}` on every device boot when no user-initiated auth has happened in the current process. Root cause: `BootReceiver.onBootCompleted()` enqueues `SyncWorker` via `WorkerInitializer.initialize(...)` BEFORE any auth restoration. `SupabaseClientProvider.connectionState` defaults to `DISCONNECTED` (`SupabaseClientProvider.kt:88`), and `SyncManager.sync()` short-circuits at line 174 when the state is not `CONNECTED`, returning `SyncResult.Offline`. WorkManager retries — a noisy logcat loop until the user opens the app. Fix: invoke `DeviceAuthManager.restoreSession()` in `BootReceiver.onBootCompleted()` BEFORE `WorkerInitializer.initialize(...)`. If restoration returns `null`, skip the sync chain — the UI flow re-attempts auth when the user opens the app.

## What changes

- **Modified** `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` — `onBootCompleted()` calls `DeviceAuthManager.getInstance(context).restoreSession()` before `WorkerInitializer.initialize(...)`. If `null`, skip the `WorkerInitializer.initialize(...)` call (sync chain) but still start `MonitorForegroundService` and `WorkScheduler.scheduleOutboxDrainer(context)`. (~+6 lines)
- **Modified** `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt:437` — promote `restoreSession()` from `private` to public (`StoredSession?`, no network, no side effects). Required because the only public caller (`authenticateOrCreate()`) falls through to `createAnonymousSession()` on null, which would hit Supabase on boot. (1 line, visibility)
- **New test** `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverRestoreSessionTest.kt` — 2 cases: (a) valid `StoredSession` → `SyncWorker` enqueued for `after_boot`; (b) `null` → no sync work. Robolectric + `WorkManagerTestInitHelper`, mirrors existing `BootReceiverTest`. (~+50 lines)

## Capabilities

### New Capabilities
- `boot-receiver`: boot-time session restoration + work-scheduling invariant. *(Spec deferred — see Spec recommendation.)*

### Modified Capabilities
- None. `parent-auth-session` is UI-driven auth; boot path is out of its scope.

## Affected Areas

| Area | Impact | Description |
|---|---|---|
| `BootReceiver.kt` | Modified | +~6 lines (call, branch, skip) |
| `DeviceAuthManager.kt:437` | Modified | visibility `private → public` (1 line) |
| `BootReceiverRestoreSessionTest.kt` | New | ~50 lines, 2 tests |
| `parent-auth-session` spec | Unchanged | UI-driven auth only |

## Out of scope

- `HeartbeatWorker` from boot — gated transitively via the sync chain (orchestrator confirmed `SyncWorker` only).
- A `boot-receiver` capability spec for this PR (see Spec recommendation).
- `routesKnownByMockEngine` guard test — separate backlog item.
- Instrumented tests — dev box has no `adb` / emulator.
- Touching `SyncManager`'s offline gate — the gate is correct, the boot sequence was the bug.

## Spec recommendation

**Skip `sdd-spec` for this change.** Mirrors the 2026-06-23 `hotfix-child-pairing-mock-redeem-route` pattern: `parent-auth-session` covers UI-driven auth, not boot-time restoration, so there is no spec to delta. Adding a `boot-receiver` capability spec for a 1-file + 1-test change would be more doc than code. **Follow-up**: add a minimal `boot-receiver` spec when boot-path invariants grow beyond this single gate.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Widening `restoreSession` API surface | Low | Doc-comment: "no network, no side effects; returns stored session if any" |
| Main-thread SharedPreferences read in receiver | Low | Same cost as existing `loadPersistedState()` in `DeviceAuthManager.init`; sub-ms |
| Future code re-introduces boot-time `SyncWorker` enqueue without gate | Medium | Focused unit test pins the invariant |
| **Risk level** | **Low** | Contained to receiver + auth-manager visibility + 1 test |

## Rollback

Revert the 2 commits (RED + GREEN). No data migration, no feature flag, no compatibility concern. Falls back to PR #7's behavior: `SyncWorker` short-circuits to `Offline` until the user opens the app.

## Success criteria

- [ ] New `BootReceiverRestoreSessionTest` passes both scenarios.
- [ ] Existing `BootReceiverTest.onBootCompleted_schedules_outbox_drainer_periodically` still passes.
- [ ] Full unit suite green: `./gradlew testDebugUnitTest`.
- [ ] `./gradlew detekt && ./gradlew ktlintCheck && ./gradlew assembleDebug` all green.
- [ ] Post-merge (CI instrumented runner, valid stored session): no `SyncWorker D Offline, reintentando...` logcat noise on fresh boot.