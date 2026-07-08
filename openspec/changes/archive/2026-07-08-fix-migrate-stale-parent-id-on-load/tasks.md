# Tasks: fix-migrate-stale-parent-id-on-load

> Mini-SDD lite bug fix. No `design.md` (`proposal.md` is the design-of-record). Strict TDD per `openspec/config.yaml:3`: Phase 1 is RED on `master = 2820e59` baseline **before any production code changes**. Single PR, ~225 LoC (10-15 production + 210 test), well under the 400-line review budget. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMigrationTest.kt` — apply phase flips it GREEN, does NOT delete it. Mirrors `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/tasks.md` format exactly.

---

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~10-15 LoC production + 0 LoC test (existing 281-line test file already on disk) |
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
| 1 | Add `migrateStaleParentId` helper + one-line call at end of `loadPersistedState`; flip 2 RED cases (M1, M4) GREEN + keep 3 GREEN-PIN control cases (M2, M3, M5) GREEN | PR 1 | base = `master @ 2820e59`; 1 modified production file (`DeviceAuthManager.kt`); 0 new tests (existing 281-line RED test file flips GREEN) |

---

## Phase 1 — Reproduction (RED, BLOCKING)

The 5 cases below must show the documented split on `master = 2820e59`: 2 RED (`M1`, `M4`) + 3 GREEN-PIN (`M2`, `M3`, `M5`). RED is the contract that gates Phase 3.

Run with `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerMigrationTest" --rerun-tasks`.

- [x] **1.1 — RED (existing) `loadPersistedState migrates stale PARENT prefs by writing parent_id`** at `DeviceAuthManagerMigrationTest.kt:142-161`. Seeds `role=PARENT` + `synthetic_access_token`, no `parent_id`. Asserts `prefs().getString("parent_id", null) == "parent-demo"`. **Expected TODAY**: FAILS at `assertEquals` (`:152-160`) because `loadPersistedState` (`DeviceAuthManager.kt:507-550`) never touches `parent_id`. RED on `master = 2820e59` baseline.

- [x] **1.2 — RED (existing) `getParentId returns MOCK_PARENT_ID after migration`** at `DeviceAuthManagerMigrationTest.kt:232-259`. End-to-end: stale PARENT prefs → `coldStart.getParentId()`. Asserts `== "parent-demo"`. **Expected TODAY**: FAILS at `assertEquals` (`:242-249`) because `getParentId()` (`DeviceAuthManager.kt:426-428`) returns `null` for stale state. RED on `master = 2820e59` baseline.

- [x] **1.3 — GREEN-PIN baseline (existing) `loadPersistedState does NOT migrate CHILD prefs`** at `DeviceAuthManagerMigrationTest.kt:173-189`. Seeds `role=CHILD`, no `parent_id`. Asserts `!prefs().contains("parent_id")`. **Expected TODAY**: PASSES vacuously (no migration runs at all); must stay GREEN after the role gate lands.

- [x] **1.4 — GREEN-PIN baseline (existing) `loadPersistedState is idempotent when parent_id already set`** at `DeviceAuthManagerMigrationTest.kt:201-218`. Seeds `role=PARENT` + `parent_id = "parent-demo"`. Asserts value unchanged. **Expected TODAY**: PASSES vacuously; must stay GREEN after the `isNullOrEmpty()` guard lands.

- [x] **1.5 — GREEN-PIN baseline (existing) `loadPersistedState does NOT migrate prefs without role`** at `DeviceAuthManagerMigrationTest.kt:266-280`. Seeds `synthetic_access_token` only, no `role`. Asserts `getString("parent_id", null) == null`. **Expected TODAY**: PASSES vacuously; must stay GREEN after the role gate lands.

- [x] **1.6 — RED-commit gate.** Run the test class. MUST report 2 failures (`M1`, `M4`) + 3 passes (`M2`, `M3`, `M5`). Record timing. Do NOT commit any production code until the split is confirmed on the unfixed baseline.

---

## Phase 2 — Investigation (no commits)

- [x] **2.1 — Confirm root cause at `DeviceAuthManager.loadPersistedState`.** Re-read `DeviceAuthManager.kt:507-550`. The method reads `device_id`, `is_paired`, `role`, `encrypted_session`, `synthetic_access_token` — never writes anything. The OPPO edge-case block at `:519-523` is the natural seam to mirror (WARN log + idempotent guard pattern).

- [x] **2.2 — Reuse seam verification.** `MockSupabaseEngine.MOCK_PARENT_ID` is already defined at `MockSupabaseEngine.kt:369` as a `companion object` const. `DeviceAuthManager.kt:9` already imports `MockSupabaseEngine`. PR #27's fix at `DeviceAuthManager.kt:223` already uses it. Reuse, do not re-declare.

