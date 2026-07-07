# Proposal: fix-parent-log-events-cleared-on-reopen

> Bug-fix proposal (NOT mini-SDD lite, NOT chained). Mirrors `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` precedent: same data-layer cold-start shape, same single-PR pattern, same ~50 LoC budget. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryColdStartTest.kt` and is structurally correct — the apply phase flips it GREEN, does NOT delete it.

## Why

The Solicitudes tab — the parent-facing "log de eventos" — starts empty on every cold start. Root cause: `ParentRepository._pendingRequestsFlow` (`ParentRepository.kt:71`) is a `@Singleton`-scoped `MutableStateFlow<List<TimeRequest>>(emptyList())` with **no disk write on `publishPendingRequests` and no disk read on init** (`ParentRepository.kt:81-83`). On process death + relaunch, Hilt constructs a fresh `ParentRepository`, the flow re-initializes to `emptyList()`, and the Solicitudes tab renders "Sin solicitudes" until the next `SolicitudesPollingWorker` tick (5–15 min per `SolicitudesPollingWorker.kt`) or an explicit `loadPendingRequests()` network round-trip. A parent whose child just sent a time request may see "no requests" for the entire polling window and miss the request.

This is NOT a `BehavioralEventEntity` bug — that table is a pure upstream outbox (engram #244, full read/write surface traced: no parent-facing read path, no Compose consumer, no Flow exposure). The real surface is `ParentRepository._pendingRequestsFlow`, which the `Solicitudes` tab mirrors via `ParentViewModel.pendingRequests` (`ParentViewModel.kt:119-122`).

## What changes

1. **New `PendingRequestsCache` (DataStore Preferences)** in `app/src/main/java/com/tudominio/parentalcontrol/data/local/PendingRequestsCache.kt` (~30 LoC). `@Singleton`, holds a `DataStore<Preferences>` keyed on `PendingRequestsPrefs.NAME = "parent_pending_requests_cache"` / `KEY_REQUESTS = "requests_json_v1"` (test-side constants in `ParentRepositoryColdStartTest.kt:186-188` are the source of truth for these names; promote them to production-side). Exposes `suspend fun read(): List<TimeRequest>` and `suspend fun write(list: List<TimeRequest>)`. Serialization reuses the existing `kotlinx.serialization.json.Json` instance shape (lenient + ignoreUnknownKeys) at `ParentRepository.kt:50-53`.
2. **`ParentRepository` cold-start hydration + merge write-through** (~15 LoC).
   - Inject `PendingRequestsCache` via Hilt (constructor parameter; default-test-fakes provided by the existing `data/local/PairedDevicesStore` Hilt pattern).
   - In `init {}`, launch a coroutine that collects `cache.observe(): Flow<List<TimeRequest>>` and pushes the first non-`Loading` value into `_pendingRequestsFlow`. `MutableStateFlow.value = list` on the IO dispatcher keeps the first-frame invariant (`emptyList()` → cached list) intact.
   - Change `publishPendingRequests(newList)` to **merge** `newList` with the current cache value before writing (preserves optimistic local updates not yet synced upstream — e.g., a parent-approved row that hasn't reached `approve-request` yet). The merge is key-based on `TimeRequest.id`; the newer `status`/`respondedAt`/`parentResponse` wins. This is the **(m) merge** decision from engram #245.
3. **Apply-phase tests** (apply, not this proposal):
   - Existing `ParentRepositoryColdStartTest` (3 cases at `ParentRepositoryColdStartTest.kt:111,144,166`) goes RED→GREEN unchanged.
   - +2 new merge-logic unit tests in `ParentRepositoryMergeTest.kt`: (a) `publishPendingRequests_merges_with_cache_preserving_local_optimistic_updates`, (b) `publishPendingRequests_local_newer_status_wins_over_stale_cache`.
   - +1 new Robolectric DataStore round-trip test in `ParentRepositoryCacheRoundTripTest.kt` asserting `write(list); read()` survives a fresh `ParentRepository` instance (mirrors the cold-start invariant from a different angle).

## Capabilities

- **New**: none.
- **Modified**: none. `time-request-approval/spec.md` is silent on cold-start hydration — the only behavioral delta is "Solicitudes is not empty after process death", which the RED test pins. Spec delta deferred unless the user wants it formalized (open question #1 below).

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `data/local/PendingRequestsCache.kt` | New | DataStore-backed read/write helper (~30 LoC). |
| `data/repository/ParentRepository.kt:44-48,71-83` | Modified | Inject cache, hydrate in `init {}`, merge in `publishPendingRequests` (~15 LoC). |
| `data/local/PairedDevicesStore.kt` | Read-only | Hilt-binding pattern reference (do NOT mirror — different concern; use DataStore, not SharedPreferences, per the **(d) DataStore** decision). |
| `test/.../ParentRepositoryColdStartTest.kt` | RED → GREEN | Existing 3 cases flip. |
| `test/.../ParentRepositoryMergeTest.kt` (new) | New | 2 merge-logic cases. |
| `test/.../ParentRepositoryCacheRoundTripTest.kt` (new) | New | 1 DataStore round-trip case. |
| `openspec/specs/time-request-approval/spec.md` | Unchanged (deferred) | See open question #1. |

## Impact

- **User-facing**: parent sees previously-fetched pending time requests immediately on cold start — no "Sin solicitudes" flicker, no missed requests during the polling window.
- **Migration**: none. Cache namespace `parent_pending_requests_cache` is new; first-deploy users start empty (test T1.3 stays GREEN as the pin for that case). Subsequent deploys preserve the cache across upgrades.
- **Data shape**: unchanged on the wire. Cache stores a JSON snapshot of `List<TimeRequest>` exactly as the flow holds it.
- **DI/Hilt/DB/Compose/nav**: zero change beyond one new constructor param on `ParentRepository`.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `init {}` coroutine races with first `pendingRequestsFlow` read | Med | First emission is `emptyList()` (today's invariant) → cache value asynchronously. `MutableStateFlow.value` setter is thread-safe; `_pendingRequestsFlow.value = list` on `Dispatchers.IO`. RED test T1.1 (`runTest`) pins the final value. |
| `merge` accidentally drops an upstream row when local already decided it | Low | Merge is union-by-id with newest-status-wins; the local optimistic row stays until the next polling tick fetches the canonical server state. |
| Stale cache survives resolved rows | Low | Same upstream `getPendingRequests` server-side filter (`status=eq.PENDING` at `ParentRepository.kt:159-160`) refreshes the cache on the next tick. Acceptable brief-stale window. |
| Multi-process Hilt | None | `ParentRepository` is single-process; `@Singleton` shared. |
| DataStore write contention between worker and VM | Low | DataStore Preferences is single-writer-atomic per file; both sites funnel through the same `PendingRequestsCache` instance. |

## Rollback

Single PR. `git revert` of the cache helper + `ParentRepository` constructor change restores prior behavior. No schema migration, no feature flag, no data loss (cache file is local-only and disposable).

## Out of scope

- `BehavioralEventEntity` outbox (unrelated; engram #244 proves no parent-facing surface).
- Server-side V2 filter change for stale resolved rows (separate follow-up per `archive/2026-07-06-feat-multi-child-picker/proposal.md:49`).
- Solicitudes UI polish (grouping, real-time, "Todos" semantics) — out per Q2 chain's locked scope.
- DataStore migration of any other repository state.

## Success criteria

- [ ] RED: `ParentRepositoryColdStartTest` 3 cases fail on `master = f5c9c66` with the current `emptyList()` cold-start behavior.
- [ ] GREEN: same 3 cases pass after the `ParentRepository` change.
- [ ] New `ParentRepositoryMergeTest` 2 cases pass (merge preserves optimistic local updates; newer status wins).
- [ ] New `ParentRepositoryCacheRoundTripTest` 1 case passes (DataStore write→read→fresh-instance cycle).
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the 2 touched files.
- [ ] `time-request-approval/spec.md` UNCHANGED unless open question #1 resolves to "yes, spec delta now" (otherwise deferred).

## Open questions

1. **Spec delta — yes or defer?** `openspec/specs/time-request-approval/spec.md` does not document cold-start hydration. T1.1 pins the behavior via the test, which is the same precedent the auth-fix proposal used (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21` — "Modified: none. parent-auth-session/spec.md unchanged; in-memory restore ordering is not documented"). Recommend: defer unless the user wants the spec formalized now.
2. **Merge tie-breaker for same `createdAt`** — current proposal says "newer status wins". If a row is updated at the exact same instant on two devices, the merge is non-deterministic. Acceptable? (YAGNI: only one parent device typically. Confirm at apply time.)
3. **Cache TTL** — should the cache self-expire after N days to prevent stale-by-week-old rows from appearing? Explore flagged this as a `7-day TTL` mitigation but the DataStore implementation is currently TTL-less. Defer to apply; first cut is no TTL.

