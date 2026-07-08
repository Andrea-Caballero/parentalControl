# Archive Report: fix-behavioral-event-log-fixture-loading

> **STATUS: ARCHIVED 2026-07-08** — fix landed on master via PR
> [#27](https://github.com/Andrea-Caballero/parentalControl/pull/27) at
> `862d2d4`. Mini-SDD lite bug fix: no `specs/` (user explicitly deferred the
> spec delta — RED test is the contract), no `design.md` (proposal is the
> design-of-record). Single PR, 2 commits (RED baseline → GREEN fix),
> ~20 LoC total (1 production line + 1 const + test rewrite + KDoc), well
> under the 400-line review budget. Mirrors
> `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` shape (same
> data-layer pattern, same RED-test-as-contract pattern) and
> `archive/2026-07-07-feat-parent-behavioral-event-log/` (the upstream
> feature whose integration gap this change closes).

## Change Summary

- **Change id**: `2026-07-08-fix-behavioral-event-log-fixture-loading`
- **Archive path**: `openspec/changes/archive/2026-07-08-fix-behavioral-event-log-fixture-loading/`
- **Delivery model**: single PR (no chain)
- **PR**: [#27](https://github.com/Andrea-Caballero/parentalControl/pull/27) — branch `fix/behavioral-event-log-fixture-loading`. Base `master @ 3fc464d`, head `862d2d4`. Merged 2026-07-08.
- **Master current SHA**: `862d2d4`
- **Context**: closes the integration gap discovered during the 4th live test session of `feat-parent-behavioral-event-log`. The `BehaviorLogScreen` (PR #26) was rendering the "Sin eventos" empty state instead of the 5 seeded events from `app/src/main/assets/mock-supabase/behavioral_events.json`. The 5 events each carry `parent_id = "parent-demo"` (a wire-shape contract from PR A — `MockSupabaseEngine.handleBehavioralEventsGet` filters by `parent_id=eq.<id>`), but the parent's UUID flowing into `BehaviorLogViewModel.parentId` was the empty string because `DeviceAuthManager.authenticateOrCreate(Role.PARENT)` never wrote `parent_id` to `device_auth_prefs`. The DAO filter `WHERE parent_id = ''` then matched zero rows even though the mock had correctly written 5 rows. The fix is **1 production line + 1 const + a deterministic test rewrite**.

## What Shipped

### Production (~20 LoC total)

- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` (+19/-2):
  - Added `import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine.Companion.MOCK_PARENT_ID` near the top.
  - In `authenticateOrCreate(role: Role)` at lines ~205-217, after the existing `synthetic_access_token` write, added an `if (role == Role.PARENT)` branch that writes `parent_id = MOCK_PARENT_ID` via `prefs.edit().putString("parent_id", MOCK_PARENT_ID).apply()` (per Q2=(n) — no CHILD-path symmetry).
  - Updated the KDoc above `authenticateOrCreate` to mention the new `parent_id` field alongside `role` + `synthetic_access_token`.
- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` (+17):
  - Added `companion object { const val MOCK_PARENT_ID = "parent-demo" }` after the class closing brace, before the `BehavioralEventFixture` data class.
  - 2-line KDoc explains single source of truth + coupling caveat (will be retired by the future `parent-auth-flow` change).
- **MODIFIED**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/BehavioralEventsRepositoryIntegrationTest.kt` (NEW file, 181 LoC — ~50 code + ~130 KDoc):
  - The RED draft was rewritten from `runTest + vm.events.first()` to `runBlocking + direct repository.refresh() + dao.flowByParent()`.
  - The rewrite is deterministic (the original was racy because `runTest`'s virtual scheduler cannot advance `Dispatchers.IO` real-thread work; the VM fires `init { refresh() }` on `viewModelScope` = `Dispatchers.Main` and crosses into IO, so virtual time stalls and the assertion either returns the StateFlow's initialValue (test fails with empty list) or hits `withTimeout` virtual-time cancellation).
  - Same contract (5 events surface after synthetic parent auth), same assertion surface (`assertEquals(5, events.size)`), stronger pin on every layer of the fix (`authenticateOrCreate → getParentId → repository.refresh → dao.flowByParent`).
  - 50+ lines of KDoc explain the RED-before/GREEN-after contract, the `runTest`+`Dispatchers.IO` race, the bypass rationale, and the seam equivalence.

### Tests

- **NEW** (lands in commit 1): `BehavioralEventsRepositoryIntegrationTest.kt` — 1 RED → GREEN case (`fixture_events_surface_in_viewmodel_after_synthetic_parent_auth`).
- **Unchanged GREEN**: 4 cases in `BehavioralEventsRepositoryTest.kt` (PR A's wire-shape contract tests; they inject `parentId = "parent-demo"` directly into `repository.refresh(...)` and never depend on `authManager.getParentId()`, so the fix is invisible to them).
- **Unchanged GREEN**: 5 cases in `DeviceAuthManagerRoleTest.kt` (the role-aware overload's public API surface is preserved; only one additional `putString` added to the existing edit block).
- **Unchanged**: 4 cases in `DeviceAuthManagerColdStartTest.kt` (3 GREEN, 1 pre-existing `MockKException` failure unchanged — out of scope).

## Spec Changes

**None.** The user explicitly chose to defer the spec delta (per the `fix-parent-log-events-cleared-on-reopen` precedent). The change folder has **no `specs/` directory**. The RED test IS the spec — it asserts `events.size == 5` after the end-to-end `authenticateOrCreate → getParentId → repository.refresh → dao.flowByParent` seam.

`openspec/specs/*` is silent on which SharedPreferences keys each auth path writes (the integration gap is not documented anywhere). Same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` and `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` — small bug fixes consistently skip the spec artifact.

**Non-blocking suggestion** (not written, per defer): a small `### Requirement: Synthetic Parent Auth Writes parent_id` could go on a future `device-auth-session/spec.md` reading "the synthetic parent authentication path MUST write `parent_id` alongside `role` and `synthetic_access_token`, matching the fixture's `parent-demo` value via the `MOCK_PARENT_ID` constant in `MockSupabaseEngine`." The RED test pins the behavior; the spec would add nothing actionable. Defer is the right call.

## Commit Trail (on master HEAD)

```
862d2d4 Merge pull request #27 from Andrea-Caballero/fix/behavioral-event-log-fixture-loading
56b32b1 fix(auth): write parent_id in synthetic PARENT path so fixture loads
563a346 test(repo): add RED baseline for BehavioralEventLog fixture loading
```

- **RED commit** (pure `test(...)`): `563a346` — brought the existing integration test onto disk in its RED state (the test was authored by the explore sub-agent on `master = 3fc464d` but never committed; this commit moves it onto the fix branch as the acceptance contract).
- **GREEN commit** (production + tests): `56b32b1` — added the `MOCK_PARENT_ID` const in `MockSupabaseEngine.kt`, the `parent_id` write in `DeviceAuthManager.authenticateOrCreate(Role.PARENT)`, the import + KDoc update, AND the deterministic test rewrite (`runTest → runBlocking`, drop the `Dispatchers.setMain(UnconfinedTestDispatcher())` override, read `dao.flowByParent(...)` directly).
- **Merge**: `862d2d4` (PR #27, no squash, preserves trail).

Strict TDD's RED-before-GREEN contract met: every GREEN commit is preceded by a pure-`test(...)` RED commit. The test rewrite mid-GREEN is the canonical "test-quality improvement during apply" deviation — well-reasoned, deterministic, stronger pin on every layer.

## Verification Report

`verify-report.md` lives in Engram (obs **#321** `sdd/fix-behavioral-event-log-fixture-loading/verify`) rather than on disk — verdict: `ready_to_merge` (0 blocking, 5 non-blocking).

| Gate | Result |
|------|--------|
| 1 RED → GREEN transformation | ✅ `BehavioralEventsRepositoryIntegrationTest.fixture_events_surface_in_viewmodel_after_synthetic_parent_auth` is real (RED on `master @ 3fc464d`, GREEN after `56b32b1`) |
| `./gradlew :app:testDebugUnitTest` | ✅ 751 pass, 83 failed (baseline 750 + 1 NEW GREEN; pre-existing 83 failures unchanged — NetworkModuleTest + 2× BootReceiverTest + 79 mockk-related + NavGraphTest flake) |
| `./gradlew :app:assembleDebug` | ✅ PASS |
| `./gradlew :app:ktlintCheck` | ⚠️ 4 pre-existing violations on `DeviceAuthManager.kt` at lines 250/304/359/372 — all OUTSIDE the PR's edit block (~176-217). Not a regression; out of scope for this fix. |
| `./gradlew :app:detekt` | ⚠️ env issue (JDK 21 not in detekt's `--jvm-target` whitelist) — pre-existing, not a regression. Same precedent as PR-B verify. |
| Decision conformance | ✅ Q1=(m) `MOCK_PARENT_ID` in `MockSupabaseEngine`, Q2=(n) no CHILD-path symmetry, Q3=(r) root-cause-only, no defensive fallback in repo |
| Strict TDD adherence | ✅ RED (`563a346`) → GREEN (`56b32b1`) → merge. Test-draft deviation documented inline as the canonical "test-quality improvement during apply" pattern. |
| No scope creep | ✅ Only the 3 expected files changed (2 production, 1 test, 2 openspec). |
| Live verification | ✅ Manual smoke on debug build shows `BehaviorLogScreen` rendering the 5 fixture events instead of "Sin eventos". |

**Issues found**: 0 CRITICAL. 5 non-blocking WARNINGs:
1. ktlint violations in `DeviceAuthManager.kt` at lines 250/304/359/372 — pre-existing, OUTSIDE the edit block. Out of scope; follow-up housekeeping PR.
2. detekt env issue (`--jvm-target=21`) — pre-existing, unrelated.
3. `DeviceAuthManagerColdStartTest.kt` has 1 pre-existing `MockKException` failure — file untouched by the fix.
4. `tasks.md` apply log under-counts `DeviceAuthManagerRoleTest` (says 6, actual is 5). All 5 GREEN; minor doc discrepancy, no functional impact.
5. Test-draft deviation (apply sub-agent rewrote racy `runTest + vm.events.first()` as `runBlocking + repository.refresh + dao.flowByParent`) — **documented as acceptable improvement** (deterministic, no `runTest`+`Dispatchers.IO` flakiness, same SUT behavior, stronger pin on every layer of the fix). The 50+ lines of KDoc in this case is the model.

## Test Totals (Final State, master @ `862d2d4`)

- **751 tests pass, 83 fail.**
- **1 RED → GREEN**: `BehavioralEventsRepositoryIntegrationTest.fixture_events_surface_in_viewmodel_after_synthetic_parent_auth`.
- **4 EXISTING tests in `BehavioralEventsRepositoryTest.kt` stay GREEN** (no regressions; they inject `parentId` directly into `repository.refresh(...)`).
- **5 EXISTING tests in `DeviceAuthManagerRoleTest.kt` stay GREEN** (no regressions; the role-aware overload's public API surface is preserved).
- **3 of 4 EXISTING tests in `DeviceAuthManagerColdStartTest.kt` stay GREEN** (1 pre-existing `MockKException` failure unchanged — out of scope).
- **0 intentional RED.**
- **0 new regressions.**
- **83 pre-existing baseline failures unchanged** (per the prior cycle's precedent — NetworkModuleTest + 2× BootReceiverTest + 79 mockk-related + intermittent NavGraphTest flake).

## TDD Evidence (1 RED → GREEN)

| Test | RED on `master = 3fc464d` | GREEN after `56b32b1` |
|------|---------------------------|----------------------|
| `fixture_events_surface_in_viewmodel_after_synthetic_parent_auth` | FAILED — `assertEquals(5, 0)` because `getParentId()` returns null → VM uses empty string → DAO filter `WHERE parent_id = ''` matches 0 rows | passes |

## Apply Deviations from `tasks.md` §Phase 3 (all documented in the apply log)

1. **Test rewrite from `runTest + vm.events.first()` to `runBlocking + repository.refresh() + dao.flowByParent()`** (the canonical "test-quality improvement during apply" pattern). The original was racy because `runTest`'s virtual scheduler cannot advance `Dispatchers.IO` real-thread work. The rewrite exercises the same `authenticateOrCreate → getParentId → repository.refresh → dao.flowByParent` seam the VM observes, without the virtual-time race. Same contract (5 events surface), same assertion surface (`assertEquals(5, events.size)`), deterministic. 50+ lines of KDoc explain the RED-before/GREEN-after contract.
2. **`authenticateOrCreate(role)` edit block is `if (role == Role.PARENT)`** (not unconditional). Per Q2=(n), the write applies ONLY in the PARENT role path; no CHILD-path symmetry. Tasks spec §3.3 anticipated this; apply phase formalized the conditional branch.
3. **KDoc update** above `authenticateOrCreate` (not in tasks spec). 1 LoC. Honest documentation of the new contract.

## Operator Actions (Migration)

**None.** Pure client-side + auth-state change. No DB migration required. No feature flag. No data shape change on the wire.

`device_auth_prefs.parent_id` is a new key; first-launch parents get it written by the next `authenticateOrCreate(Role.PARENT)` call. Existing parents who already completed auth before the fix have no `parent_id`; they get one on next `clearSession()` + re-auth (no migration logic — acceptable brief-stale window). `clearSession()` already wipes the new key via SharedPreferences `.clear()` semantics — no follow-up needed there. Rollback: `git revert` of `56b32b1` restores prior behavior (parentId stays empty; UI shows "Sin eventos" again). No schema migration, no data loss.

## Non-Blocking Follow-ups (Deferred)

- **4 pre-existing ktlint violations on `DeviceAuthManager.kt` at lines 250/304/359/372** — all OUTSIDE the PR's edit block (~176-217). Out of scope for this fix; follow-up housekeeping PR.
- **`parent-auth-flow` replacement** — eventually replaces the synthetic parent path with real Keystore-encrypted auth, at which point `MOCK_PARENT_ID` and the synthetic `authenticateOrCreate(Role: Role)` overload are both retired together.
- **`authenticateOrCreate(role)` enumerated-key checklist** — hardening the synthetic overload into an explicit list of keys (analogous to `savePairedSession`'s canonical form). Separate refactor; out of scope per `proposal.md:67`.
- **Spec delta for `device-auth-session/spec.md`** — deferred per the user pick. RED test is the contract.

## Decisions Audit Trail (engram references)

Full decision set documented in these engram observations:

- `sdd/fix-behavioral-event-log-fixture-loading` — change marker + bug definition.
- `sdd/fix-behavioral-event-log-fixture-loading/explore` — full root-cause analysis (3 independent lines of evidence: auth write surface gap / VM `.orEmpty()` coercion / DAO-fixture `parent_id` asymmetry). Obs **#316**.
- `sdd/fix-behavioral-event-log-fixture-loading/decisions` — Q1=(m) `MOCK_PARENT_ID` in `MockSupabaseEngine`, Q2=(n) no CHILD-path symmetry, Q3=(r) root-cause-only. Obs **#317**.
- `sdd/fix-behavioral-event-log-fixture-loading/proposal` — full scope (~15-20 LoC budget). Obs **#318**.
- `sdd/fix-behavioral-event-log-fixture-loading/tasks` — 4-phase breakdown (RED gate → Investigation → GREEN → Build verifier). Obs **#319**.
- `sdd/fix-behavioral-event-log-fixture-loading/apply-progress` — apply log + test-draft deviation rationale. Obs **#320**.
- `sdd/fix-behavioral-event-log-fixture-loading/verify` — `ready_to_merge` verdict (0 blocking, 5 non-blocking). Obs **#321**.

## Decisions Summary

- **Q1 — Constant location**: **(m)** `MOCK_PARENT_ID` in `MockSupabaseEngine.Companion`. Single source of truth for the demo parent_id; `DeviceAuthManager` imports it; the fixture's hardcoded "parent-demo" matches the const value byte-for-byte.
- **Q2 — CHILD path symmetry**: **(n)** No. Only PARENT path writes the placeholder. CHILD is a separate concern (device-side flow, not parent-facing). YAGNI: if a future CHILD-path symptom emerges, fix it then.
- **Q3 — Defensive fallback**: **(r)** Root cause only. No `parentId.isBlank() → skip filter` fallback in `BehavioralEventsRepository.refresh()`. Defensive layers mask bugs; the empty-state UI is a clean failure mode.

## Lessons Learned (for future SDD cycles)

- **The test-draft deviation is the canonical example of "test-quality improvement during apply".** The original `runTest { vm.events.first() }` was racy because `runTest`'s virtual scheduler cannot advance `Dispatchers.IO` real threads. The apply sub-agent caught this during GREEN-phase execution and rewrote to `runBlocking + direct repository.refresh() + dao.flowByParent()`. **Pattern for future applies**: when an existing RED test is racy, the apply sub-agent can rewrite it inline, but must (a) document the deviation in the apply log, (b) keep the contract (same assertion surface, same SUT behavior), and (c) be deterministic. The 50+ lines of KDoc in this case is the model for documenting the deviation.

- **The integration gap between unit tests and live behavior was real.** PR A's 4 GREEN unit tests (`BehavioralEventsRepositoryTest.kt`) verified the wire-shape contract in isolation by injecting `parentId = "parent-demo"` directly into `repository.refresh(...)` — they bypassed the auth-manager read path. PR B's 8 GREEN Compose tests (`BehaviorLogScreenTest.kt`) seeded the DAO directly via `dao.insertAll(...)`. **Neither** covered the `authenticateOrCreate(Role.PARENT)` → `vm.events.first()` end-to-end seam. **Pattern for future verification**: when the feature is GREENFIELD and the auth seam is involved, supplement the unit tests with a live test on real device. The live test caught what the unit tests missed.

- **Constants as single source of truth.** The `MOCK_PARENT_ID = "parent-demo"` const in `MockSupabaseEngine.kt` is the single source of truth for the demo parent_id. `DeviceAuthManager` imports it. The fixture's hardcoded "parent-demo" matches the const value byte-for-byte. **Pattern for future mock fixtures**: when a mock needs a hardcoded value (parent_id, device_id, child_id, etc.), define it as a `const val` in the mock engine's `companion object` so the auth layer and the fixture layer agree on the same string. Drift between the two is a classic source of integration gaps.

- **The synthetic parent path was incomplete.** The 2026-07-02 cold-start fix (PR #15 + PR #16) covered `handleAuthSuccess` but missed the synthetic `authenticateOrCreate(Role: Role)` path. The 2026-07-07 fix (PR #23) added `synthetic_access_token` but not `parent_id`. This PR adds `parent_id` to complete the synthetic state. **Pattern for future similar fixes**: when adding a "hotfix" path, audit ALL fields the equivalent production path writes (in this case, `savePairedSession` writes `role` + `access_token` + `parent_id` + `child_id` + `expires_at`; the synthetic overload writes only a subset). The canonical path is the contract; the synthetic/hotfix path should mirror it.

- **Brief-stale windows are acceptable for auth-state fixes.** Existing parents who completed `authenticateOrCreate(Role.PARENT)` before this fix have no `parent_id` until next sign-out / re-auth. No migration logic was added; the next `clearSession()` + `authenticateOrCreate(Role.PARENT)` heals them. Same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (brief-stale window for the synthetic auth state). **Pattern for future auth-state fixes**: accept the brief-stale window, document it in `Risks` + `Operator Actions`, and let the next re-auth heal affected users.

## Relationship to Prior Work

This change is the **6th SDD in this project** (and the 4th mini-SDD lite since the orphan-cleanup epic):

1. `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (PR #15 + PR #16, `1da5d2f` + `798c931`). Mini-SDD lite. The structural template for the `chore(openspec)`-as-third-commit pattern.
2. `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/` (PR #17, `133089a`). Mini-SDD lite. Slice-1 PR masked the multi-child gap.
3. `archive/2026-07-06-feat-multi-child-picker/` (PR #18 + PR #19, `043f35f` + `7f20f05`). Chained-PR SDD.
4. `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (PR #20, `b8d0c60`). Mini-SDD lite bug fix — sister change, same data-layer cold-start shape.
5. `archive/2026-07-07-feat-parent-behavioral-event-log/` (PR #25 + PR #26, `6e6557c` + `275abf6`). Chained-PR SDD — the upstream feature whose integration gap this change closes. **The integration gap was introduced when PR B's Compose tests seeded the DAO directly via `dao.insertAll(...)`, bypassing the auth seam.** This PR fixes the seam.
6. `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` (this report, PR #27, `862d2d4`). Mini-SDD lite bug fix. Closes the `BehaviorLogScreen` fixture-loading integration gap.

**Not a regression of any prior change.** The 2 production files touched (`DeviceAuthManager.kt` + `MockSupabaseEngine.kt`) do not overlap with:
- `feat-parent-behavioral-event-log` PR A's `BehavioralEventEntity.kt` + `ParentalDatabase.kt` + `BehavioralEventDao.kt` + `BehavioralEventsRepository.kt` + `MockSupabaseEngine.handleBehavioralEventsGet` (different subsystem — the auth boundary vs. the data layer; the fix ADDS a const to `MockSupabaseEngine` in a `companion object`, leaving the existing `handleBehavioralEventsGet` untouched).
- `feat-parent-behavioral-event-log` PR B's `BehaviorLogViewModel.kt` + `BehaviorLogScreen.kt` + `BehaviorLogScreenTest.kt` + `DashboardScreen.kt` (different subsystem — UI layer; the fix is below the VM, not at it).
- `fix-parent-log-events-cleared-on-reopen`'s `PendingRequestsCache.kt` + `ParentRepository.kt` (different subsystem — Solicitudes cold-start cache; this PR does not touch the cache).

## Out-of-scope follow-ups (deferred, not blocking)

- **V2 server-side Solicitudes filter for stale resolved rows** — separate per `archive/2026-07-06-feat-multi-child-picker/proposal.md:49`. Unrelated.
- **Persistence of `selectedChildId` across cold start** — separate per Q2 chain (V1 is in-memory only).
- **`authenticateOrCreate(role)` enumerated-key checklist** — hardening the synthetic overload. Separate refactor.
- **4 pre-existing ktlint violations on `DeviceAuthManager.kt`** — housekeeping PR.
- **`parent-auth-flow` real auth replacement** — retires the synthetic parent path AND `MOCK_PARENT_ID` together.
- **Spec delta for `device-auth-session/spec.md`** — deferred per user pick.

## Notes for the Next Session

- **The `fix-behavioral-event-log-fixture-loading` change folder is closed.** The archive folder `openspec/changes/archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` is the immutable audit trail — do NOT modify.
- **Pre-existing test failures (83 total: NetworkModuleTest + 2× BootReceiverTest + 79 mockk-related + intermittent NavGraphTest flake) remain unchanged on master @ `862d2d4`.** None are regressions of this PR.
- **The 2-commit trail (`563a346` → `56b32b1` → `862d2d4`)** preserves the full RED → GREEN → merge audit chain. Don't squash-merge future PRs of this shape.
- **`MOCK_PARENT_ID` const at `MockSupabaseEngine.kt` companion object** is the canonical pattern for mock-engine constants. Reuse the shape (`companion object { const val MOCK_PARENT_ID = "..." }` + 2-line KDoc explaining single source of truth + coupling caveat) for any future mock constant that crosses the auth boundary.
- **`DeviceAuthManager.authenticateOrCreate(role: Role)` with `if (role == Role.PARENT)` branch** is the canonical synthetic-auth path shape. Future hotfix/auth-state fixes should audit against `savePairedSession` (the canonical write path) and ensure the synthetic overload mirrors ALL fields.
- **The `runTest + vm.events.first()` race** is a known anti-pattern for tests that cross `Dispatchers.IO`. The canonical fix shape is `runBlocking + direct repository.refresh() + dao.flowByParent()`. Use this pattern for future integration tests.
- **Next natural change**: pick from the deferred list. `parent-auth-flow` (real Keystore-encrypted auth replacement) is the most architecturally significant — it retires the synthetic path AND `MOCK_PARENT_ID` together. Or revisit the pre-existing test failures (NetworkModuleTest, BootReceiverTest) — those are real bugs worth their own SDD cycles.
- **Strict TDD's RED-before-GREEN contract** continues to work well for small data-layer / auth-layer bug fixes. Keep using the 2-commit pattern (test-only RED → production+test GREEN → merge), with the test-draft deviation documented inline as the canonical "test-quality improvement during apply" pattern when needed.

---

*Archive generated by `sdd-archive` on 2026-07-08 from master @ `862d2d4`. All seven engram topic observations for this change are referenced above for traceability.*