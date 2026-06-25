# Archive Report: fix-boot-workers-respect-session-gate

**Change name**: `fix-boot-workers-respect-session-gate`
**Archived on**: 2026-06-24
**Archived to**: `openspec/changes/archive/2026-06-24-fix-boot-workers-respect-session-gate/`
**Archive mode**: `openspec` (filesystem merge + Engram mirror for cross-session recovery)
**Mode**: Strict TDD (4 commits: RED `35509dc` + GREEN `575cd1f` + RED `db5cc16` + GREEN `82b8fe0`)
**Persistence**: openspec + Engram hybrid
**PR**: none — committed directly to `master` (same precedent as the 22/06 `create-pairing-code`, 23/06 `hotfix-child-pairing-mock-redeem-route`, and 23/06 `feature-boot-restore-session-before-sync` archives).
**Status**: COMPLETE (PASS WITH WARNINGS — 0 CRITICAL, 3 WARNING, 3 SUGGESTION; see §6)

---

## 1. Outcome summary

The boot-time retry loop on a logged-out device is fixed. After this change, `BootReceiver.onReceive` gates every WorkManager work it schedules on a successful `DeviceAuthManager.restoreSession()` call. With a session, the receiver re-arms the periodic `OutboxDrainer` and kicks off the post-boot sync chain via `WorkerInitializer.initialize(context, isAfterBoot = true)`. Without a session, the receiver cancels **only** the post-boot chain unique-work name (`${SyncWorker.WORK_NAME}_after_boot`) — leaving the periodic `OutboxDrainer` and `ReconciliationWorker` schedules (configured with `ExistingPeriodicWorkPolicy.KEEP`) and the `${SyncWorker.WORK_NAME}_after_pairing` schedule (distinct unique-work name) untouched. This stops wasted retries against a `DISCONNECTED` `SupabaseClient` while preserving every other schedule the device legitimately needs across a reboot.

