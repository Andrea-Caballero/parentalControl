## Verification Report

**Change**: `fix-boot-workers-respect-session-gate`
**Version**: 2.0 (re-verify after batch 2 design-bug fix)
**Mode**: Strict TDD
**Artifact store**: openspec
**Re-verify after batch 2 fix of the design bug; supersedes the previous PASS WITH WARNINGS report.**

> **Delta summary**: The first verify (v1.0) flagged 4 UNTESTED spec scenarios as WARNING. Investigation revealed a real design bug — the cancel list over-cancelled the periodic and after-pairing schedules. The second sdd-apply batch (commits `db5cc16` + `82b8fe0`) restricted the cancel list to ONLY `${SyncWorker.WORK_NAME}_after_boot`, and added 3 negative-invariant tests + 1 regression-guard test to pin the new behaviour. This re-verify re-runs the full quality-gate suite (not UP-TO-DATE cached) and rebuilds the spec compliance matrix from the fresh test evidence.

---

### Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 19 (Phase 1: 6, Phase 2: 5, Phase 3: 7, Phase 4: 1 with sub-tasks) — actually 6+5+7+4 = 22 in tasks.md, all `[x]` |
| Tasks complete | All `[x]` (verified in `tasks.md` line-by-line) |
| Tasks incomplete | 0 |
| Commits in scope | 4: `35509dc` (RED 1), `575cd1f` (GREEN 1), `db5cc16` (RED 2), `82b8fe0` (GREEN 2) |
| Files changed by this change | 2: `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt`; `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` |

### Spec Compliance Delta

| | COMPLIANT | PARTIAL | UNTESTED | Total |
|---|---|---|---|---|
| **Before batch 2** (v1.0 report) | 7 | 1 | 4 | 12 |
| **After batch 2** (this re-verify) | **9** | **1** | **2** | 12 |
| **Net delta** | **+2** | 0 | **-2** | 0 |

- 2 new COMPLIANT: `boot-worker-lifecycle` Req 3 scenarios ("After-pairing schedule survives a reboot", "Periodic workers stay scheduled across reboots") — newly covered by tests 3.2 (regression guard) + 3.3 + 3.4.
- 2 fewer UNTESTED: same scenarios, now pinned by the new tests.
- 1 PARTIAL unchanged: pre-existing spec/code drift on "Chain ordering at boot with a session" (spec names `ReconciliationWorker.WORK_NAME` but `WorkScheduler.scheduleSyncAfterBoot` chains `HeartbeatWorker → OutboxDrainer`). Not introduced by this change. Tracked in `design.md` "Open Questions" and `apply-progress.md` "Issues Found → Out of scope".
- 2 remaining UNTESTED: in-flight worker run scenarios (OS-level / integration) — see Issues → WARNING #1.

---

### Build & Tests Execution (REAL, not cached)

**Build**: ✅ Passed
```text
$ ./gradlew assembleDebug --rerun-tasks --no-daemon
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 25s
45 actionable tasks: 45 executed
```

**Tests**: ✅ 645 passed / 0 failed / 0 skipped
```text
$ ./gradlew testDebugUnitTest --rerun-tasks --no-daemon
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 2m 3s
39 actionable tasks: 39 executed

BootReceiverTest: tests=7, failures=0, errors=0, skipped=0  (timestamp 2026-06-24T16:05:10.891Z, time=10.794s)
  - onBootCompleted_with_restored_session_enqueues_sync_after_boot (1.104s)                 [pre-existing]
  - onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning (1.113s)         [pre-existing]
  - onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain (1.241s)     [BATCH 1 NEW]
  - onBootCompleted_without_session_cancels_only_after_boot_chain (1.169s)                  [BATCH 2 RENAME of 1.3]
  - onBootCompleted_without_session_preserves_after_pairing_work (3.738s)                   [BATCH 2 NEW, regression guard]
  - onBootCompleted_without_session_preserves_periodic_outbox_drainer (1.243s)             [BATCH 2 NEW]
  - onBootCompleted_without_session_preserves_periodic_reconciliation (1.186s)             [BATCH 2 NEW]

Full suite (aggregated from app/build/test-results/testDebugUnitTest/TEST-*.xml):
  Total tests: 645 | Failures: 0 | Errors: 0 | Skipped: 0

(Robolectric CloseGuard warnings on system-err are noise from WorkManagerTestInitHelper;
they do not affect test outcomes and are not regressions.)
```

