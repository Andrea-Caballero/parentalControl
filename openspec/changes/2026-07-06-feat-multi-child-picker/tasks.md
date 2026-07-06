# Tasks: feat-multi-child-picker

> Chained-PR SDD (NOT mini-SDD lite). Two PRs stacked-to-main: **PR A** ships schema + domain + pairing (≈250 LoC, no UX change), **PR B** ships picker UI + filter + RED→GREEN (≈200 LoC). Mirrors `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md` format (Review Workload Forecast + plain-text guard lines + phase checklist + out-of-scope + notes) but is structured as **two PR-blocks** because the chain is firm. Strict TDD per `openspec/config.yaml:3` — every code-producing task is paired with a RED-test task that precedes or ships alongside it. Decisions pinned: Q1=A, Q2=b, Q3=ii, Q4=i, Q5=i (round 1); R2=V1, R2=a, chain=stacked-to-main (round 2); propose-Q1=r, Q2=s, Q3=u, Q4=a, Q5=b; spec-round backfill=A (synthetic "Anónimo"), rename=s (post-dismiss), fixtures confirmed; modal = Material 3 full-screen Dialog for the rename prompt.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | PR A ≈ 250 LoC; PR B ≈ 200 LoC (per design.md) |
| 400-line budget risk | Low (each PR under budget) |
| Chained PRs recommended | Yes (already chosen: stacked-to-main) |
| Suggested split | PR A (schema+domain+pairing) → PR B (picker UI) |
| Delivery strategy | ask-always |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Schema + domain + pairing plumbing | PR A | base = `master`; data-layer only, no UX change; existing 9 `DashboardScreenTest` cases stay GREEN |
| 2 | Picker UI + filter on both tabs + RED→GREEN | PR B | base = `master` (PR A already merged); depends on PR A; LazyColumn `key` fix bundled here |

---

## Change A — Schema + Domain + Pairing (PR A, stacked-to-main on `master`)

### Phase A.1 — RED: data-layer wire-shape (BLOCKING)

RED-first per `openspec/config.yaml:3`. Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.ParentRepositoryTest" --tests "com.tudominio.parentalcontrol.data.remote.MockSupabaseEngineTest" --rerun-tasks`. Tests MUST fail (build error or `AssertionError`) before Phase A.3.

