# Archive Report: fix-migrate-stale-parent-id-on-load

> **STATUS: ARCHIVED 2026-07-08** — fix landed on master via PR
> [#28](https://github.com/Andrea-Caballero/parentalControl/pull/28) at
> `5e392a6`. Mini-SDD lite bug fix, sister change to
> `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` (PR #27).
> Paper-thin spec delta (1 ADDED requirement, 2 scenarios, ~120 words)
> per user pick (d) at the proposal round. No `design.md` (`proposal.md`
> is the design-of-record). Single PR, 2 commits (RED+GREEN bundled +
> chore). ~13 LoC production + 281 LoC test (the 5-case
> `DeviceAuthManagerMigrationTest.kt` was on disk from the explore sub-agent
> in RED state), well under the 400-line review budget. Closes the
> live-test-#5 gap where parents with pre-PR-#27 stale auth state (missing
> `parent_id`) still saw "Sin eventos" in `BehaviorLogScreen`.

## Change Summary

- **Change id**: `2026-07-08-fix-migrate-stale-parent-id-on-load`
- **Archive path**: `openspec/changes/archive/2026-07-08-fix-migrate-stale-parent-id-on-load/`
- **Delivery model**: single PR (no chain)
- **PR**: [#28](https://github.com/Andrea-Caballero/parentalControl/pull/28) — branch `fix/migrate-stale-parent-id-on-load`. Base `master @ 2820e59`, head `5e392a6`. Merged 2026-07-08.
- **Master current SHA**: `5e392a6`
- **Context**: closes the live-test-#5 gap where parents with pre-PR-#27 stale auth state (missing `parent_id`) still saw "Sin eventos" in `BehaviorLogScreen`. The 5th live test session (2026-07-08) confirmed: a parent with stale auth state has SharedPreferences with `role=PARENT`, `synthetic_access_token=...`, NO `parent_id`. The app's persisted-state path (`DeviceAuthManager.loadPersistedState()`) skips `authenticateOrCreate(Role.PARENT)`, so `parent_id` is NEVER written for these users. Only `pm clear` or `uninstall` (which the live test session did) triggered a fresh auth. Real users can't do this in production. The fix is a **lazy on-cold-start migration** that backfills `parent_id = MOCK_PARENT_ID` when `loadPersistedState` reads a stale PARENT prefs.

## What Shipped

### Production (~13 LoC total)

- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` (+13 LoC):
  - Added `import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine.MOCK_PARENT_ID` (reuses the PR #27 import seam; no new coupling).
  - Added a private `migrateStaleParentId(prefs: SharedPreferences)` helper function (~12 LoC) inserted after `loadPersistedState()` at line ~550:
    - Reads `role` from `prefs`. If `role != Role.PARENT.name`, returns (no-op per Q2=(n)).
    - Reads `parent_id` from `prefs`. If `!isNullOrEmpty()`, returns (idempotent per Q5=(e)).
    - `prefs.edit().putString("parent_id", MockSupabaseEngine.MOCK_PARENT_ID).apply()` (async per Q1=(a), mirrors the existing pattern at `DeviceAuthManager.kt:226`).
    - `Log.w(TAG, "migrated stale parent_id for pre-PR-#27 parent")` (one-shot per Q4=(y), mirrors the OPPO edge-case log at `DeviceAuthManager.kt:519-523`).
  - Called the helper from the end of `loadPersistedState()` (lines 507-550), after the OPPO edge-case block. One-line call: `migrateStaleParentId(prefs)`.
  - Updated the KDoc near `loadPersistedState` to mention the migration.
  - Added `import android.content.SharedPreferences` at the top of the file.

### Tests

- **NEW** (lands in commit `a80ae6e` — was on disk from explore in RED state, flipped to GREEN): `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMigrationTest.kt` (281 LoC, 5 cases).
  - **M1** — `loadPersistedState migrates stale PARENT prefs by writing parent_id` — **RED → GREEN**: seeds `role=PARENT` + `synthetic_access_token`, no `parent_id`. Asserts `prefs().getString("parent_id", null) == "parent-demo"`. Was RED on `master @ 2820e59`; passes after the helper lands.
  - **M2** — `loadPersistedState does NOT migrate CHILD prefs` — **GREEN-PIN control**: seeds `role=CHILD`, no `parent_id`. Asserts `!prefs().contains("parent_id")`. Stays GREEN; pins the role gate.
  - **M3** — `loadPersistedState is idempotent when parent_id already set` — **GREEN-PIN control**: seeds `role=PARENT` + `parent_id = "parent-demo"`. Asserts value unchanged. Stays GREEN; pins the `isNullOrEmpty()` guard.
  - **M4** — `getParentId returns MOCK_PARENT_ID after migration` — **RED → GREEN**: end-to-end. Seeds stale PARENT prefs → `coldStart.getParentId()`. Asserts `== "parent-demo"`. Was RED on `master @ 2820e59`; passes after the helper lands.
  - **M5** — `loadPersistedState does NOT migrate prefs without role` — **GREEN-PIN control**: seeds `synthetic_access_token` only, no `role`. Asserts `getString("parent_id", null) == null`. Stays GREEN; pins the role gate.
- **Unchanged GREEN**: 6 cases in `DeviceAuthManagerRoleTest.kt` (the role-aware overload's public API surface is preserved).
- **Unchanged**: 4 cases in `DeviceAuthManagerColdStartTest.kt` (3 GREEN, 1 pre-existing `MockKException` failure unchanged — out of scope).
- **Unchanged GREEN**: 4 cases in `BehavioralEventsRepositoryTest.kt` (inject `parentId` directly into `repository.refresh(...)`; the migration is invisible to them).
- **Unchanged GREEN**: 1 case in `BehavioralEventsRepositoryIntegrationTest.kt` (PR #27 already shipped; the migration is additive).

## Spec Changes

A paper-thin ADDED delta was written to `openspec/changes/2026-07-08-fix-migrate-stale-parent-id-on-load/specs/parent-auth-session/spec.md` during the spec phase. **1 ADDED requirement, 2 scenarios, ~120 words** (well under the 650-word budget). The canonical spec was silent on stale-state backfill, so ADDED was the right shape (no MODIFIED content).

The delta has now been synced into `openspec/specs/parent-auth-session/spec.md` as a new requirement at the top of the `## ADDED Requirements` section:

```
### Requirement: Stale-auth-state migration on cold start

The auth state MUST be migrated on cold start when `role = PARENT` and
`parent_id` is null or empty: `parent_id` is written to the demo default
`MOCK_PARENT_ID` so the BehavioralEventLog can surface the fixture's
events. The migration MUST be role-gated (PARENT only) and idempotent.

#### Scenario: parent with pre-existing auth state, no parent_id
#### Scenario: child role, no parent_id
```

The Verification hooks table was updated with row 6 pointing at the new test class.

## Commit Trail (on master HEAD)

```
5e392a6 Merge pull request #28 from Andrea-Caballero/fix/migrate-stale-parent-id-on-load
71ef8e8 chore(openspec): mark apply tasks complete + detekt infra note
a80ae6e fix(auth): migrate stale parent_id on cold start so fixture loads
```

- **RED+GREEN bundled** (single commit): `a80ae6e` — `fix(auth): migrate stale parent_id on cold start so fixture loads`. The 281-line RED test file (`DeviceAuthManagerMigrationTest.kt`, on disk from the explore sub-agent) was committed together with the production fix in `DeviceAuthManager.kt`. **Note**: this deviates from the strict-TDD invariant of "RED commit before GREEN commit". The RED → GREEN trail is documented in `tasks.md` (Phase 1 RED gate, Phase 3 GREEN flip) but is NOT preserved in the git history as separate commits. See "Lessons Learned" below.
- **chore** (second commit): `71ef8e8` — `chore(openspec): mark apply tasks complete + detekt infra note`. Marks all 16 tasks in `tasks.md` complete and notes the pre-existing `detekt` infra failure (jvm-target=21 incompatible with bundled detekt 1.x).
- **Merge**: `5e392a6` (PR #28, no squash, preserves trail).

## Verification Report

`verify-report.md` lives in Engram (obs **#331** `sdd/fix-migrate-stale-parent-id-on-load/verify`) rather than on disk — verdict: `ready_to_merge` (PASS WITH WARNINGS, 0 blocking, 2 non-blocking).

| Gate | Result |
|------|--------|
| 1 RED → GREEN transformation | ✅ `M1` (`loadPersistedState migrates stale PARENT prefs by writing parent_id`) + `M4` (`getParentId returns MOCK_PARENT_ID after migration`) flip from RED on `master @ 2820e59` to GREEN on branch. |
| 3 GREEN-PIN preserved | ✅ `M2` (CHILD no-op) + `M3` (idempotent) + `M5` (no role no-op) stay GREEN across the fix. |
| `./gradlew :app:testDebugUnitTest` | ✅ 756 tests, 83 failed (baseline 751 + 5 NEW GREEN; pre-existing 83 failures unchanged). |
| `./gradlew :app:assembleDebug` | ✅ PASS |
| `./gradlew :app:ktlintCheck` | ⚠️ 1 NEW violation: "File must end with a newline" at `DeviceAuthManagerMigrationTest.kt:1` (the new 281-line test file lacks a trailing newline). |
| `./gradlew :app:detekt` | ⚠️ env issue (JDK 21 not in detekt's `--jvm-target` whitelist) — pre-existing, not a regression. |
| Decision conformance | ✅ Q1=(a) async `apply()`, Q2=(n) no CHILD-path migration, Q3=(h) helper extracted, Q4=(y) one-shot WARN log, Q5=(e) `isNullOrEmpty()` guard. |
| Strict TDD adherence | ⚠️ Single-commit RED+GREEN (`a80ae6e`) instead of separate RED + GREEN. Audit trail in `tasks.md` is correct, but git history does not preserve a separate RED commit for future bisects. |
| No scope creep | ✅ Only the 2 expected files changed (1 production + 1 test, plus 3 openspec artifacts). |
| Live verification | ✅ Manual smoke on debug build shows `BehaviorLogScreen` rendering the 5 fixture events for parents with stale pre-PR-#27 auth state. |

**Issues found**: 0 CRITICAL. 2 non-blocking WARNINGs (both accepted as follow-up notes per user pick "merge as-is"):

1. **Single-commit RED+GREEN** instead of separate RED + GREEN commits. The apply sub-agent bundled both into commit `a80ae6e`. Audit trail in `tasks.md` is correct, but git history does not preserve a separate RED commit for future bisects. See "Lessons Learned" below.
2. **Missing trailing newline on `DeviceAuthManagerMigrationTest.kt:1`** — trivial 1-line fix. The new 281-line test file lacks a trailing newline (confirmed by `\ No newline at end of file` in the diff). Bundled into a follow-up ktlint cleanup PR.

## Test Totals (Final State, master @ `5e392a6`)

- **5 cases in `DeviceAuthManagerMigrationTest.kt` GREEN** (2 RED → GREEN: `M1` stale PARENT migration, `M4` getParentId end-to-end; 3 GREEN-PIN preserved: `M2` CHILD no-op, `M3` idempotent when already set, `M5` no role no-op).
- **4 EXISTING tests in `DeviceAuthManagerRoleTest` + `DeviceAuthManagerColdStartTest` GREEN** (3 GREEN + 1 pre-existing `MockKException` failure unchanged — out of scope).
- **83 pre-existing baseline failures** (NetworkModuleTest + 2× BootReceiverTest + NavGraphTest + 79 mockk-related) **unchanged** — no regressions.
- **0 intentional RED.**
- **0 new regressions.**

## TDD Evidence (2 RED → GREEN)

| Test | RED on `master = 2820e59` | GREEN after `a80ae6e` |
|------|---------------------------|----------------------|
| `M1` — `loadPersistedState migrates stale PARENT prefs by writing parent_id` | FAILED — `assertEquals("parent-demo", prefs().getString("parent_id", null))` because `loadPersistedState` never touches `parent_id` | passes |
| `M4` — `getParentId returns MOCK_PARENT_ID after migration` | FAILED — `assertEquals("parent-demo", coldStart.getParentId())` because `getParentId()` returns `null` | passes |

## Apply Deviations from `tasks.md` (all documented in the apply log)

1. **Single-commit RED+GREEN** instead of separate RED + GREEN. The apply sub-agent bundled the 281-line RED test + the 12-LoC GREEN production into a single commit `a80ae6e`. Tasks spec §3.5 anticipated a single production fix commit; the apply phase did not split the RED test into a separate commit. Audit trail is preserved in `tasks.md` Phase 1 (RED gate) and Phase 3 (GREEN flip), but not in git history.
2. **Two commits instead of one** — the work-unit-commits skill recommended a single tight commit, but a missing-trailing-newline lint on the test file forced an `--amend` and a separate `chore(openspec)` commit for tasks.md completion was cleaner than mixing it into the production-fix commit.
3. **`detekt` task (4.4) marked complete with an inline note** about pre-existing infra failure (jvm-target=21).

## Operator Actions (Migration)

**None.** Pure client-side + auth-state change. No DB migration required. No feature flag. No data shape change on the wire.

The fix is self-healing on next cold start: parents with stale pre-PR-#27 auth state will see the BehavioralEventLog populated on the next app launch. The `isNullOrEmpty()` guard makes the helper a one-shot per affected device, NOT per cold start — repeated cold starts post-migration return early without writing or logging. `clearSession()` already wipes the new `parent_id` key via SharedPreferences `.clear()` semantics — no follow-up needed there. Rollback: `git revert` of `a80ae6e` restores prior behavior (stale PARENT prefs stay stale; UI shows "Sin eventos" again for affected parents). No schema migration, no data loss.

## Non-Blocking Follow-ups (Deferred per User Pick "Merge As-Is")

1. **Single-commit RED+GREEN** instead of separate RED + GREEN commits. The apply sub-agent bundled both into commit `a80ae6e`. Audit trail in `tasks.md` is correct, but git history does not preserve a separate RED commit for future bisects. Future change: the apply sub-agent should split RED-write and GREEN-impl into separate commits. See "Lessons Learned" below.
2. **Missing trailing newline on `DeviceAuthManagerMigrationTest.kt:1`** — trivial 1-line fix. The new 281-line test file lacks a trailing newline (confirmed by `\ No newline at end of file` in the diff). Bundled into a follow-up ktlint cleanup PR.
3. **4 pre-existing ktlint violations on `DeviceAuthManager.kt` at lines 19, 163, 251, 305, 360, 373** — all OUTSIDE the PR's edit block (548-589). Out of scope for this fix; follow-up housekeeping PR.
4. **`parent-auth-flow` replacement** — eventually replaces the synthetic parent path with real Keystore-encrypted auth, at which point `MOCK_PARENT_ID` and the synthetic `authenticateOrCreate(Role: Role)` overload are both retired together.
5. **`loadPersistedState` enumerated-key migration pipeline** — hardening the cold-start seam into an explicit list of keys. The `migrateStaleParentId` helper is intentionally narrow; future migrations (if any) get their own helpers.

## Decisions Audit Trail (engram references)

Full decision set documented in these engram observations:

- `sdd/fix-migrate-stale-parent-id-on-load` — change marker + bug definition (live-test discovery #3). Obs **#324**.
- `sdd/fix-migrate-stale-parent-id-on-load/explore` — root cause analysis: `loadPersistedState` is silent on `parent_id` backfill; PR #27's `authenticateOrCreate(role)` fix only fires on FRESH auth. Obs **#325**.
- `sdd/fix-migrate-stale-parent-id-on-load/decisions` — Q1=(a) `apply()` async, Q2=(n) no CHILD migration, Q3=(h) helper extracted, Q4=(y) one-shot WARN log, Q5=(e) `isNullOrEmpty()` guard. Obs **#326**.
- `sdd/fix-migrate-stale-parent-id-on-load/proposal` — full scope (~225 LoC budget). Obs **#327**.
- `sdd/fix-migrate-stale-parent-id-on-load/spec` — paper-thin delta (1 ADDED requirement, 2 scenarios). Obs **#328**.
- `sdd/fix-migrate-stale-parent-id-on-load/tasks` — 4-phase breakdown (RED gate → Investigation → GREEN → Build verifier). Obs **#329**.
- `sdd/fix-migrate-stale-parent-id-on-load/apply-progress` — apply log + single-commit RED+GREEN deviation rationale. Obs **#330**.
- `sdd/fix-migrate-stale-parent-id-on-load/verify` — `ready_to_merge` verdict (PASS WITH WARNINGS, 0 blocking, 2 non-blocking). Obs **#331**.

## Decisions Summary

- **Q1 — apply() vs commit()**: **(a) `apply()`.** Async, matches the existing pattern at `authenticateOrCreate` line 226. `commit()` would unnecessarily block the cold-start path.
- **Q2 — CHILD role migration**: **(n) No.** Only PARENT. Same as PR #27 Q2=(n). CHILD path is device-side, doesn't have `parent_id` until paired.
- **Q3 — Migration shape**: **(h) Helper** (`migrateStaleParentId(prefs)`). Testable in isolation, single responsibility, consistent with codebase pattern. The OPPO edge-case block at `loadPersistedState` lines 519-523 is the pattern to mirror.
- **Q4 — One-shot WARN log**: **(y) Yes.** Matches the OPPO edge-case pattern at `loadPersistedState` lines 519-523. Observability is gold for migrations. The log message "migrated stale parent_id for pre-PR-#27 parent" includes the WHY (pre-PR-#27 context) without including any PII (no UUIDs, no tokens, no user data).
- **Q5 — Empty string handling**: **(e) Empty string = missing.** `isNullOrEmpty()` defensivo. Consistent with the DAO SQL filter `parent_id = ''`.

## Lessons Learned (for future SDD cycles)

- **The 5-case test split is the canonical example of a "test pyramid" for a single-purpose migration.** 3 GREEN-PIN cases (`M2` CHILD no-op, `M3` idempotent when already set, `M5` no role no-op) protect the migration from regressing in either direction. The 2 RED → GREEN cases (`M1` stale PARENT migration, `M4` getParentId end-to-end) prove the fix works end-to-end. **Pattern for future similar migrations**: design the test split with 3 PIN cases + 2 RED cases from the start. The RED cases prove the fix; the PIN cases prove the fix doesn't over-reach.

- **Idempotency is critical for cold-start migrations.** The `isNullOrEmpty()` guard makes the helper a one-shot per affected device, NOT per cold start. Repeated cold starts post-migration return early without writing or logging. **Pattern for future migrations**: explicitly test idempotency in the test split (the `M3` case). Without the `M3` control test, a future "improvement" that removes the `isNullOrEmpty()` guard would silently turn the helper into a per-cold-start spam machine.

- **WARN log with no PII is the observability baseline.** The log message "migrated stale parent_id for pre-PR-#27 parent" includes the WHY (pre-PR-#27 context) without including any PII (no UUIDs, no tokens, no user data, no device fingerprint). **Pattern for future migrations**: log the WHY, not the WHAT (the WHAT is the migration itself; the WHY is the historical context for the logcat reader). The OPPO edge-case log at `DeviceAuthManager.kt:519-523` is the canonical pattern to mirror.

- **The deferred Q2 from PR #27 became a follow-up change in PR #28.** This is a clean example of a deferred open question becoming its own change. The PR #27 proposal noted "Migration for already-signed-in parents" as a deferred item (Q2, open question in `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/proposal.md`). The 5th live test session (2026-07-08) confirmed the gap and the migration became its own PR. **Pattern for future proposes**: when a decision is deferred for "user experience" reasons (e.g., to avoid disrupting existing users with a forced re-auth), the deferred item may need its own change later. Document the deferred item explicitly in the proposal's "Out of scope" section so future changes can pick it up.

- **The apply sub-agent bundled RED+GREEN into a single commit (`a80ae6e`).** This deviates from the strict-TDD invariant of "RED commit before GREEN commit". The `tasks.md` audit trail is correct (Phase 1 RED gate confirmed on master, Phase 3 GREEN flip), but git history does not preserve a separate RED commit for future bisects. **Pattern for future applies**: when the apply sub-agent has both the RED test and the GREEN production code in flight, it should commit them as separate commits (RED-test commit first, then GREEN-impl commit) to preserve the bisect-able audit trail. The verify sub-agent flagged this as a non-blocking finding; the user accepted it as a follow-up note. Future strict-TDD cycles should treat this as a blocking finding unless the user explicitly waives it.

## Relationship to Prior Work

This change is the **7th SDD in this project** (and the 5th mini-SDD lite since the orphan-cleanup epic). It is the **direct follow-up** to PR #27:

1. `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (PR #15 + PR #16, `1da5d2f` + `798c931`). Mini-SDD lite. The structural template for the `chore(openspec)`-as-third-commit pattern.
2. `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/` (PR #17, `133089a`). Mini-SDD lite. Slice-1 PR masked the multi-child gap.
3. `archive/2026-07-06-feat-multi-child-picker/` (PR #18 + PR #19, `043f35f` + `7f20f05`). Chained-PR SDD.
4. `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (PR #20, `b8d0c60`). Mini-SDD lite bug fix — sister change, same data-layer cold-start shape.
5. `archive/2026-07-07-feat-parent-behavioral-event-log/` (PR #25 + PR #26, `6e6557c` + `275abf6`). Chained-PR SDD — the upstream feature whose integration gap PR #27 closes.
6. `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` (PR #27, `862d2d4`). Mini-SDD lite bug fix. **Sister change — added `parent_id` to the fresh-auth path. Introduced the asymmetry this PR (PR #28) closes.**
7. `archive/2026-07-08-fix-migrate-stale-parent-id-on-load/` (this report, PR #28, `5e392a6`). Mini-SDD lite bug fix. Closes the live-test-#5 gap by adding a lazy on-cold-start migration for stale pre-PR-#27 PARENT auth state.

**Not a regression of any prior change.** The 1 production file touched (`DeviceAuthManager.kt`) is a strict superset of the prior auth-state work. The new helper `migrateStaleParentId(prefs)` reuses the same `MockSupabaseEngine.MOCK_PARENT_ID` import seam as PR #27 — no new coupling. The RED test (`DeviceAuthManagerMigrationTest.kt`) is a NEW test file that complements the existing `DeviceAuthManagerRoleTest` (6 cases) and `DeviceAuthManagerColdStartTest` (4 cases) — the test pyramid now has 11 cases on the `DeviceAuthManager` boundary.

## Out-of-scope follow-ups (deferred, not blocking)

- **Single-commit RED+GREEN** — future apply sub-agents should split RED-write and GREEN-impl into separate commits. See "Lessons Learned" above.
- **Missing trailing newline on `DeviceAuthManagerMigrationTest.kt:1`** — follow-up ktlint cleanup PR.
- **4 pre-existing ktlint violations on `DeviceAuthManager.kt`** at lines 19, 163, 251, 305, 360, 373 — all OUTSIDE the PR's edit block (548-589). Out of scope; follow-up housekeeping PR.
- **`loadPersistedState` enumerated-key migration pipeline** — hardening the cold-start seam. Separate refactor; the `migrateStaleParentId` helper is intentionally narrow.
- **`parent-auth-flow` real auth replacement** — retires the synthetic parent path AND `MOCK_PARENT_ID` together.
- **V2 server-side Solicitudes filter for stale resolved rows** — separate per `archive/2026-07-06-feat-multi-child-picker/proposal.md:49`. Unrelated.
- **Persistence of `selectedChildId` across cold start** — separate per Q2 chain (V1 is in-memory only).
- **Production lazy-hydration of devices** — separate long-standing follow-up. Unrelated.

## Notes for the Next Session

- **The `fix-migrate-stale-parent-id-on-load` change folder is closed.** The archive folder `openspec/changes/archive/2026-07-08-fix-migrate-stale-parent-id-on-load/` is the immutable audit trail — do NOT modify.
- **Pre-existing test failures (83 total: NetworkModuleTest + 2× BootReceiverTest + 79 mockk-related + intermittent NavGraphTest flake) remain unchanged on master @ `5e392a6`.** None are regressions of this PR.
- **The 2-commit trail (`a80ae6e` → `71ef8e8` → `5e392a6`)** preserves the full fix + chore + merge audit chain, but the strict-TDD RED-before-GREEN invariant was NOT preserved (RED+GREEN bundled in `a80ae6e`). See "Lessons Learned" above.
- **`migrateStaleParentId(prefs: SharedPreferences)` private helper at `DeviceAuthManager.kt:550-590`** is the canonical pattern for cold-start migrations. Reuse the shape (role gate → `isNullOrEmpty()` guard → `prefs.edit().putString().apply()` → one-shot `Log.w`) for any future SharedPreferences backfill.
- **`DeviceAuthManagerMigrationTest.kt` test pyramid** (5 cases: 2 RED + 3 PIN) is the canonical pattern for a single-purpose migration test class. The 3 PIN cases (CHILD no-op, idempotent, no role no-op) protect the migration from regressing in either direction.
- **Next natural change**: pick from the deferred list. The live-test-discovered follow-up cycle (3 follow-ups + 2 new bug fixes = 5 total changes this session) is now complete. Recommend: revisit the pre-existing test failures (NetworkModuleTest, BootReceiverTest) — those are real bugs worth their own SDD cycles.
- **Strict TDD's RED-before-GREEN contract** continues to work well for small data-layer / auth-layer bug fixes, but the apply sub-agent must commit RED and GREEN as separate commits to preserve the audit trail. The `fix-behavioral-event-log-fixture-loading` cycle (PR #27) demonstrated the correct shape: `563a346` (RED) → `56b32b1` (GREEN) → `862d2d4` (merge). The `fix-migrate-stale-parent-id-on-load` cycle (PR #28) deviated from this shape and is a non-blocking finding.

---

*Archive generated by `sdd-archive` on 2026-07-08 from master @ `5e392a6`. All eight engram topic observations for this change are referenced above for traceability.*
