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

- [x] 2.1 In `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt`, MOVE `WorkScheduler.scheduleOutboxDrainer(context)` from current line 57 INTO the existing `if (session != null)` branch (alongside `WorkerInitializer.initialize`).
- [x] 2.2 In the `else` branch of `BootReceiver.kt`, ADD three `WorkScheduler.cancelWork(context, name)` calls for `${SyncWorker.WORK_NAME}_after_boot`, `ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`; keep existing `Log.w`.
- [x] 2.3 Run `./gradlew testDebugUnitTest` — both new tests MUST pass (GREEN); all pre-existing unit tests MUST still pass.
- [x] 2.4 Run `./gradlew detekt ktlintCheck assembleDebug` — all MUST pass.
- [x] 2.5 Commit: `fix(receiver): gate boot-time workers on restored session`.

## Phase 3: RED — Add negative-invariant coverage (commit 3, test-only)

> **Deviation note (test scope only)**: Phase 3 RED phase 1 was wrong about which
> cancel list the spec requires. The spec's scenarios 5 (`Periodic workers stay
> scheduled across reboots`) and 6 (`After-pairing schedule survives a reboot`)
> require the boot path to cancel ONLY the after-boot chain name — not the
> periodic unique-work names. Phase 4 of this change is the GREEN fix that
> restricts the cancel list to one name. This deviates from `design.md`
> Decision 2 (which said cancel three names); the design was wrong, the spec
> was right. The spec is the source of truth.

- [x] 3.1 In `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt`, RENAME `onBootCompleted_without_session_cancels_three_boot_unique_works` → `onBootCompleted_without_session_cancels_only_after_boot_chain`. Change assertions to `verify(exactly = 1) { WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot") }`, `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }`, `verify(exactly = 0) { WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME) }`, and a bounding `verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }`.
- [x] 3.2 ADD `onBootCompleted_without_session_preserves_after_pairing_work`: pre-enqueue `${SyncWorker.WORK_NAME}_after_pairing` unique one-shot work, trigger boot with no session, assert `getWorkInfosForUniqueWork("${SyncWorker.WORK_NAME}_after_pairing")` still has size 1.
- [x] 3.3 ADD `onBootCompleted_without_session_preserves_periodic_outbox_drainer`: pre-enqueue `OutboxDrainer.WORK_NAME` unique periodic work (KEEP), trigger boot with no session, assert periodic entry is still in WorkManager.
- [x] 3.4 ADD `onBootCompleted_without_session_preserves_periodic_reconciliation`: same shape as 3.3 but for `ReconciliationWorker.WORK_NAME` periodic.
- [x] 3.5 Run `./gradlew testDebugUnitTest` — the 4 modified/added tests in `BootReceiverTest` MUST fail (RED); the 3 pre-existing `BootReceiverTest` tests plus the 638 other unit tests MUST still pass.
- [x] 3.6 Run `./gradlew detekt ktlintCheck` — both MUST pass.
- [x] 3.7 Commit: `test(receiver): add RED coverage for boot-time negative invariants`.

## Phase 4: GREEN — Restrict cancel to only `_after_boot` (commit 4, prod-only)

> **Design deviation from `design.md` Decision 2**: this commit cancels ONLY
> one unique-work name (`${SyncWorker.WORK_NAME}_after_boot`) instead of three.
> The spec scenarios 5 and 6 of `boot-worker-lifecycle/spec.md` require the
> periodic `OutboxDrainer` and `ReconciliationWorker` schedules, plus the
> `${SyncWorker.WORK_NAME}_after_pairing` schedule, to survive a no-session
> reboot — they are configured with `ExistingPeriodicWorkPolicy.KEEP` and use
> distinct unique-work names. The previous GREEN (commit `575cd1f`) was
> over-cancelling. `design.md` Decision 2 should be retroactively updated to
> match (out of scope for this batch).

- [x] 4.1 In `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt`, in the `else` branch REMOVE the two `WorkScheduler.cancelWork(context, ...)` calls for `ReconciliationWorker.WORK_NAME` and `OutboxDrainer.WORK_NAME`. KEEP only the `WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")` call. KEEP the existing `Log.w` and the imports (still needed for the one remaining cancel call).
- [x] 4.2 Run `./gradlew testDebugUnitTest` — the 4 modified/added tests in `BootReceiverTest` MUST pass (GREEN); all pre-existing 642 unit tests MUST still pass.
- [x] 4.3 Run `./gradlew detekt ktlintCheck assembleDebug` — all MUST pass.
- [x] 4.4 Commit: `fix(receiver): restrict boot-time cancel to after_boot chain only`.
