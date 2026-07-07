# Tasks: fix-parent-log-events-cleared-on-reopen

> Mini-SDD lite bug fix. No `specs/` (user deferred the spec delta; engram #247 — RED test is the contract). No `design.md` (user picked option `s` "skip design" — `proposal.md` is the design-of-record). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = f5c9c66` baseline **before any production code changes**. Each phase maps to one conventional commit. Single PR, ~50 LoC, well under the 400-line review budget.

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~50 LoC production + ~120 LoC test (per proposal §What changes) |
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
| 1 | DataStore cache helper + repo hydration + merge write-through + tests | PR 1 | base = `master`; one new file (`PendingRequestsCache.kt`) + one modified file (`ParentRepository.kt`) + 1 new test file (merge) + 1 new test file (round-trip) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 6 tests below must **fail today** at `master = f5c9c66`. The first 3 already exist; the last 3 are new RED tests the apply phase writes first. RED is the contract that gates Phase 3.

Run them with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryColdStartTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryMergeTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryCacheRoundTripTest" --rerun-tasks`.

- [x] **1.1 — RED (existing) `pendingRequestsFlow hydrates from disk on cold start`** at `ParentRepositoryColdStartTest.kt:111`. Warm session publishes `fixture` (tr-001 + tr-002 at `:56-74`); process death; fresh `ParentRepository` must hydrate `_pendingRequestsFlow.value == fixture`. **Expected TODAY**: FAILS at the `assertEquals` because `_pendingRequestsFlow` (`ParentRepository.kt:71`) re-initializes to `emptyList()` and there is no disk-read path. RED on `master = f5c9c66` baseline.

- [x] **1.2 — RED (existing) `pendingRequestsFlow on cold start is not empty after warm session`** at `ParentRepositoryColdStartTest.kt:144`. Stronger form of T1.1 — asserts the cold-start value is **not** `emptyList()`. **Expected TODAY**: FAILS for the same reason as T1.1. RED on `master = f5c9c66` baseline.

- [x] **1.3 — RED-CONTROL (existing) `pendingRequestsFlow on cold start without prior session stays empty`** at `ParentRepositoryColdStartTest.kt:166`. No warm session → fresh install → must hydrate to `emptyList()` (not throw, not fake rows). **Expected TODAY**: GREEN. Pins the no-blob path so Phase 3 does not regress it.

- [x] **1.4 — RED (NEW) `publishPendingRequests merges with cache preserving local optimistic updates`** at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryMergeTest.kt` (new). Seed cache with `tr-001` (PENDING). Warm repo optimistically calls `publishPendingRequests(listOf(tr-001_APPROVED_local))`. Then `getPendingRequests()` returns server fetch `listOf(tr-001)` (status PENDING, no `respondedAt`). After the server-publish call, cache must still hold `tr-001_APPROVED_local` (newer `status`/`respondedAt` wins). **Expected TODAY**: FAILS — `publishPendingRequests` (`ParentRepository.kt:81-83`) is a plain `_pendingRequestsFlow.value = list` with no cache merge.

- [x] **1.5 — RED (NEW) `publishPendingRequests local newer status wins over stale cache`** at `ParentRepositoryMergeTest.kt`. Seed cache with `tr-001` (APPROVED with `respondedAt = "2026-07-03T10:00:00Z"`). Warm repo receives server fetch `listOf(tr-001_PENDING)` (the canonical server-side filter at `ParentRepository.kt:159-160` may briefly surface an already-resolved row as PENDING between the upstream UPDATE and the upstream SELECT). After `publishPendingRequests(listOf(tr-001_PENDING))`, cache must still carry `tr-001_APPROVED` (the locally-known newer status wins). **Expected TODAY**: FAILS — same reason as T1.4.

- [x] **1.6 — RED (NEW) `PendingRequestsCache round-trip survives fresh instance`** at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryCacheRoundTripTest.kt` (new, Robolectric). Write `listOf(tr-001, tr-002)` via one `PendingRequestsCache` instance. Build a fresh `PendingRequestsCache(context)` instance pointing at the same `DataStore<Preferences>` file (`PendingRequestsPrefs.NAME = "parent_pending_requests_cache"` per `ParentRepositoryColdStartTest.kt:187`). Read must return the same list. **Expected TODAY**: FAILS — `PendingRequestsCache` does not exist yet, so the test class cannot even compile.

- [x] **1.7 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryColdStartTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryMergeTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryCacheRoundTripTest" --rerun-tasks`. T1.1, T1.2, T1.4, T1.5, T1.6 MUST FAIL (1.6 is a build error before all others; order doesn't matter). T1.3 MUST PASS. Record timing per test. Do NOT commit until the failures are confirmed on the unfixed baseline.
  **Commit:**
  ```
  test(repo): add RED coverage for cold-start hydration and merge write-through
  ```

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm root cause at `ParentRepository._pendingRequestsFlow`.** Re-verify `_pendingRequestsFlow` init at `ParentRepository.kt:71` is `MutableStateFlow<List<TimeRequest>>(emptyList())` with no cache wiring, and `publishPendingRequests` at `ParentRepository.kt:81-83` writes to the flow only (no SharedPreferences, no DataStore). Cross-check that no other write site touches `_pendingRequestsFlow` (it should be private + only mutated via `publishPendingRequests`).

- [x] **2.2 — Consumer sweep.**
  ```bash
  grep -rn "pendingRequestsFlow\|_pendingRequestsFlow" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected hits (already known from engram #244):
  - `data/repository/ParentRepository.kt:71-83` (definition + writer).
  - `viewmodel/ParentViewModel.kt:119-122` (mirror into `_pendingRequests`).
  - `ui/parent/screens/DashboardScreen.kt:95` (Solicitudes tab render).
  - `SolicitudesPollingWorker.kt` (writer on every 5-min tick).
  - `viewmodel/ParentViewModelTest.kt` + `ParentRepositoryColdStartTest.kt` (test consumers).

- [x] **2.3 — Confirm DataStore Preferences 1.2.0 is declared.** `gradle/libs.versions.toml` should expose `androidx.datastore:datastore-preferences:1.2.0` per engram #245 (d) decision. If absent, the first GREEN task is a `gradle/libs.versions.toml` + `app/build.gradle.kts` dep add (apply-phase decision).

- [x] **2.4 — Confirm Hilt module path.** Check `di/RepositoryModule.kt` (or equivalent). `ParentRepository` is `@Singleton @Inject`-constructed; Hilt needs to know how to construct the new `PendingRequestsCache @Singleton @Inject(@ApplicationContext context)` (mirrors `PairedDevicesStore` shape at `data/local/PairedDevicesStore.kt:17` — different storage tech but same Hilt pattern). No `@Provides` needed if the cache class has `@Inject constructor(@ApplicationContext context)`.

- [x] **2.5 — Confirm `TimeRequest` serializer shape.** Read `app/src/main/java/com/tudominio/parentalcontrol/domain/model/Models.kt:60` — `TimeRequest` is `@Serializable` (kotlinx.serialization), already used in `ParentRepository.kt:172-173` for the wire shape. Reuse the `json` instance at `ParentRepository.kt:50-53` (lenient + ignoreUnknownKeys) inside `PendingRequestsCache`. No custom serializer needed.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Create `app/src/main/java/com/tudominio/parentalcontrol/data/local/PendingRequestsCache.kt` (~30 LoC).**
  ```kotlin
  @Singleton
  class PendingRequestsCache @Inject constructor(
      @ApplicationContext private val context: Context
  ) {
      private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
          name = PendingRequestsPrefs.NAME
      )
      private val json = Json { ignoreUnknownKeys = true; isLenient = true }

      suspend fun read(): List<TimeRequest> = withContext(Dispatchers.IO) {
          val prefs = context.dataStore.data.first()
          val raw = prefs[stringPreferencesKey(PendingRequestsPrefs.KEY_REQUESTS)]
              ?: return@withContext emptyList()
          runCatching { json.decodeFromString<List<TimeRequest>>(raw) }
              .getOrDefault(emptyList())
      }

      fun observe(): Flow<List<TimeRequest>> = context.dataStore.data.map { prefs ->
          val raw = prefs[stringPreferencesKey(PendingRequestsPrefs.KEY_REQUESTS)]
              ?: return@map emptyList()
          runCatching { json.decodeFromString<List<TimeRequest>>(raw) }
              .getOrDefault(emptyList())
      }

      suspend fun write(list: List<TimeRequest>) = withContext(Dispatchers.IO) {
          context.dataStore.edit { prefs ->
              prefs[stringPreferencesKey(PendingRequestsPrefs.KEY_REQUESTS)] =
                  json.encodeToString(ListSerializer(TimeRequest.serializer()), list)
          }
      }
  }
  ```
  Promotes `PendingRequestsPrefs.NAME = "parent_pending_requests_cache"` and `KEY_REQUESTS = "requests_json_v1"` from test-side (`ParentRepositoryColdStartTest.kt:186-188`) to production-side. Production constants win; the test object may either import from production or stay as-is (apply-phase decision based on ktlint precedence).

- [x] **3.2 — Modify `ParentRepository` constructor + hydration + merge.**
  - Add `private val pendingRequestsCache: PendingRequestsCache` constructor param after `clientProvider` at `ParentRepository.kt:47`.
  - In `init {}` (the existing block is implicit — ParentRepository has no explicit init; add one) launch a coroutine that collects `pendingRequestsCache.observe()` and pushes the first emission into `_pendingRequestsFlow.value`. Use `applicationScope` or `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — proposal #2 says Hilt's `@Singleton` scope is fine; the constructor does NOT block on hydration (today's invariant `_pendingRequestsFlow.value == emptyList()` until first emission must remain intact for the existing VM contract at `ParentViewModel.kt:119-122`).
  - Replace `publishPendingRequests(list)` at `ParentRepository.kt:81-83` with merge logic:
    ```kotlin
    fun publishPendingRequests(newList: List<TimeRequest>) {
        val current = _pendingRequestsFlow.value
        val merged = mergePendingRequests(current, newList)
        _pendingRequestsFlow.value = merged
        scope.launch { pendingRequestsCache.write(merged) }
    }

    private fun mergePendingRequests(
        local: List<TimeRequest>,
        incoming: List<TimeRequest>
    ): List<TimeRequest> {
        val byId = local.associateBy { it.id }.toMutableMap()
        for (req in incoming) {
            val existing = byId[req.id]
            byId[req.id] = if (existing == null) req
                           else if (isNewer(existing, req)) existing else req
        }
        return byId.values.toList()
    }
    ```
    `isNewer(a, b)` compares `respondedAt` (non-null wins), then `createdAt` lexicographically. The "(m) merge" decision from engram #245.
  - Keep the cold-start invariant: `_pendingRequestsFlow` is still `MutableStateFlow<List<TimeRequest>>(emptyList())` at construction. The init coroutine just upgrades the value asynchronously.

