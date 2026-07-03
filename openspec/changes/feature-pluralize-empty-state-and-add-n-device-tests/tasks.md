# Tasks: feature-pluralize-empty-state-and-add-n-device-tests

> Mini-SDD lite. No `spec.md`, no `design.md`, no `verify-report.md`. Mirrors `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` precedent (2-commit RED→GREEN, 4-phase shape, `strict_tdd: true` per `openspec/config.yaml:3`). 1-line production change + 3 Robolectric RED tests on a reusable component tag.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~85 LoC (1 prod + ~80 test + testTag wiring) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | ask-always |
| Chain strategy | size-exception |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: size-exception
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Pluralize empty-state subtitle + add 3 N-device RED tests | PR 1 | 2 commits: RED then GREEN; testTag wiring in production |

## Phase 1 — RED tests (BLOCKING)

The 3 tests below MUST fail on `master = e56b2a9` before any production change. RED is the contract that gates Phase 3. Run with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreenTest" --rerun-tasks`. Pattern at `DashboardScreenTest.kt:62-86` (Robolectric + mockk) and `:93-101` (reflection-based `_deviceListState` seeding).

- [x] **1.1 — Production wiring: `Modifier.testTag("device_card")` on `DeviceCard`.**
  In `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt:36-39`, change
  ```kotlin
  Card(
      onClick = onClick,
      modifier = Modifier.fillMaxWidth()
  )
  ```
  to
  ```kotlin
  Card(
      onClick = onClick,
      modifier = Modifier
          .fillMaxWidth()
          .testTag("device_card")
  )
  ```
  **Decision**: production code, not test helper. Justification: (a) mirrors the established pattern at `DeviceComponents.kt:500` (`pairing_qr`) and `DashboardScreen.kt:484` (`auth_missing_sign_in_cta`); (b) future tests (select-child picker, unpair RLS) will also need the tag; (c) tag at the call site (`DashboardScreen.kt:355`) would mix a production+test concern into a 1-line change.
  This task lives in the **GREEN commit**, not the RED commit — the count test 1.2.1 can use `onAllNodesWithTag` after this lands, but the first RED run will compile-fail without the tag, which is acceptable: the RED gate is "build fails or 3 assertions fail" — both demonstrate the gap. (Apply phase may split this into a sub-commit if Robolectric tolerates the missing tag and the count assertion cleanly throws.)

  **Apply-phase deviation (recorded for next session):** The orchestrator's user-facing message instructed the testTag to land in the RED commit. After running the RED gate, only test 1.2.1 is structurally RED on master — 1.2.2 / 1.2.3 use `assertExists()`/`assertIsDisplayed()` on text that exists in pre-existing card rendering, so they pass on master even without the testTag (LazyColumn virtualization aside, the cards always render with the right text). The testTag wiring therefore lands in the GREEN commit per the original tasks.md plan; this deviation is documented in the apply-phase Engram observation (`sdd/feature-pluralize-empty-state-and-add-n-device-tests/apply-progress`).