**Coverage**: ➖ Not available
Coverage analysis skipped — no coverage tool detected (per `openspec/config.yaml` `testing.coverage.command: ""`).

**Lint / Static analysis** (FORCED re-run, not cached):
- ✅ `./gradlew detekt --rerun-tasks --no-daemon` — `BUILD SUCCESSFUL`. No findings.
- ⚠️ `./gradlew ktlintCheck --rerun-tasks --no-daemon` — `BUILD FAILED` with 479 pre-existing ktlint violations across 24 test files in `ktlintTestSourceSetCheck`. **None of the violations are in `BootReceiverTest.kt`** (the only test file modified by this change — verified via `grep` returning 0 matches for `BootReceiverTest.kt` in `app/build/reports/ktlint/ktlintTestSourceSetCheck/ktlintTestSourceSetCheck.txt`). `ktlintMainSourceSetCheck` reports 0 violations; `ktlintAndroidTestSourceSetCheck` reports 0 violations. The 479 pre-existing violations breakdown:
  - 368 × Trailing space(s)
  - 81 × Unused import
  - 11 × Exceeded max line length (120)
  - 5 × Unnecessary space(s)
  - 5 × Unnecessary import
  - 2 × Unexpected indentation (12) (should be 16)
  - 2 × Wildcard import
  - 1 × Redundant curly braces
  - 1 × File must end with a newline
  - 1 × Missing newline after "("
  - 1 × Missing newline before ")"
  - 1 × Needless blank line(s)

> **Important re-verify correction**: the v1.0 verify report claimed `./gradlew ktlintCheck` was `BUILD SUCCESSFUL`. That was a false positive from Gradle's UP-TO-DATE cache — the task had not been re-executed. When forced with `--rerun-tasks`, ktlintCheck DOES fail at the project level. However, this is pre-existing, not a regression from this change, and the project policy in `openspec/config.yaml` does not make ktlintCheck a merge gate (it's an informational check, not blocking). See WARNING #2 for classification.

> **Note on the session preflight's pre-existing ktlint claims**: the preflight said "2 pre-existing ktlint violations in `Workers.kt:43` (line-length), `WorkScheduler.kt:5` (wildcard import)". The current ktlint report shows 0 violations in `ktlintMainSourceSetCheck` — those specific entries are NOT present. The 479 violations are ALL in test files, not production. The preflight's note was inaccurate, but the spirit (pre-existing violations, not a regression) holds for this change.

---

### Spec Compliance Matrix

