# Proposal: feat-multi-child-picker

> Chained-PR proposal (NOT mini-SDD lite). Reframes `feature-multi-child-q2-child-picker`. The data layer is NOT 1:N — it has no child entity at all; slice-1 (`2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests`) only masked that with copy. This change ships in two PRs, stacked-to-main: PR A = schema + domain + pairing; PR B = picker UI + RED→GREEN.

## Why

The data layer has **no child entity** today:

- `devices` table (`001_initial_schema.sql:24-36`) has no `child_id`/`child_first_name`. `parent_id` is non-unique but child-less — a parent has N devices with no group identity.
- `ChildDevice` domain (`Models.kt`) has no child fields. `DeviceDto` (`ParentRepository.kt:513-546`) parses no child columns.
- `get-devices-for-parent` edge function (`supabase/functions/get-devices-for-parent/index.ts:73-78`) does not select child fields.
- `pairing/index.ts:71-96, :98-115` creates one anonymous user **per device** with no child profile captured at pairing time.
- `MockSupabaseEngine` (`MockSupabaseEngine.kt:182-191`) has no `Child` model; `app/src/main/assets/mock-supabase/devices.json` ships 3 child-less devices.
- `DashboardScreen` (`DashboardScreen.kt:155-256`) has no picker, no child-identity `testTag`, no `selectedChildId` state.