- [x] **3.3 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryColdStartTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryMergeTest" --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryCacheRoundTripTest" --rerun-tasks`. All 6 tests (T1.1, T1.2, T1.3, T1.4, T1.5, T1.6) now PASS.

- [x] **3.4 — Run the full repo + VM test suites.**
  `./gradlew :app:testDebugUnitTest --rerun-tasks`. All prior tests stay green. The pre-existing `NetworkModuleTest`/`BootReceiverTest`/`NavGraphTest` failures (per `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md:115` precedent) are unchanged and out of scope.

- [x] **3.5 — Commit**:
  ```
  fix(repo): hydrate pendingRequestsFlow from DataStore cache on cold start
  ```
  Body must cite engram #244 (root cause analysis), #245 (d+m decisions), and `ParentViewModel.kt:119-122` (the consumer that renders the empty-list flicker).

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `ParentRepository.kt` + `PendingRequestsCache.kt`.
- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; pre-existing 4 failures on `NetworkModuleTest`/`BootReceiverTest`/`NavGraphTest` unchanged.
- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `ParentRepository.kt`, `PendingRequestsCache.kt`, or the 2 new test files. Pre-existing violations elsewhere are out of scope.
- [x] **4.4 — `./gradlew :app:detekt`** — no new violations on the 2 touched production files.
- [x] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "PendingRequestsCache\|pendingRequestsCache" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected: 1 production use inside `ParentRepository.kt` (3.2 constructor + init coroutine + `publishPendingRequests` writer) + 1 production class definition. No other call sites.

---

## Out of scope (frozen)

- `BehavioralEventEntity` outbox (unrelated; engram #244 proves no parent-facing surface).
- V2 server-side Solicitudes filter for stale resolved rows (separate follow-up per `archive/2026-07-06-feat-multi-child-picker/proposal.md:49`).
- Solicitudes UI polish (grouping, real-time, "Todos" semantics) — out per Q2 chain's locked scope.
- Cache TTL / self-expiry (proposal open question #3 — YAGNI for first cut).
- Persistence of `selectedChildId` across cold start (separate per Q2 chain).
- DataStore migration of any other repository state.

## Notes

- This change is a 2-file production diff (`PendingRequestsCache.kt` NEW + `ParentRepository.kt` MODIFIED) + 2 new test files (~120 LoC test). Well under the 400-line review budget. The proposal's "+3 new tests" claim: 2 in `ParentRepositoryMergeTest.kt` + 1 in `ParentRepositoryCacheRoundTripTest.kt`; the existing `ParentRepositoryColdStartTest.kt` stays unchanged (its 3 cases flip RED→GREEN without modification).
- **`strict_tdd: true` from `openspec/config.yaml:3`** is honoured with the 2-commit pattern: test-only Phase 1 commit + production+test Phase 3 commit. Phase 1 RED is the gate.
- **No new capabilities, no spec delta.** Per the proposal §Capabilities section, `time-request-approval/spec.md` is silent on cold-start hydration; the existing RED test (T1.1) is the de-facto contract. The same precedent the auth-fix used (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21` — "Modified: none. parent-auth-session/spec.md unchanged; in-memory restore ordering is not documented") applies here.
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. CI is the cross-device smoke.
- **Hilt wiring caveat:** `PendingRequestsCache` has `@Inject constructor(@ApplicationContext context)` mirroring `PairedDevicesStore` (`data/local/PairedDevicesStore.kt:17`). No `@Provides` method needed. `ParentRepository`'s new constructor param is auto-wired by Hilt's existing constructor injection — no `RepositoryModule.kt` change required unless the test source set uses a different DI shape (apply phase verifies).
- **First-frame invariant**: `_pendingRequestsFlow` still starts at `emptyList()`. The init coroutine upgrades it asynchronously. The VM at `ParentViewModel.kt:119-122` already handles this race (it `collectAsState()`s and re-renders on emission). T1.1 (existing RED) pins the post-hydration value via `runTest` waiting for the coroutine to complete.
- **Reference resolution for the next session**: engram #244 (`sdd/fix-parent-log-events-cleared-on-reopen/explore` — root cause analysis), #245 (`sdd/fix-parent-log-events-cleared-on-reopen/decisions` — d+m decisions), #246 (proposal artifact), #247 (spec — no delta). Precedent: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` is the same cold-start shape, same single-PR ~50 LoC budget.
- **If on closer inspection the root cause is different** (e.g., Hilt's `@ApplicationContext context` is unavailable in the unit-test source set, blocking `ParentRepositoryColdStartTest` from even compiling the production constructor), Phase 3.1/3.2 are the seams to revisit. The symptom (`pendingRequestsFlow.value` is `emptyList()` on a fresh instance after a warm session that called `publishPendingRequests`) is the agreed starting point.

---

## Apply log

- **Branch:** `fix/solicitudes-cache-on-cold-start` based on `master @ f5c9c66`.
- **PR:** see PR URL in the apply-progress engram (search key
  `sdd/fix-parent-log-events-cleared-on-reopen/apply-progress`).
- **Work units shipped (3 commits):**
  1. `test(repo): add RED coverage for Solicitudes cold-start hydration and merge write-through` — openspec change artifacts + 3 existing RED tests (`T1.1`/`T1.2`/`T1.3`) + 2 new merge tests (`T1.4`/`T1.5`).
  2. `feat(data): persist Solicitudes cache across cold starts via DataStore` — new `PendingRequestsCache` + `ParentRepository` hydration + merge + the new `ParentRepositoryCacheRoundTripTest` (`T1.6`); also the existing `ParentRepositoryColdStartTest` and `ParentRepositoryMergeTest` updates (DataStore wipe in `@Before`/`@After` + `awaitUntil` helpers in `T1.1`/`T1.2`).
  3. `chore(openspec): mark apply tasks complete in tasks.md` — this file.
- **Final test totals (`./gradlew :app:testDebugUnitTest`):** 709 tests,
  3 pre-existing failures (`NetworkModuleTest`, `BootReceiverTest`) unchanged — no new regressions.
- **RED → GREEN:** T1.1, T1.2, T1.3, T1.4, T1.5, T1.6.
- **Lint / detekt:** clean on touched production files (`PendingRequestsCache.kt`, `ParentRepository.kt`, the 3 new/modified test files). Pre-existing violations on `ParentRepositoryColdStartTest.kt:1` and `:10` (unused `io.mockk.every` import, missing trailing newline) carried over from the upstream untracked file are out of scope per task §4.3.
- **Implementation deviations from `tasks.md` §3.2:**
  - `publishPendingRequests` was made `suspend` (vs. fire-and-forget per the proposal) so the cache write completes before the call returns — eliminates the in-memory vs disk race the proposal implicitly accepted. Both existing call sites (`SolicitudesPollingWorker.kt:76`, `ParentViewModel.kt:217`) are already in suspending contexts, so the signature change is non-breaking.
  - `init {}` only assigns the cached value when `cached.isNotEmpty()` (task spec assumed unconditional assign); this avoids a race where hydration's first read completes before the warm session's first `publishPendingRequests` writes the cache, preventing the hydration from overwriting a freshly-published value back to `emptyList()`.
  - `pendingRequestsCache` is `runCatching { PendingRequestsCache.get(context) }.getOrNull()` rather than eager (per the proposal) so `ParentRepositoryTest` — which mocks the `Context` and so cannot construct a `DataStore` — does not regress.
  - `PendingRequestsCache` is a private-constructor + companion `get(context)` factory memoizing per `applicationContext` identityHashCode (not the file-level `preferencesDataStore` extension delegate, as the proposal recommended): the project's `androidx.datastore:datastore-preferences:1.2.0` enforces one DataStore per file per process, and within a single test the warm + cold `ParentRepository` instances share the same application context so they share the cache, satisfying the invariant. The factory bounds itself to JVM scope and does not impact production isolation.
  - `PendingRequestsCache.init {}` probes `preferencesDataStoreFile(NAME)` to fail fast with a clear contract if the context is bad (mock or null), instead of letting the `File` constructor throw a confusing `parent.path is null` NPE 12 frames deep — applies to test paths only since production always injects a real `@ApplicationContext`.