#### `boot-worker-lifecycle/spec.md`

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| BootReceiver gates post-boot work on a restored session | Session present schedules the sync chain | `BootReceiverTest > onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` (verifies `scheduleOutboxDrainer(ctx)` + `WorkerInitializer.initialize(ctx, true)` + `verifyOrder { restoreSession; scheduleOutboxDrainer; WorkerInitializer.initialize }` + `verify(exactly = 0) { cancelWork(any(), any()) }`) | ✅ COMPLIANT |
| BootReceiver gates post-boot work on a restored session | No session cancels persisted works and skips scheduling | `BootReceiverTest > onBootCompleted_without_session_cancels_only_after_boot_chain` (verifies `cancelWork(ctx, "${SyncWorker.WORK_NAME}_after_boot")` exactly 1×) | ✅ COMPLIANT |
| scheduleSyncAfterBoot enqueues the post-boot chain | Chain ordering at boot with a session | `BootReceiverTest > onBootCompleted_with_restored_session_enqueues_sync_after_boot` (asserts chain size == 3) + `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` (asserts `WorkerInitializer.initialize(ctx, true)` invoked) | ⚠️ PARTIAL (chain is `SyncWorker → HeartbeatWorker → OutboxDrainer`, spec says `SyncWorker → ReconciliationWorker → OutboxDrainer` — pre-existing drift, not introduced by this change) |
| scheduleSyncAfterBoot enqueues the post-boot chain | scheduleSyncAfterBoot is not called without a session | `BootReceiverTest > onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning` (asserts 0 work in DB) + `onBootCompleted_without_session_cancels_only_after_boot_chain` (asserts `scheduleOutboxDrainer` never called when session is null — the `verifyOrder` block in the with-session test pins the inverse) | ✅ COMPLIANT |
| Cancel does not disturb after-pairing or periodic schedules | After-pairing schedule survives a reboot | `BootReceiverTest > onBootCompleted_without_session_preserves_after_pairing_work` (pre-enqueue `_after_pairing`, no cancel, DB still size 1) | ✅ COMPLIANT (regression guard — disclosed in apply-progress row 3.2; the buggy code's 3-name cancel list does not include `_after_pairing` so this test passes by design under both buggy and fixed code; serves as a future-bug regression guard) |
| Cancel does not disturb after-pairing or periodic schedules | Periodic workers stay scheduled across reboots | `BootReceiverTest > onBootCompleted_without_session_preserves_periodic_outbox_drainer` (pre-enqueue `OutboxDrainer.WORK_NAME` periodic with KEEP, no cancel, DB still size 1) + `onBootCompleted_without_session_preserves_periodic_reconciliation` (same shape for `ReconciliationWorker.WORK_NAME`) | ✅ COMPLIANT |
| In-flight worker runs are not affected by the gate | Worker started before reboot completes its run | (none — OS-level invariant; not unit-testable in JVM Robolectric) | ❌ UNTESTED — classified WARNING, OS-level |
| In-flight worker runs are not affected by the gate | Session restored mid-worker-run | (none — requires in-flight worker fixture; integration-level, not unit-testable in JVM Robolectric) | ❌ UNTESTED — classified WARNING, integration-level |

#### `outbox-drain/spec.md` (delta)

| Requirement | Scenario | Test | Result |
|-------------|----------|------|--------|
| OutboxDrainer boot re-arm is gated on a restored session | Session restored at boot re-arms the chain | `BootReceiverTest > onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` (asserts `scheduleOutboxDrainer(ctx)` + `WorkerInitializer.initialize(ctx, true)`) | ✅ COMPLIANT |
| OutboxDrainer boot re-arm is gated on a restored session | No session at boot skips the OutboxDrainer re-arm | `BootReceiverTest > onBootCompleted_without_session_cancels_only_after_boot_chain` + `onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning` | ✅ COMPLIANT |
| Persisted OutboxDrainer unique work is cancelled when no session exists | Cancellation at boot with no session | `BootReceiverTest > onBootCompleted_without_session_cancels_only_after_boot_chain` (asserts `cancelWork(ctx, "${SyncWorker.WORK_NAME}_after_boot")` exactly 1×; the after-boot chain name is the persistent name from a prior boot) | ✅ COMPLIANT |
| Persisted OutboxDrainer unique work is cancelled when no session exists | Periodic OutboxDrainer schedule is preserved across reboot | `BootReceiverTest > onBootCompleted_without_session_preserves_periodic_outbox_drainer` (pre-enqueue `OutboxDrainer.WORK_NAME` periodic with KEEP, no cancel, DB still size 1) | ✅ COMPLIANT |

**Compliance summary**: 9/12 scenarios COMPLIANT, 1/12 PARTIAL, 2/12 UNTESTED (both OS/integration level, classified WARNING not CRITICAL per the documented limitation in `apply-progress.md`).

---

### Correctness (Static Evidence)

| Requirement | Status | Notes |
|------------|--------|-------|
| Move `scheduleOutboxDrainer` inside the `if (session != null)` branch | ✅ Implemented | `BootReceiver.kt:72` — `WorkScheduler.scheduleOutboxDrainer(context)` is the first line of the `if (session != null)` block. |
| Cancel only `${SyncWorker.WORK_NAME}_after_boot` in the `else` branch (not the two periodic names) | ✅ Implemented | `BootReceiver.kt:75` — exactly one `WorkScheduler.cancelWork` call in the `else` branch, for the after-boot chain only. |
| `Log.w("no stored session, skipping sync chain")` preserved | ✅ Implemented | `BootReceiver.kt:76`. |
| Imports for the one worker class that the cancel targets | ✅ Implemented | `BootReceiver.kt:10` — `SyncWorker` imported. `OutboxDrainer` and `ReconciliationWorker` imports REMOVED (no longer needed). |
| Gate contract: `scheduleOutboxDrainer` runs AFTER `restoreSession` returns non-null | ✅ Implemented + tested | `verifyOrder` block in `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` (lines 223-227). |
| No `cancelWork` invoked when session is present | ✅ Implemented + tested | `verify(exactly = 0) { WorkScheduler.cancelWork(any(), any()) }` (line 229). |
| Cancel list bounded: exactly 1 cancel in the no-session branch, for `_after_boot` | ✅ Implemented + tested | `verify(exactly = 1) { WorkScheduler.cancelWork(ctx, "${SyncWorker.WORK_NAME}_after_boot") }` (lines 274-276) + bounding `verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }` (line 288). |
| After-pairing schedule survives a no-session boot | ✅ Implemented + tested | `onBootCompleted_without_session_preserves_after_pairing_work` (line 319). |
| Periodic OutboxDrainer survives a no-session boot | ✅ Implemented + tested | `onBootCompleted_without_session_preserves_periodic_outbox_drainer` (line 402). |
| Periodic ReconciliationWorker survives a no-session boot | ✅ Implemented + tested | `onBootCompleted_without_session_preserves_periodic_reconciliation` (line 480). |

---

### Coherence (Design)

| Decision | Followed? | Evidence |
|----------|-----------|----------|
| D1: `DeviceAuthManager.restoreSession()` stays `internal` (no access change) | ✅ Yes | `git diff 4b00879..HEAD -- DeviceAuthManager.kt` is empty. No `EntryPoint`, no public wrapper, no Hilt ceremony. |
| D2: Three explicit `cancelWork` calls in the else branch (no bulk function, no tag-based cancel) | ⚠️ **DEVIATION** | `BootReceiver.kt:75` contains only ONE `cancelWork` call (for `_after_boot`). The other two cancel calls that D2 said to add (`ReconciliationWorker.WORK_NAME`, `OutboxDrainer.WORK_NAME`) were REMOVED in commit `82b8fe0`. The spec (`boot-worker-lifecycle` scenarios 5 and 6) is the source of truth — the periodic and after-pairing schedules MUST survive a no-session boot. The new behaviour is correct; the design was wrong. **Follow-up: update `design.md` Decision 2 to match the implementation** (acknowledged in `apply-progress.md` "Design Deviations" and `tasks.md` Phase 4 note). |
| D3: Extend `BootReceiverTest.kt` (no new test file) | ✅ Yes | `find app/src/test -name "BootReceiverSessionGateTest.kt"` returns 0 matches. All 7 tests live in `BootReceiverTest.kt` (537 lines). The pre-existing `WorkManagerTestInitHelper` setup and `mockkObject(DeviceAuthManager.Companion)` pattern are reused. The stale `onBootCompleted_schedules_outbox_drainer_periodically` was deleted in commit `35509dc` (Phase 1, task 1.1). |
| D4: `OutboxDrainer.doWork()` semantics unchanged | ✅ Yes | `git diff 4b00879..HEAD -- OutboxDrainer.kt` is empty. `Result.retry()` on null `httpClient` preserved. |

**Coherence summary**: 3/4 decisions followed. D2 is a documented, intentional deviation: the new code is the spec, the old D2 text is wrong. See WARNING #3.

---

### TDD Compliance (per strict-tdd-verify.md)

| Check | Result | Details |
|-------|--------|---------|
| TDD Evidence reported | ✅ | `apply-progress.md` "TDD Cycle Evidence" table contains 19+ task rows covering Phase 1 (6 rows) + Phase 2 (5 rows) + Phase 3 (7 rows) + Phase 4 (4 rows) = 22 task rows with RED/GREEN/Safety Net/Refactor columns. |
| All tasks have tests | ✅ | 22/22 task rows in apply-progress have test files or test-relevant actions. Phase 3 row 3.1 (rename), 3.2/3.3/3.4 (new tests) all in `BootReceiverTest.kt`. |
| RED confirmed (tests exist) | ✅ | 4/4 modified/added tests from batch 2 present in `BootReceiverTest.kt` (3.1 renamed at line 254; 3.2 at line 319; 3.3 at line 402; 3.4 at line 480). All 3 RED-failing tests from `db5cc16` are real (verify(0) calls with named arguments). |
| GREEN confirmed (tests pass) | ✅ | 7/7 `BootReceiverTest` cases pass at runtime (re-run with `--rerun-tasks`): `tests=7 failures=0 errors=0 skipped=0`. Full suite 645/645. |
| Triangulation adequate | ✅ | Batch 2 inverted the gate perspective: instead of 1 test asserting "3 cancels happen", the design now uses 4 tests asserting "exactly 1 cancel happens for the after-boot name AND zero cancels for the periodic/after-pairing names". Spec scenarios 5 and 6 (after-pairing preserved + periodics preserved) are covered by companion tests 3.2/3.3/3.4; the bounding total is in 3.1. |
| Safety Net for modified files | ✅ | `apply-progress.md` reports 4/4 pre-existing `BootReceiverTest` tests passing before batch 2 modifications (1 stale deleted in 1.1 + 2 pre-existing + 2 from batch 1, with batch 1's 2 still passing under the batch-2 buggy code per the safety net record). All new tests use the existing `WorkManagerTestInitHelper` setup and `unmockkAll` teardown. |
| **Test 3.2 disclosed as regression guard** | ✅ | `apply-progress.md` row 3.2 honestly notes: "RED signal: PASS under buggy code (regression guard, not RED-failing). The current bug cancels `sync_work_after_boot`, `reconciliation_work`, `outbox_drain_periodic` — none targets `sync_work_after_pairing`, so the after_pairing work survives regardless." The test is acknowledged as a forward-looking guard, not a true RED. The sub-agent's transparency is acceptable per the strict-tdd protocol: a regression guard has standalone value for the spec scenario it pins. |

**TDD Compliance**: 7/7 checks passed (one with disclosure of a non-failing-RED guard).

---

### Test Layer Distribution

| Layer | Tests | Files | Tools |
|-------|-------|-------|-------|
| Unit (Robolectric JVM) | 7 (in `BootReceiverTest.kt`) | 1 | Robolectric 4.x + MockK 1.13.x + WorkManagerTestInitHelper (JVM) |
| Integration | 0 | 0 | not installed locally (per `openspec/config.yaml`; covered in CI via `connectedDebugAndroidTest`) |
| E2E | 0 | 0 | not installed locally |
| **Total** | **7** | **1** | |

All 7 tests are JVM unit tests. The change scope (boot-receiver gate + cancel-list restriction) is fully unit-testable; the pre-existing `WorkManagerTestInitHelper` already provides the integration surface for `getWorkInfosForUniqueWork` and `enqueueUnique*`. No new layer was needed.

---

### Changed File Coverage

| File | Line % | Branch % | Uncovered Lines | Rating |
|------|--------|----------|-----------------|--------|
| `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` | — | — | — | — |
| `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` | — | — | — | — |

**Coverage analysis skipped — no coverage tool detected** (per `openspec/config.yaml` `testing.coverage.command: ""`).

Manual evidence of coverage on the changed production code (`BootReceiver.onBootCompleted`):
- Happy path (session present, scheduleOutboxDrainer + WorkerInitializer.initialize) → covered by `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`.
- Cancel path (session null, 1 cancelWork for `_after_boot` + Log.w) → covered by `onBootCompleted_without_session_cancels_only_after_boot_chain` (lines 274-288) — exercises the new cancelled-list-shape (1 call, specific name, no other names).
- After-pairing preservation across no-session boot → covered by `onBootCompleted_without_session_preserves_after_pairing_work`.
- Periodic OutboxDrainer preservation across no-session boot → covered by `onBootCompleted_without_session_preserves_periodic_outbox_drainer`.
- Periodic ReconciliationWorker preservation across no-session boot → covered by `onBootCompleted_without_session_preserves_periodic_reconciliation`.
- `BootReceiver.onPackageReplaced` (delegates to `onBootCompleted`) → transitively covered.
- `BootReceiver.isUsageServiceRunning` / `startMonitorForegroundService` → pre-existing coverage (out of scope).
- `BootReceiver.onReceive` dispatch → pre-existing coverage (out of scope).

All 4 changed production-code lines (gate move, 1 cancel call, comment block, log line) and all 4 changed spec scenarios (after-pairing preserved, 2× periodic preserved, cancel-bounded) are exercised by the test surface.

---

### Assertion Quality

Per `strict-tdd-verify.md` Step 5f (mandatory Assertion Quality Audit).

| File | Line(s) | Assertion | Issue | Severity |
|------|---------|-----------|-------|----------|
| `BootReceiverTest.kt` | 110-114 | `assertEquals(3, infos.size)` for `getWorkInfosForUniqueWork("...after_boot")` | Asserts on real WorkManager DB contents (chain length 3) | — OK (pre-existing) |
| `BootReceiverTest.kt` | 145-149 | `assertEquals(0, infos.size)` | Companion "no work" assertion (pre-existing) | — OK |
| `BootReceiverTest.kt` | 152-156 | `assertTrue(warnings.any { it.msg.contains("no stored session") })` | Companion log assertion (pre-existing) | — OK |
| `BootReceiverTest.kt` | 214 | `verify(exactly = 1) { WorkScheduler.scheduleOutboxDrainer(context) }` | Behavioral call-count on public WorkScheduler API | — OK |
| `BootReceiverTest.kt` | 218 | `verify(exactly = 1) { WorkerInitializer.initialize(context, true) }` | Behavioral call-count on public WorkerInitializer API | — OK |
| `BootReceiverTest.kt` | 223-227 | `verifyOrder { mockAuthManager.restoreSession(); WorkScheduler.scheduleOutboxDrainer(context); WorkerInitializer.initialize(context, true) }` | Pins the gate contract: order over the spec surface | — OK |
| `BootReceiverTest.kt` | 229 | `verify(exactly = 0) { WorkScheduler.cancelWork(any(), any()) }` | Pins the spec invariant "no cancel when session present" | — OK |
| `BootReceiverTest.kt` | 274-276 | `verify(exactly = 1) { WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot") }` | Bounded positive assertion (exactly 1 for the after-boot name) | — OK |
| `BootReceiverTest.kt` | 279-281 | `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }` | Bounded negative assertion (exactly 0 for the periodic name) — pins spec scenario 5 | — OK |
| `BootReceiverTest.kt` | 284-286 | `verify(exactly = 0) { WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME) }` | Bounded negative assertion (exactly 0 for the periodic name) — pins spec scenario 5 | — OK |
| `BootReceiverTest.kt` | 288 | `verify(exactly = 1) { WorkScheduler.cancelWork(context, any()) }` | Bounding total: no extra cancel calls beyond the 1 named call | — OK |
| `BootReceiverTest.kt` | 360-362 | `verify(exactly = 0) { WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_pairing") }` | Bounded negative assertion (exactly 0 for the after-pairing name) — pins spec scenario 6 | — OK |
| `BootReceiverTest.kt` | 367-371 | `assertEquals(1, postInfos.size)` for `getWorkInfosForUniqueWork("...after_pairing")` | Asserts real WorkManager DB contents — pre-enqueue survived | — OK |
| `BootReceiverTest.kt` | 445-447 | `verify(exactly = 0) { WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME) }` | Bounded negative assertion (no cancel for periodic OutboxDrainer) | — OK |
| `BootReceiverTest.kt` | 453-457 | `assertEquals(1, postInfos.size)` for `getWorkInfosForUniqueWork(OutboxDrainer.WORK_NAME)` | Asserts real WorkManager DB contents — periodic entry survived | — OK |
| `BootReceiverTest.kt` | 523-525 | `verify(exactly = 0) { WorkScheduler.cancelWork(context, ReconciliationWorker.WORK_NAME) }` | Bounded negative assertion (no cancel for periodic Reconciliation) | — OK |
| `BootReceiverTest.kt` | 531-535 | `assertEquals(1, postInfos.size)` for `getWorkInfosForUniqueWork(ReconciliationWorker.WORK_NAME)` | Asserts real WorkManager DB contents — periodic entry survived | — OK |

**Mock/assertion ratio per test** (mock setups vs verify/assert statements):
- `onBootCompleted_with_restored_session_enqueues_sync_after_boot` (pre-existing): 1 `mockkObject` + 2 `every` = 3 mock setups, 1 assert → 3:1
- `onBootCompleted_with_null_restored_session_skips_sync_and_logs_warning` (pre-existing): 1 `mockkObject` + 2 `every` = 3 mock setups, 2 asserts + 2 `verifyOrder` = 4 verifications → 3:4
- `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` (batch 1 NEW): 3 `mockkObject` + 3 `every` = 6 mock setups, 5 verifies + 1 `verifyOrder` = 6 verifications → 1:1
- `onBootCompleted_without_session_cancels_only_after_boot_chain` (batch 2 RENAME): 2 `mockkObject` + 2 `every` = 4 mock setups, 4 verifies → 1:1
- `onBootCompleted_without_session_preserves_after_pairing_work` (batch 2 NEW): 2 `mockkObject` + 2 `every` = 4 mock setups, 1 verify + 2 asserts = 3 verifications → 4:3
- `onBootCompleted_without_session_preserves_periodic_outbox_drainer` (batch 2 NEW): 2 `mockkObject` + 2 `every` = 4 mock setups, 1 verify + 2 asserts = 3 verifications → 4:3
- `onBootCompleted_without_session_preserves_periodic_reconciliation` (batch 2 NEW): 2 `mockkObject` + 2 `every` = 4 mock setups, 1 verify + 2 asserts = 3 verifications → 4:3

No test exceeds the 2:1 mock/assertion threshold. No tautologies. No ghost loops (no loops in any of the new tests). No type-only assertions. No smoke-test-only assertions. The `verifyOrder` and `verify(exactly = 0)` are spec-pinned behavioural invariants, not implementation detail coupling — the assertions target the public `WorkScheduler` surface that the spec mandates the receiver call.

**Assertion quality**: ✅ All assertions verify real behavior.

---

### Quality Metrics

**Linter**:
- `detekt` — ✅ No errors. `BUILD SUCCESSFUL` (no findings in `BootReceiver.kt` or `BootReceiverTest.kt`).
- `ktlintCheck` — ⚠️ `BUILD FAILED` with 479 pre-existing violations in 24 untouched test files. `BootReceiverTest.kt` (the only test file modified by this change) has ZERO violations (verified via `grep` on the ktlint report). `ktlintMainSourceSetCheck` reports 0 violations. The failure is at the project level due to test files that pre-date this change and are out of scope. See WARNING #2.

**Type Checker**:
- `assembleDebug --rerun-tasks` — ✅ `BUILD SUCCESSFUL`. Full Kotlin compilation succeeds for both `main` and `test` source sets, including Hilt aggregation. APK packaging completes.

---

### Issues Found

**CRITICAL**: None.

**WARNING**:

1. **Spec scenario UNTESTED — "In-flight worker runs are not affected by the gate"** (2 scenarios in `boot-worker-lifecycle`: "Worker started before reboot completes its run" + "Session restored mid-worker-run"). These are OS-level / integration-level invariants not unit-testable in JVM Robolectric:
   - "Worker started before reboot completes its run" — OS terminates in-flight runs on reboot by design; this is enforced by the Android framework, not the receiver. The `BootReceiver` code never references the prior run.
   - "Session restored mid-worker-run" — requires a real worker fixture running concurrently with the receiver; would need a `Worker` integration test in `connectedDebugAndroidTest`.
   - Per the orchestrator's preflight direction and `apply-progress.md` "Issues Found → Out of scope (pre-existing)", classify as WARNING, not CRITICAL. The acceptance criterion is enforced by the Android framework (scenario 1) and the per-run `restoreSession()` call inside the workers (scenario 2); the receiver does not need to take any action.
2. **Pre-existing ktlint violations in 24 untouched test files (479 violations total)**. `BootReceiverTest.kt` (the only test file modified by this change) has ZERO violations. The project's ktlintCheck is not wired up as a merge gate (per `openspec/config.yaml` `verify.test_command: ./gradlew testDebugUnitTest` and `verify.build_command: ./gradlew assembleDebug`). The v1.0 verify report's "BUILD SUCCESSFUL" claim for ktlintCheck was a false positive from UP-TO-DATE caching — the actual run fails. **Not a regression from this change;** recommended to be addressed in a separate "lint cleanup" change.
3. **Design deviation from `design.md` Decision 2** (already disclosed in `apply-progress.md`). D2 originally said "cancel three names". The implementation cancels only ONE (the spec is the source of truth; the design was wrong). The new code matches the spec. **Follow-up: update `design.md` Decision 2 to match the implementation** (one-line edit, out of scope for this change per the orchestrator's preflight).
4. **No coverage tool installed**. Per `openspec/config.yaml` `testing.coverage.command: ""`, no Kover or JaCoCo plugin is wired up. Coverage analysis is therefore unavailable. Informational, not a regression.

**SUGGESTION**:

1. **Spec/code drift — "Chain ordering at boot with a session"** (⚠️ PARTIAL coverage in the matrix). The spec lists `ReconciliationWorker.WORK_NAME` in the boot chain (`boot-worker-lifecycle/spec.md` scenario "Chain ordering at boot with a session"), but `WorkScheduler.scheduleSyncAfterBoot` (`app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt:106-135`) actually chains `SyncWorker → HeartbeatWorker → OutboxDrainer`. This change does NOT modify the chain contents (it only moves the schedule inside the gate and restricts the no-session cancel list). The chain-vs-spec mismatch is a pre-existing spec/code drift, acknowledged in `design.md` "Open Questions" and `apply-progress.md` "Issues Found → Out of scope". The test `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` asserts `WorkerInitializer.initialize(ctx, true)` is invoked (which transitively triggers the chain via `scheduleSyncAfterBoot`), but does not assert the internal step names. Recommend a follow-up change that reconciles the spec chain contents with `scheduleSyncAfterBoot` reality — either fix the spec or fix the chain.
2. **Retroactively update `design.md` Decision 2** to match the implementation (mitigates WARNING #3). The decision should now read: "ONE explicit `WorkScheduler.cancelWork(context, name)` call in the `else` branch (for `${SyncWorker.WORK_NAME}_after_boot`). The periodics and after-pairing MUST survive a no-session reboot per spec scenarios 5 and 6." This is a one-paragraph text edit and is out of scope for this change.
3. **Add a `WorkSchedulerTest` case for `ExistingPeriodicWorkPolicy.KEEP` on `scheduleOutboxDrainer`**. The test `onBootCompleted_without_session_preserves_periodic_outbox_drainer` (test 3.3) pins the receiver-level invariant, but the underlying `KEEP` policy is a `WorkScheduler` contract. A direct test of `scheduleOutboxDrainer` called twice would close the gap at the lower layer.

---

### Verdict

**PASS WITH WARNINGS**

All three quality gates pass with real execution evidence (forced `--rerun-tasks`, not UP-TO-DATE cached): 645/645 unit tests pass (including 7/7 `BootReceiverTest` cases — 2 pre-existing + 1 batch-1 NEW + 1 batch-2 RENAME + 3 batch-2 NEW), `assembleDebug` produces a valid APK, `detekt` is clean. 3/4 design decisions are followed; Decision 2 is a documented, intentional deviation that aligns the implementation with the spec (the design text is wrong, the new code is right). The 2 remaining UNTESTED spec scenarios are OS-level / integration-level invariants that the `BootReceiver` code cannot exercise in JVM Robolectric — classified as WARNING per the orchestrator's preflight. The pre-existing ktlint violations in 24 untouched test files are not a regression from this change. No CRITICAL findings. Implementation matches the change proposal, the corrected spec-driven design, and the 22 tasks in `tasks.md`; the orchestrator may proceed to `sdd-archive` (with the recommended design.md Decision 2 retroactive update as an optional follow-up).
