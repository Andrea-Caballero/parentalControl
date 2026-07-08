# Tasks: fix-behavioral-event-log-fixture-loading

> Mini-SDD lite bug fix. No `specs/` (user picked (s) skip — engram #317). No `design.md` (`proposal.md` is the design-of-record). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = 3fc464d` baseline **before any production code changes**. Each phase maps to one conventional commit. Single PR, ~15-20 LoC, well under the 400-line review budget.

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~3-5 LoC production + ~10 LoC test (per proposal §What changes) |
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
| 1 | Add `MOCK_PARENT_ID` const + 1-line `parent_id` write in synthetic PARENT path + 4-pre-existing GREEN tests stay GREEN | PR 1 | base = `master`; 2 modified production files (`MockSupabaseEngine.kt` + `DeviceAuthManager.kt`); 0 new tests (existing RED integration test flips GREEN) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The test below must **fail today** at `master = 3fc464d`. It already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/BehavioralEventsRepositoryIntegrationTest.kt:118-159` (written by the explore sub-agent). RED is the contract that gates Phase 3.

Run with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepositoryIntegrationTest.fixture_events_surface_in_viewmodel_after_synthetic_parent_auth" --rerun-tasks`.

- [x] **1.1 — RED (existing) `fixture_events_surface_in_viewmodel_after_synthetic_parent_auth`** at `BehavioralEventsRepositoryIntegrationTest.kt:118-159`. Simulates the live-device path: `authManager.authenticateOrCreate(Role.PARENT)` (`DeviceAuthManager.kt:195-216`) → construct VM → `vm.events.first()`. Asserts `events.size == 5`. **Expected TODAY**: FAILS at `assertEquals(5, events.size)` (`BehavioralEventsRepositoryIntegrationTest.kt:153-158`) because `getParentId()` returns null → VM uses empty string → DAO filter `WHERE parent_id = ''` matches 0 rows even though the mock wrote 5 rows with `parent_id = "parent-demo"`. RED on `master = 3fc464d` baseline.

- [x] **1.2 — RED-commit gate.** Run the test above. MUST FAIL. Record timing. Do NOT commit until the failure is confirmed on the unfixed baseline.
  **Commit:**
  ```
  test(repo): confirm RED on BehavioralEventsRepositoryIntegrationTest (parent_id write gap)
  ```
  (Optional red-tag commit; the test already on disk may be committed as part of Phase 3 GREEN if prefer non-split history. Apply-phase choice.)

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm root cause at `DeviceAuthManager.authenticateOrCreate(role: Role)`.** Re-verify `DeviceAuthManager.kt:205-209` writes `role` + `KEY_SYNTHETIC_ACCESS_TOKEN` only — no `parent_id`. Cross-check `savePairedSession` at `DeviceAuthManager.kt:381` DOES write `parent_id` (the canonical path the synthetic overload inherited only a subset of).

- [x] **2.2 — Consumer sweep.**
  ```bash
  grep -rn "getParentId\|parent_id" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected hits (already known from engram #316):
  - `auth/DeviceAuthManager.kt:381` (canonical write — `savePairedSession`).
  - `auth/DeviceAuthManager.kt:409-411` (read — `getParentId()`).
  - `viewmodel/BehaviorLogViewModel.kt:50` (`authManager.getParentId().orEmpty()`).
  - `data/db/BehavioralEventDao.kt:55` (`WHERE parent_id = :parentId`).
  - `data/remote/MockSupabaseEngine.kt:478` (wire shape — `parent_id` field on `BehavioralEventFixture`).

- [x] **2.3 — Confirm fixture value.** Read `app/src/main/assets/mock-supabase/behavioral_events.json:11,22,33,44,55` — all 5 events carry `parent_id = "parent-demo"`. The constant value MUST match this string byte-for-byte.

- [x] **2.4 — Confirm GREEN tests do not depend on the fix.** Read `BehavioralEventsRepositoryTest.kt:139,154,170,192` — the 4 GREEN cases inject `parentId = "parent-demo"` directly into `repository.refresh(parentId)` (`BehavioralEventsRepositoryTest.kt:142,158,174,196-197`), bypassing `authManager.getParentId()`. The fix is INVISIBLE to them; they stay GREEN unchanged.

- [x] **2.5 — Confirm const location convention.** `MockSupabaseEngine.kt` is a `class` (line 40), not an `object` — the const needs a `companion object` block. Dev-only mock engine constants live in this class (per engram #317 Q1=(m) decision).

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Add `MOCK_PARENT_ID` const to `MockSupabaseEngine.kt`.** Append a `companion object { const val MOCK_PARENT_ID = "parent-demo" }` block at the end of `MockSupabaseEngine.kt` (after the class closing `}`, before the `BehavioralEventFixture` data class at line 468). KDoc: matches the fixture's `parent_id` value; only used by the synthetic auth path until `parent-auth-flow` retires the coupling. ~5 LoC (companion object + 1 const + 2-line KDoc).

- [x] **3.2 — Import the const in `DeviceAuthManager.kt`.** Add `import com.tudominio.parentalcontrol.data.remote.MockSupabaseEngine.Companion.MOCK_PARENT_ID` near the top of `DeviceAuthManager.kt` (~1 LoC).

- [x] **3.3 — Write `parent_id` in the synthetic PARENT auth path.** In `DeviceAuthManager.authenticateOrCreate(role: Role)` at `DeviceAuthManager.kt:205-209`, extend the existing `.edit().putString(...).apply()` block with `.putString("parent_id", MOCK_PARENT_ID)` before `.apply()`. The write applies ONLY when `role == Role.PARENT` per Q2=(n) — wrap in `if (role == Role.PARENT)` (no CHILD-path symmetry, ~2 LoC including the `if`). Update the KDoc at `DeviceAuthManager.kt:178-194` to mention the new `parent_id` field alongside `role` + `synthetic_access_token`.

- [x] **3.4 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepositoryIntegrationTest.fixture_events_surface_in_viewmodel_after_synthetic_parent_auth" --rerun-tasks`. Test now PASSES.

