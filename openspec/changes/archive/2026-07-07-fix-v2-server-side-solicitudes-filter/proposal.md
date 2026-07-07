# Proposal: fix-v2-server-side-solicitudes-filter

> Bug-fix-style enhancement (V1 → V2 server-side filter). Single PR, ~230 LoC (80 production + 150 tests). Mirrors the `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen` precedent: same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt` (V1 path is wrong; V2 turns it GREEN — does NOT delete it).

## Why

V1 (`feat-multi-child-picker`, master @ `7f20f05` via PR #18 + #19) shipped the `selectedChildId` picker UX with a **client-side filter** — the parent sees ALL pending requests for all of their devices (RLS scoped by `parent_id = auth.uid()` via `time_requests_parent_select` at `supabase/migrations/002_rls_policies.sql:153-161`), then `DashboardScreen` discards rows whose `deviceId` does not belong to the selected child. The explore note from `feat-multi-child-picker` flagged this explicitly: "V2 server-side: explicitly out of scope. Cite `ParentRepository.kt:157-163`" (engram #227, V2 deferral line).

V2 pushes the filter to the server by appending `&device_id=in.(<ids>)` to the existing static Postgrest query at `ParentRepository.kt:297-298`. RLS remains the security boundary; the `device_id` clause is a transport optimization. Why it matters now: multi-child parents with N children fetch every child's pending rows on every poll (5-min cadence via `SolicitudesPollingWorker.kt:70` plus every UI refresh). The wasted bandwidth and JSON-parsing cost grows linearly with N children — a parent with 4 children sees 4× the payload for the same Solicitudes tab.

## What changes

1. **Add `getPendingRequests(selectedChildId: String?)` overload** to `ParentRepository` (~50 LoC at `ParentRepository.kt:291-315`):
   - When `selectedChildId != null`: resolve child→device ids **inside the repo** (per decision Q1=r) using the existing device cache the repo already maintains for `getDevices()`. Append `&device_id=in.(<comma-separated ids>)` to the static query string at `ParentRepository.kt:297-298`. UUIDs are URL-safe — no encoding needed.
   - When `selectedChildId == null` (Todos): URL stays parameter-less — RLS alone scopes the rows. Avoids forcing the repo to fetch the parent's full device list on every poll.
   - 0-device-ids edge case (per Q4=e): return `Result.success(emptyList())` — `DashboardScreen` already renders "Sin solicitudes" for an empty list, so no new error state.
   - Static clauses (`status=eq.PENDING`, `order=created_at.desc`, `select=*,devices(device_name)`) MUST stay intact — additive only.
2. **Preserve the no-arg `getPendingRequests()` overload** (used by `SolicitudesPollingWorker.kt:70` per Q3=t). Internally delegates to the new overload with `selectedChildId = null` so the worker keeps its Todos-only semantics with zero behavioral change. The merge logic (T1.4/T1.5 from V1) absorbs new requests regardless of which child they're for.
3. **Update existing `ParentRepositoryTest.kt` callers** to use the no-arg overload explicitly (~5 LoC across call sites). No change in behavior — these tests stay GREEN throughout.
4. **Apply-phase tests** (apply, not this proposal):
   - Existing `ParentRepositoryV2FilterTest.kt` (3 cases at `ParentRepositoryV2FilterTest.kt:126,182,212`) goes RED→GREEN. Two of those cases are RED today (signature doesn't exist; URL has no `device_id` clause); the Todos-null case is a GREEN guardrail to prevent regression.
   - Existing `ParentRepositoryTest`, `ParentViewModelTest`, `SolicitudesPollingWorkerTest` stay GREEN (no-arg overload preserved).
   - +0 new VM tests (per Q5=r — passthrough is implicitly covered by the existing VM test).

## Capabilities

- **New**: none.
- **Modified**: none. `time-request-approval/spec.md` is silent on whether the filter is client-side or server-side — V2 is a transport optimization with the same observable behavior. Spec delta deferred unless the user wants it formalized (open question #1 below). Same precedent as the auth-fix proposal (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21` — "Modified: none. parent-auth-session/spec.md unchanged; in-memory restore ordering is not documented").

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `data/repository/ParentRepository.kt:291-315` | Modified | Add `selectedChildId: String?` overload; preserve no-arg overload as a thin delegate (~50 LoC). |
| `data/repository/ParentRepository.kt:297-298` | Modified (in-place) | Static URL builder appends `&device_id=in.(<ids>)` when child is selected; unchanged otherwise. |
| `viewmodel/ParentViewModel.kt` | Modified (no API change) | Thread `_selectedChildId.value` through `loadPendingRequests()` to the new overload (~5 LoC). No public API change. |
| `workers/SolicitudesPollingWorker.kt:70` | Unchanged | Keeps the no-arg overload — Todos-only semantics per Q3=t. |
| `data/local/PendingRequestsCache.kt` | Unchanged | Cache remains global per Q2=g; per-child cache deferred. |
| `test/.../ParentRepositoryV2FilterTest.kt` | RED → GREEN | 3 existing cases flip (signature + URL builder). |
| `test/.../ParentRepositoryTest.kt` | Updated call sites | Use the no-arg overload explicitly; no behavior change. |
| `openspec/specs/time-request-approval/spec.md` | Unchanged (deferred) | See open question #1. |
| `supabase/migrations/*.sql` | Unchanged | `idx_time_requests_device` already covers the filter (`001_initial_schema.sql:77`); RLS unchanged (`002_rls_policies.sql:153-161`). |

