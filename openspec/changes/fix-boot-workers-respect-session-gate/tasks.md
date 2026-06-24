# Tasks: Boot-time workers respect restored session gate

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~50-70 |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | single PR |
| Delivery strategy | ask-on-risk |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: stacked-to-main
400-line budget risk: Low

Reasoning: 2 files, 2 commits (RED + GREEN), no chained concerns. Well under the 400-line review budget.

## Phase 1: RED — Add failing tests (commit 1, test-only)

- [x] 1.1 Delete `onBootCompleted_schedules_outbox_drainer_periodically` from `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` (stale, pins the bug).
- [x] 1.2 In `BootReceiverTest.kt`, add `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`: mock `DeviceAuthManager.restoreSession()` non-null; assert `scheduleOutboxDrainer` and `WorkerInitializer.initialize` invoked.
- [x] 1.3 In `BootReceiverTest.kt`, add `onBootCompleted_without_session_cancels_three_boot_unique_works`: mock `restoreSession()` null; assert `WorkScheduler.cancelWork` called for `${SyncWorker.WORK_NAME}_after_boot`, `ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`.
- [x] 1.4 Run `./gradlew testDebugUnitTest` — both new tests MUST fail (RED); pre-existing tests MUST still pass.
- [x] 1.5 Run `./gradlew detekt ktlintCheck` — both MUST pass (test-only changes are lint-clean).
- [x] 1.6 Commit: `test(receiver): add RED coverage for boot-time session gate`.

## Phase 2: GREEN — Move schedule + add cancels (commit 2, prod-only)

- [ ] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt`, MOVE `WorkScheduler.scheduleOutboxDrainer(context)` from current line 57 INTO the existing `if (session != null)` branch (alongside `WorkerInitializer.initialize`).
- [ ] 2.2 In the `else` branch of `BootReceiver.kt`, ADD three `WorkScheduler.cancelWork(context, name)` calls for `${SyncWorker.WORK_NAME}_after_boot`, `ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`; keep existing `Log.w`.
- [ ] 2.3 Run `./gradlew testDebugUnitTest` — both new tests MUST pass (GREEN); all pre-existing unit tests MUST still pass.
- [ ] 2.4 Run `./gradlew detekt ktlintCheck assembleDebug` — all MUST pass.
- [ ] 2.5 Commit: `fix(receiver): gate boot-time workers on restored session`.