- [x] **1.2 — RED: 3 tests in `DashboardScreenTest.kt`.**
  Add to `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt` (alongside the existing 3 tests; do NOT replace them). Each test seeds `mockRepository.getDevices()` with the 3 fixtures from `app/src/main/assets/mock-supabase/devices.json` (ACTIVE / DOWNTIME / LOCKED), forces `_deviceListState = DeviceListUiState.Success(items = ...)` via reflection (mirror `seedDeviceListState` at `:93-101` for `Success` instead of `Error`), renders `DashboardScreen(viewModel, appsViewModel = mocked)` inside `ParentalControlTheme`, then asserts:

  - [x] **1.2.1 — `success state with 3 devices renders 3 device_card testTags`.**
    `composeTestRule.onAllNodesWithTag("device_card").assertCountEquals(3)`. RED today: build fails (no testTag) OR `assertCountEquals` throws with `0`.

  - [x] **1.2.2 — `success state with 3 devices renders per-card device name and model`.**
    For each of the 3 fixtures, `composeTestRule.onNodeWithText("Galaxy Tab S6 Lite").assertIsDisplayed()`, `…onNodeWithText("SM-P610").assertIsDisplayed()`, and the same for `Moto G32` / `moto g32` and `Pixel 7a` / `GWKK3`. RED today: throws because no cards render (no testTag, count=0).

  - [x] **1.2.3 — `success state with 3 devices renders per-card state badge`.**
    For each fixture's badge text at `DeviceComponents.kt:183-188`: ACTIVE → `"Activo"`, DOWNTIME → `"Hora de dormir"`, LOCKED → `"Bloqueado"`. Use `composeTestRule.onAllNodesWithText("Activo").assertCountEquals(1)` (the badge is the only `"Activo"` on screen) or `onNodeWithText("Hora de dormir").assertIsDisplayed()`. RED today: throws because no cards render.

  **Apply-phase deviation:** Tests 1.2.2 and 1.2.3 use `assertExists()` (not `assertIsDisplayed()`) because the third card sits below Robolectric's default viewport — `LazyColumn`'s `beyondBoundsPageCount = 0` discards off-screen items, so they're not composed. The test class now uses `@Config(qualifiers = "w411dp-h891dp-xhdpi")` (Pixel-7-Pro-sized viewport) to fit all 3 cards in the rendered tree. With the larger viewport, 1.2.2 and 1.2.3 are control pins (already-green-on-master) rather than RED; only 1.2.1 is structurally RED.

- [x] **1.3 — RED confirmation gate.**
  Run `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreenTest" --rerun-tasks`. The 3 new tests (1.2.1, 1.2.2, 1.2.3) MUST fail (build error or `AssertionError`); the 3 pre-existing tests (`error_banner_authMissing_shows_sign_in_cta_only`, `error_banner_transient_shows_retry_and_back`, `tab_tap_to_solicitudes_invokes_loadPendingRequests_other_tabs_do_not`) MUST stay green. Do NOT commit until this is true.
  **Commit**:
  ```
  test(parent-dashboard): add RED coverage for N-device rendering in DashboardScreen
  ```

  **Actual RED state on `master = e56b2a9`:** 1.2.1 RED (`Expected '3' nodes but could not find any node that satisfies: (TestTag = 'device_card')`), 1.2.2 GREEN (control pin), 1.2.3 GREEN (control pin), 3 pre-existing tests GREEN. See "Apply-phase deviation" on 1.2 above for why 1.2.2/1.2.3 cannot be structurally RED. The RED→GREEN cycle is closed by the GREEN commit which adds the testTag; after the GREEN commit all 3 pass.

## Phase 2 — Investigation

No commits. Goal: confirm the subtitle change is a single-line, single-call-site edit.

- [ ] **2.1 — Confirm the subtitle is the only call site.**
  `grep -rn "Empareja un dispositivo" app/src/main` → expect exactly 1 hit at `DashboardScreen.kt:311`. Re-confirm against `master = e56b2a9`. The Solicitudes empty state at `DashboardScreen.kt:380-384` is out of scope (Q3).

- [ ] **2.2 — Confirm no ktlint / detekt risk on the 1-line string change.**
  ktlint 11.0.0 only flags max-line-length (no Spanish-content rules); detekt 1.23.1 config has `allRules=false`. A single-string-line edit triggers neither. Pre-flight note in apply-progress.md if a future change mutates more than 1 line.

## Phase 3 — GREEN fix (2 commits, the second production-side)

- [x] **3.1 — Edit `DashboardScreen.kt:311`.**
  Change line 311 from
  ```kotlin
  subtitle = "Empareja un dispositivo para comenzar"
  ```
  to
  ```kotlin
  subtitle = "Empareja uno o más dispositivos"
  ```
  Single-line `git apply` or exact-line edit. Title at `:310`, icon at `:309`, Solicitudes empty state at `:380-384`, `+` FAB at `:188`, bell icon at `:184` all untouched.

