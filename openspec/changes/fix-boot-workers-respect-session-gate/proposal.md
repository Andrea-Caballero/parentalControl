# Proposal: Boot-time workers respect restored session gate

## Problem Statement

Logcat 2026-06-24 09:20 shows two recurring post-`BOOT_COMPLETED` errors:

1. **`OutboxDrainer: httpClient no inicializado, retry`** — guard at `app/src/main/java/com/tudominio/parentalcontrol/workers/OutboxDrainer.kt:57` always trips because `SyncManager.httpClient` (`.../sync/SyncManager.kt:143`) is never set in production. Worker returns `Result.retry()` → exponential backoff.
2. **`SyncWorker: Offline, reintentando...`** — `${SyncWorker.WORK_NAME}_after_boot` unique work persists across boots; PR #8's gate only blocks new enqueues, not stored unique work.

Root cause: `.../receiver/BootReceiver.kt:57` re-arms `scheduleOutboxDrainer` outside the `if (session != null)` block at line 64.

## Intent

Make every WorkManager work scheduled at boot depend on a restored session. No session → `Result.success()` clean NO-OP.

## Scope

### In Scope
- Move `WorkScheduler.scheduleOutboxDrainer(context)` from `BootReceiver.kt:57` inside `if (session != null)`.
- In the `else` branch, call `WorkScheduler.cancelWork(...)` for `${SyncWorker.WORK_NAME}_after_boot`, `ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`.
- Robolectric unit test for both gate branches.

### Out of Scope (deferred)
- Remove dead `var httpClient: HttpClient?` at `SyncManager.kt:143`.
- Set `httpClient` from Hilt-injected `clientProvider.httpClient`.
- `USE_MOCK_SUPABASE=true` in `local.properties`.
- Unpair flow (YAGNI).

## Capabilities

### New Capabilities
- `boot-worker-lifecycle`: boot-time WorkManager enqueues are gated on a restored session; previously-persisted unique works are cancelled otherwise.

### Modified Capabilities
- `outbox-drain`: `OutboxDrainer` periodic is re-armed at boot only when a session is restored.

## Approach

| Step | File | Change |
|------|------|--------|
| 1 | `app/src/main/java/.../receiver/BootReceiver.kt:57` | Move `scheduleOutboxDrainer(context)` inside `if (session != null)`. |
| 2 | `BootReceiver.kt` (else) | Add `WorkScheduler.cancelWork(...)` for the three unique work names. |
| 3 | `app/src/test/.../receiver/BootReceiverSessionGateTest.kt` | New Robolectric test for schedule-vs-cancel branches. |

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `app/src/main/java/.../receiver/BootReceiver.kt` | Modified | Move schedule; add cancel calls. |
| `openspec/specs/outbox-drain/spec.md` | Modified (delta) | Boot-time session gate requirement. |
| `openspec/specs/boot-worker-lifecycle/spec.md` | New | Capability spec. |
| `app/src/test/.../receiver/BootReceiverSessionGateTest.kt` | New | Robolectric gate test. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Cancel misses works enqueued under different policy | Low | Code uses `enqueueUnique*` consistently; covered by Robolectric test. |
| Cancel races post-pairing schedule | Low | Post-pairing uses `${WORK_NAME}_after_pairing` (distinct name), runs after boot receiver returns. |
| Session restored mid-worker-run | Medium | Workers read session at run time; no change to in-flight runs. |

## Rollback Plan

`git revert` of the merge commit. Restores unconditional `scheduleOutboxDrainer`. No data migration.

## Dependencies

- None. Pure code re-organization.

## Success Criteria

- [ ] Post-boot logcat with no stored session: ZERO `httpClient no inicializado, retry` or `Offline, reintentando...` lines for 24 h.
- [ ] After pairing, `OutboxDrainer` and `SyncWorker` resume normal scheduling.
- [ ] `./gradlew testDebugUnitTest` passes; `BootReceiverSessionGateTest` green.
- [ ] `./gradlew ktlintCheck detekt` passes.