## Impact

- **User-facing**: no change. Same UX. Same `selectedChildId` picker, same "Sin solicitudes" empty state, same Solicitudes tab render path. V2 is invisible to the parent — purely an internal optimization (smaller payloads, less JSON parsing for multi-child households).
- **Migration**: none. No DB migration, no schema change, no RLS change, no edge function. Index `idx_time_requests_device` already exists. Postgrest handles the `in.(...)` filter natively.
- **Data shape**: unchanged on the wire for the Todos path; the per-child path adds a single `device_id=in.(...)` query parameter.
- **DI/Hilt/Compose/nav**: zero change. The new overload is a constructor-internal helper; Hilt is not involved at the call site.
- **Bandwidth**: empirically, single-child parents see zero change (no `device_id` filter applied). Multi-child parents see roughly `1/N` of the original payload on the per-child fetch path. Polling still hits the Todos path.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Repository fetches the device list on every `loadPendingRequests` call (extra round-trip) | Low | The repo already holds the device list as a `StateFlow` from `getDevices()`; it reads the cached snapshot, not a re-fetch. |
| `device_id=in.(...)` produces an empty list when the child's devices changed server-side between calls | Low | `DashboardScreen` already renders "Sin solicitudes" for an empty list — same UX as a child with no pending requests. |
| `init {}` coroutine races with first `loadPendingRequests` call (cache not yet hydrated) | Low | `_devices` is fetched eagerly on `ParentRepository` init via `loadDevices()`; the device-cache snapshot is `emptyList()` only if `loadDevices()` has not completed. RED test pins this via mockk-controlled `httpClient`. |
| Postgrest planner picks a sequential scan instead of `idx_time_requests_device` | Very Low | `001_initial_schema.sql:77` already declares the index; for a 4-child parent the planner should use a bitmap index scan. Verified by inspection, not benchmarked. |
| `getPendingRequests(selectedChildId)` accidentally sends `device_id=in.()` for 0 ids (malformed query) | Med | Explicit `if (deviceIds.isEmpty()) return@withContext Result.success(emptyList())` guard in the URL builder (per Q4=e). RED test T1.4 pins this. |
| Existing `ParentRepositoryTest` breaks when the static URL builder mutates | Low | That file uses the no-arg overload — its URL assertions check `status=eq.PENDING` / `order=created_at.desc`, both of which remain intact. Updated call sites use the no-arg form explicitly. |

## Rollback

Single PR. `git revert` of the new overload + the in-place URL builder change restores prior V1 behavior. No schema migration, no feature flag, no data loss. The no-arg overload stays callable by `SolicitudesPollingWorker` so the polling path is unaffected even mid-revert.

## Out of scope

