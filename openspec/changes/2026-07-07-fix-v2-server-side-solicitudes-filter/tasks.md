# Tasks: fix-v2-server-side-solicitudes-filter

> Mini-SDD bug-fix-style enhancement. No `specs/` (user deferred the spec delta in the proposal — RED test at `ParentRepositoryV2FilterTest.kt` IS the contract). No `design.md` (proposal IS the design-of-record, same precedent as `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen`). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = 9243b39` baseline **before any production code changes**. Single PR, ~230 LoC (80 production + 150 test), well under the 400-line review budget. Two RED cases from explore (`getPendingRequests with selectedChildId sends device_id in filter` and the explicit-failure form of the same) gate Phase 3; the Todos-null guardrail at `ParentRepositoryV2FilterTest.kt:182` stays GREEN throughout as a regression guard.

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~80 LoC production + ~150 LoC test (per proposal §What changes) |
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
| 1 | `ParentRepository` V2 overload + URL builder + no-arg delegate + VM thread-through | PR 1 | base = `master @ 9243b39`; 1 modified prod file (`ParentRepository.kt`) + 1 modified prod file (`ParentViewModel.kt`) + the pre-existing `ParentRepositoryV2FilterTest.kt` flipped RED→GREEN |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 2 RED cases below MUST fail today at `master = 9243b39`. They already exist at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt` (sibling RED contract, NOT a T1.4-style new test). RED is the gate that unlocks Phase 3.

- [x] **1.1 — RED (existing) `getPendingRequests with selectedChildId sends device_id in filter`** at `ParentRepositoryV2FilterTest.kt:126`. Calls `repository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)` and asserts the captured URL contains `device_id=in.` and `dev-001` and `status=eq.PENDING`. **Expected TODAY**: FAILS at the `getPendingRequests(selectedChildId = …)` compile (the no-arg overload at `ParentRepository.kt:291` does not accept a parameter — Kotlin: "no parameter named selectedChildId"). RED on `master = 9243b39` baseline.

- [x] **1.2 — RED-GUARD (existing) `getPendingRequests with null selectedChildId omits device_id filter`** at `ParentRepositoryV2FilterTest.kt:182`. Calls `repository.getPendingRequests(selectedChildId = null)` — also fails to compile on master (same reason as T1.1). Pinning it here means Phase 3 must add the `String?` parameter signature; once it compiles, the URL assertion (no `device_id=` clause) and the static-clause assertions (`status=eq.PENDING`, `order=created_at.desc`) must hold at the same time. RED on `master = 9243b39` baseline by virtue of compile failure.