**Empirical proof (2026-07-06, Robolectric test spike)**: 2 RED assertions at `DashboardScreenTest.kt:462` (`q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices`) and `:504` (`q2_gap_dashboard_renders_child_picker_or_filter_control`) confirm the gap. The 3-device mock renders UID-style anonymous entries, no child names, no picker chip row. Slice-1 (PR #17) merged at `d10bd11` shipped copy + tests but did NOT add a child entity.

The picker UX the parent sees is structurally blocked until the schema+domain+pairing migration lands — hence the chained-PR split.

## What changes

### Change A — Schema + Domain + Pairing (PR A, ~250 lines)

1. **New `children` table** + FK `devices.child_id → children.id` + `ON DELETE CASCADE`. New index `idx_children_parent`. RLS `children_parent_select/insert/update/delete` mirroring `002_rls_policies.sql:37-41`.
2. **New `Child` domain model** in `Models.kt`. Add `ChildDevice.child: Child?` (nullable for back-compat with devices paired before the migration).
3. **Pairing flow migration**: `pairing/index.ts:71-115` captures `child_first_name` + parent-supplied `child_id`; creates a `children` row tied to `parent_id`.
4. **Wire shape**: `get-devices-for-parent` adds `child_id, child_first_name` to `.select(...)`; `ParentRepository.toChildDevice()` parses them.
5. **Mock fixtures**: extend `devices.json` + `MockSupabaseEngine` so 3 devices span 2 children (e.g., Lucas owns dev-001+dev-002; Sofía owns dev-003).
6. **Tests**: 3+ new `MockSupabaseEngineTest` cases (parse new fields); 3+ new `ParentRepositoryTest` cases (wire-shape; extend `getDevices_calls_get_devices_for_parent_with_jwt` at `:223-274`).
7. **No behavior change visible to the user** — Change A is purely data-layer plumbing. Existing tests stay green; existing dashboard render unchanged.

### Change B — Picker UI (PR B, ~200 lines, depends on A)

1. **`ParentViewModel.kt:45-67`**: new `_selectedChildId: MutableStateFlow<String?>(null)` + `setSelectedChild(id: String?)`. In-memory only (V1 reset-on-cold-start, no DataStore).
2. **`DeviceComponents.kt`**: new `ChildPickerChips` composable using existing `FilterChip` pattern (`:329-335` minutes, `:431-438` age bands). `LazyRow` so N≥5 scrolls. testTags: `child_picker_chip_all`, `child_picker_chip_$childId`. Snake-case per established convention.
3. **`DashboardScreen.kt:155-256`**: render `ChildPickerChips` between `TabRow` and the tab `when` branch. Apply filter on `listState.items` + legacy `devices` alias (`:299-305`) in Devices tab; apply filter on `pendingRequests` by `deviceId IN childDevices` in Solicitudes tab.
4. **"Todos" chip** first, explicit, when N≥2. **Picker HIDDEN** when `devices.distinctBy { it.child?.id }.size ≤ 1` (per Q5).
5. **`PairingBottomSheet`**: `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` so a freshly-paired child appears in the chip row.
6. **Notifications badge** (`DashboardScreen.kt:176-190`) keeps counting the UNFILTERED `pendingRequests.size` — by design.
7. **Tests**: convert 2 RED tests at `DashboardScreenTest.kt:462` and `:504` to GREEN; extend `threeDeviceFixtures` (`:128-159`) with `childUserId`/`childFirstName`; 2-3 new RED→GREEN tests for filter behavior.

### Chain strategy

`stacked-to-main` — PR A merges first, then PR B targets `main` on top. Each PR is independently reviewable; the 400-line budget is respected (≈250 + ≈200).

## Scope

**In scope**: child entity + picker UI + 2 RED→GREEN tests + 3+ repo tests + 3+ wire tests.
**Out of scope**: `ParentRepository.kt:157-163` static-query refactor (V2 server-side filter); log-events-cleared-on-reopen bug (separate change, post-Q2); Solicitudes grouping logic (covered by client-side filter); realtime pairing notifications (later); DataStore persistence (deferred until users complain).

## Capabilities

- **New**: `child-entity` — `children` table + `Child` domain + pairing-capture flow + RLS + cascading FK.
- **Modified**: `parent-device-list` — adds `selectedChildId` filter behavior on Devices tab + Solicitudes tab + "Todos" semantics + hide-when-N=1 + pairing-refresh hook.

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `supabase/migrations/001_initial_schema.sql` + new `00X_child_entity.sql` | New | `children` table + FK + cascade + index. |
| `002_rls_policies.sql` | Modified | New `children_*` RLS policies. |
| `supabase/functions/pairing/index.ts:71-115` | Modified | Capture `child_first_name` + create `children` row. |
| `supabase/functions/get-devices-for-parent/index.ts:73-78` | Modified | Select `child_id, child_first_name`. |
| `app/.../domain/model/Models.kt` | Modified | New `Child` + `ChildDevice.child`. |
| `app/.../data/repository/ParentRepository.kt:513-546` | Modified | Parse child fields in `toChildDevice()`. |
| `app/src/main/assets/mock-supabase/devices.json` + `MockSupabaseEngine.kt:182-191` | Modified | Fixtures span 2 children. |
| `app/.../viewmodel/ParentViewModel.kt:45-67` | Modified | `_selectedChildId` + `setSelectedChild()`. |
| `app/.../ui/parent/components/DeviceComponents.kt` | Modified | New `ChildPickerChips` composable. |
| `app/.../ui/parent/screens/DashboardScreen.kt:155-256, :299-305, :354` | Modified | Chip row + filter on both tabs. |
| `app/.../ui/parent/components/PairingBottomSheet*` | Modified | `DisposableEffect` on dismiss → `loadDevices()`. |
| `app/src/test/.../DashboardScreenTest.kt:462, :504` | Modified | RED → GREEN. |
| `app/src/test/.../ParentRepositoryTest.kt:223-274` | Modified | Wire-shape assertions for new fields. |
| `app/src/test/.../MockSupabaseEngineTest.kt` | Modified | Parse-failure cases. |
| `openspec/specs/parent-device-list/spec.md` | Modified | Add filter + hide-when-N=1 requirements. |
| `openspec/specs/child-entity/spec.md` | New | Children-table + pairing-capture requirements. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `LazyColumn` key instability at `:354` (no `key = { it.id }`) flickers on filter switch | Med | Set `key` in Change B (small, scoped). |
| `MockSupabaseEngine` wire-shape drift breaks fixtures | Med | Update fixtures + 3+ parse tests in lockstep. |
| Cascade `ON DELETE` deletes `devices` if a child row is removed | Low | Only `parents` (RLS) can delete children; UI never exposes delete. |
| `DisposableEffect` misses pairings from child-side without opening sheet | Low | Acceptable for V1; realtime is out-of-scope. |
| `selectedChildId` survives `loadDevices()` and points to a removed child | Low | Default to `null` in `loadDevices()` when the cached id is no longer in the list. |
| PR A and PR B review budget (400 lines) | Med | Split honors budget (~250 + ~200); changelog ref in PR B links A's hash. |

## Rollback

PR A is reversible via `git revert` of the migration + code (drop FK, drop table — keep migration file for audit). PR B is a pure UI revert — no schema impact. `selectedChildId` is in-memory, so reverting B restores the pre-change dashboard without data loss.

## Success criteria

- [ ] PR A: `children` table + FK + RLS applied; `Child` domain present; pairing captures `child_first_name`; 3+ repo tests + 3+ wire tests green; existing 9 `DashboardScreenTest` cases stay green (no behavior change yet).
- [ ] PR B: `child_picker_chip_all` and `child_picker_chip_$childId` rendered when N≥2; HIDDEN when N=1; filter applies to Devices + Solicitudes tabs; "Todos" restores unfiltered list; `DisposableEffect` refreshes after `PairingBottomSheet` dismiss.
- [ ] PR B: `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices` (`DashboardScreenTest.kt:462`) → GREEN.
- [ ] PR B: `q2_gap_dashboard_renders_child_picker_or_filter_control` (`DashboardScreenTest.kt:504`) → GREEN.
- [ ] `./gradlew testDebugUnitTest assembleDebug detekt ktlintCheck` green on both PRs.
- [ ] `openspec/specs/parent-device-list/spec.md` updated with filter + hide-when-N=1 scenarios; new `openspec/specs/child-entity/spec.md` created.
- [ ] Chained PR: PR A merged to `main` before PR B is opened.

## Open questions

1. **Pairing UX for `child_first_name` capture**: text field in `PairingBottomSheet` (recommended for V1) vs. named after the device (`child_first_name = device_name` fallback) vs. re-prompt on parent-side after first pair. Spec phase to decide.
2. **Backfill strategy** for devices paired before this migration: leave `child_id` NULL (nullable FK) vs. require a backfill script. Spec phase to specify.
3. **`Child` uniqueness**: per-parent unique on `(parent_id, first_name)` to prevent "two Lucas" confusion? Or allow duplicates? Spec phase to specify.
4. **Mock fixture ownership**: pin fixtures in test files (existing pattern, `:128-159`) vs. read from `devices.json` so prod-debug also shows the picker. Spec phase to decide.

## References

- Cached explore: engram `#218` `sdd/feature-multi-child-q2-child-picker/explore`.
- Decisions round 1: engram `#221` (Q1=A, Q2=b, Q3=ii, Q4=i, Q5=i).
- Decisions round 2: engram `#222` (R2=V1, R2=a, chain=stacked-to-main).
- Test spike: engram `#220` (2 RED tests at `:462` and `:504`).
- Recent precedent for mini-SDD lite: `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/proposal.md`.
- Recent precedent for chained PR: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` (PR #16 code + PR #15 docs).
- Spec to modify: `openspec/specs/parent-device-list/spec.md:38-50` (renders the device list — adding a filter is a behavioral delta).
- Spec to create: new `openspec/specs/child-entity/spec.md` for the `children` table.