- Per-child `PendingRequestsCache` (deferred — Q2=g; revisit if multi-child parents report stale cache artifacts).
- Edge function refactor for `getPendingRequests` — there is no edge function today (Postgrest direct), so none to refactor. engram #255.
- V3 — RLS-aware per-device filtering on the server (separate change, requires schema/RLS work; not on the roadmap).
- `SolicitudesPollingWorker` learning the currently-selected child — explicitly out per Q3=t; the worker stays dumb.
- `selectedChildId` persistence across cold starts (already shipped in `feat-multi-child-picker` via DataStore).
- Spec delta for `time-request-approval/spec.md` — see open question #1.

## Success criteria

- [ ] RED baseline on `master @ 9243b39`: `ParentRepositoryV2FilterTest` signature-does-not-compile OR 2 of 3 cases fail with "no `device_id=in.` clause".
- [ ] GREEN: same 3 cases pass after the `ParentRepository` overload + URL builder change. The 2 RED cases turn GREEN by construction (signature exists, URL has the clause); the Todos-null guardrail stays GREEN.
- [ ] `ParentRepositoryTest`, `ParentViewModelTest`, `SolicitudesPollingWorkerTest` stay GREEN (no-arg overload preserved; VM thread-through is a 5-line, behavior-preserving change).
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on `ParentRepository.kt` and `ParentViewModel.kt`.
- [ ] `time-request-approval/spec.md` UNCHANGED unless open question #1 resolves to "yes, spec delta now" (otherwise deferred).
- [ ] Postgrest query plan still uses `idx_time_requests_device` (smoke-check via `EXPLAIN` against a staging project — apply phase can skip if no staging env).

## Open questions

1. **Spec delta — yes or defer?** `openspec/specs/time-request-approval/spec.md` does not document whether the filter is client-side or server-side. V2 is a transport optimization with the same observable behavior; the RED test pins the URL shape. Recommend: defer unless the user wants the spec formalized now (same precedent as the auth-fix proposal).
2. **Per-child cache revisited?** Q2=g locks the cache as global. If a multi-child parent toggles children rapidly, the global cache will alternate between sub-sets per toggle — brief flicker possible. Acceptable for V2, but flag for V3.
3. **Index coverage under load** — `idx_time_requests_device` is a single-column index. Postgrest's planner combines it with the existing `status` filter via bitmap AND. For a parent with hundreds of devices across many children, a composite `(device_id, status)` index might be worth considering. Recommend: defer to V3 unless production load data shows it.

## References

- **Diagnosis**: engram **#255** `sdd/fix-v2-server-side-solicitudes-filter/explore` (Postgrest `device_id=in.(...)` shape, single-PR ~80 LoC estimate, no edge function, no DB migration).
- **Decisions**: engram **#256** `sdd/fix-v2-server-side-solicitudes-filter/decisions` — Q1=(r) repo-resolves, Q2=(g) global cache, Q3=(t) Todos-only worker, Q4=(e) empty list, Q5=(r) repo-only tests.
- **V1 deferral source**: `archive/2026-07-06-feat-multi-child-picker/proposal.md:49` — "V2 server-side filter is a separate follow-up"; engram **#227** `sdd/feature-multi-child-q2-child-picker/design` — "V2 server-side: explicitly out of scope. Cite `ParentRepository.kt:157-163`."
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/ParentRepositoryV2FilterTest.kt:126,182,212` (T1.1 selectedChildId sends `device_id=in.`; T1.2 null omits the clause; T1.3 static shape preserved).
- **Production surface**: `ParentRepository.kt:291-315` (`getPendingRequests()` static URL builder at `:297-298`).
- **Worker caller (must stay no-arg)**: `SolicitudesPollingWorker.kt:70`.
- **VM caller (will thread `selectedChildId`)**: `ParentViewModel.kt:194-228` (`loadPendingRequests()` — read-only at apply time to confirm no other consumers).
- **Schema/RLS confirmation**: `supabase/migrations/001_initial_schema.sql:64-79` (table + `idx_time_requests_device` at `:77`); `supabase/migrations/002_rls_policies.sql:153-161` (`time_requests_parent_select`).
- **Format precedent**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract).