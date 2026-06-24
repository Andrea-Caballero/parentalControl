# Design: Boot-time workers respect restored session gate

## Technical Approach

Move `WorkScheduler.scheduleOutboxDrainer(context)` from its current unconditional position in `BootReceiver.onBootCompleted` (line 57) into the existing `if (session != null)` branch that already gates `WorkerInitializer.initialize(...)`. Add three `WorkScheduler.cancelWork(...)` calls in the `else` branch to remove the persisted unique works that would otherwise retry against a DISCONNECTED SupabaseClient. This satisfies the new `boot-worker-lifecycle` capability and the delta added to `outbox-drain`. `DeviceAuthManager.restoreSession()` is already `internal` (line 440) since PR #8, so no access-modifier change is required.

## Architecture Decisions

### Decision 1: Access to `DeviceAuthManager.restoreSession()`

| Choice | Alternatives | Rationale |
|---|---|---|
| **No change** — the function is already `internal fun restoreSession(): StoredSession?` since PR #8. | (a) Promote to `public`. (b) Add a thin `public` wrapper. (c) Inject `DeviceAuthManager` via Hilt `@EntryPoint` from `BootReceiver`. | `BootReceiver` (`com.tudominio.parentalcontrol.receiver`) and `DeviceAuthManager` (`com.tudominio.parentalcontrol.auth`) live in the same Gradle module, so `internal` is visible across the call site. The existing `BootReceiverTest` already mocks the call (`mockkObject(DeviceAuthManager.Companion)` + `every { mockAuthManager.restoreSession() }`), proving current access is sufficient. `public` would leak an auth-internal helper; `EntryPoint` adds ceremony for a singleton already reachable via `getInstance`. |

### Decision 2: How to cancel the three persisted works

| Choice | Alternatives | Rationale |
|---|---|---|
| **Three explicit `WorkScheduler.cancelWork(context, name)` calls** in the `else` branch. | (a) New `WorkScheduler.cancelBootChain(context)` bundling all three. (b) Tag-based cancel via `cancelAllWorkByTag`. | `WorkScheduler.cancelWork(context, workName)` already exists (line 194) and calls `cancelUniqueWork` on the exact name — no API gap. The proposal and both specs enumerate three distinct names; a bulk function has a single caller (`BootReceiver`), so the indirection pays nothing. Tag-based cancel risks touching periodic or after-pairing schedules that share tags (`WORK_NAME`, `TAG_AFTER_PAIRING`). |

### Decision 3: Where to place the test

| Choice | Alternatives | Rationale |
|---|---|---|
| **Extend `BootReceiverTest.kt`** — remove the stale `onBootCompleted_schedules_outbox_drainer_periodically` (currently pins the bug) and add two new tests for the gate branches. | New file `BootReceiverSessionGateTest.kt`. | The existing file already exercises both branches of the gate from the `WorkerInitializer` side using the same `WorkManagerTestInitHelper` setup. Adding `with_session_enqueues_outbox_drainer_and_after_boot_chain` and `without_session_cancels_three_boot_unique_works` keeps the entire boot-gate test surface in one file with consistent mocking of `DeviceAuthManager.Companion`. |

### Decision 4: `OutboxDrainer.doWork()` behavior on no-session (out of scope, documented only)

| Choice | Alternatives | Rationale |
|---|---|---|
| **No change** — keep `Result.retry()` when `httpClient == null`. | Change the worker to `Result.success()` NO-OP. | The boot gate removes the symptom: the work is no longer enqueued without a session, so the retry path is unreachable from boot. The retry's root cause is the dead `var httpClient` field on `SyncManager`, deferred per the proposal. Changing worker semantics is independent of this bug fix and would alter behavior in non-boot scheduling paths. |

## Data Flow

**BOOT_COMPLETED with restored session (linear):**

