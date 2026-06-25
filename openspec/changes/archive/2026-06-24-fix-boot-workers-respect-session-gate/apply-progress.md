# Apply Progress: fix-boot-workers-respect-session-gate

**Change**: `fix-boot-workers-respect-session-gate`
**Mode**: Strict TDD
**Artifact store**: openspec (filesystem only, no Engram)
**Topic key**: `sdd/fix-boot-workers-respect-session-gate/apply-progress`
**Type**: `architecture`
**Project**: `parentalcontrol`
**Capture prompt**: false

> **MERGED PROGRESS (BATCH 2 — RED+GREEN for negative invariants)** — this
> artifact now covers all 19 tasks across BOTH apply batches:
> - First batch (`35509dc` + `575cd1f`): Phase 1 (RED, 6 tasks) + Phase 2 (GREEN, 5 tasks)
> - This batch (`db5cc16` + `82b8fe0`): Phase 3 (RED, 7 tasks) + Phase 4 (GREEN, 4 tasks)
>
> The 4 UNTESTED spec scenarios surfaced by `sdd-verify` on the first batch
> exposed a real design bug — the cancel list was over-scoped. This batch
> restricts the cancel list to ONE unique-work name (`sync_work_after_boot`)
> and pins the negative invariants with new tests. See "Design Deviations"
> below for the deviation from `design.md` Decision 2.

## Goal

Gate every WorkManager work scheduled at boot on a successfully
restored session. With a session, re-arm the periodic OutboxDrainer
and kick off the after-boot sync chain. Without a session, cancel
ONLY the post-boot sync chain (`${SyncWorker.WORK_NAME}_after_boot`)
so it does not retry indefinitely against a DISCONNECTED SupabaseClient.
Periodic workers (OutboxDrainer, ReconciliationWorker) and the
after-pairing schedule MUST be left alone — they have distinct
unique-work names and are configured with `ExistingPeriodicWorkPolicy.KEEP`.

## Commits

| # | Hash | Message | Files |
|---|------|---------|-------|
| 1 | `35509dc` | `test(receiver): add RED coverage for boot-time session gate` | `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` (modified — delete stale + add 2); `openspec/changes/fix-boot-workers-respect-session-gate/*` (new) |
| 2 | `575cd1f` | `fix(receiver): gate boot-time workers on restored session` | `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` (modified — gate move + 3 cancels); `openspec/changes/fix-boot-workers-respect-session-gate/tasks.md` (Phase 1+2 [x]) |
| 3 | `db5cc16` | `test(receiver): add RED coverage for boot-time negative invariants` | `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` (modified — rename 1 test + add 3 negative-invariant tests); `openspec/changes/fix-boot-workers-respect-session-gate/tasks.md` (Phase 3 [x]) |
| 4 | `82b8fe0` | `fix(receiver): restrict boot-time cancel to after_boot chain only` | `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` (modified — restrict cancel to 1 name; remove unused imports for OutboxDrainer and ReconciliationWorker); `openspec/changes/fix-boot-workers-respect-session-gate/tasks.md` (Phase 4 [x]) |

## Files Changed (cumulative)

