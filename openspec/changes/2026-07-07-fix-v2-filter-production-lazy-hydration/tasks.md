# Tasks: fix-v2-filter-production-lazy-hydration

> Mini-SDD bug-fix-style enhancement that closes the V2 production lazy-hydration follow-up explicitly flagged in `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md` known-limitations. No `specs/` (paper-thin deferral per proposal §Capabilities — same precedent as V2 and the auth-fix cycle). No `design.md` (proposal IS the design-of-record, same precedent as `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md` and `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen`). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = 17866de` baseline BEFORE any production code changes. Single PR, ~210 LoC (50 production + 160 test), well under the 400-line review budget. The 3rd RED method at `ParentRepositoryV2FilterTest.kt:265-426` is the acceptance contract; the 2 existing GREEN cases at `ParentRepositoryV2FilterTest.kt:126, 182` MUST stay GREEN throughout (migrated from the removed `primeDevicesCache` test seam to the route-by-path MockEngine pattern already used by the 3rd RED method).

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~50 LoC production + ~160 LoC test (per proposal §What changes) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-always |
| Chain strategy | n/a (single PR) |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | `ParentRepository` inline lazy-hydration gate + Mutex + `getDevicesForParent()` public wrapper + worker pre-warm + `primeDevicesCache` removal + RED test GREEN + 2 existing GREEN cases migrated to MockEngine pattern | PR 1 | base = `master @ 17866de`; 2 modified prod files (`ParentRepository.kt` + `SolicitudesPollingWorker.kt`) + 1 modified test file (`ParentRepositoryV2FilterTest.kt` flips the 3rd method RED→GREEN, migrates the 2 existing GREEN cases off `primeDevicesCache`, +2 new cases for hydration-failure and concurrent dedup) + 1 modified test file (`SolicitudesPollingWorkerTest.kt`, +1 new case for pre-warm) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 3rd RED method below MUST fail today at `master = 17866de`. It already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt:265-426` (added during explore, engram #266). The 2 existing GREEN cases at `:126, 182` MUST still pass on master — they use the `primeDevicesCache` test seam (today's `_devicesCache` seeder) at `ParentRepository.kt:423-427`, which V2 keeps wired through `@VisibleForTesting` on master. RED is the gate that unlocks Phase 3.

- [x] **1.1 — RED (existing 3rd method) `getPendingRequests with selectedChildId lazily hydrates device cache when empty`** at `ParentRepositoryV2FilterTest.kt:265-426`. Builds a `coldRepository` (no `primeDevicesCache` call — simulating cold start) and asserts (1) `isSuccess`, (2) result is NOT empty, (3) a POST to `/functions/v1/get-devices-for-parent` was issued, (4) the time-requests GET carries `device_id=in.(dev-001)`, (5) the warm-cache second call succeeds, (6) no NEW hydration POST on the second call, (7) the second GET still carries the `device_id=in.(dev-001)` clause. **Expected TODAY on master**: assertions 2, 3, 4, 5, 7 fail (cache empty → `deviceIdsForChild` resolves to `[]` → V2 returns `success(emptyList())` at `ParentRepository.kt:366-371` → no hydration POST is issued → no time-requests GET fires → result is empty). Assertions 1 and 6 pass vacuously (`isSuccess` is true for `success(emptyList())`; "no new hydration POST" is true because none was issued at all).

- [x] **1.2 — GREEN-pin (existing) `getPendingRequests with selectedChildId sends device_id in filter`** at `ParentRepositoryV2FilterTest.kt:126` — MUST still pass on master. Pins the V2 contract; Phase 3 must not regress the URL-shape assertions (URL contains `device_id=in.`, contains `dev-001`, contains `status=eq.PENDING`).

- [x] **1.3 — GREEN-pin (existing) `getPendingRequests with null selectedChildId omits device_id filter`** at `ParentRepositoryV2FilterTest.kt:182` — MUST still pass on master. Pins the Todos-null guardrail; Phase 3 must not add a `device_id=` clause on the null path.

- [x] **1.4 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryV2FilterTest" --rerun-tasks`. T1.1 MUST FAIL on assertions 2/3/4/5/7; T1.2 and T1.3 MUST still PASS. Record the failure mode for the report. Do NOT touch `ParentRepository.kt` or `SolicitudesPollingWorker.kt` until the baseline failures are confirmed. **No new commits** — the RED test file is already on master (added during explore, engram #266). Phase 3's commit is the only one in this chain.

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm the surface area at `ParentRepository.kt`.** The proposal locks the V2-overload hydration gate at `:354-391` and the public `getDevicesForParent()` wrapper at `:272-306`. Today's `getDevices()` at `:272-306` already does the right HTTP round-trip (POST `/functions/v1/get-devices-for-parent` with auth headers, decode `List<DeviceDto>`). The new public wrapper is a thin lock+delegate+seed wrapper around it. Apply phase needs to choose one of:
  - **(a) Add `private val devicesCacheMutex = Mutex()` at `ParentRepository.kt:109`** next to the existing `_devicesCache` `MutableStateFlow`, and have BOTH `hydrateDevicesCache()` (called inline from the V2 overload) and `getDevicesForParent()` (called from the worker) acquire it. The Mutex dedupes concurrent cold-start calls to a single `getDevices()` HTTP round-trip — the captured-request assertion in the new concurrent-dedup test case verifies this.
  - **(b) Inline the lock in `getDevicesForParent()` only, call it from both paths.** Reject — adds a public surface dependency between the V2-overload gate and the worker path. Decision required at apply time, default (a) per proposal §What changes #1 and #4.

- [x] **2.2 — Consumer sweep on `primeDevicesCache`.**
  ```bash
  grep -rn "primeDevicesCache" \
    app/src/main/java/com/tudominio/parentalcontrol \
    app/src/test/java/com/tudominio/parentalcontrol
  ```
  Expected hits per engram #266:
  - `data/repository/ParentRepository.kt:423-427` (definition; REMOVED per Q4=r).
  - `test/.../ParentRepositoryV2FilterTest.kt:115` (call site in `@Before` setUp — the 2 existing GREEN cases at `:126, 182` use the seeded cache via this `@Before`).
  - **No other consumers.** The RED 3rd method at `:265-426` does NOT call `primeDevicesCache` (line 297-305 explicitly opts out to simulate cold start).

- [x] **2.3 — Migrate the 2 existing GREEN cases off `primeDevicesCache`.** The `@Before` block at `ParentRepositoryV2FilterTest.kt:77-116` calls `repository.primeDevicesCache(LUCAS_DEVICE_FIXTURE)` to seed `_devicesCache` before the 2 GREEN cases run. After Q4=r (removal), the 2 GREEN cases MUST switch to the route-by-path MockEngine pattern already shown at `ParentRepositoryV2FilterTest.kt:274-290`:
  - **Option (a) — promote the route-by-path MockEngine to `@Before`**: every test gets a `repository` whose MockEngine routes POST `/functions/v1/get-devices-for-parent` to `GET_DEVICES_RESPONSE_BODY` and GET `/rest/v1/time_requests` to `PENDING_REQUESTS_RESPONSE_BODY`. The 2 GREEN cases then assert the captured-request URL the same way they do today. This is the lowest-friction migration — minimal per-test changes, keeps the existing URL assertions verbatim.
  - **Option (b) — per-test MockEngine (the 3rd RED method's pattern)**: each test builds its own MockEngine. More boilerplate but mirrors the RED test's isolation.
  - **Decision required at apply time**, default (a). If T1.2's URL assertion (`url.contains("status=eq.PENDING")`) accidentally picks up the hydration POST URL after (a) is applied, demote the captured-URL assertion to `captured.last()` (matching the 3rd RED method's pattern at `:353-359`).

- [x] **2.4 — Worker pre-warm placement.** `SolicitudesPollingWorker.kt:60-95` already has the D4 connectivity gate at `:64-67` and the no-arg `getPendingRequests()` call at `:70`. The pre-warm MUST be inserted inside the `try` block at `:69`, AFTER the connectivity gate, BEFORE the `getPendingRequests()` call (so a failed pre-warm does NOT short-circuit the existing polling path). Failure mode: swallow + log warning (proposal §What changes #2 — "the polling path is best-effort, the V2 lazy-hydration still covers any cold-start miss"). The pre-warm call uses the public `getDevicesForParent()` wrapper, which internally acquires the same Mutex the V2 overload acquires — single in-flight `getDevices()` HTTP call across the app at any moment.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Add inline lazy-hydration gate to `getPendingRequests(selectedChildId)` at `ParentRepository.kt:354-391`.**
  - New precondition block (BEFORE the existing `try`):
    ```kotlin
    if (selectedChildId != null && _devicesCache.value.isEmpty()) {
        when (val hydration = hydrateDevicesCache()) {
            is Result.Failure -> return@withContext hydration  // Q1=f propagate
            is Result.Success -> Unit  // _devicesCache now populated; proceed
        }
    }
    ```
  - `hydrateDevicesCache()` is a private helper that wraps `devicesCacheMutex.withLock { _devicesCache.value = getDevices().getOrElse { return@withLock Result.failure(it) } ; Result.success(Unit) }` — but the public surface is `Result<Unit>` so the V2 overload can branch on `is Success` / `is Failure`.
  - The existing `try { … deviceIdsForChild(selectedChildId) … }` at `:354-391` stays verbatim AFTER the new gate runs. The V2 cold-start path now flows: gate fires → `_devicesCache` populated → `deviceIdsForChild` resolves to real ids → URL carries `device_id=in.(dev-001)` → time-requests GET returns the real rows. The Q4=e guard at `:366-371` remains as a safety net (empty `deviceIds` even after hydration → `success(emptyList())`).
  - Preserve the existing `try { … } catch (e: Exception) { Result.failure(DeviceListError.Transient(...)) }` envelope at `:388-390` intact.

- [x] **3.2 — Add `getDevicesForParent()` public wrapper at `ParentRepository.kt:272-306` (or right after, before the V2 overload).**
  - New signature: `suspend fun getDevicesForParent(): Result<List<ChildDevice>>`.
  - Body: `return devicesCacheMutex.withLock { val result = getDevices(); if (result is Result.Success) _devicesCache.value = result.value; result }`.
  - Reuses the existing `getDevices()` private method (no HTTP-code duplication). The worker calls this wrapper; the V2 overload calls the private `hydrateDevicesCache()` (which acquires the same Mutex).

- [x] **3.3 — Declare the Mutex at `ParentRepository.kt:109` (next to `_devicesCache`).**
  - New line: `private val devicesCacheMutex = Mutex()`.
  - Import: `kotlinx.coroutines.sync.Mutex`.
  - No other production code references this field outside T3.1 and T3.2.

- [x] **3.4 — Remove `primeDevicesCache(devices)` from `ParentRepository.kt:423-427` (per Q4=r).**
  - Delete the `@Suppress("unused") @VisibleForTesting fun primeDevicesCache(...)` block.
  - Delete the now-unused `androidx.annotation.VisibleForTesting` import (grep for any other consumer before deleting; engram #266 confirms only `ParentRepositoryV2FilterTest.kt:115` is the caller, migrated in T3.5).
  - Delete the doc-block at `:411-422` referencing the test seam.

- [x] **3.5 — Migrate the 2 existing GREEN cases at `ParentRepositoryV2FilterTest.kt:77-116, 126, 182` off `primeDevicesCache` to the route-by-path MockEngine (per Phase 2 decision (a)).**
  - Update `@Before` at `:77-116` to install a MockEngine that routes by encoded path (POST `/functions/v1/get-devices-for-parent` → `GET_DEVICES_RESPONSE_BODY`; GET `/rest/v1/time_requests` → `PENDING_REQUESTS_RESPONSE_BODY`), mirroring the pattern at `:274-290`.
  - Delete the `repository.primeDevicesCache(LUCAS_DEVICE_FIXTURE)` call at `:115`. The hydration POST now happens naturally on the first V2 call.
  - The existing URL-shape assertions in T1.2 (`url.contains("status=eq.PENDING")` etc.) continue to hold — `captured.last()` is the time-requests GET in both the warm and cold paths. Verify with T3.7 below.

- [x] **3.6 — Add 2 new RED cases to `ParentRepositoryV2FilterTest.kt`:**
  - **Hydration failure**: route the MockEngine to respond with `HttpStatusCode.InternalServerError` for `/functions/v1/get-devices-for-parent`. Call `repository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)`. Assert `result.isFailure` AND `result.exceptionOrNull() is DeviceListError.Transient`. Per Q1=f.
  - **Concurrent cold-start dedup**: launch 5 concurrent `getPendingRequests(selectedChildId = CHILD_LUCAS_ID)` coroutines with `async` + `awaitAll`. After `runCurrent()`, assert exactly ONE POST to `/functions/v1/get-devices-for-parent` was issued across all 5 calls. Per Q2=m.

- [x] **3.7 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryV2FilterTest" --rerun-tasks`. T1.1 (the 3rd method, now GREEN) and T1.2 + T1.3 (the migrated 2 GREEN cases) ALL PASS, plus the 2 new cases from T3.6 (hydration failure → `Transient`; concurrent dedup → 1 HTTP call).

- [x] **3.8 — Worker pre-warm GREEN confirmation.**
  Add a new test case to `SolicitudesPollingWorkerTest`: route a MockEngine to answer both the hydration POST and the time-requests GET (mirroring the 3rd RED method's pattern). Invoke `worker.doWork()`. Assert (a) `Result.success()` is returned, (b) the captured requests include BOTH a POST to `/functions/v1/get-devices-for-parent` AND a GET to `/rest/v1/time_requests`, (c) the POST precedes the GET. Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.workers.SolicitudesPollingWorkerTest" --rerun-tasks`. New case PASSES; pre-existing `SolicitudesPollingWorkerTest` cases stay GREEN.

- [x] **3.9 — Add the worker pre-warm call at `SolicitudesPollingWorker.kt:69-71`.**
  - Insert BEFORE `val result = parentRepository.getPendingRequests()`:
    ```kotlin
    runCatching { parentRepository.getDevicesForParent() }
        .onFailure { Log.w(TAG, "Pre-warm falló: ${it.message}") }
    ```
  - The `runCatching` deliberately swallows the failure (matches proposal §What changes #2 — "the polling path is best-effort"). The existing `try` block at `:69-90` stays intact; the pre-warm is its own statement above.
  - Verify with T3.8.

- [x] **3.10 — Run the full repo + VM + worker test suites.**
  `./gradlew :app:testDebugUnitTest --rerun-tasks`. Pre-existing 4 failures on `NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest` (per precedent at `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/tasks.md:95` and `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:151`) are unchanged and out of scope. `ParentRepositoryTest`, `ParentRepositoryColdStartTest`, `ParentRepositoryMergeTest`, `ParentViewModelTest`, `SolicitudesPollingWorkerTest` stay GREEN. `ParentRepositoryV2FilterTest` 5 cases (3 RED-flip + 2 new) all GREEN.

- [x] **3.11 — Commit**:
  ```
  fix(repo): V2 lazy-hydration + worker pre-warm + remove test seam

  Body cites engram #265 (change start), #266 (explore — RED test
  origin, Option B inline gate), #267 (Q1=f/Q2=m/Q3=y/Q4=r decisions),
  #268 (proposal), and the acceptance contract at
  ParentRepositoryV2FilterTest.kt:265-426 (3rd method flips RED→GREEN)
  + 126, 182 (existing GREEN cases migrated to MockEngine).
  ```
  Touched files: `ParentRepository.kt` (inline gate + Mutex + `getDevicesForParent()` wrapper + `primeDevicesCache` removed), `SolicitudesPollingWorker.kt` (pre-warm call). Test files: `ParentRepositoryV2FilterTest.kt` (3rd method GREEN + 2 existing cases migrated + 2 new cases), `SolicitudesPollingWorkerTest.kt` (+1 new case). No new files. No spec delta. No migrations.

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `ParentRepository.kt` + `SolicitudesPollingWorker.kt`.
- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; pre-existing 4 failures on `NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest` unchanged.
- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `ParentRepository.kt`, `SolicitudesPollingWorker.kt`, `ParentRepositoryV2FilterTest.kt`, `SolicitudesPollingWorkerTest.kt`. Pre-existing violations elsewhere are out of scope.
- [x] **4.4 — `./gradlew :app:detekt`** — no new violations on the 2 touched production files.
- [x] **4.5 — Final repo-wide grep on the new + removed symbol surface.**
  ```bash
  grep -rn "primeDevicesCache\|devicesCacheMutex\|getDevicesForParent\|hydrateDevicesCache" \
    app/src/main/java/com/tudominio/parentalcontrol \
    app/src/test/java/com/tudominio/parentalcontrol
  ```
  Expected: 0 hits on `primeDevicesCache` (removed); 1 declaration + 2 acquire sites + 0 production callers outside `ParentRepository.kt` for `devicesCacheMutex`; 1 declaration + 1 worker call site + 2 test references for `getDevicesForParent`; 1 declaration + 1 V2-overload caller for `hydrateDevicesCache`.
- [x] **4.6 — Confirm `time-request-approval/spec.md` is UNCHANGED.** Same paper-thin deferral as the V2 cycle (proposal §Capabilities). Git diff on the spec must show zero lines.

---

## Out of scope (frozen)

- Per-child `_devicesCache` (V3, separate change — proposal §Out of scope #1).
- Realtime Solicitudes via Supabase Realtime (V3, separate change — not on the roadmap).
- Refactoring `ParentViewModel.loadDevices()` at `ParentViewModel.kt:147-183` to call `getDevicesForParent()` (deferred — would close the VM-init/worker-tick double-call window but is not a bug fix; proposal §Out of scope #3 and §Risks row 2).
- Spec delta for `time-request-approval/spec.md` (deferred per proposal §Capabilities; same precedent as the V2 proposal and the auth-fix proposal).
- Edge function refactor for `get-devices-for-parent` (none needed — function already returns the right shape per `feat-multi-child-picker`).
- Mutex vs. `CompletableDeferred` for dedup — `Mutex` chosen per Q2=m and proposal §Open questions #4.
- `SolicitudesPollingWorker` learning the currently-selected child (already covered by the V2 cycle's Q3=t; the worker continues with no-arg `getPendingRequests()` after the pre-warm).
- VM-init double-call window (VM's `loadDevices()` calls private `getDevices()`, not the new public wrapper; the two paths can issue parallel HTTP calls — wasteful but correct, deferred per proposal §Open questions #1).

## Notes

- This change is a 2-file production diff (`ParentRepository.kt` MODIFIED + `SolicitudesPollingWorker.kt` MODIFIED, ~50 LoC net) + 2 test files (`ParentRepositoryV2FilterTest.kt` MODIFIED + `SolicitudesPollingWorkerTest.kt` MODIFIED, ~160 LoC net including 2 new cases + 1 new worker case). Well under the 400-line review budget.
- **`strict_tdd: true` from `openspec/config.yaml:3`** is honoured with the 1-commit pattern (Phase 3 commit includes both production and the RED → GREEN flip + migration). The 3rd RED method was committed to master during the explore phase (engram #266); Phase 1 here only verifies the baseline failure mode, not a new test commit.
- **Device-cache Mutex decision (T2.1)** — option (a) (single Mutex at `ParentRepository.kt:109`, acquired by both `hydrateDevicesCache()` and `getDevicesForParent()`) is the proposal-of-record (Q2=m). The concurrent-dedup test case (T3.6) proves the Mutex dedupes via the captured-request count assertion.
- **Existing GREEN cases migration (T2.3, T3.5)** — the 2 GREEN cases at `ParentRepositoryV2FilterTest.kt:126, 182` were authored against the now-removed `primeDevicesCache` test seam. They MUST be migrated to the route-by-path MockEngine pattern (option (a) — promote the MockEngine to `@Before`). This is the only test-surface change; no new test file, no test file split.
- **Worker pre-warm placement (T2.4, T3.9)** — the `runCatching { ... }.onFailure { Log.w(...) }` pattern deliberately swallows failures (proposal §What changes #2). The pre-warm call is a statement INSIDE the `try` block, ABOVE the existing `val result = parentRepository.getPendingRequests()` line — a failed pre-warm logs a warning but does NOT prevent the existing polling path from running. The V2 lazy-hydration in `getPendingRequests` is the safety net for any cold-start miss.
- **Reference resolution for the next session**: engram #265 (change start), #266 (explore — RED test origin, Option B), #267 (4/4 decisions), #268 (proposal). Precedent: `archive/2026-07-07-fix-v2-server-side-solicitudes-filter/tasks.md` is the same single-PR ~210-LoC shape with the same Phase 1 RED-pinning pattern.
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke.
- **If on closer inspection the Mutex surface differs** (e.g., a pre-existing `Mutex` is already in the repo at `ParentRepository.kt`), T2.1 collapses to "reuse the existing lock" — that's a 5-minute win at apply time. T3.3 becomes a `private val devicesCacheMutex = existingMutex` reassignment (or a no-op if names match).