- [ ] **A.1.1 — RED in `ParentRepositoryTest.kt`**: extend `getDevices_calls_get_devices_for_parent_with_jwt` at `:223-274` with three new assertions on the parsed `ChildDevice.child` field. Wire JSON fixture: `child_id = "child-lucas"`, `child_first_name = "Lucas"` → assert `result[0].child?.firstName == "Lucas"`. Wire JSON fixture without `child_id` → assert `result[1].child == null`. Wire JSON fixture with `child_id = "child-sofia"`, `child_first_name = "Sofía"` → assert `result[2].child?.id == "child-sofia"`. RED today: parse throws on unknown `child_id`/`child_first_name` columns (parser doesn't read them) OR the resulting `ChildDevice` has no `child` field.
- [ ] **A.1.2 — RED in `MockSupabaseEngineTest.kt`**: add 3 parse cases mirroring the 3-device fixture at `app/src/main/assets/mock-supabase/devices.json:1-15` once it has child fields. Asserts: (a) `dev-001` parses with `child == Child("child-lucas", "Lucas")`; (b) `dev-002` parses with `child == null`; (c) `dev-003` parses with `child == Child("child-sofia", "Sofía")`. RED today: `DeviceFixture` has no `child_id`/`child_first_name`, so the JSON columns are silently dropped.
- [ ] **A.1.3 — RED gate**: `./gradlew :app:testDebugUnitTest --rerun-tasks` — A.1.1 + A.1.2 MUST fail; pre-existing 9 `DashboardScreenTest` cases (per `DashboardScreenTest.kt:62-86` pattern) MUST stay GREEN (this change ships no UI yet).
- [ ] **A.1.4 — Commit**: `test(data): add RED coverage for child fields on ChildDevice wire + mock fixtures`.

### Phase A.2 — Investigation (no commits)

- [ ] **A.2.1 — Confirm `ChildRepository.kt` (child-side, `:1-157`) is unrelated to the new parent-side repo.** The existing file lives at `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ChildRepository.kt` and exposes `getPolicy`, `requestTime`, `getPendingRequests`, `sendHeartbeat`. Apply-phase decision: name the new parent-side repo `ChildrenRepository.kt` (per proposal.md) OR `ParentChildrenRepository.kt` to disambiguate. Document the choice in the apply-phase engram.
- [ ] **A.2.2 — Confirm `pairing/index.ts:71-115` shape.** Read the existing edge function to map where `child_first_name` slots in without breaking the anonymous-user creation at `:71-96`. The pair flow currently has no `pairing_codes.child_first_name` column (decision A.7 in design.md adds it).
- [ ] **A.2.3 — Confirm ktlint/detekt risk on the new SQL migration.** SQL is out of ktlint scope. detekt `allRules=false` per `openspec/config.yaml`. No pre-flight flag.

### Phase A.3 — GREEN: schema + RLS + backfill (1 commit)

- [ ] **A.3.1 — Create `supabase/migrations/00X_children_table.sql`.** Mirrors the spec at `openspec/changes/2026-07-06-feat-multi-child-picker/specs/child-entity/spec.md:11-36`. Schema per design.md A.1: `children` table with `id UUID PK`, `parent_id UUID NOT NULL FK → auth.users(id) ON DELETE CASCADE`, `first_name TEXT NOT NULL` (CHECK length 1-32), `created_at`/`updated_at TIMESTAMPTZ DEFAULT now()`. Constraints: `UNIQUE (parent_id, first_name)`, `CREATE INDEX idx_children_parent_id ON children(parent_id)`. Then `ALTER TABLE devices ADD COLUMN child_id UUID NULL` + `ADD CONSTRAINT fk_devices_child FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE SET NULL`.
- [ ] **A.3.2 — Append RLS to `002_rls_policies.sql`.** Add `children_parent_select`, `children_parent_insert`, `children_parent_update`, `children_parent_delete` (mirroring `:37-41`) PLUS `devices_parent_update_child_assignment` (FOR UPDATE USING/WITH CHECK `parent_id = auth.uid()`). Append-only — do NOT replace existing policies.
- [ ] **A.3.3 — Create `supabase/migrations/00X_children_backfill.sql`.** Idempotent DO-block script per design.md A.4: iterates over `SELECT DISTINCT parent_id FROM devices WHERE child_id IS NULL`, INSERTs synthetic "Anónimo" child per parent with `ON CONFLICT (parent_id, first_name) DO NOTHING`, then UPDATEs the parent's NULL `child_id` rows. Re-run is a safe no-op.
- [ ] **A.3.4 — GREEN confirmation.** Re-run the A.1.3 gate. The migration is SQL and not exercised by the JVM test suite, so this phase closes when A.3.1-A.3.3 ship as code review artifacts. Manual verification: apply migration + backfill against a staging Supabase project, assert every `devices.child_id` becomes non-NULL.
- [ ] **A.3.5 — Commit**: `feat(db): add children table, FK from devices, RLS, and idempotent Anónimo backfill`.

### Phase A.4 — GREEN: edge functions + domain + repo (1 commit)

- [ ] **A.4.1 — Modify `supabase/functions/pairing/index.ts:71-115`.** Accept `{child_first_name: string}` in body; INSERT children row + UPDATE `devices.child_id` atomically; reject empty name with HTTP 400. Per design.md A.7: trim, length 1-32, then INSERT with `ON CONFLICT (parent_id, first_name) DO NOTHING RETURNING id`; if `RETURNING id` is null, SELECT by `(parent_id, first_name)`. Then INSERT device with the resulting `child_id`.
- [ ] **A.4.2 — Modify `supabase/functions/get-devices-for-parent/index.ts:73-78`.** Extend `.select(...)` to include `"..., child_id, child:children(id, first_name)"`. Supabase resource embedding returns `child` as a nested object or `null`; the Kotlin parser (next task) handles both shapes.
- [ ] **A.4.3 — Modify `app/src/main/java/com/tudominio/parentalcontrol/domain/model/Models.kt`.** Add new `data class Child(val id: String, val parentId: String, val firstName: String, val createdAt: String, val updatedAt: String)` per design.md A.5 (serializable). Add `val child: Child? = null` to existing `ChildDevice` at `:9-18`. Default-null keeps every call site compiling.
- [ ] **A.4.4 — Modify `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt:513-546`.** Extend `DeviceDto` with `val child_id: String? = null, val child_first_name: String? = null`. Hydrate `toChildDevice()` → `child = if (child_id != null && child_first_name != null) Child(...) else null` (parentId/timestamps empty on the device payload — see design.md A.6).
- [ ] **A.4.5 — Modify `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt:182-191`.** `DeviceFixture` mirrors the same nullable defaults. Add a `path.endsWith("/rest/v1/children")` branch returning `children.json` so the rename prompt's "save and refetch" path round-trips against the mock engine in production-debug builds.
- [ ] **A.4.6 — Update `app/src/main/assets/mock-supabase/devices.json`.** Add `child_id` + `child_first_name` per row per design.md A.9: `dev-001` + `dev-002` → Lucas; `dev-003` → Sofía.
- [ ] **A.4.7 — Create `app/src/main/assets/mock-supabase/children.json`.** 2 children: `child-lucas` (Lucas) and `child-sofia` (Sofía), both under `parent-demo`. Required for the A.4.5 children branch.
- [ ] **A.4.8 — GREEN confirmation.** `./gradlew :app:testDebugUnitTest --rerun-tasks`. All previously-RED tests from A.1.1 + A.1.2 now pass; pre-existing 9 `DashboardScreenTest` cases stay GREEN.
- [ ] **A.4.9 — Commit**: `feat(parent-side): wire Child entity through domain, repo, edge fns, and mock fixtures`.

### Phase A.5 — Build verifier (PR A gate)

- [ ] **A.5.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on the 4 touched JVM files (`Models.kt`, `ParentRepository.kt`, `MockSupabaseEngine.kt`, `ChildRepository.kt`).
- [ ] **A.5.2 — `./gradlew :app:testDebugUnitTest`** — full `:app` unit suite green; pre-existing `NetworkModuleTest`/`BootReceiverTest`/`NavGraphTest` failures remain unchanged (per `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md:115` precedent).
- [ ] **A.5.3 — `./gradlew :app:ktlintCheck :app:detekt`** — green; no new violations on touched files.
- [ ] **A.5.4 — `grep -rn "child_first_name" supabase/`** — expected: `pairing/index.ts` + `00X_children_backfill.sql` + `001_initial_schema.sql` references. No other call sites.
- [ ] **A.5.5 — `grep -rn "Child(" app/src/main`** — expected: exactly 1 hit at `Models.kt` (the new data class). Existing `ChildRepository` reference is unrelated.

### Phase A.6 — PR A merge

- [ ] **A.6.1 — Open PR A** against `master`. Body cites `openspec/changes/2026-07-06-feat-multi-child-picker/specs/child-entity/spec.md` as the contract; references design.md A.1-A.10. Branch: `feat/multi-child-picker-a-schema-pairing`.
- [ ] **A.6.2 — Merge PR A → `master`.** No squash (preserve RED + GREEN commit trail). Confirm CI green on API 28/31/35.

---

## Change B — Picker UI (PR B, stacked-to-main on `master` AFTER PR A merged)

### Phase B.1 — RED: ParentViewModel + filter (BLOCKING)

Strict TDD. Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.viewmodel.ParentViewModelTest" --rerun-tasks`. Tests MUST fail before Phase B.3.

- [ ] **B.1.1 — RED in `app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelTest.kt`.** Three new tests on a `ParentViewModel` constructed with a mock `ParentRepository` returning 3 fixtures (Lucas x2 + Sofía x1 per the A.4.6 fixtures): (a) `cold_start_defaults_selectedChildId_to_null` — assert `vm.selectedChildId.value == null`; (b) `setSelectedChild_updates_StateFlow` — call `vm.setSelectedChild("child-lucas")`, assert flow emits `"child-lucas"`; (c) `filteredDevices_filters_by_selectedChildId` — collect `vm.filteredDevices`, tap Lucas chip, assert only 2 devices remain; tap Todos (`setSelectedChild(null)`), assert 3 devices. RED today: `selectedChildId` and `filteredDevices` don't exist on `ParentViewModel.kt:45-67` → build error.
- [ ] **B.1.2 — RED in `DashboardScreenTest.kt`**: add 4 new tests at the bottom of the file (after the 3 pre-existing + the 2 RED from `DashboardScreenTest.kt:462, :504` that stay RED until B.4): (a) `picker_hidden_when_one_child` — seed 1 child, assert `onAllNodesWithTag("child_picker_row").assertCountEquals(0)`; (b) `chip_tap_filters_devices_tab_to_one_child` — seed 2 children, tap Sofía chip, assert `onAllNodesWithTag("device_card").assertCountEquals(1)`; (c) `chip_tap_filters_solicitudes_tab_to_one_child` — mirror (b) against `RequestCard` after tapping Solicitudes tab; (d) `todos_chip_restores_unfiltered_list` — tap child then Todos, assert 3 cards visible. RED today: no `ChildPickerChips` composable exists → `onAllNodesWithTag("child_picker_row")` throws.
- [ ] **B.1.3 — RED gate**: full `:app:testDebugUnitTest` must show B.1.1 + B.1.2 RED, pre-existing tests GREEN, and the 2 original Q2-gap tests at `DashboardScreenTest.kt:462` and `:504` still RED (they convert in B.4).
- [ ] **B.1.4 — Commit**: `test(viewmodel+ui): add RED coverage for child picker state, filter, and N=1 hide`.

### Phase B.2 — Investigation (no commits)

- [ ] **B.2.1 — Confirm Material 3 `FilterChip` import path.** Search `grep -rn "androidx.compose.material3.FilterChip" app/src/main` — expect to find precedent at `DeviceComponents.kt:329-335` (minutes) and `:431-438` (age bands). The new `ChildPickerChips` mirrors that pattern.
- [ ] **B.2.2 — Confirm LazyColumn `key` debt at `DashboardScreen.kt:354`.** Bundle the `key = { it.id }` fix here per propose-Q5=b. Verify the current `items(list)` call has no `key` parameter (causes flicker on filter switch).
- [ ] **B.2.3 — Confirm `DisposableEffect` precedent.** Search `grep -rn "DisposableEffect" app/src/main` — likely zero hits today; this change introduces the first one. The `onDispose { viewModel.loadDevices() }` lives inside `PairingBottomSheet` (`DeviceComponents.kt:363`).

### Phase B.3 — GREEN: ParentViewModel state + composable (1 commit)

- [ ] **B.3.1 — Modify `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModel.kt`.** Add `private val _selectedChildId = MutableStateFlow<String?>(null)` + `val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()` + `fun setSelectedChild(id: String?)`. Add derived `val filteredDevices: StateFlow<List<ChildDevice>> = combine(_devices, _selectedChildId) { list, id -> if (id == null) list else list.filter { it.child?.id == id } }.stateIn(...)`. In `loadDevices()`: after `_devices.value = list`, reset `_selectedChildId` to null when its value no longer matches any `it.child?.id` (stale-selection reset per `openspec/changes/2026-07-06-feat-multi-child-picker/specs/parent-device-list/spec.md:93-95`).
- [ ] **B.3.2 — Create `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/ChildPickerChips.kt`.** `@Composable fun ChildPickerChips(children: List<Child>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier)` per design.md B.2. `LazyRow` + Material 3 `FilterChip`. testTags: `child_picker_row`, `child_picker_chip_all` ("Todos"), `child_picker_chip_$childId` (per child). Snake-case per project convention.
- [ ] **B.3.3 — GREEN confirmation for B.1.1.** The 3 `ParentViewModelTest` tests now pass.
- [ ] **B.3.4 — Commit**: `feat(viewmodel+ui): add selectedChildId state and ChildPickerChips composable`.

### Phase B.4 — GREEN: DashboardScreen integration + DeviceCard child row + 2 RED→GREEN (1 commit)

- [ ] **B.4.1 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt`.** Two edits: (a) `DeviceCard` at `:28-77` adds the child-identity row per design.md B.4 — `device.child?.let { Text(text = it.firstName, modifier = Modifier.testTag("device_card_child_name")) } ?: Text(text = "Sin asignar")`; (b) `PairingBottomSheet` at `:363` gains `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` (the dismiss-refresh hook per design.md B.5).
- [ ] **B.4.2 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt:155-256`.** Three edits: (a) render `ChildPickerChips` between `TabRow` and `when (selectedTab)` when `distinctChildren.size >= 2` per design.md B.3; (b) apply `filteredDevices` to BOTH the Devices tab `LazyColumn` and the Solicitudes tab `pendingRequests` filter (Solicitudes by `deviceId IN filteredDevices.map { it.id }.toSet()`); (c) bundle the `LazyColumn` `key = { it.id }` fix at `:354`. Notifications badge at `:176-190` keeps the UNFILTERED `pendingRequests.size` — by design.
- [ ] **B.4.3 — RED → GREEN conversion for the 2 Q2-gap tests.** `DashboardScreenTest.kt:439-462` (the `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices` test) and `DashboardScreenTest.kt:486-504` (the `q2_gap_dashboard_renders_child_picker_or_filter_control` test) flip from RED to GREEN automatically once B.4.1 (a) and B.4.2 (a) land — the testTag surface + the composable are the contract.
- [ ] **B.4.4 — GREEN confirmation.** All of B.1.1 + B.1.2 + the 2 Q2-gap conversions are GREEN. `./gradlew :app:testDebugUnitTest --rerun-tasks` — no regressions.
- [ ] **B.4.5 — Commit**: `feat(dashboard): mount child picker, filter both tabs, surface child identity on DeviceCard`.

### Phase B.5 — GREEN: RenameChildDialog (1 commit, optional but recommended)

> This phase is optional — the spec says the rename is "post-dismiss" via a screen or modal. The orchestrator confirmed modal = Material 3 full-screen Dialog. Skip if a follow-up change picks up the rename UX.

- [ ] **B.5.1 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt`.** New `@Composable fun RenameChildDialog(initialName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit)` using Material 3 `Dialog` with `properties = DialogProperties(usePlatformDefaultWidth = false)` per design.md B.6. Body: `OutlinedTextField` (`testTag("rename_child_first_name")`) + Cancel + Guardar.
- [ ] **B.5.2 — Modify `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt`.** Add `suspend fun renameChild(childId: String, newFirstName: String): Result<Child>` — RLS-guarded `UPDATE children SET first_name = $newFirstName WHERE id = $childId AND parent_id = auth.uid()`. Then `loadDevices()`.
- [ ] **B.5.3 — Wire dialog open** in `DashboardScaffold` (`DashboardScreen.kt:259` area) as `var showRenameDialog by remember { mutableStateOf(false) }` — opens when `devices.any { it.child == null }`.
- [ ] **B.5.4 — RED → GREEN test in `DeviceComponentsTest.kt`**: `rename_dialog_cancels_without_throwing` + `rename_dialog_confirm_calls_onConfirm_with_typed_name`.
- [ ] **B.5.5 — Commit**: `feat(dashboard): add Material 3 modal RenameChildDialog for unassigned devices`.

### Phase B.6 — Build verifier (PR B gate)

- [ ] **B.6.1 — `./gradlew :app:assembleDebug`** — green; no new warnings on `DashboardScreen.kt`, `DeviceComponents.kt`, `ParentViewModel.kt`, `ParentRepository.kt`.
- [ ] **B.6.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; pre-existing failures on `NetworkModuleTest`/`BootReceiverTest`/`NavGraphTest` unchanged.
- [ ] **B.6.3 — `./gradlew :app:ktlintCheck :app:detekt`** — green.
- [ ] **B.6.4 — `grep -rn "child_picker_chip" app/src/main`** — expected: production hits in `ChildPickerChips.kt` + test hits in `DashboardScreenTest.kt`. No stray references.
- [ ] **B.6.5 — `grep -rn "device_card_child_name" app/src/main`** — expected: 1 production hit (`DeviceComponents.kt` DeviceCard) + N test hits.

### Phase B.7 — PR B merge

- [ ] **B.7.1 — Open PR B** against `master` (PR A already merged). Body cites `parent-device-list/spec.md` and `time-request-approval/spec.md` as the contract; references design.md B.1-B.7. Branch: `feat/multi-child-picker-b-picker-ui`.
- [ ] **B.7.2 — Chained-PR diagram in PR B body**, per `chained-pr/SKILL.md` hard rule:
  ```
  master ──→ PR A (schema+domain+pairing, MERGED)
              └─→ PR B (picker UI) 📍
  ```
- [ ] **B.7.3 — Merge PR B → `master`.** No squash. Confirm CI green.

---

## Out of scope (frozen)

- `ParentRepository.kt:157-163` static-query refactor (V2 server-side Solicitudes filter on `getPendingRequests()`).
- Persistence of `selectedChildId` across cold start (V1 in-memory only per R2-V1).
- Real-time pairing notifications when the child pairs without the parent opening the sheet.
- Deleting a child from the parent UI (RLS permits it; UI affordance deferred).
- Manual sort controls on the device list (only `last_seen_at` DESC).
- Solicitudes tab copy / grouping refactor (parallel but separate).
- Unpair wired to RLS (separate change).

## Notes

- **Chain structure is firm**: PR A ships first, merges to `master`; PR B targets `master` on top. Per `chained-pr/SKILL.md` hard rule, each PR must include a dependency diagram marking the current PR with `📍`.
- **RED-first is firm**: every code-producing task (A.3, A.4, B.3, B.4, B.5) is paired with a RED-test task that precedes it (A.1, B.1) or ships alongside it (the 2 RED→GREEN conversions in B.4.3). This matches `openspec/config.yaml:3` (`strict_tdd: true`).
- **Naming caveat for Phase A.2.1**: the orchestrator prompt and proposal.md use `ChildrenRepository.kt`, but `ChildRepository.kt` (child-side, `:1-157`) already exists at the same path. Apply phase MUST resolve the naming (option a: `ChildrenRepository.kt` and accept the visual proximity; option b: `ParentChildrenRepository.kt` for disambiguation). Whichever name wins, the repo lives next to `ParentRepository.kt` so it stays in the parent-side cluster.
- **No spec delta for Change A**: the data-layer plumbing is invisible to the user. Existing dashboard render unchanged. The 9 pre-existing `DashboardScreenTest` cases stay GREEN throughout PR A — that's the explicit non-regression contract.
- **LazyColumn `key` fix** is bundled into B.4.2 (c) per propose-Q5=b (debt fix that costs 1 LoC and prevents filter-switch flicker).
- **DisposableEffect placement** (B.4.1 (b)): lives inside `PairingBottomSheet` so its `onDispose` fires whenever `showPairingSheet` flips false in `DashboardScaffold` (`DashboardScreen.kt:259`). The refresh is unconditional on dismiss — even a no-op cancel triggers `loadDevices()` per `parent-device-list/spec.md:121-123`.
- **No manual smoke / instrumented runs locally** per `openspec/config.yaml:57` gotcha. RED→GREEN + build verifier are the gate; CI on API 28/31/35 is the cross-device smoke.
- **Reference resolution for apply phase**: `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md` is the 4-phase-shape precedent. `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` is the chained-PR precedent (PR #15 docs + PR #16 code). The 3 fixtures live in `app/src/main/assets/mock-supabase/devices.json`. Spec floors: `openspec/changes/2026-07-06-feat-multi-child-picker/specs/child-entity/spec.md` (A) + `parent-device-list/spec.md` (B) + `time-request-approval/spec.md` (B).
- **PR A and PR B may land on the same calendar day**, but PR B's branch MUST be re-pointed to `master` after PR A merges — otherwise the diff picks up PR A's changes and the review budget blows up.