| File | Action | What Was Done |
|------|--------|---------------|
| `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` | Modified (batch 1) → Modified again (batch 2) | Batch 1: moved `WorkScheduler.scheduleOutboxDrainer(context)` from unconditional position into the `if (session != null)` branch (alongside `WorkerInitializer.initialize`) and added three `WorkScheduler.cancelWork(context, name)` calls in the `else` branch for `sync_work_after_boot`, `reconciliation_work`, `outbox_drain_periodic`. Batch 2 (this batch): removed the two extra `cancelWork` calls — now only `cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")` remains in the `else` branch. Removed unused `OutboxDrainer` and `ReconciliationWorker` imports. Updated the gate comment block to reflect the negative invariants. |
| `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` | Modified (batch 1) → Modified again (batch 2) | Batch 1: deleted stale `onBootCompleted_schedules_outbox_drainer_periodically` (pinned the bug) and added `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` and `onBootCompleted_without_session_cancels_three_boot_unique_works`. Batch 2 (this batch): renamed the latter test to `onBootCompleted_without_session_cancels_only_after_boot_chain` and rewrote its assertions to verify exactly 1 cancel for `_after_boot` and 0 cancels for the two periodic names (and a bounding `verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }`). Added 3 new negative-invariant tests: `onBootCompleted_without_session_preserves_after_pairing_work`, `onBootCompleted_without_session_preserves_periodic_outbox_drainer`, `onBootCompleted_without_session_preserves_periodic_reconciliation`. Each new test pre-enqueues the work, triggers boot with no session, and asserts `WorkScheduler.cancelWork` was NOT called for that specific name + the entry is still in the WorkManager DB. |
| `openspec/changes/fix-boot-workers-respect-session-gate/tasks.md` | Modified (batch 1) → Modified again (batch 2) | Batch 1: marked all Phase 1 + Phase 2 tasks `[x]`. Batch 2: appended Phase 3 (RED) and Phase 4 (GREEN), all 11 new subtasks marked `[x]`. The deviation note from `design.md` Decision 2 is embedded in both phases. |
| `openspec/changes/fix-boot-workers-respect-session-gate/apply-progress.md` | Modified (batch 1) → Modified again (batch 2, this file) | Merged: includes all 19 task rows in the TDD Evidence table, plus the new "Design Deviations" section. |
| `openspec/changes/fix-boot-workers-respect-session-gate/design.md` | **Not modified** (acknowledged drift, see Deviations) | Decision 2 still says "cancel three names". The spec was right, the design was wrong. Updating `design.md` is out of scope for this batch. |
| `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` | **Not modified** (per hard constraint) | `restoreSession()` visibility unchanged. |
| `app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt` | **Not modified** (per hard constraint) | `cancelWork(context, workName)` and `scheduleOutboxDrainer / scheduleReconciliation` unchanged. |

## TDD Cycle Evidence (mandatory under Strict TDD) — CUMULATIVE 19 tasks

Safety net baseline (batch 1): 3 pre-existing `BootReceiverTest` tests passing
before any modification (`onBootCompleted_with_restored_session_enqueues_sync_after_boot`,
`onBootCompleted_schedules_outbox_drainer_periodically` (deleted in task 1.1),
`onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning`).