- [x] **3.5 — Run sister test suites (no regressions).**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepositoryTest" --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerRoleTest" --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --rerun-tasks`. All pre-existing cases stay GREEN unchanged: 4 in `BehavioralEventsRepositoryTest` + 6 in `DeviceAuthManagerRoleTest` + 4 in `DeviceAuthManagerColdStartTest`.

- [x] **3.6 — Commit**:
  ```
  fix(auth): write parent_id in synthetic PARENT path so fixture loads
  ```
  Body must cite engram #316 (root cause), #317 (Q1=m + Q2=n + Q3=r decisions), and `DeviceAuthManager.kt:205-209` (the edit block).

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `DeviceAuthManager.kt` + `MockSupabaseEngine.kt`.
- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; 83 pre-existing failures (PR A + PR B baseline) unchanged.
- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `DeviceAuthManager.kt` + `MockSupabaseEngine.kt`. Pre-existing violations elsewhere are out of scope.
- [x] **4.4 — `./gradlew :app:detekt`** — no new violations on the 2 touched production files.
- [x] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "MOCK_PARENT_ID\|parent_id" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected: 1 production read of `MOCK_PARENT_ID` in `DeviceAuthManager.kt` (Phase 3.3 conditional) + 1 production const definition in `MockSupabaseEngine.kt` (Phase 3.1 companion). The other `parent_id` hits (DAO filter, VM read, fixture wire shape) are pre-existing.

---

## Out of scope (frozen)

- `BehavioralEventsRepository.parentId.isBlank() → skip filter` defensive fallback — explicitly excluded per Q3=(r). Defensive layers mask bugs; the empty-state UI is the agreed failure mode.
- CHILD-path `parent_id` symmetry — explicitly excluded per Q2=(n). CHILD doesn't read `parent_id` today.
- Migration of `device_auth_prefs.parent_id` for parents who signed in before the fix — acceptable brief-stale window (next `clearSession()` + `authenticateOrCreate(Role.PARENT)` heals them). Confirm at verify time.
- Spec delta for `device-auth-session/spec.md` — deferred per open question #1 (same precedent as `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/`).
- Hardening `authenticateOrCreate(role)` into an enumerated-key checklist (analogous to `savePairedSession`) — separate refactor.

## Notes

- This change is a **2-file production diff** (`MockSupabaseEngine.kt` + `DeviceAuthManager.kt`, ~7-8 LoC production) + 0 new tests (the RED integration test at `BehavioralEventsRepositoryIntegrationTest.kt:118-159` flips GREEN without modification). Well under the 400-line review budget.
- **`strict_tdd: true`** is honoured by the Phase 1 RED gate before any Phase 3 production change. The proposal estimates the data-layer shape; this tasks file mirrors `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/tasks.md` format exactly (same precedence).
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. Live verification on device happens at `sdd-verify` time (the existing PR-B live-test-#5 surface).
- **Coupling caveat**: `DeviceAuthManager` imports `MockSupabaseEngine.MOCK_PARENT_ID` — production code now references a dev-only mock constant. Mitigation: the constant is a clearly-named `MOCK_*` value; the eventual `parent-auth-flow` change retires both the synthetic path and this coupling together (acceptable per `archive/2026-06-19-hotfix-parent-auth-session/` precedent).
- **`clearSession()` invariant**: at `DeviceAuthManager.kt` (SharedPreferences `.clear()` semantics) wipes the new `parent_id` key automatically — no follow-up needed. `DeviceAuthManagerColdStartTest` (4 cases) pins this invariant.
- **Reference resolution for the next session**: engram #315 (change marker), #316 (`sdd/fix-behavioral-event-log-fixture-loading/explore` — auth-layer gap diagnosis), #317 (decisions Q1=m + Q2=n + Q3=r), #318 (proposal artifact). Precedent: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` is the same single-PR ~50-LoC bug-fix shape.
- **If on closer inspection the root cause is different** (e.g., the RED test actually fails for a reason OTHER than the `parent_id` write gap — say, a Hilt wiring issue in the test source set or a `Context` injection mismatch), Phase 3.3 is the seam to revisit. The symptom (`vm.events.first()` is empty after `authManager.authenticateOrCreate(Role.PARENT)` even though the mock wrote 5 rows) is the agreed starting point.

---

## Apply log

- **Branch:** `fix/behavioral-event-log-fixture-loading` based on `master @ 3fc464d`.
- **PR:** see PR URL in the apply-progress engram (search key `sdd/fix-behavioral-event-log-fixture-loading/apply-progress`).
- **Work units shipped (2 commits):**
  1. `test(repo): confirm RED on BehavioralEventsRepositoryIntegrationTest` — openspec change artifacts + RED-tag for the existing integration test.
  2. `fix(auth): write parent_id in synthetic PARENT path so fixture loads` — `MockSupabaseEngine.kt` companion const + `DeviceAuthManager.kt` import + 2-line edit-block extension + RED → GREEN.
- **Final test totals (`./gradlew :app:testDebugUnitTest`):** pre-existing 750 tests / 83 baseline failures unchanged — no new regressions.
- **RED → GREEN:** `fixture_events_surface_in_viewmodel_after_synthetic_parent_auth`.
- **Lint / detekt:** clean on touched production files.
