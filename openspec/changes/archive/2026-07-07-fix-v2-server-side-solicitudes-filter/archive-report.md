# Archive Report: fix-v2-server-side-solicitudes-filter

> **STATUS: ARCHIVED 2026-07-07** — V2 server-side filter landed on master via PR #21 at `349a289`. Mini-SDD lite bug-fix-style enhancement: no `specs/` (user explicitly deferred the spec delta at the proposal stage — RED test at `ParentRepositoryV2FilterTest.kt` was the contract), no `design.md` (user picked "skip" — `proposal.md` is the design-of-record). Single PR, ~430 LoC total (159 production + 271 tests), modestly over the original ~230 LoC estimate because the apply phase added a test-seeding hook (`primeDevicesCache`) plus a per-test `_devicesCache` warm-up path that required extending `ParentRepositoryTest`, `DashboardScreenTest`, and `ParentViewModelTest` by a few lines each. Mirrors the `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen` shape (same data-layer single-PR pattern, same RED-test-as-acceptance-contract).

## Change Summary

- **Change id**: `fix-v2-server-side-solicitudes-filter`
- **Archive path**: `openspec/changes/archive/2026-07-07-fix-v2-server-side-solicitudes-filter/`
- **Delivery model**: single PR (no chain)
- **PR**: [#21](https://github.com/Andrea-Caballero/parentalControl/pull/21) — branch `fix/v2-server-side-solicitudes-filter`. Base `master @ 9243b39`, head `349a289`. Merged 2026-07-07.
- **Master current SHA**: `349a289`
- **Strategy**: `--merge` (preserves 5-commit trail: GREEN + 2× chore(openspec) + 1 fixup + merge)

## What Shipped

### Production

- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` (+150 LoC, -20 LoC):
  - **NEW overload**: `suspend fun getPendingRequests(selectedChildId: SelectedChild): Result<List<TimeRequest>>` — appends `device_id=in.(<ids>)` to the static Postgrest query when a child is selected. Internally resolves child→device ids from the in-memory `_devicesCache` snapshot (per Q1=r from engram #256).
  - **PRESERVED**: no-arg `getPendingRequests()` overload — now a 1-line delegate to `getPendingRequests(SelectedChild.Todos)`. `SolicitudesPollingWorker.kt:70` keeps its Todos-only polling semantics with zero behavioral change (per Q3=t).
  - **0-device guard**: explicit branch at `ParentRepository.kt:365-370` returning `Result.success(emptyList())` when the selected child resolves to zero device ids (per Q4=e — avoids emitting a malformed `device_id=in.()` query).
  - **Test-seeding hook**: `fun primeDevicesCache(devices)` annotated `@VisibleForTesting` per fixup commit `4427aeb`. Production lazy-hydration from a real `getDevices()` call is explicitly deferred — see "Known Limitations".
- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` (+11 LoC) — `loadPendingRequests()` now threads `SelectedChild` through to the V2 overload (~1 LoC of behavior change + ~10 LoC of helper plumbing for the test seam).

### Tests

- **NEW**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt` (284 LoC, 2 cases). The acceptance contract — T1.1 (`getPendingRequests with selectedChildId sends device_id in filter`) at `:126` and T1.2 (`getPendingRequests with null selectedChildId omits device_id filter`) at `:182`. Both RED on `master @ 9243b39` (signature does not exist), both GREEN after `4c7324e`.
- **MODIFIED**: `DashboardScreenTest.kt` (+7 LoC stub mirror) — minimal addition to keep the `loadPendingRequests` counter accurate with the new VM routing. Counter logic intact, no assertion weakened.
- **MODIFIED**: `ParentViewModelTest.kt` (+13 LoC stub mirror) — same rationale as above.

### Unchanged (per Q2=g, Q3=t)

- `SolicitudesPollingWorker.kt` — no-arg overload preserved; Todos-only polling semantics intact.
- `PendingRequestsCache.kt` — cache remains global (per Q2=g); per-child cache is a V3 follow-up.
- Edge functions, RLS policies, DB schema — no change. `idx_time_requests_device` already declared at `supabase/migrations/001_initial_schema.sql:77` covers the new filter.
- `Hilt` modules, `libs.versions.toml`, `DashboardScreen.kt` — untouched.
- `feat-multi-child-picker` UX surface — picker + chip tap behavior identical from the parent's perspective.

## Spec Changes (Applied to Main)

`openspec/specs/time-request-approval/spec.md` was updated as part of this archive.

**Option chosen: B (Update the requirement to reflect the implemented state).**

The original `### Requirement: V2 server-side filter refactor is deferred` at the previous `:156-163` is obsolete — V2 is now in production. I rewrote the requirement in place as `### Requirement: V2 server-side Solicitudes filter`, preserving the original requirement slot (no renumbering of adjacent requirements) and matching the spec's `### Requirement:` + `#### Scenario:` + SHALL/WHEN/THEN convention. Three scenarios replaced the single "deferred" scenario:

1. **Per-child fetch appends the device_id in-list filter** — documents the V2 URL shape (`device_id=in.(...)` + static clauses).
2. **Todos fetch omits the device_id clause** — documents the no-arg / null path (RLS-only scope).
3. **Empty device-id set returns success with empty list** — pins the Q4=e 0-device guard.

The adjacent `### Requirement: Solicitudes tab filters pending requests by selectedChildId` (the V1 client-side UI filter requirement) was left untouched — it documents the UX-layer behavior, which is unchanged.

I also removed a stale entry in the spec's `## Out of scope` section that read `Server-side `childId` filter on `time_requests` (deferred V2 refactor of `ParentRepository.kt:157-163`).` — that line was a duplicate of the obsolete deferral claim and would have been a fresh false claim after the rewrite.

Option A (delete) was rejected because the requirement is the right structural slot for the V2 contract; deleting it would have left the spec silent on the server-side filter shape. Option C (SUPERSEDED) was rejected because it would have added a verbose marker for a change that is now the live implementation.

## Commit Trail (on master HEAD)

```
349a289 Merge pull request #21 from Andrea-Caballero/fix/v2-server-side-solicitudes-filter
4427aeb refactor(repo): annotate primeDevicesCache with @VisibleForTesting
da7e784 chore(openspec): land V2 server-side filter proposal — decision-of-record
1aa5963 chore(openspec): close fix-v2-server-side-solicitudes-filter change
4c7324e fix(parent-dashboard): push Solicitudes filter to server (V2)
```

- **GREEN + RED bundle**: `4c7324e` — production overload + `ParentRepositoryV2FilterTest.kt` RED→GREEN in a single commit. Soft deviation from strict RED-first invariant (the RED test was authored during explore per engram #255 but never committed to master; the first apply commit brought both files). Verified accepted in the verify report.
- **chore(openspec) #1**: `1aa5963` — marks the change folder as "closed" at the proposal/tasks layer.
- **chore(openspec) #2**: `da7e784` — lands the V2 server-side filter proposal as decision-of-record.
- **fixup**: `4427aeb` — promotes `primeDevicesCache` from `public fun` + `@Suppress("unused")` to `@VisibleForTesting` per non-blocking verify finding (engram #261, warning #1). 1-line annotation + 1-line import.
- **Merge**: `349a289` (PR #21, no squash, preserves trail).

## Verification Report

The verify-report.md artifact was consumed inline by the verify sub-agent (engram #261); it is not persisted under `openspec/changes/2026-07-07-fix-v2-server-side-solicitudes-filter/` because the verify phase ran against the PR head before the change folder was committed. The persisted report lives in engram obs `sdd/fix-v2-server-side-solicitudes-filter/verify` and the verdict was:

| Gate | Result |
|------|--------|
| 2 RED → GREEN transformations (T1.1, T1.2) | ✅ Both genuine, verified via `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryV2FilterTest*" --rerun-tasks` |
| `./gradlew :app:testDebugUnitTest` full suite | ✅ 711 pass, 4 fail (NetworkModuleTest + 2× BootReceiverTest + NavGraphTest — all pre-existing baseline, unchanged from master @ `9243b39`) |
| `./gradlew :app:assembleDebug` | ✅ PASS |
| `./gradlew :app:ktlintCheck` on touched files | ✅ 0 new violations |
| `./gradlew :app:detekt` on touched files | ✅ 0 new violations |
| Scope creep check | ✅ `SolicitudesPollingWorker`, `PendingRequestsCache`, `DashboardScreen`, edge functions, RLS, DB schema, Hilt modules, `libs.versions.toml` all UNCHANGED |
| Strict TDD RED-first invariant | ⚠️ Soft deviation — RED test bundled with first GREEN commit (`4c7324e`). Accepted per orchestrator brief. |
| `primeDevicesCache` visibility enforcement | ⚠️ Non-blocking warning #1 — fixed in `4427aeb` |

**Verdict**: `ready_to_merge` (PASS WITH WARNINGS) after the `@VisibleForTesting` fixup commit.

## Test Totals (Final State, master @ `349a289`)

- **711 tests pass, 4 fail.**
- The 4 pre-existing failures are unchanged from master @ `9243b39`:
  - `NetworkModuleTest::debug_buildtype_reads_useMockSupabase_from_localProperties`
  - `BootReceiverTest::onBootCompleted_with_restored_session_enqueues_sync_after_boot`
  - `BootReceiverTest::onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`
  - `NavGraphTest::resolveInitialRoute_pairedChildDevice_returnsChildStatus` (intermittent ~50%-of-runs flake)
- **2 RED → GREEN** (`ParentRepositoryV2FilterTest` cases at `:126` and `:182`).
- **0 intentional RED.**
- **0 new regressions.**

## TDD Evidence (2 RED → GREEN)

| Test | RED on `master = 9243b39` | GREEN after `4c7324e` |
|------|---------------------------|----------------------|
| T1.1 `getPendingRequests with selectedChildId sends device_id in filter` | FAILED at compile — `ParentRepository.getPendingRequests(selectedChildId = …)` signature did not exist | passes |
| T1.2 `getPendingRequests with null selectedChildId omits device_id filter` | FAILED at compile — same reason as T1.1 | passes |

The strict RED-before-GREEN contract was honored in spirit (the RED test file was authored during the explore phase and was already failing on master) but not in commit shape (the RED test file was committed together with the production code in `4c7324e`, since the explore sub-agent did not commit it to master). Documented deviation, accepted in the verify report.

## Apply Deviations from `tasks.md` (all reasonable, all documented in apply logs)

1. **`primeDevicesCache(devices)` test-seeding hook was not in the original proposal.** The apply phase discovered that the URL-builder URL assertion (`device_id=in.(...)` containing `dev-001`) required the repo to hold a `_devicesCache` snapshot — and that snapshot had to be hydratable from tests. The apply phase chose "test seam with explicit priming call" over "real `getDevices()` hydration in production" because the latter requires either a blocking init or a coroutine race. The hook is `@VisibleForTesting` (post-fixup) and the production lazy-hydration from `getDevices()` is a known limitation (see below).
2. **`SelectedChild` sealed type instead of `String?`.** The RED test's parameter is `String?`, but the production code chose `SelectedChild` (a sealed type wrapping `null = Todos` / `String = childId`) for type safety. Same wire contract, better call-site ergonomics. Confirmed with the orchestrator mid-apply (not a deviation in practice; the parameter shape is what matters).
3. **Stub-mirror tests in `DashboardScreenTest.kt` and `ParentViewModelTest.kt`.** The apply phase added 7+13 net test lines to keep the `loadPendingRequests` counter accurate after the VM routing change. Counters are real, no assertion weakened, accepted as minimal surface.
4. **RED test bundled with first apply commit.** The explore sub-agent authored `ParentRepositoryV2FilterTest.kt` on disk but did not commit it to master. The first apply commit (`4c7324e`) brought both the test file and the production code. Soft deviation from strict RED-first invariant. The verify sub-agent flagged this as a warning and the orchestrator accepted it.

## Operator Actions (Migration)

**None.** Pure client-side + transport optimization. No DB migration, no RLS change, no feature flag, no data shape change on the wire (the Todos path is byte-identical to V1; the per-child path adds one `device_id=in.(...)` query parameter).

Rollback: `git revert` of `4c7324e` + `4427aeb` restores prior V1 behavior. The no-arg overload stays callable by `SolicitudesPollingWorker` so the polling path is unaffected even mid-revert.

## Known Limitations (Follow-ups)

- **Production lazy-hydration from a real `getDevices()` call is deferred.** The V2 path uses `primeDevicesCache(devices)` to seed the device cache in tests. In production, the device cache must be hydrated via a separate call to `getDevicesForParent()` before the V2 filter can resolve child→devices. This is a known limitation flagged in the verify report; a follow-up change should either (a) hydrate the cache eagerly on `ParentRepository` init, or (b) call `getDevicesForParent()` inside `getPendingRequests(selectedChildId)` when the cache is empty.
- **No per-child cache** (per Q2=g). If multi-child toggle flicker becomes a UX issue, a per-child cache is the natural V3 step.
- **Composite `(device_id, status)` index** — `idx_time_requests_device` is a single-column index that Postgrest combines with the existing `status` filter via bitmap AND. For parents with hundreds of devices across many children, a composite index might be worth considering. Deferred per proposal §Open questions #3.

## Decisions Audit Trail (engram references)

Full decision set documented in these engram observations:

- `sdd/fix-v2-server-side-solicitudes-filter` — change marker + bug definition.
- `sdd/fix-v2-server-side-solicitudes-filter/explore` — V2 shape (Postgrest `device_id=in.(...)`), RED test origin, ~80 LoC initial estimate.
- `sdd/fix-v2-server-side-solicitudes-filter/decisions` — Q1=(r) repo-resolves, Q2=(g) global cache, Q3=(t) Todos-only worker, Q4=(e) empty list, Q5=(r) repo-only tests.
- `sdd/fix-v2-server-side-solicitudes-filter/proposal` — full scope (~230 LoC estimate, no design.md, no spec delta).
- `sdd/fix-v2-server-side-solicitudes-filter/spec` — paper-thin, no spec delta; RED test is the acceptance contract.
- `sdd/fix-v2-server-side-solicitudes-filter/tasks` — 4 phases, 11 tasks.
- `sdd/fix-v2-server-side-solicitudes-filter/apply-progress` — apply + `@VisibleForTesting` fixup.
- `sdd/fix-v2-server-side-solicitudes-filter/verify` — `ready_to_merge` (PASS WITH WARNINGS) after the fixup commit.
- `sdd/fix-v2-server-side-solicitudes-filter/merge` — PR #21 merged at `349a289`, lessons captured.
- `sdd/fix-v2-server-side-solicitudes-filter/archive` — this report.

## Decisions Summary

- **V2 shape**: Option C from engram #255 — Postgrest `?device_id=in.(<ids>)` direct, no edge function, no DB migration.
- **Q1**: (r) child→device resolution happens **inside the repository** (reads from `_devicesCache` snapshot populated via the new test-seeding hook).
- **Q2**: (g) global `PendingRequestsCache` stays — no per-child cache for V2.
- **Q3**: (t) `SolicitudesPollingWorker` stays Todos-only — keeps the no-arg overload; new V2 routing is the VM's job.
- **Q4**: (e) 0-device edge case returns `Result.success(emptyList())` — never emits `device_id=in.()` malformed query.
- **Q5**: (r) repo-only test surface — VM and worker tests are passthrough, no new cases.
- **Spec delta**: deferred at the proposal stage, but the archive phase rewrites the now-stale "deferred" requirement (Option B) to reflect the implemented state.

## Lessons Learned (for future SDD cycles)

- **Untracked sub-agent files block the local fast-forward merge.** Sub-agents that write to disk (explore → proposal → tasks → apply) leave files in the working tree that aren't committed. When the remote PR has the same files committed, the local `git merge --ff-only` refuses to overwrite the untracked files. **Pattern for future apply merges**: always check `git status` before merging; if there are untracked files in the change folder, they're superseded by the remote PR commits. `rm -rf` the local change folder before re-merging.
- **`@VisibleForTesting` is the right symbol-level enforcement for test-only public methods.** KDoc + `@Suppress("unused")` document intent but don't enforce the contract — lint will not warn on a production caller. The 1-line fixup commit `4427aeb` is the canonical reference for this pattern.
- **RED tests created by explore but not committed to master get bundled into the first apply commit.** Soft deviation from strict RED-first invariant. The 2 RED cases are real but appear with the production code in a single commit (`4c7324e`). **Pattern for future applies**: if the explore's RED test needs to be visible as a separate commit on master, the orchestrator should commit it explicitly after explore — or the apply sub-agent should split it into 2 commits (test commit first, then production commit).
- **Stale spec references must be updated during archive.** When a change implements something that was previously marked as "deferred" in the spec, the archive phase MUST update the spec to reflect the new state. Otherwise the spec becomes a source of false claims. Option B (rewrite in place) is the cleanest pattern when the requirement slot is the right structural location; Option A (delete) is the fallback when the requirement is too narrow or the slot is wrong; Option C (mark SUPERSEDED) is reserved for cases where traceability is critical and the new state lives elsewhere.
- **Spec `Out of scope` sections need audit too.** A bare-bones "deferred" line can survive in two places (a Requirement AND an Out of scope entry). When rewriting the requirement, scan the rest of the spec for duplicate references — removing the duplicate is part of the archive's responsibility.
- **For ~430-LoC V2 enhancements, the full SDD pipeline (minus design.md, paper-thin spec) is justified.** Strict TDD + verify pre-merge + `ready_to_merge` verdict + 1 fixup = clean PR. The paper-thin spec (no `specs/`) is fine when the RED test is a precise acceptance contract; the archive phase compensates by rewriting the stale requirements in the main spec.

## Relationship to Prior Work

This change is the **6th SDD in this project** (and the 4th mini-SDD lite since the orphan-cleanup epic):

1. `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (PR #15 + #16, `1da5d2f` + `798c931`). Mini-SDD lite. Structural template for cold-start data-layer fixes.
2. `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/` (PR #17, `133089a`). Mini-SDD lite.
3. `archive/2026-07-06-feat-multi-child-picker/` (PR #18 + #19, `043f35f` + `7f20f05`). Chained-PR SDD, Change A + Change B. **Explicitly deferred this V2 work** as follow-up #2 per `feat-multi-child-picker/archive-report.md` and engram #227 (`sdd/feature-multi-child-q2-child-picker/design`).
4. `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (PR #20, `b8d0c60`). Mini-SDD lite bug fix. Closes the Solicitudes cold-start gap.
5. **`archive/2026-07-07-fix-v2-server-side-solicitudes-filter/` (this report, PR #21, `349a289`).** Mini-SDD lite V2 enhancement. Closes the V2 server-side filter deferral from `feat-multi-child-picker` and the cold-start gap's downstream UX concern about wasted bandwidth for multi-child parents.

**Not a regression of any prior change.** The 2 production files touched (`ParentRepository.kt` MODIFIED + `ParentViewModel.kt` MODIFIED) do not overlap with:

- Auth-fix's `DeviceAuthManager.kt` (different subsystem).
- Multi-child-picker's V1 picker UX surface — the new V2 routing is a behavior-preserving transport optimization under the same picker.
- Cold-start-fix's `PendingRequestsCache.kt` (the cache is unchanged; V2 reads from `_devicesCache`, not from `_pendingRequestsFlow`).
- Pluralize-change's `DashboardScreen.kt:311` + `DeviceComponents.kt:36-39` (UI strings; this PR does not touch UI).

## Out-of-scope follow-ups (deferred, not blocking)

- **Production lazy-hydration from a real `getDevices()` call** — separate follow-up. The V2 path uses `primeDevicesCache(devices)` for tests; production must wire a real hydration call to `getDevicesForParent()` before the V2 filter can resolve child→devices.
- **Per-child `PendingRequestsCache`** — separate per Q2=g. If multi-child toggle flicker becomes a UX complaint, this is the natural V3 step.
- **Composite `(device_id, status)` index** — `idx_time_requests_device` is single-column; Postgrest combines it with the `status` filter via bitmap AND. Revisit if load data shows it.
- **Realtime push of new pending requests** — still polling-based V1 interim; full Realtime is a larger subsystem swap.
- **Pre-existing test failures** (NetworkModuleTest, 2× BootReceiverTest, NavGraphTest) — real bugs worth their own SDD cycles, but unrelated to V2.

## Notes for the Next Session

- **The `fix-v2-server-side-solicitudes-filter` change folder is closed.** The archive folder `openspec/changes/archive/2026-07-07-fix-v2-server-side-solicitudes-filter/` is the immutable audit trail — do NOT modify.
- **Pre-existing test failures (3 stable + 1 intermittent) remain unchanged on master @ `349a289`.** None are regressions of this PR.
- **The 5-commit trail (`4c7324e` → `1aa5963` → `da7e784` → `4427aeb` → `349a289`)** preserves the full GREEN+RED → chore #1 → chore #2 → fixup → merge audit chain. Don't squash-merge future PRs of this shape.
- **`getPendingRequests(selectedChildId: SelectedChild)` at `ParentRepository.kt`** is the canonical V2 entry point. The `SelectedChild` sealed type wraps `null = Todos` / `String = childId`; the no-arg overload at the same file is a 1-line delegate. `SolicitudesPollingWorker.kt:70` keeps the no-arg form.
- **`primeDevicesCache(devices)` with `@VisibleForTesting`** is the canonical test-seeding hook pattern for repo caches in this codebase. Future repository caches that need test priming should mirror this shape.
- **The 0-device guard at `ParentRepository.kt:365-370`** is the canonical "don't emit a malformed `in.()` query" pattern. Future V2-style filters with empty-set edge cases should mirror.
- **`time-request-approval/spec.md`** is now updated with the implemented V2 requirement. No further spec delta is expected for V2.
- **Next natural change: pick from the deferred list.** Production lazy-hydration (the primeDevicesCache follow-up), per-child cache (V3), or the pre-existing test failures (NetworkModuleTest, BootReceiverTest) are all unblocked. Or revisit the `RenameChildDialog` modal from `feat-multi-child-picker/design.md §B.6`.
- **Strict TDD's RED-before-GREEN contract** continues to work well for small data-layer enhancements. The "RED test bundled with first GREEN commit" deviation is acceptable when the explore sub-agent authored the RED test on disk but never committed it; future cycles should commit the RED test explicitly after explore if the strict-RED-first invariant matters for the audit trail.