Safety net baseline (batch 2 — before this batch's modifications): 4 `BootReceiverTest`
tests passing (`onBootCompleted_with_restored_session_enqueues_sync_after_boot`,
`onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning`,
`onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`,
`onBootCompleted_without_session_cancels_three_boot_unique_works`).

| Task | Test File | Layer | Safety Net | RED | GREEN | TRIANGULATE | REFACTOR |
|------|-----------|-------|------------|-----|-------|-------------|----------|
| 1.1 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 3/3 | ✅ Deleted stale test that pinned the bug | n/a (test-only change) | ➖ Single (deletion) | ✅ No code change |
| 1.2 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 3/3 | ✅ Written — `verifyOrder { restoreSession; scheduleOutboxDrainer; WorkerInitializer.initialize }` fails because scheduleOutboxDrainer ran at line 57 (sync, before GlobalScope coroutine). Mock setup: `mockkObject(WorkScheduler)` + `mockkObject(WorkerInitializer)` with `every { WorkerInitializer.initialize(any(), any()) } returns Unit` to isolate the direct BootReceiver call from scheduleAllPeriodicWork side-effects. | ✅ Passed after Phase 2 fix | ✅ Happy path (session present) covers scenario "Session present schedules the sync chain" from boot-worker-lifecycle spec and "Session restored at boot re-arms the chain" from outbox-drain delta. Companion `onBootCompleted_without_session_cancels_three_boot_unique_works` (task 1.3) covers the inverse branch. | ✅ Extracted cancelWork assertion to dedicated test (no production code to refactor) |
| 1.3 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 3/3 | ✅ Written — `verify(exactly = 1) { WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot") }` fails because the else branch only called `Log.w`. Failure message: `cancelWork(eq(...), eq(sync_work_after_boot))) was not called. Calls to same mock: scheduleOutboxDrainer(...)`. Mock setup: `mockkObject(WorkScheduler)` (no `every` overrides needed). | ✅ Passed after Phase 2 fix | ✅ Negative path (session null) covers scenario "No session cancels persisted works and skips scheduling" from boot-worker-lifecycle spec and "Cancellation at boot with no session" + "No session at boot skips the OutboxDrainer re-arm" from outbox-drain delta. Companion `onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning` (pre-existing) cross-checks the no-session branch. | ✅ No code change |
| 1.4 | n/a | n/a | ✅ 3/3 → 640/642 (pre-existing 3 + 637 unrelated) | ✅ Confirmed RED: 2 new tests failed, 640 others passed. | n/a | n/a | n/a |
| 1.5 | n/a | n/a | n/a | ✅ `detekt` BUILD SUCCESSFUL; `ktlintTestSourceSetCheck` 0 violations on my touched test file. `ktlintMainSourceSetCheck` reports only pre-existing violations in production files I did not touch (Workers.kt:43, WorkScheduler.kt:5, AppMonitorService.kt, LockManager.kt, AnalyticsSyncWorker.kt). | n/a | n/a | n/a |
| 1.6 | n/a | n/a | n/a | ✅ Committed as `35509dc test(receiver): add RED coverage for boot-time session gate`. Single line, conventional commit, no Co-Authored-By. | n/a | n/a | n/a |
| 2.1 | `BootReceiver.kt` | Production (Receiver) | n/a | n/a | ✅ Deleted unconditional `WorkScheduler.scheduleOutboxDrainer(context)` at the original line 57. Added `WorkScheduler.scheduleOutboxDrainer(context)` as the first line inside `if (session != null)`. Existing `WorkerInitializer.initialize(context, isAfterBoot = true)` follows on the next line. | n/a | ✅ Extracted the gate comment block (lines 57-64) explaining the contract. |
| 2.2 | `BootReceiver.kt` | Production (Receiver) | n/a | n/a | ✅ Added three `WorkScheduler.cancelWork(context, name)` calls in `else` branch: `"${SyncWorker.WORK_NAME}_after_boot"`, `ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`. Existing `Log.w(TAG, "no stored session, skipping sync chain")` follows. Added imports for the three worker classes. **Note: this implementation is the BUGGY state fixed by batch 2 / Phase 4 — it over-cancelled the periodic and reconciliation schedules, contradicting spec scenarios 5 and 6.** | n/a | ✅ Spanish `Log.w` message kept verbatim (per user constraint: user-facing and not part of this change). |
| 2.3 | n/a | n/a | n/a | n/a | ✅ `./gradlew testDebugUnitTest` BUILD SUCCESSFUL. All 4 `BootReceiverTest` cases pass (2 pre-existing + 2 new). Full suite: 642 tests, 0 failures, 0 errors. | n/a | n/a |
| 2.4 | n/a | n/a | n/a | n/a | ✅ `./gradlew detekt ktlintCheck assembleDebug` BUILD SUCCESSFUL. | n/a | n/a |
| 2.5 | n/a | n/a | n/a | n/a | ✅ Committed as `575cd1f fix(receiver): gate boot-time workers on restored session`. Single line, conventional commit, no Co-Authored-By. | n/a | n/a |
| 3.1 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 4/4 (1 deleted stale + 2 pre-existing + 2 from batch 1) | ✅ RENAMED `onBootCompleted_without_session_cancels_three_boot_unique_works` → `onBootCompleted_without_session_cancels_only_after_boot_chain`. Rewrote assertions to: `verify(exactly = 1) { WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot") }`, `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }`, `verify(exactly = 0) { WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME) }`, bounding `verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }`. Fails RED because the production else branch cancels `reconciliation_work` (extra). Failure: `Verification failed: call 1 of 1: ... cancelWork(eq(context), eq(reconciliation_work))) should not be called. Calls: 1) cancelWork(ctx, sync_work_after_boot) 2) cancelWork(ctx, reconciliation_work) 3) cancelWork(ctx, outbox_drain_periodic)`. | ✅ Passed after Phase 4 fix (production now cancels only 1 name) | ➖ Single — this is the comprehensive negative-assertion test for the cancel list; companion tests 3.3 / 3.4 cover the periodic survival invariants | ✅ No code change (test-only) |
| 3.2 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 4/4 | ✅ Written — pre-enqueue `${SyncWorker.WORK_NAME}_after_pairing` unique one-shot work, mock `restoreSession()` null, mock `WorkScheduler` to assert NO `cancelWork` call for after_pairing name, assert `getWorkInfosForUniqueWork(...)` still has size 1. **RED signal: PASS under buggy code (regression guard, not RED-failing).** The current bug cancels `sync_work_after_boot`, `reconciliation_work`, `outbox_drain_periodic` — none targets `sync_work_after_pairing`, so the after_pairing work survives regardless. The test serves as a future-bug regression guard for any change that accidentally adds after_pairing to the cancel list. Documented in the test KDoc. | ✅ Passed after Phase 4 fix | ✅ Regression guard for spec scenario "After-pairing schedule survives a reboot". Companion test 3.1 covers the bounding total count of cancel calls. | ✅ No code change |
| 3.3 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 4/4 | ✅ Written — pre-enqueue `OutboxDrainer.WORK_NAME` unique periodic work (KEEP), mock `restoreSession()` null, mock `WorkScheduler` to assert NO `cancelWork` call for `OutboxDrainer.WORK_NAME`, assert DB still has the entry. Fails RED because the production else branch cancels `outbox_drain_periodic`. Failure: `Verification failed: call 1 of 1: ... cancelWork(eq(context), eq(outbox_drain_periodic))) should not be called`. **Note**: an earlier prototype of this test used real WorkScheduler + state assertions, but Robolectric's WorkManager test driver runs the periodic OutboxDrainer worker synchronously on enqueue and (because Hilt is not initialised in unit tests) the worker fails, leaving the WorkInfo state at FAILED (terminal). `cancelUniqueWork` is a no-op on terminal states, so the real-DB state never transitions to CANCELLED and the state-based assertion cannot RED-fail under the buggy code. Switched to the API-level `verify(exactly = 0)` assertion + DB size sanity check, which is robust against test-harness quirks. | ✅ Passed after Phase 4 fix | ✅ Companion tests 3.1 (bounding count) + 3.4 (sister periodic); spec scenarios "Periodic workers stay scheduled across reboots" + "Periodic OutboxDrainer schedule is preserved across reboot" | ✅ No code change |
| 3.4 | `BootReceiverTest.kt` | Unit (Robolectric) | ✅ 4/4 | ✅ Written — same shape as 3.3 but for `ReconciliationWorker.WORK_NAME` periodic. Fails RED because the production else branch cancels `reconciliation_work`. Failure: `Verification failed: call 1 of 1: ... cancelWork(eq(context), eq(reconciliation_work))) should not be called`. | ✅ Passed after Phase 4 fix | ✅ Companion to 3.3; spec scenario "Periodic workers stay scheduled across reboots" (ReconciliationWorker variant) | ✅ No code change |
| 3.5 | n/a | n/a | ✅ 4/4 → 642/645 (4 RED tests + 641 others passing — including the 4 pre-existing `BootReceiverTest` tests passing under the buggy code) | ✅ Confirmed RED: 3 of 4 new/modified tests failed (3.1, 3.3, 3.4). 1 test (3.2) passed as a regression guard (documented in row 3.2). All other 641 unit tests (including the 4 pre-existing `BootReceiverTest` tests) pass. | n/a | n/a | n/a |
| 3.6 | n/a | n/a | n/a | ✅ `detekt` BUILD SUCCESSFUL; `ktlintCheck` BUILD SUCCESSFUL. Initially failed on unused `WorkInfo` import (state assertions were removed in iteration), fixed by removing the import. | n/a | n/a | n/a |
| 3.7 | n/a | n/a | n/a | ✅ Committed as `db5cc16 test(receiver): add RED coverage for boot-time negative invariants`. Single line, conventional commit, no Co-Authored-By. | n/a | n/a | n/a |
| 4.1 | `BootReceiver.kt` | Production (Receiver) | n/a | n/a | ✅ In the `else` branch of `BootReceiver.kt`, REMOVED the two `cancelWork` calls for `ReconciliationWorker.WORK_NAME` and `OutboxDrainer.WORK_NAME`. KEPT only `WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")`. KEPT the existing `Log.w(TAG, "no stored session, skipping sync chain")`. Updated the gate comment block (lines 57-70) to explicitly document the negative invariants (do not cancel periodic, do not cancel after_pairing). Removed the now-unused `OutboxDrainer` and `ReconciliationWorker` imports (ktlint `Unused import` flagged them). | n/a | ✅ Removed unused imports; expanded the gate comment with rationale; Spanish `Log.w` kept verbatim. |
| 4.2 | n/a | n/a | n/a | n/a | ✅ `./gradlew testDebugUnitTest` BUILD SUCCESSFUL. All 7 `BootReceiverTest` cases pass (2 pre-existing + 1 batch-1 with-session + 1 batch-1 renamed cancel-only + 3 batch-2 negative-invariant tests). Full suite: 645 tests, 0 failures, 0 errors, 0 skipped. | n/a | n/a |
| 4.3 | n/a | n/a | n/a | n/a | ✅ `./gradlew detekt ktlintCheck assembleDebug` BUILD SUCCESSFUL. detekt clean; ktlint clean across all source sets (the unused-import fix from task 3.6 carried forward); assembleDebug produces a valid APK. | n/a | n/a |
| 4.4 | n/a | n/a | n/a | n/a | ✅ Committed as `82b8fe0 fix(receiver): restrict boot-time cancel to after_boot chain only`. Single line, conventional commit, no Co-Authored-By. | n/a | n/a |

## RED → GREEN Transition Notes (cumulative)

**Batch 1 (commits `35509dc` + `575cd1f`):** The RED signal that drove the GREEN fix
was the `verifyOrder { restoreSession; scheduleOutboxDrainer; WorkerInitializer.initialize }`
assertion in test 1.2. With the buggy code, `WorkScheduler.scheduleOutboxDrainer` was invoked at
`BootReceiver.kt:57` synchronously (BEFORE the `GlobalScope.launch { ... restoreSession() ... }`
block), producing the recorded call order `[scheduleOutboxDrainer, restoreSession, WorkerInitializer.initialize]`.
The `verifyOrder` block expected `[restoreSession, scheduleOutboxDrainer, WorkerInitializer.initialize]`,
so the assertion failed with `Verification failed: calls are not in verification order`.

Test 1.3's RED signal was simpler: `cancelWork` was never called in the old
`else` branch. The batch-1 GREEN fix added the three `cancelWork` invocations.

**Batch 2 (commits `db5cc16` + `82b8fe0`):** The batch-2 RED signals came from the
newly added negative-invariant tests in `BootReceiverTest`. With the batch-1 GREEN
still in place (3 cancel calls in the `else` branch):
- Test 3.1 (`onBootCompleted_without_session_cancels_only_after_boot_chain`) failed
  on `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }`.
- Test 3.3 (`onBootCompleted_without_session_preserves_periodic_outbox_drainer`) failed
  on `verify(exactly = 0) { WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME) }`.
- Test 3.4 (`onBootCompleted_without_session_preserves_periodic_reconciliation`) failed
  on `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }`.

The batch-2 GREEN fix (`82b8fe0`) removed the two extra `cancelWork` calls from the
`else` branch, leaving only `cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")`.
All 3 RED tests passed; test 3.2 (regression guard) continued to pass.

## Design Deviations

**Phase 4 deviates from `design.md` Decision 2** (which said cancel 3 names). The spec
scenarios 5 (`Periodic workers stay scheduled across reboots`) and 6 (`After-pairing
schedule survives a reboot`) of `boot-worker-lifecycle/spec.md` require preserving the
periodics and the after-pairing schedule, so the cancel list was restricted to only
`${SyncWorker.WORK_NAME}_after_boot` (the boot chain name). The spec is the source of
truth; the design was wrong. `design.md` Decision 2 should be retroactively updated to
match the final implementation (out of scope for this batch — flagged for follow-up).

**Why this is the right deviation**: `WorkScheduler.scheduleOutboxDrainer` (line 67-71)
and `WorkScheduler.scheduleReconciliation` (line 97-99) both use
`enqueueUniquePeriodicWork(name, KEEP, ...)`. The unique-work name IS the periodic's
identity in WorkManager. Calling `cancelUniqueWork(periodicName)` removes the periodic
schedule, which contradicts the `KEEP` policy and the spec. The boot chain name
`${SyncWorker.WORK_NAME}_after_boot` is the only boot-persisted work that the spec says
should be cancelled (because it represents a stale `BOOT_COMPLETED` invocation that
should not retry against a DISCONNECTED SupabaseClient).

**Test design iteration**: An earlier prototype of tests 3.3 / 3.4 used the real
WorkScheduler (not mocked) and asserted on WorkInfo state (not CANCELLED). This did
not work under the buggy code because Robolectric's WorkManager test driver runs the
periodic workers synchronously on enqueue and (because Hilt is not initialised in unit
tests) the workers fail, leaving the WorkInfo state at FAILED (terminal).
`cancelUniqueWork` is a no-op on terminal states, so the state never transitions to
CANCELLED under the buggy code and the state-based assertion cannot RED-fail. The
final design uses mocked WorkScheduler with `verify(exactly = 0)` (API-level surface)
plus a WorkManager DB size sanity check — robust against the test-harness quirks.

**Scope note**: Spec scenarios "In-flight worker runs are not affected by the gate"
(2 scenarios in `boot-worker-lifecycle`) remain UNTESTED — these are OS-level invariants
enforced by the Android framework, not unit-testable in JVM Robolectric. They were
already UNTESTED in the batch-1 verify report and are unchanged by this batch.

## Issues Found

**Resolved by this batch**:
1. ~~Spec scenario UNTESTED — "After-pairing schedule survives a reboot"~~ → now pinned by test 3.2 (regression guard).
2. ~~Spec scenario UNTESTED — "Periodic workers stay scheduled across reboots"~~ → now pinned by tests 3.3 + 3.4.
3. ~~Spec scenario UNTESTED — "Periodic OutboxDrainer schedule is preserved across reboot"~~ → now pinned by test 3.3.

**Out of scope (pre-existing, unchanged by this batch)**:
- Spec scenarios "In-flight worker runs are not affected by the gate" (2 scenarios): OS-level, not unit-testable in JVM Robolectric.
- Pre-existing ktlint violations in `Workers.kt:43` and `WorkScheduler.kt:5` (acknowledged in batch 1).
- No coverage tool installed (no kover/jaCoCo).
- `design.md` Decision 2 not retroactively updated to match the corrected implementation (flagged for follow-up).
- Spec/code drift in `boot-worker-lifecycle/spec.md` scenario "Chain ordering at boot with a session" — spec lists `ReconciliationWorker.WORK_NAME` in the chain but `WorkScheduler.scheduleSyncAfterBoot` actually chains `HeartbeatWorker → OutboxDrainer`. Pre-existing drift, tracked in batch-1 apply-progress.

## Remaining Tasks

All 19 tasks in `tasks.md` are `[x]`. None remaining.

## Workload / PR Boundary

- Mode: single PR (no chained PRs)
- Current work unit: the entire `fix-boot-workers-respect-session-gate` change (now extended with a second RED+GREEN pair for the negative invariants surfaced by verify)
- Boundary: complete implementation across 4 commits (2 RED + 2 GREEN). The two batches together represent a single conceptual change: gate boot-time work on restored session AND restrict the no-session cancel list to only the after-boot chain.
- Estimated review budget impact:
  - Batch 1: ~457 added, 41 deleted in RED commit (mostly openspec docs + 2 new tests); ~20 added, 13 deleted in GREEN commit (BootReceiver.kt gate move).
  - Batch 2: ~308 added, 14 deleted in RED commit (1 test rename + 3 negative-invariant tests in BootReceiverTest.kt + tasks.md Phase 3); ~9 added, 7 deleted in GREEN commit (BootReceiver.kt cancel restriction + comment expansion).
  - Total combined changed lines (production code `BootReceiver.kt` across both batches): ~30 lines net. Well under budget.
- 400-line budget risk: Low. Confirmed at session preflight and re-confirmed by the actual `git diff --stat` (325 insertions / 24 deletions across the 3 modified files in the batch-2 commits; combined across both batches, ~775 insertions / 65 deletions but most of the insertions are in tests + openspec docs).

## Related Engram Observation

- Previous observation id: `obs-3af10c6961b75656`
- Topic key: `sdd/fix-boot-workers-respect-session-gate/apply-progress`
- This merged artifact is persisted via `mem_update` on `obs-3af10c6961b75656`
  (type: `architecture`, capture_prompt: `false`).