## References

- **Diagnosis**: engram **#244** `sdd/fix-parent-log-events-cleared-on-reopen/explore` (full read/write surface trace; proves `BehavioralEventEntity` is unrelated).
- **Decisions**: engram **#245** `sdd/fix-parent-log-events-cleared-on-reopen/decisions` — (d) DataStore, (m) merge.
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryColdStartTest.kt:111,144,166` (T1.1, T1.2, T1.3).
- **Bug surface**: `ParentRepository.kt:71` (`_pendingRequestsFlow` init), `ParentRepository.kt:81-83` (`publishPendingRequests` write — no disk).
- **Consumer surface**: `ParentViewModel.kt:119-122` (mirror), `DashboardScreen.kt:95` (Solicitudes tab render).
- **Dep confirmation**: `gradle/libs.versions.toml` — `datastore-preferences = "androidx.datastore:datastore-preferences:1.2.0"`.
- **DI pattern reference**: `data/local/PairedDevicesStore.kt` (SharedPreferences today; we deliberately diverge to DataStore per the (d) decision).
- **Format precedent**: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` (same cold-start shape, same single-PR ~50 LoC budget).
- **Related deferred work**: `archive/2026-07-06-feat-multi-child-picker/proposal.md:49` — V2 server-side filter is a separate follow-up; `selectedChildId` persistence is also separate.