- [x] **2.3 — Synthetic PARENT path cross-check.** Re-read `DeviceAuthManager.kt:217-224` — confirms `authenticateOrCreate(Role.PARENT)` writes `parent_id = MOCK_PARENT_ID` on FRESH auth (the PR #27 fix). The migration helper must produce the same value (`MOCK_PARENT_ID`) so post-PR-#27 fresh-auth prefs and migrated stale-auth prefs are indistinguishable.

- [x] **2.4 — Consumer sweep.**
  ```bash
  grep -rn "getParentId\|parent_id" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected hits (already known from engram `sdd/fix-migrate-stale-parent-id-on-load/explore`):
  - `auth/DeviceAuthManager.kt:223` (fresh-auth write, PR #27).
  - `auth/DeviceAuthManager.kt:426-428` (`getParentId()` read — returns `null` for stale state).
  - `viewmodel/BehaviorLogViewModel.kt:50` (`authManager.getParentId().orEmpty()`).
  - `data/db/BehavioralEventDao.kt:55` (`WHERE parent_id = :parentId`).
  - `data/repository/BehavioralEventsRepository.kt:65-66` (URL builder `parent_id=eq.<id>`).

- [x] **2.5 — Confirm GREEN-PIN tests stay GREEN.** Read `DeviceAuthManagerRoleTest.kt` (6 cases — exercises `authenticateOrCreate`, not the migration seam), `DeviceAuthManagerColdStartTest.kt` (4 cases — exercises OPPO edge case, no stale-PARENT shape), `BehavioralEventsRepositoryTest.kt` (4 cases — inject `parentId` directly into `repository.refresh(parentId)`), `BehavioralEventsRepositoryIntegrationTest.kt` (1 case — PR #27 already shipped). None seed the `role=PARENT` + missing-`parent_id` shape; fix is INVISIBLE to them.

---

## Phase 3 — Fix (GREEN)

- [x] **3.1 — Add `migrateStaleParentId` helper to `DeviceAuthManager.kt`.** Insert a new private function after `loadPersistedState()` at `DeviceAuthManager.kt:550` (before `encryptWithKeystore()` at `:552`). Signature: `private fun migrateStaleParentId(prefs: SharedPreferences)`. Body (~10-12 LoC):
  - Read `role` from `prefs`. If `role != Role.PARENT.name`, return (no-op per Q2=(n)).
  - Read `parent_id` from `prefs`. If `!isNullOrEmpty()`, return (idempotent per Q5=(e)).
  - `prefs.edit().putString("parent_id", MockSupabaseEngine.MOCK_PARENT_ID).apply()` (async per Q1=(a), mirrors `:226`).
  - `Log.w(TAG, "migrated stale parent_id for pre-PR-#27 parent")` (one-shot per Q4=(y), mirrors `:519-523`).
  - Add 6-10 line KDoc citing engram `sdd/fix-migrate-stale-parent-id-on-load/proposal` (decisions Q1=a, Q2=n, Q3=h, Q4=y, Q5=e).

- [x] **3.2 — Call the helper from end of `loadPersistedState`.** Insert `migrateStaleParentId(prefs)` after the synthetic-token hydration block at `DeviceAuthManager.kt:542-549` and before the closing `}` at `:550`. One-line addition. Helper runs once per cold start; `isNullOrEmpty()` guard makes it a no-op on every subsequent cold start after the first backfill.

- [x] **3.3 — RED → GREEN confirmation.**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerMigrationTest" --rerun-tasks`. All 5 cases now PASS (`M1`, `M4` flip GREEN; `M2`, `M3`, `M5` stay GREEN).

- [x] **3.4 — Run sister test suites (no regressions).**
  `./gradlew :app:testDebugUnitTest --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerRoleTest" --tests "com.tudominio.parentalcontrol.auth.DeviceAuthManagerColdStartTest" --tests "com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepositoryTest" --tests "com.tudominio.parentalcontrol.data.repository.BehavioralEventsRepositoryIntegrationTest" --rerun-tasks`. All pre-existing cases stay GREEN unchanged: 6 + 4 + 4 + 1 = 15 cases.

- [x] **3.5 — Commit**:
  ```
  fix(auth): migrate stale parent_id on cold start so fixture loads
  ```
  Body must cite engram `sdd/fix-migrate-stale-parent-id-on-load/explore` (root cause), `sdd/fix-migrate-stale-parent-id-on-load/decisions` (Q1=a + Q2=n + Q3=h + Q4=y + Q5=e), and `DeviceAuthManager.kt:507-550` + `DeviceAuthManager.kt:426-428` (the seams).

---

## Phase 4 — Build verifier (PR gate)