```
OS BOOT_COMPLETED
  └─→ BootReceiver.onReceive
        ├─ MonitorForegroundService (unconditional)
        └─ GlobalScope.launch
              ├─ DeviceAuthManager.restoreSession() → StoredSession
              ├─ WorkScheduler.scheduleOutboxDrainer(ctx)   ← MOVED HERE
              └─ WorkerInitializer.initialize(ctx, isAfterBoot=true)
                    └─ scheduleSyncAfterBoot
                          → enqueueUnique "${SyncWorker.WORK_NAME}_after_boot"
                          → then HeartbeatWorker
                          → then OutboxDrainer
```

**BOOT_COMPLETED without session (cancel branch):**

```
OS BOOT_COMPLETED
  └─→ BootReceiver.onReceive
        ├─ MonitorForegroundService (unconditional)
        └─ GlobalScope.launch
              ├─ DeviceAuthManager.restoreSession() → null
              ├─ WorkScheduler.cancelWork(ctx, "${SyncWorker.WORK_NAME}_after_boot")
              ├─ WorkScheduler.cancelWork(ctx, ReconciliationWorker.WORK_NAME)
              ├─ WorkScheduler.cancelWork(ctx, OutboxDrainer.WORK_NAME)
              └─ Log.w "no stored session, skipping sync chain"
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` | Modify | Delete unconditional `scheduleOutboxDrainer` at current line 57; inside the `if (session != null)` branch add `WorkScheduler.scheduleOutboxDrainer(context)`; inside the `else` branch add three `WorkScheduler.cancelWork(context, …)` calls and keep the existing `Log.w`. |
| `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` | None | `restoreSession()` is already `internal` (line 440) since PR #8; no change. |
| `app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt` | None | `cancelWork(context, workName)` already exists at line 194; no new code. |
| `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` | Modify | Delete stale `onBootCompleted_schedules_outbox_drainer_periodically`. Add `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` and `onBootCompleted_without_session_cancels_three_boot_unique_works`. |
| `openspec/changes/fix-boot-workers-respect-session-gate/design.md` | Create | This document. |

## Interfaces / Contracts

No new interfaces. No signature changes.

- `DeviceAuthManager.restoreSession(): StoredSession?` — visibility stays `internal`.
- `WorkScheduler.cancelWork(context: Context, workName: String)` — unchanged.

## Testing Strategy

Strict TDD per `openspec/config.yaml` `strict_tdd: true`, two commits:

1. **RED** — modify `BootReceiverTest.kt` per the File Changes table. Run `./gradlew testDebugUnitTest`; both new tests fail: the "session present" test fails because `scheduleOutboxDrainer` is not yet inside the gate, and the "session null" test fails because `cancelWork` is never invoked.
2. **GREEN** — modify `BootReceiver.kt` per the File Changes table. Re-run `./gradlew testDebugUnitTest`; both new tests pass.

Quality gates: `./gradlew testDebugUnitTest detekt ktlintCheck assembleDebug`. No instrumented tests required — this is pure boot-path logic and `WorkManagerTestInitHelper` (already used in the file) covers enqueue/cancel semantics on the JVM.

## Migration / Rollout

No migration. Pure boot-path re-organization. After the first successful pair, `WorkerInitializer.reinitializeAfterPairing` calls `scheduleSyncAfterPairing` which uses the distinct unique work name `${SyncWorker.WORK_NAME}_after_pairing` — unaffected by the boot cancel.

## Open Questions

None blocking the bug fix.

Spec/code drift noted for follow-up (not part of this change): `openspec/changes/fix-boot-workers-respect-session-gate/specs/boot-worker-lifecycle/spec.md` scenario "Chain ordering at boot with a session" lists `ReconciliationWorker.WORK_NAME` in the boot chain, but the current `WorkScheduler.scheduleSyncAfterBoot` chains `HeartbeatWorker → OutboxDrainer` (line 116/120). The proposal scopes this change to the gate move + three cancels; chain contents remain untouched and are tracked as a future task.