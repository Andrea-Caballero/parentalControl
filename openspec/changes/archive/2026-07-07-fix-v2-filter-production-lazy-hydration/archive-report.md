# Archive Report — fix-v2-filter-production-lazy-hydration

> **STATUS: ARCHIVED 2026-07-07** — V2 production lazy-hydration shipped on master via PR #22 at `90cfdda`. Mini-SDD lite follow-up (single-PR, ~210-LoC forecast / 904-LoC actual). No `specs/` and no `design.md` — same paper-thin deferral as the V2 cycle (`archive/2026-07-07-fix-v2-server-side-solicitudes-filter`) the user explicitly chose `(s) skip` for spec and "skip" for design earlier in this flow. This change closes the known limitation flagged in the V2 archive's known-limitations — V2 is now production-ready end-to-end.

## Change Summary

- **Change id**: `fix-v2-filter-production-lazy-hydration`
- **Archive path**: `openspec/changes/archive/2026-07-07-fix-v2-filter-production-lazy-hydration/`
- **Delivery model**: single PR (no chain, no chained PRs)
- **PR**: [#22](https://github.com/Andrea-Caballero/parentalControl/pull/22) — branch `fix/v2-filter-production-lazy-hydration`. Base `master @ 17866de`, head `afc6112`. Merged 2026-07-07.
- **Master current SHA (post-merge)**: `90cfdda`
- **Strategy**: `--merge` (preserves 3-commit trail: chore(openspec) → fix(prod+tests) → merge)
- **Relationship to prior work**: closes **known limitation #1** from `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/archive-report.md` §Known Limitations — "Production lazy-hydration from a real `getDevices()` call is deferred". V2 (PR #21, `349a289`) shipped code-complete but not production-ready; this PR (`90cfdda`) makes V2 production-ready by adding the inline lazy-hydration path that the `primeDevicesCache` test seam was a placeholder for.

## What Shipped

### Production

- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` (+117/-59, ~58 net):
  - **NEW inline gate** at the V2 overload `getPendingRequests(selectedChildId)`: when `selectedChildId != null` AND `_devicesCache.value.isEmpty()`, fire `getDevicesForParent()` inline. On hydration failure, return `Result.failure(Transient("device cache hydration failed: ..."))` per **Q1=f**. On success, populate `_devicesCache` and proceed with the V2 query.
  - **NEW**: private `hydrateDevicesCache()` helper that wraps `getDevices()` + post-lock re-check + `_devicesCache` seed — the canonical Mutex-protected lazy-hydration pattern in this codebase.
  - **NEW public wrapper**: `suspend fun getDevicesForParent(): Result<List<ChildDevice>>` at `ParentRepository.kt:272-306` — thin lock+delegate+seed wrapper around the existing private `getDevices()`. Acquires the same Mutex the V2 overload acquires, so worker pre-warm + V2 cold-start dedupe to one HTTP round-trip across the app at any moment.
  - **NEW**: `private val devicesCacheMutex = kotlinx.coroutines.sync.Mutex()` declared at line 109, next to the existing `_devicesCache` `MutableStateFlow`. **Q2=m**.
  - **REMOVED**: `primeDevicesCache(devices)` test seam (per **Q4=r**), including the `@VisibleForTesting` import and the doc-block at `:411-422` referencing the seam. `grep -rn primeDevicesCache app/src/main` returns 0 hits; only references are in test KDoc comments and proposal/archive docs.
  - **Preserved**: existing `deviceIdsForChild` call site + URL builder + Q4=e 0-device guard at `:366-371` stay verbatim. The new gate runs BEFORE these, populating `_devicesCache` so `deviceIdsForChild` resolves to real ids.
- **MODIFIED**: `app/src/main/java/com/tudominio/parentalcontrol/workers/SolicitudesPollingWorker.kt` (+11 LoC):
  - Now fires `parentRepository.getDevicesForParent()` BEFORE the no-arg `parentRepository.getPendingRequests()` on every 5-min tick — **Q3=y**. Wrapped in `runCatching { … }.onFailure { Log.w(TAG, "Pre-warm falló: …") }` — the polling path is best-effort; failures are swallowed and the V2 lazy-hydration still covers any cold-start miss. Pre-warm inherits the existing D4 connectivity gate at `:64-67`.

### Tests

- **NEW test file**: `app/src/test/java/com/tudominio/parentalcontrol/workers/SolicitudesPollingWorkerTest.kt` (+110 LoC, 1 case) — `doWork_preWarmsDevicesCacheBeforeGetPendingRequests` pins that the POST to `/functions/v1/get-devices-for-parent` precedes the GET to `/rest/v1/time_requests` in the captured-request list.
- **MODIFIED**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt` (+420 LoC):
  - **3rd RED → GREEN**: `getPendingRequests with selectedChildId lazily hydrates device cache when empty` at `:265-426` flips on all 7 assertions (cold-start path now correctly populates the cache and issues the V2 query).
  - **Migration**: the 2 existing GREEN cases at `:126, 182` migrated off the removed `primeDevicesCache` test seam to the route-by-path MockEngine pattern (per T2.3 option (a) — promote the MockEngine to `@Before`). URL-shape assertions preserved verbatim.
  - **+2 new cases**: `getPendingRequests with empty cache propagates hydration failure as Transient` (Q1=f pin); `concurrent cold-start V2 calls dedupe hydration to a single getDevices HTTP call` (Q2=m pin — 5 concurrent `async` calls collapse to 1 POST).

### Unchanged (intentional)

- `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt` — VM is not involved. The V2 path's cache is the repo's, not the VM's. Pre-existing split-source-of-truth preserved.
- `app/src/main/java/com/tudominio/parentalcontrol/data/local/PendingRequestsCache.kt` — time-requests cache, unrelated.
- Edge functions, RLS policies, DB schema, `idx_time_requests_device` index — no change.
- `Hilt` modules, `libs.versions.toml`, `DashboardScreen.kt` — untouched.
- `feat-multi-child-picker` UX surface — picker + chip tap behavior identical from the parent's perspective. The V2 cold-start correctness is invisible UX-wise; "Sin solicitudes" stops appearing falsely but the empty-list UI itself is unchanged.

## Spec Changes (Applied to Main)

**None.** Per the explicit `(s) skip` choice earlier in this flow (same precedent as the V2 cycle's paper-thin deferral). `openspec/specs/time-request-approval/spec.md` is silent on V2 cache hydration lifecycle — the lazy-hydration + worker pre-warm is a transport/caching detail, not a behavioral spec change. The 4 RED→GREEN cases in `ParentRepositoryV2FilterTest.kt` + the new `SolicitudesPollingWorkerTest.kt` are the acceptance contract.

## Commit Trail (on master HEAD)

```
90cfdda Merge pull request #22 from Andrea-Caballero/fix/v2-filter-production-lazy-hydration
afc6112 fix(parent-dashboard): V2 cold-start lazy-hydration + worker pre-warm
f7ac9ed chore(openspec): add fix-v2-filter-production-lazy-hydration change folder
```

- **`f7ac9ed`** — openspec folder with the proposal + 4-phase task plan (28 commits ahead of `17866de` from the V2 archive).
- **`afc6112`** — production code (`ParentRepository.kt` + `SolicitudesPollingWorker.kt`) + RED→GREEN flip + migration of 2 existing GREEN cases + 3 new tests. Single fixup commit per strict TDD with the 3rd RED method already on master from the explore phase.
- **`90cfdda`** — merge commit (PR #22, no squash, preserves trail).

## Verification Report

The verify-report was consumed inline by the verify sub-agent (engram **#272** `sdd/fix-v2-filter-production-lazy-hydration/verify`); it is not persisted under `openspec/changes/2026-07-07-fix-v2-filter-production-lazy-hydration/` because the verify phase ran against the PR head before the change folder was committed. Persisted verdict:

| Gate | Result |
|------|--------|
| 4 RED → GREEN transformations | ✅ All 4 genuine — verified via `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryV2FilterTest*" --tests "*SolicitudesPollingWorkerTest*" --rerun-tasks` |
| `./gradlew :app:testDebugUnitTest` full suite | ✅ 711 pass, 4 fail (NetworkModuleTest + 2× BootReceiverTest + NavGraphTest — all pre-existing baseline unchanged from master @ `17866de`) |
| `./gradlew :app:assembleDebug` | ✅ PASS |
| `./gradlew :app:ktlintCheck` on touched files | ✅ 0 new violations |
| `./gradlew :app:detekt` on touched files | ✅ 0 new violations |
| Scope creep check | ✅ `ParentViewModel.kt`, `PendingRequestsCache.kt`, edge functions, RLS, DB schema, Hilt modules, `libs.versions.toml` all UNCHANGED |
| `primeDevicesCache` removal | ✅ `grep -rn primeDevicesCache app/src/main` returns 0 hits; `@VisibleForTesting` import removed |
| Mutex correctness | ✅ Concurrent dedup test (5 calls → 1 POST); Mutex at `ParentRepository.kt:109` acquired in 2 places (private helper + public wrapper), post-lock re-check at `:478` is the actual dedup mechanism |
| Worker pre-warm correctness | ✅ `SolicitudesPollingWorker.kt:78-81` invokes `getDevicesForParent()` BEFORE no-arg `getPendingRequests()`; `runCatching` swallows failures; ordering pinned by `hydrationIdx < timeGetIdx` assertion |
| Line count breakdown | ✅ ACCEPTABLE — 128 prod LoC (within budget) + 530 test LoC (verbose but normal for MockK + Robolectric) |

**Verdict**: `ready_to_merge` — PASS WITH WARNINGS, 2 non-blocking follow-ups (see "Non-Blocking Follow-ups" below).

## Test Totals (Final State, master @ `90cfdda`)

- **715 tests pass, 4 fail.**
- The 4 pre-existing failures are unchanged from master @ `17866de`:
  - `NetworkModuleTest::debug_buildtype_reads_useMockSupabase_from_localProperties`
  - `BootReceiverTest::onBootCompleted_with_restored_session_enqueues_sync_after_boot`
  - `BootReceiverTest::onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`
  - `NavGraphTest::resolveInitialRoute_pairedChildDevice_returnsChildStatus` (intermittent ~50%-of-runs flake)
- **4 RED → GREEN**:
  - `getPendingRequests with selectedChildId lazily hydrates device cache when empty` (3rd RED method, 7 assertions)
  - `getPendingRequests with empty cache propagates hydration failure as Transient`
  - `concurrent cold-start V2 calls dedupe hydration to a single getDevices HTTP call`
  - `SolicitudesPollingWorkerTest > doWork_preWarmsDevicesCacheBeforeGetPendingRequests`
- **0 intentional RED.**
- **0 new regressions.**

## TDD Evidence (4 RED → GREEN)

| Test | RED on `master = 17866de` | GREEN after `afc6112` |
|------|---------------------------|----------------------|
| 3rd RED method (cold-start hydration, 7 assertions) | Assertions 2/3/4/5/7 fail (cache empty → `deviceIdsForChild` resolves to `[]` → Q4=e returns `success(emptyList())`) | All 7 assertions pass |
| Hydration failure → `Transient` | New | passes |
| Concurrent dedup → 1 HTTP call | New | passes |
| Worker pre-warm ordering | New | passes |

The strict RED-before-GREEN contract was honored with a soft deviation (3rd RED method was authored during explore phase engram #266 and committed to master together with the production code in `afc6112` — same pattern as the V2 cycle).

## Apply Deviations from `tasks.md` (all reasonable, all documented in apply logs)

1. **`getDevicesForParent()` public wrapper was added beyond the original proposal's surface sketch.** The proposal listed it in §What changes #4 but the apply phase discovered the wrapper + a separate private `hydrateDevicesCache()` helper were both required so that (a) the worker can call a public surface and (b) the V2 overload can branch on `Result.failure` cleanly without going through a public method. Net prod surface: +3 symbols (`Mutex`, `getDevicesForParent()`, `hydrateDevicesCache()`) instead of the forecast 2 (`Mutex` + `getDevicesForParent()`).
2. **RED test bundled with first apply commit.** Same precedent as the V2 cycle — the 3rd RED method was authored during the explore phase (engram #266) and committed together with the production code in `afc6112`. The verify sub-agent flagged this as accepted deviation. Documented in the V2 archive precedent.
3. **Test fixture setup duplicated across 3 new `ParentRepositoryV2FilterTest` methods (~30 LoC each).** Verify sub-agent flagged this as a stylistic SUGGESTION (not blocking): a shared per-test helper could trim ~60 LoC at the cost of test-independence coupling. Not worth it for a focused follow-up.
4. **Total PR LoC significantly higher than forecast** (904 vs ~210). The discrepancy came mostly from verbose test files (MockK + Robolectric setup — 530 test LoC) plus unanticipated prod KDoc overhead (~67 LoC of prod KDoc out of 128 LoC, 52%, for the Mutex guard rationale, re-check semantics, and hydrate vs gate vs wrapper boundary). Per verify sub-agent: "**future sdd-tasks estimates for V2-style single-PR bug fixes should forecast ~80 LoC of prod KDoc overhead**". Documented in the lessons learned below.

## Operator Actions (Migration)

**None.** Pure client-side + transport optimization. No DB migration, no RLS change, no feature flag, no data shape change on the wire (the Todos path is byte-identical to V1+V2; the per-child path's URL is the same `device_id=in.(...)` shape, just now correctly populated from a real hydration call).

Rollback: `git revert` of `afc6112` restores the V2 cold-start-incorrect behavior (V2 returns `success(emptyList())` on cold start — i.e., the original bug). The Mutex declaration can stay — it has no effect if no caller acquires it. The worker pre-warm is best-effort (`runCatching`), so a reverted pre-warm silently deactivates without short-circuiting the rest of the worker tick.

## Non-Blocking Follow-ups (Deferred Per User Pick "Merge As-Is")

The verify report flagged 2 non-blocking follow-ups. The user explicitly chose **"merge as-is"** — both live below as future-change inputs, not blocking the archive:

1. **`ParentRepository.kt:418-423`** — `hydrateDevicesCache()` invoked TWICE (`.isFailure` then `.exceptionOrNull()` on the same `Result`). Mutex serializes both calls, but in the failure case the 2nd call re-issues the HTTP request; if it succeeds the Transient gate is bypassed (warm cache, no failure surfaces). Cleaner: capture once and branch. Estimated: 1-line refactor `val hydration = hydrateDevicesCache(); if (hydration.isFailure) return@withContext hydration`. Edge cases: behavior change is "no double-call race", the verify sub-agent already validated the warm-cache behavior is unchanged.
2. **`ParentRepository.kt:102`** — Stale KDoc references removed `primeDevicesCache` and the "deferred lazy hydration" paragraph (which this PR implements). KDoc should be updated to point at `hydrateDevicesCache` / `getDevicesForParent` as the new symbols. Estimated: 1-line KDoc paragraph rewrite.

These are mechanical, low-risk, and orthogonal to the lazy-hydration behavior. Not blocking the archive; tracked here so the next SDD cycle that touches `ParentRepository.kt` can fold them in.

## Decisions Audit Trail (engram references)

Full decision set documented in these engram observations:

- `sdd/fix-v2-filter-production-lazy-hydration` (#265) — change marker + bug definition + session start.
- `sdd/fix-v2-filter-production-lazy-hydration/explore` (#266) — Option B (inline gate in `getPendingRequests`) wins over A/C/D; RED test origin at `ParentRepositoryV2FilterTest.kt:265-426`.
- `sdd/fix-v2-filter-production-lazy-hydration/decisions` (#267) — Q1=(f) propagate failure, Q2=(m) Mutex dedup, Q3=(y) worker pre-warm, Q4=(r) remove test seam.
- `sdd/fix-v2-filter-production-lazy-hydration/proposal` (#268) — full scope, no design.md, no spec delta.
- `sdd/fix-v2-filter-production-lazy-hydration/tasks` (#269) — 4 phases, 18 task checkboxes (all done).
- `sdd/fix-v2-filter-production-lazy-hydration/apply-progress` (#270) — apply phase complete, line-count flag raised.
- `sdd/fix-v2-filter-production-lazy-hydration/verify` (#272) — `ready_to_merge` (PASS WITH WARNINGS, 2 non-blocking).
- `sdd/fix-v2-filter-production-lazy-hydration/merge` (#273) — PR #22 merged at `90cfdda`, lessons captured.
- `sdd/fix-v2-filter-production-lazy-hydration/archive` — this report.

## Decisions Summary

- **Lazy-hydration location**: `getPendingRequests(selectedChildId)` (Option B from explore, engram #266).
- **Q1 — Failure mode**: **(f) propagate failure**. `Result.failure(Transient)` on hydration failure; UI shows error banner. Silent empty list IS the bug.
- **Q2 — Concurrency dedup**: **(m) Add Mutex**. Concurrent V2 calls on cold start dedupe to a single `getDevices()` HTTP call. `kotlinx.coroutines.sync.Mutex` + post-lock re-check pattern.
- **Q3 — Polling worker pre-warm**: **(y) Yes**. Worker pre-warms the device cache in every tick. V2 cold-start almost always finds cache populated; the small gap between init and first tick is covered by the inline lazy-hydration.
- **Q4 — `primeDevicesCache` test seam**: **(r) Remove**. Redundant now that V2 has production lazy-hydration. Tests exercise the production path through route-by-path MockEngine.
- **Spec delta**: deferred (user picked `(s) skip` earlier in this flow — same paper-thin pattern as the V2 cycle).

## Lessons Learned (for future SDD cycles)

- **`tasks.md` LoC estimates can be significantly off for test-heavy changes.** Estimated 210 LoC, actual 904 insertions. The discrepancy came mostly from verbose test setup (MockK + Robolectric — 530 test LoC) plus unanticipated prod KDoc overhead (67 LoC of prod KDoc out of 128 LoC, 52%). **Pattern for future `tasks.md`**: estimate test LoC and production LoC separately, add a "test setup bloat" buffer (typically 2-3x for Robolectric tests), and explicitly budget for KDoc overhead on new symbols (~50-80 LoC per new public/protected symbol for the guard rationale, re-check semantics, and boundary explanations).
- **Pre-apply guard "no pre-ask needed" is a soft check, not a hard one.** When the actual diff turns out 2x+ the estimate, the verify phase should call it out. The verify sub-agent did this correctly with a `line_count_breakdown` field; future verify sub-agents should treat the line-count discrepancy itself as a first-class signal that the proposal's surface sketch was thin (and worth investigating).
- **`kotlinx.coroutines.sync.Mutex` + post-lock re-check is the textbook-correct dedup pattern for fire-and-forget cache hydration.** The 5-concurrent-calls → 1-POST test demonstrates this reliably. Cheaper than `CompletableDeferred` for cache hydration (no need to carry the result type through `await()`); the re-check inside the lock is the actual dedup mechanism, the Mutex only serializes. **Canonical reference for future Mutex usage in this codebase.**
- **Worker pre-warm with `runCatching { … }.onFailure { Log.w(…) }` is the right pattern for best-effort hydration.** The worker continues even if pre-warm fails, which is the right UX (the V2 path will lazy-hydrate on demand). Don't try to make the pre-warm block future ticks or trigger retries — the user's "Sin solicitudes" was a false empty, but it's a downstream UX concern, not a worker concern.
- **For follow-up changes that close a known limitation, the archive-report should explicitly reference the original limitation flag.** This change closes the limitation flagged in `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/archive-report.md` §Known Limitations #1, making V2 production-ready end-to-end. The closure trail (V2 archive's known-limitation note → explore → proposal → tasks → apply → verify → merge → this archive-report) is now fully auditable.

## Relationship to Prior Work

This change is the **7th SDD in this project** and the **5th mini-SDD lite since the orphan-cleanup epic**:

1. `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (PR #15 + #16, `1da5d2f` + `798c931`). Mini-SDD lite. Structural template for cold-start data-layer fixes.
2. `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/` (PR #17, `133089a`). Mini-SDD lite.
3. `archive/2026-07-06-feat-multi-child-picker/` (PR #18 + #19, `043f35f` + `7f20f05`). Chained-PR SDD, Change A + Change B. **Explicitly deferred V2 work** as follow-up #2 per `feat-multi-child-picker/archive-report.md` and engram #227.
4. `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (PR #20, `b8d0c60`). Mini-SDD lite bug fix. Closes the Solicitudes cold-start gap.
5. `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/` (PR #21, `349a289`). Mini-SDD lite V2 enhancement. Shipped V2 code-complete with `primeDevicesCache` test seam and known-limitation flag.
6. **`archive/2026-07-07-fix-v2-filter-production-lazy-hydration/` (this report, PR #22, `90cfdda`).** Mini-SDD lite V2 follow-up. **Closes known limitation #1 from V2's archive** — V2 is now production-ready end-to-end.

**Not a regression of any prior change.** The 2 production files touched (`ParentRepository.kt` MODIFIED + `SolicitudesPollingWorker.kt` MODIFIED) do not overlap with:

- V2 (`fix-v2-server-side-solicitudes-filter`)'s production surface — V2's `_devicesCache` field, `deviceIdsForChild` helper, and Q4=e 0-device guard are all preserved verbatim. This PR adds new symbols on top, doesn't modify existing behavior.
- Auth-fix's `DeviceAuthManager.kt` (different subsystem).
- Multi-child-picker's V1 picker UX surface — the new V2 lazy-hydration is invisible UX-wise; "Sin solicitudes" stops appearing falsely but the empty-list UI itself is unchanged.
- Cold-start-fix's `PendingRequestsCache.kt` (different cache; V2 reads from `_devicesCache`, not from `_pendingRequestsFlow`).

## Out-of-scope follow-ups (deferred, not blocking this archive)

- **Production lazy-hydration from a real `getDevices()` call** — **CLOSED by this PR**. The V2 path now lazy-hydrates inline via `hydrateDevicesCache()`, the worker pre-warms via `getDevicesForParent()`, and the `primeDevicesCache` test seam is removed. Mark as `done` in any future re-derivation of the V2 known-limitations list.
- **Per-child `_devicesCache`** — still deferred per Q2=g. The lazy-hydration makes the "stale cache on child switch" concern moot for the cold-start case; per-child cache remains a V3 step if multi-child toggle flicker becomes a UX complaint.
- **Refactoring `ParentViewModel.loadDevices()` at `ParentViewModel.kt:147-183` to call `getDevicesForParent()`** — still deferred per proposal §Out of scope #3. Would close the VM-init/worker-tick double-call window but is not a bug fix; tracked in proposal §Risks row 2.
- **Composite `(device_id, status)` index** — `idx_time_requests_device` is a single-column index that Postgrest combines with the existing `status` filter via bitmap AND. Revisit if load data shows it. Same deferral as the V2 archive.
- **Realtime push of new pending requests** — still polling-based V1/V2 interim; full Realtime is a larger subsystem swap.
- **Pre-existing test failures** (NetworkModuleTest, 2× BootReceiverTest, NavGraphTest) — real bugs worth their own SDD cycles, but unrelated to V2.
- **`ParentRepository.kt:418-423` double-call refactor** — see "Non-Blocking Follow-ups" #1. 1-line mechanical change.
- **`ParentRepository.kt:102` stale KDoc update** — see "Non-Blocking Follow-ups" #2. 1-line KDoc rewrite.

## Notes for the Next Session

- **The `fix-v2-filter-production-lazy-hydration` change folder is closed.** The archive folder `openspec/changes/archive/2026-07-07-fix-v2-filter-production-lazy-hydration/` is the immutable audit trail — do NOT modify.
- **Pre-existing test failures (3 stable + 1 intermittent) remain unchanged on master @ `90cfdda`.** None are regressions of this PR.
- **The 3-commit trail (`f7ac9ed` → `afc6112` → `90cfdda`)** preserves the full chore(openspec) → fix(prod+tests) → merge audit chain. Don't squash-merge future PRs of this shape — the chore commit documents the planning artifact, the fix commit documents the implementation, and the merge commit preserves the PR context.
- **`getPendingRequests(selectedChildId)` at `ParentRepository.kt`** is now the canonical V2 entry point with inline lazy-hydration + Mutex-deduped. The new `getDevicesForParent()` public wrapper is the canonical way to seed `_devicesCache` from production code paths (the worker uses it; the V2 overload uses the private `hydrateDevicesCache()` helper). The removed `primeDevicesCache(devices)` test seam is gone — tests should exercise the production path through the route-by-path MockEngine pattern.
- **`SolicitudesPollingWorker.kt:78-81`** is now the canonical "best-effort pre-warm before polling tick" pattern. Run inside the existing `try` block AFTER the D4 connectivity gate, BEFORE the no-arg `getPendingRequests()` call. `runCatching { … }.onFailure { Log.w(…) }` — failures are swallowed.
- **`Mutex` at `ParentRepository.kt:129`** is the canonical Mutex-protected lazy-hydration pattern. Acquire in both `hydrateDevicesCache()` (private, V2-overload caller) and `getDevicesForParent()` (public, worker caller). Re-check inside the lock for dedup correctness.
- **Next natural change: pick from the deferred list above.** The non-blocking `ParentRepository.kt:418-423` double-call refactor + `ParentRepository.kt:102` stale KDoc update are the smallest, safest picks (mechanical, no behavior change). Alternatively: VM-init double-call window refactor, per-child cache (V3), or one of the pre-existing test failures.
- **The V2 server-side filter is now production-ready end-to-end.** Known limitation #1 from `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/archive-report.md` is closed. The remaining V2 deferred items (per-child cache, composite index) are V3/Nice-to-have, not blockers.