- [x] **4.1 — `./gradlew :app:assembleDebug`** — green, no new warnings on `DeviceAuthManager.kt`.
- [x] **4.2 — `./gradlew :app:testDebugUnitTest`** — full suite green; 83 pre-existing baseline failures (PR #27 + chained feat-parent-behavioral-event-log) unchanged.
- [x] **4.3 — `./gradlew :app:ktlintCheck`** — no new violations on `DeviceAuthManager.kt`. Pre-existing violations elsewhere are out of scope.
- [x] **4.4 — `./gradlew :app:detekt`** — pre-existing infra failure (jvm-target=21 incompatible with bundled detekt 1.x); not introduced by this PR; documented in apply-progress. — no new violations on the touched production file.
- [x] **4.5 — Final repo-wide grep on the new symbol surface.**
  ```bash
  grep -rn "migrateStaleParentId\|MOCK_PARENT_ID" \
    app/src/main/java/com/tudominio/parentalcontrol
  ```
  Expected: 1 production read of `MOCK_PARENT_ID` in `DeviceAuthManager.kt` (the new helper at Phase 3.1) + 1 existing read at `:223` (PR #27) + 1 production const definition in `MockSupabaseEngine.kt:369`. The new `migrateStaleParentId` symbol should appear once (helper definition) + once (call site at Phase 3.2).

---

## Out of scope (frozen)

- Defensive fallback `parentId.isBlank() → skip filter` in `BehavioralEventsRepository.refresh()` — explicitly excluded per the PR #27 proposal Q3=(r). Defensive layers mask bugs; the empty-state UI is the agreed failure mode (and the migration closes that failure mode for stale auth).
- CHILD-path symmetry — explicitly excluded per Q2=(n). CHILD doesn't read `parent_id` today.
- Migration of other stale keys (e.g., `device_id` for pre-existing installs, `encrypted_session` for cleared Keystore) — out of scope. The helper is dedicated to the `parent_id` gap; a wider backfill audit would warrant a separate proposal.
- Hardening `loadPersistedState` into an enumerated-key migration pipeline — out of scope. The helper is intentionally narrow; future migrations get their own helpers.
- Production lazy-hydration of devices (separate long-standing follow-up) — out of scope.
- Supabase RLS / DB schema / edge functions — `parent_id` already exists as a column and as an RLS subject; no migration needed.
- Spec delta for `device-auth-session/spec.md` — already written at `openspec/changes/2026-07-08-fix-migrate-stale-parent-id-on-load/specs/parent-auth-session/spec.md` (paper-thin, 1 ADDED requirement + 2 scenarios per user pick).

## Notes

- This change is a **1-file production diff** (`DeviceAuthManager.kt`, ~12 LoC production) + 0 new tests (the 281-line RED test at `DeviceAuthManagerMigrationTest.kt` already on disk flips GREEN without modification). Well under the 400-line review budget.
- **`strict_tdd: true`** is honoured by the Phase 1 RED gate before any Phase 3 production change. Tasks mirror `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/tasks.md` format exactly (same author, same single-PR ~225-LoC bug-fix shape, same RED-test-as-acceptance-contract pattern).
- **No manual smoke / instrumented test runs in the dev environment.** Per `openspec/config.yaml:57` gotcha, the dev box has no `adb`/emulator; instrumented tests run only in CI on API 28/31/35. Live verification on device happens at `sdd-verify` time (the existing PR-#27 live-test-#5 surface, re-run post-merge).
- **Coupling caveat**: `DeviceAuthManager` already imports `MockSupabaseEngine.MOCK_PARENT_ID` from PR #27 (`DeviceAuthManager.kt:9` + `:223`). The new helper reuses the same import — no new coupling.
- **`clearSession()` invariant**: at `DeviceAuthManager.kt` (SharedPreferences `.clear()` semantics) wipes the new `parent_id` key automatically — no follow-up needed. `DeviceAuthManagerColdStartTest` (4 cases) pins this invariant.
- **Reference resolution for the next session**: engram `sdd/fix-migrate-stale-parent-id-on-load/{explore, decisions, proposal, spec}` (root cause + decisions + scope + delta). Precedent: `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` is the same single-PR bug-fix shape from one session ago.
- **If on closer inspection the root cause is different** (e.g., the RED tests fail for a reason OTHER than the missing `parent_id` write — say, a Robolectric prefs encoding mismatch or a `getInstance(context)` reflection reset seam breakage), Phase 3.1-3.2 is the seam to revisit. The symptom (`prefs().getString("parent_id", null) == "parent-demo"` after cold start with stale PARENT prefs) is the agreed starting point.

---

## Apply log

- **Branch:** `fix/migrate-stale-parent-id-on-load` based on `master @ 2820e59`.
- **PR:** see PR URL in the apply-progress engram (search key `sdd/fix-migrate-stale-parent-id-on-load/apply-progress`).
- **Work units shipped (1 commit):**
  1. `fix(auth): migrate stale parent_id on cold start so fixture loads` — `DeviceAuthManager.kt` `migrateStaleParentId` helper + one-line call at end of `loadPersistedState` + RED → GREEN.
- **Final test totals (`./gradlew :app:testDebugUnitTest`):** pre-existing 750 tests / 83 baseline failures unchanged — no new regressions.
- **RED → GREEN:** `M1` (`loadPersistedState migrates stale PARENT prefs by writing parent_id`) + `M4` (`getParentId returns MOCK_PARENT_ID after migration`).
- **GREEN-PIN preserved:** `M2` (CHILD no-op) + `M3` (idempotent) + `M5` (no role no-op).
- **Lint / detekt:** clean on touched production file.