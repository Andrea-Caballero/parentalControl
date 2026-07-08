# Tasks: feat-parent-behavioral-event-log

> Chained-PR SDD (NOT mini-SDD lite). Two PRs stacked-to-main: **PR A** (data layer, ≈180 LoC, ✅ MERGED as PR #25 at commit `6e6557c`) ships the entity column + DAO Flow + repository + mock GET + 4 GREEN repo tests. **PR B** (UI layer, ≈270 LoC production + ≈180 LoC Phase 0 tests, ⏳ active slice) ships the ViewModel + Screen + nav entry + 8 RED→GREEN screen tests. Strict TDD per `openspec/config.yaml:3` — Phase 0 of PR B writes the 8 RED tests **first** as failing tests on master @ `475ca73`; Phase 3 turns them GREEN. Mirrors `archive/2026-07-06-feat-multi-child-picker/tasks.md` (chained A/B block format) + `archive/2026-07-07-fix-rename-child-dialog/tasks.md:33,53-57` (Phase 0 RED-write precedent). No separate `design.md` — `proposal.md` is the design-of-record. Decisions: engram #302 — Q1=c carry `parent_id`, Q2=h hybrid cache, Q3=c card-as-terminal, Q4=f range follow-up, Q5=a chained A/B stacked-to-main.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | PR A ≈ 180 LoC ✅ shipped; PR B ≈ 285 LoC production + ≈180 LoC tests = ≈465 LoC |
| 400-line budget risk | Low (PR A) → Medium (PR B with Phase 0; concise test code keeps it under 500) |
| Chained PRs recommended | Yes (already chosen: stacked-to-main) |
| Suggested split | PR A (data layer) → PR B (UI layer + Phase 0 RED tests) |
| Delivery strategy | ask-always |
| Chain strategy | stacked-to-main |

Decision needed before apply: No
Chained PRs recommended: Yes
Chain strategy: stacked-to-main
400-line budget risk: Low (PR A) / Medium (PR B)

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Schema column + DAO Flow + repository + mock GET + fixture + 4 RED→GREEN repo tests | PR A ✅ | base = `master`; data-layer only, no UX change; merged at `6e6557c` |
| 2 | 8 RED tests + ViewModel + Screen + nav entry + DashboardScreen wiring | PR B ⏳ | base = `master` (PR A merged); Phase 0 RED-write is BLOCKING — the 8 tests must fail on master before Phase 3 lands |

---

## PR A — Data layer (✅ MERGED as PR #25 at `6e6557c`)

Historical record. Already shipped — these tasks are reference-only for the PR B context.

### Phase A.1 — RED repo tests (✅ committed)

- [x] **A.1.1** — RED `empty_events_response_yields_zero_dao_rows` — `BehavioralEventsRepositoryTest.kt:139-151`. Empty `[]` response → DAO row count 0.
- [x] **A.1.2** — RED `single_event_response_yields_one_dao_row` — `:155-167`. Single-event JSON → 1 DAO row with `parent_id = "parent-demo"`.
- [x] **A.1.3** — RED `multiple_events_response_yields_three_dao_rows_in_desc_order` — `:171-185`. 3-row JSON → 3 DAO rows ordered newest-first.
- [x] **A.1.4** — RED `refresh_idempotency_keeps_row_count_stable_across_replays` — `:193-207`. Two `refresh()` calls against the same payload → 3 rows (no duplicate inflation).
- [x] **A.1.5** — RED-commit gate (commit `85fb9b3` `feat(data): BehavioralEventsRepository + MockSupabaseEngine GET + behavioral_events.json fixture`).

### Phase A.2 — GREEN data layer (✅ committed)

- [x] **A.2.1** — Add nullable `parent_id` column to `BehavioralEventEntity` (commit `9d8b1ff` `feat(data): add parent_id column + Room v6→v7 migration + DAO flowByParent/upsertAll`).
- [x] **A.2.2** — `ParentalDatabase.MIGRATION_6_7` — `ALTER TABLE behavioral_events ADD COLUMN parent_id TEXT`.
- [x] **A.2.3** — `BehavioralEventDao.flowByParent(parentId)` + `upsertAll(events)`.
- [x] **A.2.4** — `BehavioralEventsRepository` (NEW) — `observe(parentId)` + `refresh(parentId)`. Typealias `BehaviorLogRepository` for test-side seam.
- [x] **A.2.5** — `MockSupabaseEngine` GET branch for `/rest/v1/behavioral_events` + `behavioral_events.json` 5-event fixture.

### Phase A.3 — Build verifier + PR A merge (✅ completed)

- [x] **A.3.1** — `./gradlew :app:testDebugUnitTest` — 4 GREEN repo tests; 83 pre-existing failures unchanged.
- [x] **A.3.2** — `./gradlew :app:ktlintCheck :app:detekt` — clean after follow-up commit `475ca73`.
- [x] **A.3.3** — PR #25 merged at `6e6557c`.

### Out of scope (PR A — frozen)

- ViewModel / screen / nav entry — all PR B.
- AnalyticsManager writer extension to populate `parent_id` at write time — V2 follow-up; pre-migration rows stay invisible.
- Pagination beyond `limit=50` — Q4=f follow-up.

---

## PR B — UI layer (⏳ active slice, ≈465 LoC)

### Phase B.0 — RED (NEW, BLOCKING): write 8 failing tests for BehaviorLogScreen

The 8 tests below are the **PR B acceptance contract**. They MUST exist on disk and MUST fail (compile-time `Unresolved reference: BehaviorLogScreen / BehaviorLogViewModel / BehaviorLogUiState`) on master @ `475ca73` BEFORE Phase B.3 lands any production code. The previous explore sub-agent **claimed** these tests existed at `BehaviorLogScreenTest.kt:108-303` — verified they do NOT exist on disk (per engram #307). Phase B.0 is the corrected write of those 8 tests as the first PR B commit.

Run with `./gradlew :app:testDebugUnitTest --tests "*BehaviorLogScreenTest*" --rerun-tasks`. All 8 tests MUST fail at compile time on master.

**File to create**: `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/BehaviorLogScreenTest.kt`

**Imports/conventions to mirror** — the file follows the same `RenameChildDialogTest.kt:1-80` + `DashboardScreenTest.kt:1-120` pattern: `@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [33])`, `@OptIn(ExperimentalCoroutinesApi::class)`, `@get:Rule val composeTestRule = createComposeRule()`, `Dispatchers.setMain(UnconfinedTestDispatcher())` in `@Before`, `Dispatchers.resetMain()` in `@After`. `BehaviorLogScreen` is constructed directly with the VM (which itself takes a mock `BehavioralEventsRepository`) — mirroring `DeviceDetailScreenTest.kt:43-60` + a Robolectric-seeded `BehavioralEventsRepository` (Room in-memory + mock client, per `BehavioralEventsRepositoryTest.kt:62-104`).

**snake_case testTags** (locked here so Phase B.3 lands matching selectors):

- `behavior_log_lazy_column` — wrapping `LazyColumn`.
- `behavior_log_event_card_${event_type}` — per-event Card (one per `event_type` value present in the rendered list).
- `behavior_log_event_timestamp` — formatted `created_at` text inside each Card.
- `behavior_log_event_child_name` — child first name inside each Card.
- `behavior_log_pull_refresh` — `PullRefresh` indicator container.
- `behavior_log_empty_state` — empty-state Text node.
- `behavior_log_error_banner` — error banner Text node.

**Spanish UI copy** (matching project convention — Dashboard uses "Dispositivos", "Solicitudes", "Sin dispositivos", etc.):
- Empty state title: "Sin eventos"
- Empty state subtitle: "Los eventos aparecerán aquí cuando los dispositivos los reporten"
- Error banner title: "Error cargando eventos"
- Error banner retry CTA: "Reintentar"
- Top bar title: "Registro de eventos"

**Test list** (8 cases):

- [x] **B.0.1 — RED (NEW) `behavior_log_renders_events_in_reverse_chronological_order`** at `BehaviorLogScreenTest.kt`. Compose the screen with 3 mock events ordered oldest→middle→newest in the test list; assert the rendered Card order in `behavior_log_lazy_column` is newest→middle→oldest. Use `composeTestRule.onAllNodesWithTag("behavior_log_lazy_column").fetchSemanticsNodes()` + child tag enumeration OR a `LazyColumn` `key` assertion. **Expected TODAY**: FAILS at compile time — `Unresolved reference: BehaviorLogScreen`.

- [x] **B.0.2 — RED (NEW) `behavior_log_per_child_filter_chip_narrows_visible_events`** at `BehaviorLogScreenTest.kt`. Compose the screen with 4 mock events split across 2 children (Lucas: 2 events, Sofía: 2 events). Tap `child_picker_chip_child-lucas` (from `ChildPickerChips`). Assert `behavior_log_lazy_column` contains exactly 2 Cards, both tagged for Lucas's device. **Expected TODAY**: FAILS at compile time — same reason as B.0.1.

- [x] **B.0.3 — RED (NEW) `behavior_log_pull_to_refresh_triggers_repository_refresh`** at `BehaviorLogScreenTest.kt`. Compose the screen with an empty `BehavioralEventsRepository` state. Trigger `PullRefresh` on `behavior_log_pull_refresh` via `performTouchInput { swipeDown() }`. Assert `repository.refresh(parentId)` was called exactly once (capture with a `MutableList<Unit>` on a stubbed `BehavioralEventsRepository.refresh(...)`). **Expected TODAY**: FAILS at compile time.

- [x] **B.0.4 — RED (NEW) `behavior_log_empty_state_shows_placeholder_when_no_events`** at `BehaviorLogScreenTest.kt`. Compose the screen with empty events list + a stubbed `repository.refresh()` returning `Result.success(Unit)`. Assert `behavior_log_empty_state` is displayed with the Spanish copy "Sin eventos" + "Los eventos aparecerán aquí…". **Expected TODAY**: FAILS at compile time.

- [x] **B.0.5 — RED (NEW) `behavior_log_loading_state_shows_spinner_during_refresh`** at `BehaviorLogScreenTest.kt`. Compose the screen with `repository.refresh()` set to suspend forever (a `CompletableDeferred` that never completes). Assert a `CircularProgressIndicator` is displayed while the refresh is in-flight. Use `composeTestRule.onNodeWithTag("behavior_log_pull_refresh").onChild()` + `assertExists()` OR a `hasProgressBarRangeInfo` semantics matcher. **Expected TODAY**: FAILS at compile time.

- [x] **B.0.6 — RED (NEW) `behavior_log_error_state_shows_error_banner_on_refresh_failure`** at `BehaviorLogScreenTest.kt`. Compose the screen with `repository.refresh()` returning `Result.failure(DeviceListError.Transient("HTTP 500"))`. Assert `behavior_log_error_banner` is displayed with "Error cargando eventos" + the retry CTA. **Expected TODAY**: FAILS at compile time.

- [x] **B.0.7 — RED (NEW) `behavior_log_card_displays_icon_eventType_timestamp_and_childName`** at `BehaviorLogScreenTest.kt`. Compose the screen with 1 mock event of `event_type = "limit_reached"`, `created_at = "2026-07-08T10:00:00Z"`, `device_id` → child "Lucas". Assert `behavior_log_event_card_limit_reached` exists + contains an `Icon` + the text "limit_reached" + a formatted timestamp (e.g. "10:00") + "Lucas" at `behavior_log_event_child_name`. **Expected TODAY**: FAILS at compile time.

- [x] **B.0.8 — RED (NEW) `behavior_log_all_events_chip_taps_clear_filter`** at `BehaviorLogScreenTest.kt`. Compose with 4 events across 2 children. Tap `child_picker_chip_child-lucas`. Tap `child_picker_chip_all`. Assert the rendered Card count is back to 4 (all events visible). **Expected TODAY**: FAILS at compile time.

- [x] **B.0.9 — RED-commit gate.** Run `./gradlew :app:testDebugUnitTest --tests "*BehaviorLogScreenTest*" --rerun-tasks`. All 8 tests MUST FAIL (compile errors on `BehaviorLogScreen` / `BehaviorLogViewModel` / `BehaviorLogUiState`). Do NOT proceed to Phase B.3 until the failures are confirmed on master. Capture the failure log to the PR description.

  **Commit** (Phase B.0):
  ```
  test(behavior-log): add RED coverage for BehaviorLogScreen (8 cases — Phase 0 of PR B)
  ```

### Phase B.1 — RED gate (no commits)

- [x] **B.1.1** — Re-run `./gradlew :app:testDebugUnitTest --tests "*BehaviorLogScreenTest*" --rerun-tasks`. Confirm all 8 tests still RED on master @ `475ca73` after the B.0 commit. Acceptance: 8 failures, all `Unresolved reference: BehaviorLogScreen` (or equivalent compile error).
- [x] **B.1.2** — Re-run full `./gradlew :app:testDebugUnitTest`. Confirm 83 pre-existing failures unchanged (per `archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/tasks.md:115` precedent) — Phase B.0 must not introduce new regressions.

### Phase B.2 — Investigation (no commits)

- [x] **B.2.1** — Read `ParentViewModel.kt:62-81` (`_selectedChildId` + `filteredDevices`) + `:452-454` (`setSelectedChild`). The PR B `BehaviorLogViewModel` mirrors this pattern but feeds from `BehavioralEventsRepository.observe` instead of `_devices`. Per the prompt, the simplest seam is to inject `ParentViewModel` into `BehaviorLogViewModel` and re-expose `selectedChildId` — but apply phase picks the cleanest wiring (alternatives: shared `@Singleton` selection-state holder; `@AssistedInject`; manual constructor). Document the chosen approach in the apply-phase engram.
- [x] **B.2.2** — Read `BehavioralEventsRepository.kt:47-48` (`observe`) + `:60-96` (`refresh`). The PR B VM's `events` StateFlow wraps `observe(parentId)` where `parentId` is sourced from `authManager.getParentId()` (mirror `ParentRepository.getDevices` pattern).
- [x] **B.2.3** — Read `DashboardScreen.kt:107` (`navTarget: NavTarget`) + `:112-156` (when branches). Add a new `NavTarget.BehaviorLog` variant + a `when` branch rendering `BehaviorLogScreen`. Top-bar entry point: extend the `TopAppBar` `actions` row at `:221-237` with an `IconButton(Icons.Default.History)` that sets `navTarget = NavTarget.BehaviorLog`.
- [x] **B.2.4** — Read `ChildPickerChips.kt:60-66` ("Todos" chip) + `:68-79` (per-child chips). The PR B screen embeds `ChildPickerChips(children = distinctChildrenFromEvents, selected = selectedChildId.value, onSelect = vm::selectChild)` — the children list is derived from `events.value.map { it.device_id }.distinct()` joined against the cached `ParentViewModel.devices` to get `Child` records. Apply phase may add a `BehaviorLogViewModel.distinctChildren: StateFlow<List<Child>>` derived flow that joins `events + ParentViewModel.devices`.
- [x] **B.2.5** — Read `AppNavHost.kt:47-84` + `NavGraph.kt` (the parent nav graph). Confirm whether `behavior_log` should be a new `NavGraph` composable destination OR continue using the in-scaffold `navTarget` state machine. The dashboard precedent (`DashboardScreen.kt:107`) already uses `navTarget` for `DeviceDetail` + `Apps` — simplest path is to extend `NavTarget` with `BehaviorLog` and keep the screen inside the dashboard scope. Apply phase picks one.
- [x] **B.2.6** — Read `RenameChildDialog.kt` for the Material 3 stateless-composable + `DialogProperties` precedent. Confirm Material 3 `PullRefresh` (`androidx.compose.material3.pulltorefresh.PullRefreshIndicator` + `rememberPullRefreshState`) is available in the Compose BOM (`config.yaml:7` = `2024.10.01` — `PullRefresh` is stable in M3 1.3+).

### Phase B.3 — GREEN (≈285 LoC production, in 2-3 commits)

#### Commit 1: ViewModel + Screen skeleton (≈255 LoC)

- [x] **B.3.1 — Create `app/src/main/java/com/tudominio/parentalcontrol/viewmodel/BehaviorLogViewModel.kt` (≈80 LoC).**
  ```kotlin
  @HiltViewModel
  class BehaviorLogViewModel @Inject constructor(
      private val repository: BehavioralEventsRepository,
      private val authManager: DeviceAuthManager,
      parentViewModel: ParentViewModel  // for selectedChildId mirror
  ) : ViewModel() {
      private val parentId: String = authManager.getParentId() ?: ""

      val events: StateFlow<List<BehavioralEventEntity>> =
          repository.observe(parentId)
              .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

      val selectedChildId: StateFlow<String?> = parentViewModel.selectedChildId

      val filteredEvents: StateFlow<List<BehavioralEventEntity>> =
          combine(events, selectedChildId) { list, id ->
              if (id == null) list else list.filter { it.deviceId == id }
          }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

      private val _isLoading = MutableStateFlow(false)
      val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

      private val _error = MutableStateFlow<String?>(null)
      val error: StateFlow<String?> = _error.asStateFlow()

      init {
          refresh()
      }

      fun refresh() {
          viewModelScope.launch {
              _isLoading.value = true
              _error.value = null
              val result = repository.refresh(parentId)
              if (result.isFailure) {
                  _error.value = "Error cargando eventos: " +
                      (result.exceptionOrNull()?.message ?: "Unknown error")
              }
              _isLoading.value = false
          }
      }

      fun selectChild(id: String?) {
          // delegate to ParentViewModel so the chip selection persists
          // across navigation between dashboard and log screen
          parentViewModel.setSelectedChild(id)
      }

      fun clearError() { _error.value = null }
  }
  ```
  Mirrors `ParentViewModel.kt:62-81` (selectedChildId) + `:91-97` (loading + error StateFlows). The `refresh()` init call + the `clearError()` symmetry with `ParentViewModel.clearError()` are deliberate — keeps the screen's error lifecycle self-contained.

- [x] **B.3.2 — Create `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/BehaviorLogScreen.kt` (≈175 LoC).**
  ```kotlin
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun BehaviorLogScreen(
      viewModel: BehaviorLogViewModel,
      onNavigateBack: () -> Unit
  ) {
      val events by viewModel.filteredEvents.collectAsState()
      val isLoading by viewModel.isLoading.collectAsState()
      val error by viewModel.error.collectAsState()
      val selectedChildId by viewModel.selectedChildId.collectAsState()
      val pullRefreshState = rememberPullRefreshState(
          refreshing = isLoading,
          onRefresh = { viewModel.refresh() }
      )

      Scaffold(
          topBar = {
              TopAppBar(
                  title = { Text("Registro de eventos") },
                  navigationIcon = {
                      IconButton(onClick = onNavigateBack) {
                          Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                      }
                  }
              )
          },
          modifier = Modifier.nestedScroll(pullRefreshState.nestedScrollConnection)
      ) { padding ->
          Column(modifier = Modifier.fillMaxSize().padding(padding)) {
              // ChildPickerChips (top row, hidden when ≤1 child — mirrors DashboardScreen:290)
              if (events.map { it.deviceId }.distinct().size >= 2) {
                  ChildPickerChips(
                      children = deriveChildrenFromEvents(events),
                      selected = selectedChildId,
                      onSelect = viewModel::selectChild
                  )
              }

              PullRefreshIndicator(
                  refreshing = isLoading,
                  state = pullRefreshState,
                  modifier = Modifier.align(Alignment.CenterHorizontally).testTag("behavior_log_pull_refresh")
              )

              Box(modifier = Modifier.fillMaxSize()) {
                  when {
                      error != null -> ErrorBanner(
                          message = error!!,
                          onRetry = { viewModel.refresh() },
                          modifier = Modifier.testTag("behavior_log_error_banner")
                      )
                      events.isEmpty() && !isLoading -> EmptyState(
                          icon = Icons.Default.History,
                          title = "Sin eventos",
                          subtitle = "Los eventos aparecerán aquí cuando los dispositivos los reporten",
                          modifier = Modifier.testTag("behavior_log_empty_state")
                      )
                      else -> LazyColumn(
                          modifier = Modifier.fillMaxSize().testTag("behavior_log_lazy_column"),
                          contentPadding = PaddingValues(16.dp),
                          verticalArrangement = Arrangement.spacedBy(12.dp)
                      ) {
                          items(events, key = { it.id }) { event ->
                              EventCard(
                                  event = event,
                                  childName = deriveChildName(event),
                                  modifier = Modifier.testTag("behavior_log_event_card_${event.event_type}")
                              )
                          }
                      }
                  }
              }
          }
      }
  }
  ```
  Card-as-terminal per Q3=c: each event renders as a Material 3 `Card` with icon (mapped per `event_type` via `EventTypeIcons`) + `event_type` label (uppercase) + formatted timestamp at `behavior_log_event_timestamp` + child name at `behavior_log_event_child_name`. **No `onClick`** — the card has no tap action. Spanish UI copy throughout.

#### Commit 2: DashboardScreen + AppNavHost wiring (≈30 LoC)

- [x] **B.3.3 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt` (~20 LoC).**
  - Add `NavTarget.BehaviorLog` variant (alongside existing `DeviceDetail`, `Apps` at `:165-167`).
  - Add a `when` branch at `:141-155` rendering `BehaviorLogScreen(viewModel = hiltViewModel(), onNavigateBack = { navTarget = NavTarget.Dashboard })`.
  - Add an `IconButton(Icons.Default.History)` to the `TopAppBar` `actions` row at `:221-237` that sets `navTarget = NavTarget.BehaviorLog`. `contentDescription = "Registro de eventos"`. `testTag = "behavior_log_top_bar_entry"`.
  - Inject `BehaviorLogViewModel` via `hiltViewModel<BehaviorLogViewModel>()` at the top of `DashboardScreen` and pass through to the new `when` branch (mirrors how `appsVm` is handled at `:109-110`).

- [x] **B.3.4 — Modify `app/src/main/java/com/tudominio/parentalcontrol/ui/navigation/AppNavHost.kt` (or `NavGraph.kt`) (~10 LoC).** Per B.2.5 — simplest path is to keep `behavior_log` inside `DashboardScreen`'s `navTarget` state machine (no new top-level route needed). If the apply phase decides to add a real nav-graph route instead, register `composable("behavior_log") { BehaviorLogScreen(...) }` and pass `onNavigateToBehaviorLog = { navController.navigate("behavior_log") }` to `DashboardScreen`. Document the choice in the apply-phase engram.

- [x] **B.3.5 — GREEN confirmation.** `./gradlew :app:testDebugUnitTest --tests "*BehaviorLogScreenTest*" --rerun-tasks` — all 8 tests GREEN. `./gradlew :app:testDebugUnitTest` — full suite, 83 pre-existing failures unchanged, 0 new regressions.

  **Commits** (Phase B.3):
  ```
  feat(behavior-log): add BehaviorLogViewModel + BehaviorLogScreen with Material 3 + PullRefresh
  feat(behavior-log): wire dashboard entry + nav graph for BehaviorLogScreen
  ```

### Phase B.4 — Build verifier

- [x] **B.4.1** — `./gradlew :app:assembleDebug` — green, no new warnings on the 2 NEW JVM files + 2 modified files. The 5 touched files: `BehaviorLogViewModel.kt` (NEW), `BehaviorLogScreen.kt` (NEW), `DashboardScreen.kt` (modified), `AppNavHost.kt` or `NavGraph.kt` (modified), `BehaviorLogScreenTest.kt` (NEW from Phase B.0).
- [x] **B.4.2** — `./gradlew :app:testDebugUnitTest` — full suite GREEN. 8 NEW GREEN tests + 4 pre-existing GREEN repo tests + 83 pre-existing failures unchanged.
- [x] **B.4.3** — `./gradlew :app:ktlintCheck :app:detekt` — clean on new + modified files. Apply phase may need a ktlint follow-up commit if 2-line labels collide with the 120-char limit.
- [x] **B.4.4** — `grep -rn "BehaviorLogScreen\|BehaviorLogViewModel" app/src/main app/src/test` — expected hits only in `BehaviorLogScreen.kt` + `BehaviorLogViewModel.kt` + `BehaviorLogScreenTest.kt` + `DashboardScreen.kt` (the wiring). No other call sites.
- [ ] **B.4.5** — Manual smoke (out of agent reach — operator step): log in as parent, navigate dashboard → "Registro de eventos" → see 5 mock events reverse-chrono, tap "Lucas" chip → 3 events remain, pull-to-refresh → loading spinner → events re-render.

### Out of scope (PR B — frozen)

- AnalyticsManager writer extension to populate `parent_id` at write time — V2 follow-up. Pre-migration rows stay invisible until re-written.
- Pagination beyond `limit=50` — Q4=f follow-up. V1 hardcodes the limit; `BehaviorLogScreen` accepts whatever the DAO emits.
- Event detail navigation — Q3=c card-as-terminal means each event Card has no `onClick`. A future V2 could add a detail screen.
- Realtime push of new events — V2 follow-up. V1 polls via `PullRefresh`.
- Cross-parent aggregation — RLS scopes the GET. No parent selector.
- Range / date filters — V2 follow-up.
- Event-type icon set (`EventTypeIcons.kt`) — the apply phase adds a small `when (event.event_type)` mapping inside `BehaviorLogScreen.kt`; a standalone file is over-decomposition for V1.

---

## Notes

- The Q2 chain precedent (`archive/2026-07-06-feat-multi-child-picker/tasks.md:30-118`) is the structural template for the chained-PR block format.
- The Phase 0 RED-write precedent (`archive/2026-07-07-fix-rename-child-dialog/tasks.md:33,53-57`) is the structural template for writing RED tests inside the apply phase when the orchestrator's prompt references tests that don't exist on disk yet.
- PR B's ≈465 LoC is at the edge of the 400-line review budget. Concise Phase 0 tests (≤22 LoC each via shared `setUp` + helper for `mockEvents()` + `behaviorLogRepositoryHolder`) keep the total under 500. If a single test feels verbose (>30 LoC), split the assertion into a helper function in the same file.
- 83 pre-existing failures (per engram #307) MUST remain unchanged after PR B lands — strict no-regression contract per `archive/2026-07-06-feat-multi-child-picker/tasks.md:147` precedent.