- [x] **1.3 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryV2FilterTest" --rerun-tasks`. T1.1 AND T1.2 MUST FAIL (compile error today, per the docstring at `ParentRepositoryV2FilterTest.kt:51-55`). Record the failure mode. Do NOT touch `ParentRepository.kt` until the baseline failures are confirmed and recorded. **No new commits** — the RED test file is already on master (added during explore, engram #255). Phase 3's commit is the only one in this chain.

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm the device-cache mechanism Q1=r relies on.** The proposal locks child→device resolution inside the repo (decision Q1=r from engram #256), and the RED test at `ParentRepositoryV2FilterTest.kt:142-156` asserts the URL ends up with `dev-001` (Lucas's device) — the repo MUST know which `deviceId`s belong to child `child-lucas-id`. Today's `ParentRepository` does NOT hold a cached `_devices: StateFlow<List<ChildDevice>>` snapshot (grep confirms `getDevices()` at `:240` is a one-shot fetch, no mirror). The apply phase needs to choose one of:
  - **(a) Add a `_devicesCache: MutableStateFlow<List<ChildDevice>>` mirror in `ParentRepository`** (analogous to the `_pendingRequestsFlow` pattern at `ParentRepository.kt:71`), populated by `getDevices()` once on init + again on every explicit refetch. The V2 overload reads the snapshot synchronously from `value` — no extra round-trip on each poll.
  - **(b) Move the resolution to the VM** (pass `deviceIds: List<String>?` into the overload) — but this contradicts Q1=r and changes the RED test contract at `:142-156`. Reject this branch.
  - **Decision required at apply time**, default (a). If the apply phase finds (a) ripples into other test consumers (e.g., `ParentRepositoryTest` constructs the repo with no auth / no devices), prefer (a) with a `runCatching { getDevices() }.getOrDefault(emptyList())` initial cache rather than a blocking call.

- [x] **2.2 — Consumer sweep on `getPendingRequests`.**
  ```bash
  grep -rn "getPendingRequests" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected hits per engram #255:
  - `data/repository/ParentRepository.kt:291` (no-arg definition, the surface to overload).
  - `viewmodel/ParentViewModel.kt:210` (caller — becomes the V2 path; thread `_selectedChildId.value` from `:61`).
  - `workers/SolicitudesPollingWorker.kt:70` (caller — KEEPS the no-arg form per Q3=t; engram #256).

- [x] **2.3 — Re-verify `_selectedChildId` shape.** `ParentViewModel.kt:61` declares `private val _selectedChildId = MutableStateFlow<String?>(null)` (matches the RED test's `String?` parameter at `ParentRepositoryV2FilterTest.kt:127`, confirmed in engram #257). No `SelectedChild` enum/sealed type — orchestrator brief's `SelectedChild` is a hypothetical, the RED test is the source of truth.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Add `ParentRepository.getPendingRequests(selectedChildId: String?)` overload at `ParentRepository.kt:291-315`.**
  - New signature: `suspend fun getPendingRequests(selectedChildId: String?): Result<List<TimeRequest>>`.
  - URL builder (replaces the hardcoded query at `:297-298`):
    ```kotlin
    val base = "${SupabaseClientProvider.SUPABASE_URL}/rest/v1/time_requests?" +
        "select=*,devices(device_name)&status=eq.PENDING&order=created_at.desc"
    val filtered = if (selectedChildId != null) {
        val deviceIds = deviceIdsForChild(selectedChildId)   // from T2.1's cache
        if (deviceIds.isEmpty()) return@withContext Result.success(emptyList())  // Q4=e guard
        base + "&device_id=in.(${deviceIds.joinToString(",")})"
    } else base
    ```
  - `deviceIdsForChild(id)` is a private helper that reads the `_devicesCache.value` and filters by `device.child?.id == id`. UUIDs are URL-safe; no encoding needed.
  - Preserve the static clauses verbatim (`status=eq.PENDING`, `order=created_at.desc`, `select=*,devices(device_name)`) — additive only.
  - Keep the existing `try { … } catch (e: Exception) { Result.failure(…) }` envelope at `:312-314` intact.

- [x] **3.2 — Wrap the existing no-arg overload as a thin delegate (per Q3=t / proposal §What changes #2).**
  ```kotlin
  suspend fun getPendingRequests(): Result<List<TimeRequest>> =
      getPendingRequests(selectedChildId = null)
  ```
  - This single-line delegate sits at `ParentRepository.kt:291` (the position the no-arg version occupies today) and forwards to the new `:297`-ish implementation. `SolicitudesPollingWorker.kt:70` keeps calling the no-arg form with zero behaviour change.

- [x] **3.3 — Thread `_selectedChildId.value` through VM `loadPendingRequests()` at `ParentViewModel.kt:194-228`.** Change line 210 from `repository.getPendingRequests()` to `repository.getPendingRequests(selectedChildId = _selectedChildId.value)`. No other change in `loadPendingRequests()`. ~1 LoC.

- [x] **3.4 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryV2FilterTest" --rerun-tasks`. T1.1 and T1.2 (now T1.1 = URL contains `device_id=in.` + `dev-001` + `status=eq.PENDING`; T1.2 = URL omits `device_id=` but keeps `status=eq.PENDING` + `order=created_at.desc`) BOTH PASS.

- [x] **3.5 — Run the full repo + VM + worker test suites.**
  `./gradlew :app:testDebugUnitTest --rerun-tasks`. Pre-existing `NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest` failures (per precedent at `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:151` and `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md:115`) are unchanged and out of scope. `ParentRepositoryTest`, `ParentViewModelTest`, `SolicitudesPollingWorkerTest` stay GREEN (no-arg overload preserved by T3.2).

- [x] **3.6 — Commit**:
  ```
  fix(repo): server-side device_id filter on getPendingRequests (V2)

  Body cites engram #255 (explore — Postgrest in-list shape),
  engram #256 (Q1=r/Q2=g/Q3=t/Q4=e/Q5=r decisions), #257 (proposal),
  and the RED contract at ParentRepositoryV2FilterTest.kt:126,182.
  ```
  Touched files: `ParentRepository.kt` (add overload + delegate + 0-device guard), `ParentViewModel.kt` (1-line thread-through at :210). No new files. No spec delta. No migrations.

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `ParentRepository.kt` + `ParentViewModel.kt`.
- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; pre-existing 4 failures on `NetworkModuleTest` + 2× `BootReceiverTest` + `NavGraphTest` unchanged.
- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `ParentRepository.kt`, `ParentViewModel.kt`. Pre-existing violations elsewhere are out of scope.
- [x] **4.4 — `./gradlew :app:detekt`** — no new violations on the 2 touched production files.
- [x] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "deviceIdsForChild\|_devicesCache" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected: 1 helper definition inside `ParentRepository.kt` (if T2.1 picks option (a)) + 1 read site in the V2 overload + 0 hits in `DashboardScreen.kt` / `SolicitudesPollingWorker.kt` / `ParentViewModel.kt` (the filter is repo-internal).

---

## Out of scope (frozen)

- Per-child `PendingRequestsCache` (deferred — Q2=g).
- Edge function refactor for `getPendingRequests` — none exists (Postgrest direct), engram #255.
- V3 RLS-aware per-device filtering on the server (separate change).
- `SolicitudesPollingWorker` learning the currently-selected child — explicitly out per Q3=t.
- `selectedChildId` persistence across cold starts (already shipped in `feat-multi-child-picker`).
- Spec delta for `time-request-approval/spec.md` — proposal §Open questions #1 resolves to "defer" (same precedent as the auth-fix proposal).
- Composite `(device_id, status)` index — proposal §Open questions #3, defer to V3 unless load data shows it.

## Notes

- This change is a 2-file production diff (`ParentRepository.kt` MODIFIED + `ParentViewModel.kt` MODIFIED, ~15 LoC total net) + 0 new test files. The RED test at `ParentRepositoryV2FilterTest.kt` flips RED→GREEN without modification. Well under the 400-line review budget.
- **`strict_tdd: true` from `openspec/config.yaml:3`** is honoured with the 1-commit pattern (Phase 3 commit includes both production and the RED → GREEN flip). The RED test was committed to master during the explore phase (engram #255); Phase 1 here only verifies the baseline failure mode, not a new test commit.
- **Device-cache decision (T2.1) is the single open seam** at apply time. The RED test's URL assertion at `:142-156` (must contain `dev-001`) makes (a) effectively mandatory — the test mocks the HTTP response but expects the repository to translate the child id to device ids on its own. If the apply phase needs a `FakeTimeRequestsRepository`-style helper for testability, mirror the `PendingRequestsCache` precedent at `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:215-216`.
- **`SolicitudesPollingWorker` stays no-arg** per Q3=t. The worker's Todos-only polling semantics mean a parent who has a child selected still sees new requests for OTHER children merge in via the worker — preserves the V1 merge invariant from `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md:121-130`.
- **Reference resolution for the next session**: engram #254 (change start), #255 (explore — RED test origin), #256 (5/5 decisions), #257 (proposal), #258 (spec — paper-thin, no delta). Precedent: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` is the same single-PR ~50-LoC shape (now pre-extended to ~230 LoC for this V2 because the test surface is wider — 1 acceptance-contract file plus the 1-line VM change).
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke.
- **If on closer inspection the device-cache surface differs** (e.g., there's already a `_devicesCache` somewhere in the repo that `grep` missed), T2.1 collapses to "reuse the existing snapshot" — that's a 5-minute win at apply time.
