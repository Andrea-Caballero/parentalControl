# Proposal: fix-v2-filter-production-lazy-hydration

> Bug-fix-style enhancement to make the V2 server-side filter (`fix-v2-server-side-solicitudes-filter`, PR #21 merged at `349a289`) production-ready. Single PR, ~210 LoC (50 production + 160 tests). Mirrors the `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md` format exactly — same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt:265-426` (the cold-start hydration case — V2 must self-hydrate from a real `getDevices()` call when the cache is empty).

## Why

V2 (`fix-v2-server-side-solicitudes-filter`, merged at `349a289`) shipped the server-side `device_id=in.(...)` filter with a **test-only seam** at `ParentRepository.kt:423-427` (`primeDevicesCache(devices)`, annotated `@VisibleForTesting`) to seed `_devicesCache` from unit tests. The V2 production path was deliberately left without a real cache seeder — the V2 archive's known-limitations note flagged this as a follow-up (engram `sdd/fix-v2-server-side-solicitudes-filter/archive`).

Why it matters now: in production, `_devicesCache` is empty unless a test calls `primeDevicesCache`. The VM's `loadDevices()` at `ParentViewModel.kt:147-183` seeds the VM's own `_devices` mirror, NOT the repo's `_devicesCache`. When a parent opens the app on cold start and selects a child (or has one pre-selected from a prior session via the DataStore-backed picker), the V2 path calls `deviceIdsForChild(childId)` at `ParentRepository.kt:365` against an empty cache → `deviceIds` resolves to `[]` → the Q4=e branch at `ParentRepository.kt:366-371` returns `Result.success(emptyList())` → `DashboardScreen` renders "Sin solicitudes" even when there ARE pending requests for that child. The parent sees an empty list when the truth is "we never looked".

This change makes V2 production-ready by (1) lazy-hydrating the device cache inline inside the V2 path when the cache is empty AND a child is selected, and (2) having `SolicitudesPollingWorker` pre-warm the cache on every 5-min tick so the common "parent opens Solicitudes tab after the worker has been running" path always finds a warm cache.

## What changes

1. **Add inline lazy-hydration gate** to `ParentRepository.getPendingRequests(selectedChildId: String?)` (~40 LoC at `ParentRepository.kt:354-391`):
   - **Precondition**: `selectedChildId != null` (i.e., a specific child is selected — Todos path is unaffected) AND `_devicesCache.value.isEmpty()` (cold-start condition).
   - **Action**: invoke a private `hydrateDevicesCache()` helper that calls `getDevices()` (the existing `ParentRepository.kt:272-306` method) and seeds `_devicesCache` from the success payload. The helper holds a `Mutex` (`Mutex()` or `kotlinx.coroutines.sync.Mutex`) so concurrent V2 cold-start calls dedupe to a single `getDevices()` HTTP call.
   - **Failure mode (Q1=f)**: on hydration `Result.failure(...)`, return `Result.failure(DeviceListError.Transient("device cache hydration failed: <msg>"))` from the V2 call — do NOT silently return `success(emptyList())` (that IS the bug).
   - **Success path**: `_devicesCache.value` is now populated, proceed with the existing `deviceIdsForChild` resolution + `device_id=in.(...)` URL builder at `ParentRepository.kt:365-373`.
   - **Warm cache**: if `_devicesCache.value.isEmpty()` is false, the gate is skipped — the V2 path runs in <1ms as before. The Mutex is only acquired on cold start, never contended on warm cache.
2. **Add `getDevicesForParent()` to the worker** (`SolicitudesPollingWorker.kt:60-95`, ~10 LoC):
   - In the existing `try` block, BEFORE `parentRepository.getPendingRequests()` (line 70), call `parentRepository.getDevicesForParent()` (a new public method that internally acquires the same Mutex and seeds `_devicesCache`) to keep the device cache fresh on every tick.
   - The worker swallows `getDevicesForParent()` failures (logs a warning, continues to the no-arg `getPendingRequests()` call) — the polling path is best-effort, the V2 lazy-hydration still covers any cold-start miss.
   - Worker reuses the same Mutex the V2 path uses — single in-flight `getDevices()` call across the app at any moment.
3. **Remove `primeDevicesCache(devices)` test seam** from `ParentRepository.kt:423-427` (per Q4=r) — redundant now that V2 has production lazy-hydration. Update `ParentRepositoryV2FilterTest.kt` to construct a `ParentRepository` against a URL-routed `MockEngine` that answers both the hydration POST and the V2 GET (the RED test at `ParentRepositoryV2FilterTest.kt:265-426` already follows this pattern).
4. **Add `getDevicesForParent()` public method** to `ParentRepository` (~10 LoC at `ParentRepository.kt:272-306` — wraps the existing `getDevices()` body, acquires the Mutex, seeds `_devicesCache` on success, returns the raw `Result<List<ChildDevice>>`). The worker uses this; the V2 path uses the private `hydrateDevicesCache()` helper.
5. **Apply-phase tests** (apply, not this proposal):
   - Existing `ParentRepositoryV2FilterTest.kt:265-426` (`getPendingRequests with selectedChildId lazily hydrates device cache when empty`) goes RED → GREEN. All 7 assertions flip.
   - Existing 2 GREEN cases at `ParentRepositoryV2FilterTest.kt:126, 182` MUST stay GREEN (no behavioral change on warm cache + Todos path).
   - New `ParentRepositoryV2FilterTest` cases: hydration failure → `Result.failure(Transient)`; concurrent cold-start calls dedupe to a single HTTP call (MutEx proof via captured `lazyCaptured` size).
   - New `SolicitudesPollingWorkerTest` case: worker pre-warm — the no-arg `getPendingRequests()` tick is preceded by a `getDevicesForParent()` call, captured via a URL-routed `MockEngine` similar to the V2 test.

## Capabilities

- **New**: none.
- **Modified**: none. `time-request-approval/spec.md` is silent on V1/V2 cache lifecycle (V1 was client-side filter, V2 is server-side filter with a lazy cache). The lazy-hydration + worker pre-warm is a transport/caching detail, not a behavioral spec change. Same precedent as the V2 proposal's spec deferral (`archive/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md:26-28`) and the auth-fix proposal's spec deferral (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21`).

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `data/repository/ParentRepository.kt:354-391` | Modified | Add the inline lazy-hydration gate (precondition check + Mutex acquisition + `getDevices()` call + `_devicesCache` seed) to the V2 overload. Existing `deviceIdsForChild` + URL builder unchanged. |
| `data/repository/ParentRepository.kt:272-306` | Modified | Expose a public `getDevicesForParent()` wrapper that acquires the Mutex and seeds `_devicesCache`. Internal `getDevices()` stays private. |
| `data/repository/ParentRepository.kt:109` | Modified (add) | Declare `private val devicesCacheMutex = Mutex()` next to the existing `_devicesCache` `MutableStateFlow`. |
| `data/repository/ParentRepository.kt:423-427` | Removed | `primeDevicesCache(devices)` test seam deleted (Q4=r). `@VisibleForTesting` import also removed. |
| `workers/SolicitudesPollingWorker.kt:60-95` | Modified | Add `parentRepository.getDevicesForParent()` pre-warm call before the no-arg `getPendingRequests()`. Failure-tolerant (log + continue). |
| `viewmodel/ParentViewModel.kt` | Unchanged | VM is not involved. The V2 path's cache is the repo's, not the VM's — pre-existing split-source-of-truth is preserved. |
| `data/local/PendingRequestsCache.kt` | Unchanged | PendingRequestsCache is the time-requests cache, not the device cache. Unrelated. |
| `test/.../ParentRepositoryV2FilterTest.kt:265-426` | RED → GREEN | 7 assertions flip on cold-start + warm-cache contracts. Existing 2 GREEN cases stay GREEN. |
| `test/.../ParentRepositoryV2FilterTest.kt` | +2 new cases | Hydration failure → `Transient`; concurrent cold-start dedup (1 HTTP call). |
| `test/.../SolicitudesPollingWorkerTest.kt` | +1 new case | Worker pre-warm: `getDevicesForParent()` is called before `getPendingRequests()` on each tick. |
| `openspec/specs/time-request-approval/spec.md` | Unchanged (deferred) | Same precedent as the V2 proposal — caching/lifecycle is not a spec-level concern. |
| `supabase/migrations/*.sql` | Unchanged | RLS unchanged. `idx_time_requests_device` unchanged. `get-devices-for-parent` edge function unchanged. |
| `network/SupabaseClientProvider.kt` | Unchanged | `getDevicesForParent()` is a repo method, not a network method. |

## Impact

- **User-facing**: V2 filter now works correctly on cold start. Parent sees actual pending requests when selecting a child, not the misleading "Sin solicitudes" empty state. The behavior change is from "always empty on cold start" to "shows the real rows, or shows an error banner on transient failure".
- **Migration**: none. No DB migration, no schema change, no RLS change, no edge function change.
- **Concurrency**: 1 `Mutex` per `ParentRepository` instance. Singleton-scoped via Hilt — single Mutex for the whole app. Acquired only on cold-start hydration; warm-cache path is uncontended. Mutex `lock`/`unlock` is ~50ns when uncontended — zero impact on the hot path.
- **HTTP traffic**: 1 extra `POST /functions/v1/get-devices-for-parent` per cold start (one-time per app session until the first successful V2 call). 1 extra call per worker tick (every 5 min). Both are idempotent GET-equivalents — Supabase handles them gracefully.
- **DI/Hilt/Compose/nav**: zero change. `getDevicesForParent()` is a regular suspend method on the existing `@Singleton` `ParentRepository`. The worker injection stays the same.
- **Failure UX**: when `getDevices()` fails transiently AND the V2 path needs the cache, the user sees a transient error banner (per Q1=f). This is a deliberate, observable behavior change — the silent "Sin solicitudes" was the bug.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Mutex deadlock on recursive hydration call | Very Low | The Mutex is acquired by `hydrateDevicesCache()` only; `getDevicesForParent()` (the public wrapper) also acquires it. No recursive calls — `getDevices()` does not call `getPendingRequests()`. The Mutex is a `kotlinx.coroutines.sync.Mutex`, not a Java `synchronized` block — coroutine-friendly. |
| `getDevicesForParent()` in the worker races with an in-flight VM `loadDevices()` | Low | The VM's `loadDevices()` at `ParentViewModel.kt:147-183` calls `getDevices()` (the private version, not the new `getDevicesForParent()`), which does NOT acquire the Mutex. The two paths can issue parallel `getDevices()` calls. Wasteful but correct. Future follow-up: refactor `getDevices()` to internally call `getDevicesForParent()`. Out of scope for this change. |
| Hydration failure surfaces as a user-visible error banner when the parent has 0 pending requests for the selected child | Low | The hydration error ONLY appears when `getDevices()` itself fails. If `getDevices()` succeeds, the V2 path proceeds to issue the time-requests GET and returns the real (possibly empty) list. The "Sin solicitudes" empty state remains for the "child has no pending requests" case. |
| `SolicitudesPollingWorker` pre-warm adds latency to the worker tick | Very Low | `getDevicesForParent()` is a single HTTP round-trip (~50-200ms). Worker ticks are 5-min cadence — latency is invisible to the user. Failure is swallowed (log + continue). |
| Removing `primeDevicesCache` breaks a test that wasn't migrated | Low | The test file is `ParentRepositoryV2FilterTest.kt` — only consumer of `primeDevicesCache`. The RED test at `ParentRepositoryV2FilterTest.kt:265-426` already uses a URL-routed `MockEngine` (no `primeDevicesCache` call). The 2 existing GREEN cases at lines 126, 182 DO call `primeDevicesCache` — they need to be migrated to the MockEngine pattern. Apply phase will handle this. |
| `getDevicesForParent()` in worker causes a second device-list fetch (one from VM init, one from worker tick) | Low | This is the desired behavior (cache stays fresh). The double-call is the design — pre-warm is the whole point. |
| V2 lazy-hydration adds a `getDevices()` round-trip on the V2 cold-start path, increasing the first-call latency | Low | First V2 call latency goes from ~200ms (time-requests GET only, with empty device filter) to ~400ms (POST get-devices + GET time-requests). The empty-filter case was BROKEN — the latency increase is the cost of returning the correct data. Second V2 call is ~200ms (warm cache). |

## Rollback

Single PR. `git revert` of the V2-overload change + the `primeDevicesCache` removal + the worker pre-warm call restores the prior behavior (V2 returns `success(emptyList())` on cold start — i.e., the original bug). No schema migration, no feature flag, no data loss. The Mutex declaration can stay — it has no effect if no caller acquires it.

## Out of scope

- Per-child `_devicesCache` (V3, separate change — Q2=g from the V2 cycle still holds for the global cache; lazy-hydration makes the "stale cache on child switch" concern moot).
- Realtime Solicitudes via Supabase Realtime (V3, separate change — not on the roadmap).
- Refactoring `ParentViewModel.loadDevices()` to call `getDevicesForParent()` (deferred — see Risks table; would close the VM-init/worker-tick double-call window, but is not a bug fix).
- Spec delta for `time-request-approval/spec.md` (deferred per the V2 proposal's precedent — caching/lifecycle is not a spec-level concern).
- Edge function refactor for `get-devices-for-parent` (none needed — the function already returns the right shape per `feat-multi-child-picker`).

## Success criteria

- [ ] RED baseline on `master @ 17866de`: `ParentRepositoryV2FilterTest:265-426` fails on assertions 2 (empty result), 3 (no hydration POST), 4 (no time-requests GET issued), 5 (second call also returns empty), 7 (second GET has no `device_id` clause). Assertion 1 (`isSuccess`) and 6 (no new hydration POST — vacuously true on master) pass.
- [ ] GREEN after the V2-overload + `getDevicesForParent()` + worker pre-warm change: all 7 assertions in the RED test pass.
- [ ] Existing 2 GREEN cases at `ParentRepositoryV2FilterTest.kt:126, 182` stay GREEN (after migration from `primeDevicesCache` to the MockEngine pattern).
- [ ] New tests pass: hydration failure → `Result.failure(Transient)`; concurrent cold-start dedup → 1 HTTP call; worker pre-warm → `getDevicesForParent()` precedes `getPendingRequests()`.
- [ ] `ParentRepositoryTest`, `ParentViewModelTest`, `SolicitudesPollingWorkerTest` (existing cases) stay GREEN.
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on `ParentRepository.kt` and `SolicitudesPollingWorker.kt`.
- [ ] `time-request-approval/spec.md` UNCHANGED (same paper-thin deferral as the V2 cycle).
- [ ] `primeDevicesCache` removed from `ParentRepository.kt`; `@VisibleForTesting` import removed; no other production code references the symbol.

## Open questions

1. **VM-init double-call window** — `ParentViewModel.loadDevices()` at `ParentViewModel.kt:147-183` calls the private `getDevices()` (not the new `getDevicesForParent()`), so the VM-init fetch does NOT acquire the Mutex. If the VM-init fetch and a concurrent V2 cold-start call both issue `getDevices()`, the Mutex does not dedupe them. Recommendation: refactor `getDevices()` to delegate to `getDevicesForParent()` (acquires Mutex). Defer to a future change unless the user wants it in this PR.
2. **Worker pre-warm on no-network tick** — the worker's `doWork()` already has a D4 connectivity gate at `SolicitudesPollingWorker.kt:64-67` that returns `Result.success()` without doing work. The pre-warm call would naturally be inside the `try` block AFTER the gate, so it inherits the gate. No new failure mode.
3. **Spec delta revisited** — `time-request-approval/spec.md` is silent on cache lifecycle. The lazy-hydration + worker pre-warm is a caching detail, not a spec-level behavioral change. Recommendation: defer (same as the V2 cycle). Confirm with user.
4. **Mutex vs. `CompletableDeferred` for dedup** — `Mutex` serializes concurrent callers (each one waits for the hydration to finish, then reads the now-warm cache). `CompletableDeferred<List<ChildDevice>>` would let callers `await()` on the same in-flight result without re-entering the hydration block. `Mutex` is simpler (~5 LoC) and matches the existing `kotlinx.coroutines.sync` patterns in the codebase. Recommendation: `Mutex`. Confirm with user only if there's a reason to prefer `CompletableDeferred`.

## References

- **Diagnosis**: engram **#266** `sdd/fix-v2-filter-production-lazy-hydration/explore` — Option B (inline gate in `getPendingRequests`) wins over A (init {}), C (VM loadPendingRequests), D (VM loadDevices). RED test designed at `ParentRepositoryV2FilterTest.kt:265-426`.
- **Decisions**: engram **#267** `sdd/fix-v2-filter-production-lazy-hydration/decisions` — Q1=(f) propagate failure, Q2=(m) Mutex dedup, Q3=(y) worker pre-warm, Q4=(r) remove test seam.
- **V2 archive's known-limitation note**: engram **#262** `sdd/fix-v2-server-side-solicitudes-filter/archive` — explicitly flagged the lazy-hydration follow-up as the next change.
- **V2 verify's note**: engram **#264** `sdd/fix-v2-server-side-solicitudes-filter/verify` — verify sub-agent flagged the `primeDevicesCache` test seam as a non-blocking follow-up; this change closes it.
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt:265-426` (7 assertions on cold-start + warm-cache contracts).
- **Existing GREEN cases (must stay GREEN)**: `ParentRepositoryV2FilterTest.kt:126, 182` (selectedChildId sends `device_id=in.`; null omits the clause).
- **Production surface**:
  - `ParentRepository.kt:354-391` — V2 overload (primary target).
  - `ParentRepository.kt:365-373` — `deviceIdsForChild` call site + Q4=e empty-list guard (unchanged; the new gate runs BEFORE this).
  - `ParentRepository.kt:406-409` — `deviceIdsForChild` helper (unchanged).
  - `ParentRepository.kt:272-306` — `getDevices()` (becomes wrapped by new public `getDevicesForParent()`).
  - `ParentRepository.kt:109` — `_devicesCache` `MutableStateFlow` (add Mutex next to this).
  - `ParentRepository.kt:423-427` — `primeDevicesCache` test seam (REMOVED).
- **Worker caller (pre-warm target)**: `SolicitudesPollingWorker.kt:60-95` — add `getDevicesForParent()` before the no-arg `getPendingRequests()` at line 70.
- **VM caller (unchanged)**: `ParentViewModel.kt:147-183` — `loadDevices()` keeps calling the private `getDevices()`; the VM is not involved in this change.
- **Format precedent**: `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md` (same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract, same paper-thin spec deferral).