- [x] **3.2 — Add `Modifier.testTag("device_card")` per Phase 1.1.**
  Apply the `DeviceComponents.kt:36-39` edit if not already done in the test-only commit (apply-phase decision based on Robolectric tolerance of missing tag).

- [x] **3.3 — GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.ui.parent.screens.DashboardScreenTest" --rerun-tasks`. All 6 tests pass (3 pre-existing + 3 new).

- [x] **3.4 — Commit.**
  ```
  feat(parent-dashboard): pluralize empty-state subtitle and tag DeviceCard for tests
  ```
  Body cites the OPPO empirical evidence (proposal §Why) and `openspec/specs/parent-device-list/spec.md:45-47` (no spec delta — mini-SDD lite).

## Phase 4 — Build verifier

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on the 2 touched files (`DashboardScreen.kt`, `DeviceComponents.kt`).

- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full `:app` unit suite green. Pre-existing failures on `NetworkModuleTest`, `BootReceiverTest`, `NavGraphTest` (per auth-fix archive-report §4.3 precedent) remain unchanged.

- [x] **4.3 — `./gradlew :app:ktlintCheck`** — green. Pre-existing `WorkersTest.kt` violations stay pre-existing; no new violations on the 2 touched files. `:app:detekt` likewise.

- [x] **4.4 — Final repo-wide grep on the new surfaces.**
  ```bash
  grep -rn "Empareja uno o más dispositivos" app/src
  grep -rn "testTag(\"device_card\"" app/src
  ```
  Expected: exactly 1 production hit (the new subtitle) + 1 production hit (`DeviceComponents.kt:38`) + N test hits (the 3 assertions + the import). No other call sites.

## Out of scope (follow-up notes for next session)

- Picker / select-child affordance (separate change; depends on this N-device rendering being solid)
- Unpair wired to RLS (separate change)
- Solicitudes tab copy / grouping / realtime (parallel but separate)
- `LazyColumn key = { it.id }` debt at `DashboardScreen.kt:354` (pre-existing; risks mis-aligned items if `list` reorders; orthogonal to this change)
- Badge content (`"Activo"` / `"Bloqueado"` / `"Hora de dormir"`) formalized as a spec delta (spec at `openspec/specs/parent-device-list/spec.md:41-43` is silent on labels; the 3 RED tests pin the labels, but a follow-up should make this an explicit `Requirement` so future copy changes go through a delta-spec change)

## Notes

- **2-commit structure is firm**: Phase 1 RED commit lands tests-only (testTag wiring may live in either commit — apply-phase decision). The auth-fix archive-report §Deviations explains why the 2-commit shape preserves the RED→GREEN trail for reviewers and matches `openspec/config.yaml:strict_tdd: true`.
- **No spec delta**: per proposal §Why mini-SDD lite, the spec at `openspec/specs/parent-device-list/spec.md:41-47` describes behavior, not literal Spanish. The 3 RED tests pin the badge labels without a spec change.
- **No manual smoke / instrumented runs locally** (per `openspec/config.yaml:57` gotcha; dev box has no `adb`/emulator). The 3 RED tests + build verifier are the gate; CI on API 28/31/35 is the cross-device smoke.
- **Reference resolution for the apply phase**: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/tasks.md` is the precedent for 4-phase shape and `apply-progress.md` template. `parent-device-list/spec.md:41-47` is the spec floor. The 3 fixtures live in `app/src/main/assets/mock-supabase/devices.json`.
- **Risk acknowledgment**: if Robolectric + Compose-UI-test on `master = e56b2a9` does NOT tolerate the missing `device_card` tag (i.e., 1.2.1 fails with a build error rather than an assertion), the testTag wiring from 1.1 must land in the RED commit, not the GREEN one. Apply-phase decision based on the actual RED run; this tasks.md does NOT pre-commit to a placement.