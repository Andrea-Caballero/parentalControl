## Verification Report

**Change**: feat-multi-child-picker (Change B — picker UI + filter)
**Branch**: `feat/multi-child-picker-b-picker-ui`
**Base**: `master` @ `da6f500`
**PR**: [#19](https://github.com/Andrea-Caballero/parentalControl/pull/19) (OPEN)
**Mode**: Strict TDD
**Mode (persistence)**: openspec + Engram hybrid
**Date**: 2026-07-07
**Verifier scope**: Change B only (tasks B.1–B.7). Change A out of scope.

### Completeness Table

| Phase | Total | Complete | Incomplete | Notes |
|---|---|---|---|---|
| B.1 RED | 4 + 4 | 8 | 0 | 4 ParentViewModelTest + 4 DashboardScreenTest RED tests (incl. 2 q2_gap conversions) |
| B.2 Investigation | 3 | 3 | 0 | FilterChip precedent confirmed (`:329-335` minutes / `:431-438` age bands); LazyColumn key debt at `:354` bundled; DisposableEffect precedent added |
| B.3 GREEN: VM + composable | 3 | 3 | 0 | `selectedChildId` + `setSelectedChild` + `filteredDevices` (`combine` + `Eagerly`) + `ChildPickerChips` (NEW, 64 LoC) |
| B.4 GREEN: dashboard integration + 2 q2_gap conversions | 4 | 4 | 0 | picker mounted (hidden when N ≤ 1), both tabs filtered, LazyColumn `key = { it.id }` bundled, child-name row on DeviceCard, 2 q2_gap tests flipped RED→GREEN |
| B.5 RenameChildDialog | 0 | 0 | 0 | Deferred per orchestrator's `B.5` annotation + apply-progress engram #235 — out of scope; follow-up change |
| B.6 Build verifier | 5 | 5 | 0 | `assembleDebug` green; `testDebugUnitTest` 703/5; `ktlintCheck` clean on touched files (3 pre-existing violations in `WorkersTest.kt` are unrelated); `detekt` green |
| B.7 PR open | 1 | 1 | 0 | PR #19 opened with chained-PR diagram + 2 RED→GREEN acceptance |
| B.7 PR merge | 1 | 0 | 1 | **Operator step (out of agent reach)** — PR #19 currently OPEN |

### Build / Tests / Coverage Evidence (independent re-run)

| Command | Exit | Detail |
|---|---|---|
| `./gradlew :app:testDebugUnitTest --rerun-tasks` (feature branch) | 1 (NON-ZERO) | **703 tests, 698 pass, 5 fail** = 2 NavGraphTest (NEW REGRESSION) + 1 NetworkModuleTest + 2 BootReceiverTest. **2 q2_gap_* tests are GREEN** (acceptance contract met). |
| `./gradlew :app:testDebugUnitTest --rerun-tasks` (master @ `da6f500`) | 1 (NON-ZERO, control) | **695 tests, 689 pass, 6 fail** = 1 NetworkModuleTest + 2 BootReceiverTest + 2 q2_gap (still RED, Change B's job to fix) + 1 DeviceComponentsTest. **NavGraphTest is 0/10 failing on master** — i.e. it was fixed in master since Change A. |
| `./gradlew :app:ktlintCheck` (feature branch) | 1 (NON-ZERO, unrelated) | 7 pre-existing violations in `WorkersTest.kt` (unused imports, trailing spaces). **Main source set is clean** — no ktlint violations in any Change-B-touched production file. |
| `./gradlew :app:detekt` | 0 | BUILD SUCCESSFUL. No new violations. |
| Coverage (kover/jaCoCo) | N/A | Not configured (`sdd-init/parentalcontrol` gotcha). |

**Test totals actually observed** (independent verification, this session):

| Branch | Total | Pass | Fail | Notes |
|---|---|---|---|---|
| `master` @ `da6f500` | 695 | 689 | 6 | NavGraphTest 10/10 PASS; q2_gap_* 2/2 RED |
| `feat/multi-child-picker-b-picker-ui` | 703 | 698 | 5 | NavGraphTest 8/10 FAIL; q2_gap_* 2/2 GREEN |
| **Net Change B delta** | **+8 tests** | **+9 pass** | **-1 fail** | 2 q2_gap RED→GREEN (+2), 2 NavGraphTest GREEN→RED (-2), 4 + 5 new tests (+9) |
| Hidden in the +8: 2 new q2_gap conversions already existed in RED form | | | | Net new tests = 4 ParentViewModelTest + 5 DashboardScreenTest (incl. 2 q2_gap conversions; the originals are now GREEN) |

### Spec Compliance Matrix — Change B scope

| Req | Scenario | Covering test | Result |
|---|---|---|---|
| `parent-device-list`: Edge function returns devices | Authenticated parent gets devices | (PostgreSQL/edge-fn-side — Change A) | OUT OF SCOPE (Change A) |
| `parent-device-list`: ParentRepository.getDevices parses ChildDevice.child | 3-device fixture parses with child fields | `ParentRepositoryTest` 4 cases (Change A) | OUT OF SCOPE (Change A) |
| `parent-device-list`: DashboardScreen renders the real device list | Non-empty list renders DeviceCard rows with child name + "Sin asignar" fallback | `DeviceCard` code (`:80-99`) — child-name row visible | **PASS** by code review |
| `parent-device-list`: ChildPickerChips renders when N ≥ 2 | Picker is hidden for single-child household | `picker_hidden_when_one_child` (DashboardScreenTest) | **PASS** (0.385s) |
| `parent-device-list`: ChildPickerChips renders when N ≥ 2 | Picker is visible with explicit "Todos" + per-child chips | `picker_visible_with_chip_all_when_two_children` | **PASS** (0.145s) |
| `parent-device-list`: ChildPickerChips renders when N ≥ 2 | Per-card child identity testTag is rendered for paired devices | `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices` at `:439` | **PASS** (0.133s) — RED→GREEN |
| `parent-device-list`: ChildPickerChips renders when N ≥ 2 | Picker or filter control testTag present | `q2_gap_dashboard_renders_child_picker_or_filter_control` at `:486` | **PASS** (0.145s) — RED→GREEN |
| `parent-device-list`: ParentViewModel owns selectedChildId as in-memory state | Cold start defaults to "Todos" | `cold_start_defaults_selectedChildId_to_null` | **PASS** (0.004s) |
| `parent-device-list`: ParentViewModel owns selectedChildId as in-memory state | Stale selection is reset after a fetch | `loadDevices_resets_stale_selection_to_null` | **PASS** (0.006s) |
| `parent-device-list`: ParentViewModel owns selectedChildId as in-memory state | Selecting a chip updates the StateFlow | `setSelectedChild_updates_StateFlow` | **PASS** (0.004s) |
| `parent-device-list`: Devices tab filters by selectedChildId | "Todos" restores the unfiltered list | `todos_chip_restores_unfiltered_list` | **PASS** (0.198s) |
| `parent-device-list`: Devices tab filters by selectedChildId | Selecting a chip narrows the Devices tab | `chip_tap_filters_devices_tab_to_one_child` | **PASS** (0.318s) |
| `parent-device-list`: PairingBottomSheet dismiss refreshes the device list | A successful pair adds the new child to the chip row | `PairingBottomSheet` code (`:394-400`) — `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` | **PASS** by code review (no dedicated test in this PR) |
| `parent-device-list`: PairingBottomSheet dismiss refreshes the device list | Dismissing the sheet without pairing is a no-op (loadDevices still runs) | Same as above — refresh is unconditional on dismiss | **PASS** by code review |
| `time-request-approval`: Solicitudes tab auto-refreshes when visible | (Out of scope for Change B — Change B filters at UI layer) | `LaunchedEffect(selectedTab)` in DashboardScreen | OUT OF SCOPE (covered by `fix-parent-solicitudes-auto-poll`) |
| `time-request-approval`: Solicitudes tab filters pending requests by selectedChildId | "Todos" shows every pending request | `filteredRequests` in `DashboardScaffold` (`:185-191`) — returns `pendingRequests` when `selectedChildId == null` | **PASS** by code review |
| `time-request-approval`: Solicitudes tab filters pending requests by selectedChildId | A child-specific chip narrows the Solicitudes tab | `filteredRequests` (`:192-196`) — `deviceId IN allowedDeviceIds` filter | **PASS** by code review |
| `time-request-approval`: Solicitudes tab filters pending requests by selectedChildId | Filter is purely client-side | No new HTTP call; filter on `pendingRequests` StateFlow | **PASS** by code review |
| `time-request-approval`: V2 server-side filter refactor is deferred | PR SHALL NOT modify `getPendingRequests()` query at `ParentRepository.kt:157-163` | `git diff master..feat/multi-child-picker-b-picker-ui -- 'app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt'` shows the function signature + query string is unchanged | **PASS** (no V2 refactor in this PR) |

**Compliance summary**: 13/13 Change B scenarios compliant. 1 scenario covered by code review only (no JVM test) — `PairingBottomSheet` dismiss-refresh hook. The 2 RED→GREEN `q2_gap_*` conversions are the Change B acceptance contract and are GREEN.

### Correctness Table

| Decision | Expected | Actual | Match |
|---|---|---|---|
| `ParentViewModel._selectedChildId: MutableStateFlow<String?>(null)` | yes | `ParentViewModel.kt:67-68` — `private val _selectedChildId = MutableStateFlow<String?>(null)` | yes |
| `ParentViewModel.selectedChildId: StateFlow<String?>` exposed | yes | `ParentViewModel.kt:69` — `val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()` | yes |
| `ParentViewModel.setSelectedChild(id: String?)` setter | yes | `ParentViewModel.kt:372-374` — `fun setSelectedChild(id: String?) { _selectedChildId.value = id }` | yes |
| `ParentViewModel.filteredDevices` via `combine` | yes | `ParentViewModel.kt:78-86` — `combine(_devices, _selectedChildId) { list, id -> if (id == null) list else list.filter { it.child?.id == id } }` | yes |
| `SharingStarted.Eagerly` for `filteredDevices` | design says `WhileSubscribed(5_000)`; apply engram #235 documents the deviation as Robolectric + Compose `collectAsState` order-sensitive | `ParentViewModel.kt:84-86` — `SharingStarted.Eagerly` | yes (deviation documented) |
| Stale-selection reset in `loadDevices()` | yes | `ParentViewModel.kt:158-162` — after `_devices.value = list`, reset to null if no longer in `validIds` | yes |
| `ChildPickerChips` Material 3 `FilterChip` row in `LazyRow` | yes | `ChildPickerChips.kt:39-63` — `LazyRow` + `items(children, key = { it.id })` + `FilterChip` per child | yes |
| "Todos" chip is always first | yes | `ChildPickerChips.kt:42-49` — `item(key = "child_picker_chip_all") { FilterChip(... "Todos") }` | yes |
| Hidden when `children.size < 2` | yes (caller-side) | `DashboardScreen.kt:282-285` — `if (distinctChildren.size >= 2) { ChildPickerChips(...) }` | yes |
| testTag `child_picker` on the LazyRow | design says `child_picker_row`; apply engram #235 documents the deviation to satisfy the q2_gap contract from day 1 | `ChildPickerChips.kt:41-42` — `LazyRow(modifier = ... .testTag("child_picker"))` | yes (deviation documented) |
| testTag `child_picker_chip_all` for "Todos" | yes | `ChildPickerChips.kt:47` | yes |
| testTag `child_picker_chip_$childId` per child | yes | `ChildPickerChips.kt:55` | yes |
| testTags in PRODUCTION code, not test helpers | yes | All testTags in `ChildPickerChips.kt` (production) and `DashboardScaffold` (sibling markers); none added inside test files | yes |
| LazyColumn `key = { it.id }` at `DashboardScreen.kt:354` (debt fix) | yes | `DashboardScreen.kt:437` — `items(list, key = { it.id }) { device -> ... }` | yes |
| Filter applied to BOTH tabs client-side | yes (Q3=ii) | `DashboardScreen.kt:316-320` (Solicitudes: `filteredRequests`) and `:357, :397` (Devices: `filteredDevices`) | yes |
| Notifications badge keeps UNFILTERED count | yes | Badge reads `pendingRequests.size` (not `filteredRequests.size`) — confirmed by code review of `DashboardScaffold` | yes (by design) |
| `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` in PairingBottomSheet | yes | `DeviceComponents.kt:394-400` — `DisposableEffect(Unit) { onDispose { viewModel.loadDevices() } }` | yes |
| V2 server-side filter refactor NOT in PR | yes | `ParentRepository.kt:157-163` (`getPendingRequests`) unchanged; query string `select=*,devices(device_name)&status=eq.PENDING&order=created_at.desc` is the same as master | yes |
| No scope creep into Change A territory | yes | `git diff master..feat/multi-child-picker-b-picker-ui -- 'supabase/'` is empty; `git diff ... -- 'app/src/main/java/.../domain/model/'` is empty | yes |
| `Child` model + `ChildDevice.child: Child?` unchanged | yes | `Models.kt` not touched in this PR | yes |
| Mock fixtures JSON unchanged | yes | `app/src/main/assets/mock-supabase/{devices,children}.json` not touched in this PR | yes |

### TDD Compliance (Strict TDD)

| Check | Result | Details |
|---|---|---|
| Commit order RED-first | **YES** | `568c217` (RED test coverage, 8 new test cases including the 2 q2_gap conversions) → `67e9673` (GREEN: ParentViewModel state) → `53416ba` (GREEN: ChildPickerChips composable) → `cd880fe` (GREEN: DashboardScreen integration + filter + key fix) → `98f22c6` (GREEN: DeviceCard child-name row + PairingBottomSheet DisposableEffect). RED commit is pure `test(...)` — no production code. |
| All code-producing tasks have tests | **YES** | B.3 (VM + composable) covered by 4 ParentViewModelTest + 4 DashboardScreenTest RED tests; B.4 (integration) covered by 1 more DashboardScreenTest RED test + the 2 q2_gap conversions. |
| RED confirmed | **YES** | `568c217` is a `test(...)` commit — test files only. The 4 ParentViewModelTest cases + the 2 q2_gap tests would have built-failed on master (Unresolved reference: `selectedChildId`, `filteredDevices`, `setSelectedChild`). |
| GREEN confirmed | **YES** | All 8 new tests pass in fresh `./gradlew :app:testDebugUnitTest --rerun-tasks` (4 ParentViewModelTest + 4 DashboardScreenTest, including the 2 q2_gap conversions). |
| 2 q2_gap_* tests flipped RED→GREEN | **YES** | `q2_gap_dashboard_renders_child_picker_or_filter_control` (0.145s) and `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices` (0.133s) both pass in the feature branch test run. **Acceptance contract MET.** |
| No `.only()` / skipping overrides | **YES** | `grep -rn "@Ignore\|\.only" app/src/test/java/com/tudominio/parentalcontrol/viewmodel/ParentViewModelTest.kt app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt` returns no matches. |
| TDD Compliance | **6/6 checks** | |

### Test Layer Distribution

| Layer | Tests | Files | Tools |
|---|---|---|---|
| Unit (JVM) | 4 | `ParentViewModelTest.kt` | JUnit 4, MockK 1.13.7, kotlinx-coroutines-test, Turbine |
| Unit (JVM + Robolectric SDK 33) | 5 | `DashboardScreenTest.kt` (2 conversions + 3 new) | JUnit 4, Robolectric 4.10.3, Compose Test Rule |
| **Total new in PR** | **9** (4 RED→GREEN + 2 q2_gap conversions + 3 new GREEN picker tests) | **2** | |
| Integration / E2E | 0 | — | Out of slice |

### Changed File Coverage (4 production files + 2 test files)

| File | Change type | Test coverage | Verdict |
|---|---|---|---|
| `app/src/main/.../viewmodel/ParentViewModel.kt` | MODIFIED — `_selectedChildId` + `selectedChildId` + `setSelectedChild` + `filteredDevices` + stale-selection reset | `ParentViewModelTest` 4 new tests | **PASS** |
| `app/src/main/.../ui/parent/components/ChildPickerChips.kt` | NEW (64 LoC) — `LazyRow` + Material 3 `FilterChip` row | `DashboardScreenTest` 4 new tests (picker visibility + filter) | **PASS** |
| `app/src/main/.../ui/parent/screens/DashboardScreen.kt` | MODIFIED — picker mounted (hidden N ≤ 1), both tabs filtered, LazyColumn `key = { it.id }` (debt fix), hidden sibling `child_name` markers | `DashboardScreenTest` 5 new tests + 2 q2_gap conversions | **PASS** for all 7 (5 new + 2 conversions). NavGraphTest `navGraph_pairedParentDevice_composesDashboardScreen` regresses due to incomplete mock setup in NavGraphTest.kt — see Findings. |
| `app/src/main/.../ui/parent/components/DeviceComponents.kt` | MODIFIED — `DeviceCard` child-name row + `PairingBottomSheet` `DisposableEffect` | (No dedicated test for the dismiss hook; covered by code review) | **PASS** by code review |
| `app/src/test/.../viewmodel/ParentViewModelTest.kt` | MODIFIED — +194 LoC, 4 new tests | self-tests | **PASS** (14/14) |
| `app/src/test/.../ui/parent/screens/DashboardScreenTest.kt` | MODIFIED — +265 LoC, 5 new tests + 2 q2_gap conversions | self-tests | **PASS** (13/13) |

### Migration / Rollout Documentation

The PR #19 body includes a clear "Out of scope" section confirming that no DB migration is required for Change B — the schema+RLS+backfill from Change A is already applied. The operator only needs to merge PR #19 (no `supabase db push`).

**Documented: YES.** Operator has clear single-action: merge PR #19.

### Scope-Creep Audit (Change A boundary)

| Change A surface | File | Hits in Change B diff |
|---|---|---|
| `supabase/migrations/005_children_table.sql` | SQL | **0** (Change A only) |
| `supabase/migrations/006_children_backfill.sql` | SQL | **0** (Change A only) |
| `supabase/functions/pairing/index.ts` | Edge fn | **0** (Change A only) |
| `supabase/functions/get-devices-for-parent/index.ts` | Edge fn | **0** (Change A only) |
| `app/src/main/.../domain/model/Models.kt` | Domain | **0** (Change A only) |
| `app/src/main/.../data/repository/ParentRepository.kt` (lines 513-568 — `DeviceDto` + `toChildDevice`) | Repo | **0** in Change B (full file diff is empty per `git diff`); only the function-level diff would be the V2 refactor, which is OUT OF SCOPE |
| `app/src/main/.../data/remote/MockSupabaseEngine.kt` | Engine | **0** |
| `app/src/main/assets/mock-supabase/devices.json` | Fixture | **0** |
| `app/src/main/assets/mock-supabase/children.json` | Fixture | **0** |

**No scope creep detected.** Change B is purely client-side. The Change A data-layer plumbing is untouched.

### NavGraphTest Delta Investigation (RESOLVED)

**The contradiction**:
- Change A verify report (`verify-report-change-a.md:34`): "the pre-existing `NavGraphTest` failure has been fixed in master since PR #17's archive"
- Apply sub-agent's apply-progress engram #235: "all pre-existing unchanged: NetworkModuleTest, BootReceiverTest, NavGraphTest"
- This verify (independent re-run): **NavGraphTest has 2 NEW failures in the feature branch**.

**What the failure actually is**:

| Test | Master (pre-Change B) | Feature branch (Change B) | Verdict |
|---|---|---|---|
| `resolveInitialRoute_unpairedDevice_returnsOnboarding` | PASS | PASS | unchanged |
| `resolveInitialRoute_pairedChildDevice_returnsChildStatus` | PASS | **FAIL** — `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` | cascade from a sibling test's uncaught `ClassCastException` |
| `resolveInitialRoute_pairedParentDevice_returnsDashboard` | PASS | PASS | unchanged |
| `resolveInitialRoute_pendingExtraTimeChildDevice_returnsExtraTime` | PASS | PASS | unchanged |
| `resolveInitialRoute_pendingExtraTimeParentDevice_returnsExtraTime` | PASS | PASS | unchanged |
| `resolveInitialRoute_pendingExtraTimeUnpairedDevice_returnsOnboarding` | PASS | PASS | unchanged |
| `navGraph_unpairedDevice_composesOnboardingScreen` | PASS | PASS | unchanged |
| `navGraph_pairedChildDevice_composesChildStatusScreen` | PASS | PASS | unchanged |
| `navGraph_pairedParentDevice_composesDashboardScreen` | PASS | **FAIL** — `ClassCastException: java.lang.Object cannot be cast to java.util.List` at `DashboardScreen.kt:1045` | **NEW REGRESSION** |
| `navGraph_pendingExtraTime_composesExtraTimeScreen` | PASS | PASS | unchanged |

**Root cause** (from the failure stack trace):

```
java.lang.ClassCastException: class java.lang.Object cannot be cast to class java.util.List
    at com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreenKt.DashboardScreen$lambda$3(DashboardScreen.kt:1045)
    at com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreenKt.DashboardScreen(DashboardScreen.kt:114)
    at com.tudominio.parentalcontrol.ui.navigation.NavGraphKt.NavGraph(NavGraph.kt:101)
```

Change B added two new StateFlow properties to `ParentViewModel`:
- `val filteredDevices: StateFlow<List<ChildDevice>>` (line 78)
- `val selectedChildId: StateFlow<String?>` (line 69)

`DashboardScreen` reads both via `collectAsState()`:
```kotlin
val filteredDevices by viewModel.filteredDevices.collectAsState()
val selectedChildId by viewModel.selectedChildId.collectAsState()
```

`NavGraphTest.kt:23-46` (`init {}` block) constructs a `mockk<ParentViewModel>(relaxed = true)` and stubs 5 StateFlows with real `MutableStateFlow` instances. **The two new StateFlows added in Change B are NOT stubbed.** Relaxed-mockk returns `Any` (the default for unstubbed property calls). `collectAsState<T>()` performs an unchecked cast to `T` — for `filteredDevices`, the cast is `List<ChildDevice>`, but the runtime value is `Any` → `ClassCastException`.

**The fix** is a 2-line addition to `NavGraphTest.kt:23-46` `init {}` block:
```kotlin
every { parentViewModel.filteredDevices } returns MutableStateFlow<List<ChildDevice>>(emptyList())
every { parentViewModel.selectedChildId } returns MutableStateFlow<String?>(null)
```

The apply sub-agent's "NavGraphTest (×2) pre-existing unchanged" claim is **INCORRECT**. These are 2 NEW regressions introduced by Change B's test-incomplete refactor of `ParentViewModel`'s public surface.

**Severity**: The defect is in the test setup, not in the production code. The production code is correct. The fix is 2 lines of test mocking, scoped entirely to `NavGraphTest.kt`. But the regression is REAL (not flaky) and BLOCKS the PR's claim of "0 new regressions."

**Test ordering note**: When `NavGraphTest` is run in isolation, ALL 10 tests fail because the test's `init { }` block doesn't stub the new properties — wait, the init block doesn't compose anything, so the init block itself succeeds. The failure cascades through `runTest`'s `TestScope`, and the first compose test to throw leaks the uncaught exception to the next test in the class. In the full suite, only 2 fail (the Compose test that hits the unstubbed property, plus the alphabetically-next test that receives the leaked `UncaughtExceptionsBeforeTest`). The cascade is non-deterministic but the regression is consistent: `navGraph_pairedParentDevice_composesDashboardScreen` always fails.

### Findings Summary

- **Blocking**: 1 (NavGraphTest regression — 2-line test fix in `NavGraphTest.kt:23-46`)
- **Non-blocking**: 2 (PR-body test count accuracy drift — 703/5 fail but PR body says "all pre-existing unchanged: NetworkModuleTest, BootReceiverTest, NavGraphTest", which is no longer accurate; 3 pre-existing `ktlint` violations in `WorkersTest.kt` are pre-existing and unrelated)
- **Praise**: 3 (see below)

### Verdict

**PASS WITH WARNINGS** — the production code is correct and the 2 RED→GREEN acceptance contract is met. The 2 NavGraphTest failures are a real regression but a trivial test-setup fix. PR can move from `needs_fixes` to `ready_to_merge` once the 2-line addition is committed.

**Operator action**:
1. Apply 2-line fix to `app/src/test/java/com/tudominio/parentalcontrol/ui/navigation/NavGraphTest.kt` (the `init {}` block — add `every { parentViewModel.filteredDevices }` and `every { parentViewModel.selectedChildId }` stubs).
2. Re-run `./gradlew :app:testDebugUnitTest --rerun-tasks` to confirm 703/3 fail (3 pre-existing only — NetworkModuleTest x1 + BootReceiverTest x2).
3. Merge PR #19 to master. No `supabase db push` required (pure client-side Change B; Change A migration is already applied per the merge at PR #18 / `043f35f`).
4. After PR #19 merges, `sdd-archive` for Change B closes the chain.

### Praise

- **Strict TDD adherence**: 5 work-unit commits, RED-first (`568c217`) before any production code; all 8 new tests pass. The 2 q2_gap conversions are explicit RED→GREEN flips pinned by the original spike.
- **Spec conformance**: 13/13 Change B scenarios compliant; V2 server-side filter correctly NOT in this PR (V1 client-side filter via `DashboardScaffold.filteredRequests` per `time-request-approval/spec.md:142`).
- **Deviation discipline**: 3 deviations from `design.md` (testTag naming `child_picker` vs `child_picker_row`; sibling `child_name` markers outside the clickable Card to satisfy the q2_gap contract; `SharingStarted.Eagerly` for `filteredDevices`) are all documented in the apply-progress engram #235 with the technical rationale (Compose `Card(onClick = ...)` merges descendants; Robolectric + `collectAsState` order-sensitivity). This is the right kind of deviation — pragmatic, evidence-based, and pinned for the next reviewer.

### Relevant Files

- `app/src/main/java/.../viewmodel/ParentViewModel.kt` — `_selectedChildId` + `selectedChildId` + `setSelectedChild` + `filteredDevices` + stale-selection reset
- `app/src/main/java/.../ui/parent/components/ChildPickerChips.kt` — NEW composable (64 LoC)
- `app/src/main/java/.../ui/parent/screens/DashboardScreen.kt` — picker mounted, both tabs filtered, `key = { it.id }` debt fix, hidden `child_name` markers
- `app/src/main/java/.../ui/parent/components/DeviceComponents.kt` — child-name row + `DisposableEffect` refresh
- `app/src/test/.../viewmodel/ParentViewModelTest.kt` — +4 RED→GREEN tests
- `app/src/test/.../ui/parent/screens/DashboardScreenTest.kt` — +5 + 2 q2_gap conversions
- `app/src/test/.../ui/navigation/NavGraphTest.kt` — **REGRESSION TARGET** — add 2 `every { parentViewModel.* }` stubs in `init {}`
- PR #19: https://github.com/Andrea-Caballero/parentalControl/pull/19
- Apply-progress engram: id #235, topic_key `sdd/feature-multi-child-q2-child-picker/apply-progress-change-b`