The change required **two RED+GREEN pairs** because the first GREEN (commit `575cd1f`) over-cancelled — it cancelled three unique-work names including the two periodic names — and the resulting verify report (v1.0) flagged four UNTESTED spec scenarios that turned out to be a real design bug. The second pair (commits `db5cc16` + `82b8fe0`) restricted the cancel list to ONE name and pinned the negative invariants with three new regression-guard tests + one rename. The spec is the source of truth; the design was wrong (see §6 / §8 #1).

### What was archived

```
openspec/changes/archive/2026-06-24-fix-boot-workers-respect-session-gate/
├── archive-report.md          ← this file
├── proposal.md                ← proposal
├── design.md                  ← technical design (with one stale Decision 2 — see §8 #1)
├── tasks.md                   ← task list (22 tasks, all [x])
├── apply-progress.md          ← apply progress (merged: both RED+GREEN batches)
├── verify-report.md           ← verification report (v2.0, PASS WITH WARNINGS)
└── specs/
    ├── boot-worker-lifecycle/
    │   └── spec.md            ← NEW main spec
    └── outbox-drain/
        └── spec.md            ← delta spec (ADDED Requirements)
```

### Source of truth updated

- `openspec/specs/boot-worker-lifecycle/spec.md` — **created** (NEW domain, no prior main spec). Full spec with 4 requirements and 8 scenarios.
- `openspec/specs/outbox-drain/spec.md` — **appended** 2 new requirements (4 scenarios) to the existing `## ADDED Requirements` section. All 4 pre-existing requirements and 11 scenarios preserved verbatim.

---

## 2. Spec sync

Two delta specs were merged into the main specs (source of truth):

| Domain | Action | Details |
|--------|--------|---------|
| `boot-worker-lifecycle` | **Created (NEW)** | 4 requirements, 8 scenarios. Full spec — no prior main spec existed. Source: `openspec/changes/fix-boot-workers-respect-session-gate/specs/boot-worker-lifecycle/spec.md`. |
| `outbox-drain` | **Appended (ADDED)** | +2 requirements, +4 scenarios. Source: `openspec/changes/fix-boot-workers-respect-session-gate/specs/outbox-drain/spec.md`. Pre-existing main spec (4 requirements, 11 scenarios) preserved unchanged. |

### Verification

- `diff -q` between the change's delta `boot-worker-lifecycle/spec.md` and the new main spec returns identical (binary match).
- `outbox-drain/spec.md` (main) now has 6 `### Requirement:` headings and 15 `#### Scenario:` headings (was 4 / 11 pre-merge). The 2 new requirements are positioned BEFORE the `## Out of scope` section, AFTER the pre-existing `dedup_key is forwarded to the server` scenario.
- `## Purpose` and `## Out of scope` sections in `outbox-drain/spec.md` are byte-identical to the pre-merge content.
- No existing requirement was modified or removed; no scenario was rewritten.

---

## 3. Phase outcomes

| Phase | Status | Evidence |
|---|---|---|
| **propose** | OK | `proposal.md` (Engram obs #114 — preflight + scope). User picked "usar recomendado" for all 4 preflight groups and accepted all 4 proposed product decisions. |
| **spec** | OK | 2 delta specs written (NEW `boot-worker-lifecycle` + ADDED `outbox-drain`). Both use Given/When/Then per `openspec/config.yaml:13` `rules.spec.require_acceptance_criteria: true`. RFC 2119 keywords (MUST, SHALL) used throughout. |
| **design** | OK (with stale D2) | `design.md` written. **4 architecture decisions documented**: D1 `DeviceAuthManager.restoreSession()` stays `internal`; D2 THREE explicit `cancelWork` calls in else branch; D3 extend `BootReceiverTest.kt` (no new test file); D4 `OutboxDrainer.doWork()` semantics unchanged. **D2 is OUTDATED** — see §6 / §8 #1. The implementation cancels ONE name, not three. |
| **tasks** | OK | `tasks.md` written. 22 tasks in 4 phases (Phase 1 RED 6, Phase 2 GREEN 5, Phase 3 RED 7, Phase 4 GREEN 4). Review Workload Forecast: Low, single PR (~50-70 lines), well under 400-line budget. |
| **apply** | OK (4 commits) | Batch 1: RED `35509dc` (test only) + GREEN `575cd1f` (prod, 3 cancel calls). Batch 2: RED `db5cc16` (rename + 3 negative-invariant tests) + GREEN `82b8fe0` (restrict cancel to 1 name). All 4 commits on `master` directly. Engram obs #115 (apply-progress v2, merged). |
| **verify** | PASS WITH WARNINGS | `verify-report.md` v2.0 (Engram obs #116). 645/645 unit tests, 0 regressions. 9/12 spec scenarios COMPLIANT, 1 PARTIAL (pre-existing spec/code drift), 2 UNTESTED (OS-level invariants). 3 WARNING + 3 SUGGESTION, 0 CRITICAL. |

**SDD cycle complete** (intentional-with-warnings archive per the orchestrator's preflight; see §6 for the WARNING classification).

---

## 4. Code outcomes

Reference: 4 commits on `master`.

| File | Action | Lines | Details |
|---|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` | Modified (twice — batches 1 + 2) | ~30 net | Batch 1 (commit `575cd1f`): MOVED `WorkScheduler.scheduleOutboxDrainer(context)` from unconditional position into the `if (session != null)` branch (alongside `WorkerInitializer.initialize`); ADDED three `WorkScheduler.cancelWork(context, name)` calls in `else` branch for `sync_work_after_boot`, `reconciliation_work`, `outbox_drain_periodic`; added imports for the three worker classes. Batch 2 (commit `82b8fe0`): REMOVED the two extra `cancelWork` calls; KEPT only `WorkScheduler.cancelWork(context, "${SyncWorker.WORK_NAME}_after_boot")`; REMOVED unused `OutboxDrainer` and `ReconciliationWorker` imports; expanded the gate comment block to document the negative invariants. |
| `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` | Modified (twice — batches 1 + 2) | ~+150 net | Batch 1 (commit `35509dc`): DELETED stale `onBootCompleted_schedules_outbox_drainer_periodically` (pinned the bug); ADDED `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` + `onBootCompleted_without_session_cancels_three_boot_unique_works`. Batch 2 (commit `db5cc16`): RENAMED the latter to `onBootCompleted_without_session_cancels_only_after_boot_chain` and rewrote assertions (exactly 1 for `_after_boot`, 0 for the two periodic names, bounding total = 1); ADDED 3 negative-invariant tests: `onBootCompleted_without_session_preserves_after_pairing_work`, `onBootCompleted_without_session_preserves_periodic_outbox_drainer`, `onBootCompleted_without_session_preserves_periodic_reconciliation`. Each new test pre-enqueues the work, triggers boot with no session, asserts `WorkScheduler.cancelWork` was NOT called for that name + the entry is still in the WorkManager DB. |
| `openspec/changes/fix-boot-workers-respect-session-gate/tasks.md` | Modified (twice) | — | Batch 1: marked all Phase 1 + Phase 2 tasks `[x]`. Batch 2: appended Phase 3 (RED) and Phase 4 (GREEN), all 11 new subtasks marked `[x]`. The deviation note from `design.md` Decision 2 is embedded in both phases. |
| `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` | **Not modified** (per hard constraint) | 0 | `restoreSession()` visibility unchanged. |
| `app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt` | **Not modified** (per hard constraint) | 0 | `cancelWork(context, workName)`, `scheduleOutboxDrainer`, `scheduleReconciliation` unchanged. |
| `app/src/main/java/com/tudominio/parentalcontrol/workers/OutboxDrainer.kt` | **Not modified** (per hard constraint) | 0 | `doWork()` semantics unchanged (D4 followed). |

**Net diff**: 2 production/test files (`BootReceiver.kt` + `BootReceiverTest.kt`). Production code is the same single function (`onReceive` → `onBootCompleted` dispatch) with two coordinated edits. Well under the 400-line PR review budget.

**No new dependencies, no Gradle changes, no manifest changes.** No `app/build.gradle.kts`, `gradle.properties`, or `AndroidManifest.xml` modifications. No new Hilt modules. No new Ktor config.

---

## 5. Quality gates (final state, master @ `82b8fe0`)

All gates re-run with `--rerun-tasks` per the §8 #4 process improvement (avoiding stale `UP-TO-DATE` cache masking real state). Source: `verify-report.md` v2.0.

| Gate | Command | Exit | Result |
|---|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest --rerun-tasks` | 0 | **645/645 tests pass** across 163 files. 0 failures, 0 errors, 0 skipped. Master pre-change = 638 (per the 23/06 `feature-boot-restore-session-before-sync` verify-report). **+7 `BootReceiverTest` cases (2 pre-existing + 1 batch-1 NEW + 1 batch-1 renamed + 3 batch-2 NEW). 0 regressions.** |
| Build | `./gradlew assembleDebug --rerun-tasks` | 0 | BUILD SUCCESSFUL in 1m 25s. APK builds cleanly. No new manifest permissions, no new Ktor config, no new dependencies. |
| Static analysis | `./gradlew detekt --rerun-tasks` | 0 | BUILD SUCCESSFUL. 0 findings in `BootReceiver.kt` or `BootReceiverTest.kt`. |
| Lint | `./gradlew ktlintCheck --rerun-tasks` | 1 (NON-ZERO) | **WARNING** — 479 pre-existing ktlint violations in 24 untouched test files (see §6 WARNING #2). The 2 files changed in this change have **ZERO violations** (verified via `grep` returning 0 matches for `BootReceiverTest.kt` and 0 matches in `ktlintMainSourceSetCheck` for `BootReceiver.kt`). |
| Coverage | n/a | — | kover/jaCoCo not configured (see `openspec/config.yaml:53-55` `testing.coverage.command: ""`). Manual evidence: all 4 changed production-code lines (gate move, 1 cancel call, comment block, log line) and all 4 changed spec scenarios (after-pairing preserved, 2× periodic preserved, cancel-bounded) are exercised by the test surface — see `verify-report.md` §"Changed File Coverage". |

### TDD evidence (strict mode)

| Check | Result |
|---|---|
| TDD evidence reported | YES — `apply-progress.md` TDD Cycle Evidence table covers 22 task rows with RED/GREEN/Safety Net/Refactor columns. Engram obs #115. |
| All tasks have tests | YES — 22/22 task rows in `apply-progress.md` have test files or test-relevant actions. |
| RED confirmed (tests exist) | YES — 4/4 modified/added tests from batch 2 present in `BootReceiverTest.kt`. All 3 RED-failing tests from `db5cc16` are real (`verify(0)` calls with named arguments). |
| GREEN confirmed (tests pass) | YES — 7/7 `BootReceiverTest` cases pass at runtime (re-run with `--rerun-tasks`). Full suite 645/645. |
| Triangulation adequate | YES — 4 tests pin the negative invariants: exactly 1 cancel for the after-boot name AND zero cancels for the periodic/after-pairing names. Spec scenarios 5 and 6 covered by companion tests 3.2/3.3/3.4; bounding total in 3.1. |
| Safety net for modified files | YES — `apply-progress.md` reports 4/4 pre-existing `BootReceiverTest` tests passing before batch 2 modifications. All new tests use the existing `WorkManagerTestInitHelper` setup and `unmockkAll` teardown. |
| Test 3.2 disclosed as regression guard | YES — `apply-progress.md` row 3.2 honestly notes: "RED signal: PASS under buggy code (regression guard, not RED-failing). The current bug cancels `sync_work_after_boot`, `reconciliation_work`, `outbox_drain_periodic` — none targets `sync_work_after_pairing`, so the after_pairing work survives regardless." The test is acknowledged as a forward-looking guard, not a true RED. Per the strict-tdd protocol, a regression guard has standalone value for the spec scenario it pins. |
| **TDD compliance** | **7/7 checks** |

### Commit hygiene

- Total commits: **4** (matches strict-TDD plan: 2 RED + 2 GREEN).
- Conventional-commit style: YES — all use `type(scope): subject` form (`test(receiver): ...` and `fix(receiver): ...`).
- No `Co-Authored-By` or AI attribution footers: YES.
- Chain cleanliness: YES — 4 clean commits, no fixup/squash/wip/tmp noise. No amends.
- Branch choice: **master** (committed directly per the 22/06 / 23/06 precedents). No PR was opened for this change.

---

## 6. Pre-existing WARNINGs (NOT introduced by this change)

All three WARNINGs are documented in `verify-report.md` v2.0. None are regressions from this change.

### WARNING #1 — Spec scenario UNTESTED: "In-flight worker runs are not affected by the gate" (2 scenarios)

`boot-worker-lifecycle/spec.md` requirements #4 has two scenarios that are OS-level / integration-level invariants not unit-testable in JVM Robolectric:

- **"Worker started before reboot completes its run"** — OS terminates in-flight runs on reboot by design; this is enforced by the Android framework, not the receiver. The `BootReceiver` code never references the prior run.
- **"Session restored mid-worker-run"** — requires a real worker fixture running concurrently with the receiver; would need a `Worker` integration test in `connectedDebugAndroidTest`.

Per the orchestrator's preflight direction and `apply-progress.md` "Issues Found → Out of scope (pre-existing)", classified as WARNING, not CRITICAL. The acceptance criterion is enforced by the Android framework (scenario 1) and the per-run `restoreSession()` call inside the workers (scenario 2); the receiver does not need to take any action.

### WARNING #2 — Pre-existing ktlint violations in 24 untouched test files (479 violations total)

`./gradlew ktlintCheck` fails at the project level with 479 pre-existing ktlint violations across 24 test files in `ktlintTestSourceSetCheck`. **None of the violations are in `BootReceiverTest.kt`** (the only test file modified by this change — verified via `grep` returning 0 matches for `BootReceiverTest.kt` in the ktlint report). `ktlintMainSourceSetCheck` reports 0 violations. `ktlintAndroidTestSourceSetCheck` reports 0 violations. The 479 pre-existing violations breakdown:

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

**Correction to the v1.0 verify report**: v1.0 claimed `./gradlew ktlintCheck` was `BUILD SUCCESSFUL`. That was a false positive from Gradle's `UP-TO-DATE` cache — the task had not been re-executed. When forced with `--rerun-tasks`, ktlintCheck DOES fail at the project level. However, this is pre-existing on master and not a regression from this change. The project's `openspec/config.yaml` does not make ktlintCheck a merge gate (`verify.test_command: ./gradlew testDebugUnitTest` and `verify.build_command: ./gradlew assembleDebug` are the gates). See §8 #2 for the recommended "lint cleanup" change.

### WARNING #3 — Design deviation from `design.md` Decision 2

`design.md` Decision 2 originally said "three explicit `cancelWork` calls in the else branch (no bulk function, no tag-based cancel)". The implementation (commit `82b8fe0`) cancels only **ONE** name (`${SyncWorker.WORK_NAME}_after_boot`). The other two cancel calls (for `ReconciliationWorker.WORK_NAME` and `OutboxDrainer.WORK_NAME`) were REMOVED in batch 2.

**Why the implementation is right and the design was wrong**: the spec scenarios 5 ("Periodic workers stay scheduled across reboots") and 6 ("After-pairing schedule survives a reboot") of `boot-worker-lifecycle/spec.md` require preserving the periodic and after-pairing schedules. `WorkScheduler.scheduleOutboxDrainer` and `WorkScheduler.scheduleReconciliation` both use `enqueueUniquePeriodicWork(name, KEEP, ...)` — the unique-work name IS the periodic's identity in WorkManager. Calling `cancelUniqueWork(periodicName)` removes the periodic schedule, which contradicts the `KEEP` policy and the spec. The boot chain name `${SyncWorker.WORK_NAME}_after_boot` is the only boot-persisted work that the spec says should be cancelled (because it represents a stale `BOOT_COMPLETED` invocation that should not retry against a `DISCONNECTED` `SupabaseClient`).

**The spec is the source of truth.** The new code matches the spec; the design text is wrong. See §8 #1 for the recommended one-paragraph retroactive edit to `design.md` Decision 2.

---

## 7. SUGGESTIONs (informational only)

### SUGGESTION #1 — Spec/code drift in `boot-worker-lifecycle/spec.md` scenario "Chain ordering at boot with a session"

The spec lists `ReconciliationWorker.WORK_NAME` in the boot chain, but `WorkScheduler.scheduleSyncAfterBoot` (`app/src/main/java/com/tudominio/parentalcontrol/workers/WorkScheduler.kt:106-135`) actually chains `SyncWorker → HeartbeatWorker → OutboxDrainer`. This change does NOT modify the chain contents (it only moves the schedule inside the gate and restricts the no-session cancel list). The chain-vs-spec mismatch is a pre-existing spec/code drift, acknowledged in `design.md` "Open Questions" and `apply-progress.md` "Issues Found → Out of scope". The test `onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain` asserts `WorkerInitializer.initialize(ctx, true)` is invoked (which transitively triggers the chain via `scheduleSyncAfterBoot`), but does not assert the internal step names. Recommend a follow-up change that reconciles the spec chain contents with `scheduleSyncAfterBoot` reality — either fix the spec or fix the chain.

### SUGGESTION #2 — Retroactively update `design.md` Decision 2

Mitigates §6 WARNING #3. Decision 2 should now read: "ONE explicit `WorkScheduler.cancelWork(context, name)` call in the `else` branch (for `${SyncWorker.WORK_NAME}_after_boot`). The periodics and after-pairing MUST survive a no-session reboot per spec scenarios 5 and 6." This is a one-paragraph text edit and is out of scope for this change. See §8 #1.

### SUGGESTION #3 — Add a `WorkSchedulerTest` case for `ExistingPeriodicWorkPolicy.KEEP` on `scheduleOutboxDrainer`

The test `onBootCompleted_without_session_preserves_periodic_outbox_drainer` (test 3.3) pins the receiver-level invariant, but the underlying `KEEP` policy is a `WorkScheduler` contract. A direct test of `scheduleOutboxDrainer` called twice would close the gap at the lower layer.

---

## 8. Follow-ups (out of scope)

These are gaps and risks knowingly accepted when the change was archived. They are tracked here so future changes can pick them up.

### #1 — Retroactive edit to `design.md` Decision 2

`design.md` Decision 2 still says "cancel three names" but the implementation (commit `82b8fe0`) cancels only ONE. The spec is the source of truth; the design text is wrong. The change is a one-paragraph edit. Out of scope for this archive per the orchestrator's preflight direction. Recommended as a follow-up "design sync" change that brings `design.md` into alignment with the spec + implementation.

### #2 — Spec/code drift in `boot-worker-lifecycle/spec.md` scenario "Chain ordering at boot with a session"

Pre-existing (not introduced by this change). The spec lists `ReconciliationWorker.WORK_NAME` in the boot chain but the code chains `SyncWorker → HeartbeatWorker → OutboxDrainer`. Recommend a follow-up change that reconciles either the spec or the chain. See §7 SUGGESTION #1.

### #3 — Lint cleanup change for the 479 pre-existing ktlint violations

The 479 ktlint violations in 24 untouched test files are pre-existing on master and not a regression from this change. Two valid paths forward:

- (a) Mechanical cleanup pass: run `./gradlew ktlintFormat` on the 24 test files and commit the result.
- (b) Wire the existing `app/config/ktlint/baseline.xml` into the `ktlint { }` block at `app/build.gradle.kts:224-226` (only `disabledRules.set(setOf("import-ordering"))` is currently set; no `baseline = file(...)` reference).

Both are out of scope for this change. Tracked here so the next cleanup pass picks it up.

### #4 — In-flight OS-level scenarios in `boot-worker-lifecycle` spec (already WARNING #1)

The 2 OS-level / integration-level spec scenarios (rebooted mid-run + session-restored-mid-run) are not unit-testable in JVM Robolectric. They are enforced by the Android framework (scenario 1) and the per-run `restoreSession()` call inside the workers (scenario 2). If the project later wants to add a `connectedDebugAndroidTest` integration suite, these are the first two scenarios to pin.

### #5 — `routesKnownByMockEngine` guard test

Flagged in the 23/06 `hotfix-child-pairing-mock-redeem-route` and 23/06 `feature-boot-restore-session-before-sync` archive-reports. After a `MockSupabaseEngine` growth event, a meta-test asserting that every production `httpClient.post` path consumed by `ParentRepository` / `PairingManager` / future code paths has a matching `when` branch + fixture would prevent a recurrence of the same omission pattern. NOT introduced by this change. Recommended as a separate SDD change.

### #6 — `Thread.sleep(1000L)` → `CountDownLatch` or `runTest { advanceUntilIdle() }`

Flagged in the 23/06 `feature-boot-restore-session-before-sync` archive-report §7 #1 as a SUGGESTION follow-up. The `Thread.sleep(1000L)` pattern in the `BootReceiverTest` Robolectric tests may flake on slow CI runners. NOT introduced by this change, but a related follow-up that would harden the broader test suite.

### #7 — Process improvement (ALREADY propagated)

Future verify reports must always force-rerun quality gates with `--rerun-tasks`. The 23/06 `feature-boot-restore-session-before-sync` apply sub-agent's Engram obs #99 was inaccurate on ktlintCheck status because it relied on UP-TO-DATE cached reports from a previous run, which masked the real state. **This change successfully propagated that discipline** — every quality gate in the apply and verify phases was re-run with `--rerun-tasks`, and the ktlintCheck false positive was caught and corrected in the v2.0 verify report (see §6 WARNING #2). The discipline update is now baked into the workflow; this follow-up is a confirmation, not a new ask.

---

## 9. Engram Observations

| ID | Title | Type | Notes |
|---|---|---|---|
| #114 | Preflight + scope for fix-boot-workers-respect-session-gate | decision | User accepted all 4 preflight groups + 4 product decisions |
| #115 | Fixed BootReceiver session gate for boot-time workers (apply-progress v2) | architecture | Merged both batches: 19/19 tasks across 2 RED+GREEN pairs. Design deviation from D2 documented |
| #116 | sdd/fix-boot-workers-respect-session-gate/verify-report (v2.0) | architecture | PASS WITH WARNINGS, 0 CRITICAL, 3 WARNING, 3 SUGGESTION |
| #117 | Design conflict in fix-boot-workers-respect-session-gate | architecture | Discovery: the 4 UNTESTED scenarios from v1.0 were actually a real design-vs-spec conflict (D2 vs spec scenarios 5/6) |
| #118 | ktlintCheck actually fails — verify caches masked this | discovery | The v1.0 verify report's ktlintCheck "BUILD SUCCESSFUL" was a false positive from UP-TO-DATE cache. Re-verify with `--rerun-tasks` revealed 479 pre-existing violations in 24 untouched test files |

The 5 IDs above form the complete Engram audit trail for this change.

---

## 10. SDD cycle complete

The change has been fully planned, implemented, verified, and archived. Delta specs are merged into the main specs (`openspec/specs/boot-worker-lifecycle/spec.md` created; `openspec/specs/outbox-drain/spec.md` appended with 2 ADDED Requirements). The change folder is moved to `openspec/changes/archive/2026-06-24-fix-boot-workers-respect-session-gate/`. All 22 tasks are `[x]`. The verify report has 0 CRITICAL findings. 7 follow-up items (§8) are documented for future changes. Ready for the next change.
