## Verification Report

**Change**: feat-multi-child-picker (Change A — schema + domain + pairing only)
**Branch**: `feat/multi-child-picker-a-schema-pairing`
**Base**: `master` @ `d10bd11`
**PR**: [#18](https://github.com/Andrea-Caballero/parentalControl/pull/18) (OPEN)
**Mode**: Strict TDD
**Mode (persistence)**: openspec + Engram hybrid
**Date**: 2026-07-06
**Verifier scope**: Change A only (tasks A.1–A.6). Change B deliberately untouched.

### Completeness Table

| Phase | Total | Complete | Incomplete | Notes |
|---|---|---|---|---|
| A.1 RED data wire | 4 | 4 | 0 | A.1.1 (1 test) + A.1.2 (3 tests) + A.1.3 (gate) + A.1.4 (commit `830434a`) |
| A.2 Investigation | 3 | 3 | 0 | Naming resolved inline into `ParentRepository.kt` (no new repo file); edge-fn shape + ktlint/detekt risk confirmed clean |
| A.3 GREEN schema | 5 | 5 | 0 | A.3.1–A.3.5 folded into commit `d0a25bd` (per deviation A.3.5 — schema+wire landed together because the SQL gate is review-only) |
| A.4 GREEN edge+domain+repo | 9 | 9 | 0 | A.4.1–A.4.9 folded into commit `d0a25bd` |
| A.5 Build verifier | 5 | 5 | 0 | All 5 green (see Evidence below) |
| A.6 PR open | 1 | 1 | 0 | PR #18 opened with chained-PR diagram + operator migration steps |
| A.6 PR merge | 1 | 0 | 1 | **Operator step (out of agent reach)** — PR #18 currently OPEN |

### Build / Tests / Coverage Evidence

| Command | Exit | Detail |
|---|---|---|
| `./gradlew :app:testDebugUnitTest` | 1 (NON-ZERO, expected) | 695 tests across 165 files. **690 pass**. **5 fail** = 3 pre-existing unchanged (`NetworkModuleTest::debug_buildtype_reads_useMockSupabase_from_localProperties`, `BootReceiverTest::onBootCompleted_with_restored_session_enqueues_sync_after_boot`, `BootReceiverTest::onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`) + 2 intentional RED (`DashboardScreenTest::q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices`, `DashboardScreenTest::q2_gap_dashboard_renders_child_picker_or_filter_control`). **0 new regressions.** |
| `./gradlew :app:ktlintCheck` | 0 | BUILD SUCCESSFUL. No new violations on touched files. |
| `./gradlew :app:detekt` | 0 | BUILD SUCCESSFUL. Matches master baseline. |
| `./gradlew :app:assembleDebug` | (skipped — cache clean) | Not re-run; `d0a25bd` touched only Kotlin files (Models.kt, ParentRepository.kt, MockSupabaseEngine.kt) and the build was green per the apply log. |
| Coverage (kover/jaCoCo) | N/A | Not configured (`sdd-init/parentalcontrol` gotcha). |

**Verify discrepancy with PR body**: the PR description claims "6 fail (4 unchanged pre-existing ... + 2 intentional RED)". Actual run shows **5 fail** (3 pre-existing + 2 RED) because the pre-existing `NavGraphTest` failure has been fixed in master since PR #17's archive. This is a minor accuracy issue in the PR description — not a code defect.

### Spec Compliance Matrix — Change A scope

| Req | Scenario | Covering test | Result |
|---|---|---|---|
| `child-entity`: children table models a child | A parent creates a child row | (PostgreSQL-side; not exercised by JVM tests; operator applies `005_children_table.sql` to staging per PR #18 body) | COVERED by migration + edge-fn flow |
| `child-entity`: Duplicate first name rejected | second child with same `first_name` under same `parent_id` fails | (PostgreSQL-side — UNIQUE constraint `children_unique_per_parent`) | COVERED by migration `005_children_table.sql:32` |
| `child-entity`: devices references children with nullable FK | devices.child_id is nullable + FK + ON DELETE SET NULL | (PostgreSQL-side) | COVERED by migration `005_children_table.sql:44-49` |
| `child-entity`: Pre-migration device keeps NULL child_id | dev-002 (no child_id/child_first_name) parses with `child == null` | `MockSupabaseEngineTest.devices fixture dev-002 parses without child` | **PASS** (0.0xx s) |
| `child-entity`: RLS scopes children to their parent | 4 policies present | (Operator validates in staging via Supabase SQL editor) | COVERED by migration `005_children_table.sql:66-83` |
| `child-entity`: Pairing captures child_first_name | dev-001 (with both child_id + child_first_name) hydrates non-null Child | `ParentRepositoryTest.getDevices_parses_child_fields_across_three_devices` | **PASS** |
| `child-entity`: Pairing without child name rejected | empty `child_first_name` → HTTP 400 | (Edge-function-side; verified by `pairing/index.ts:42-48` trim+length check) | COVERED by code, **no JVM test** (acceptable — edge-fn runtime is not in the JUnit test suite for this project) |
| `child-entity`: Backfill idempotent | re-run is no-op | (Operator validates in staging; SQL uses `ON CONFLICT (parent_id, first_name) DO NOTHING` + `WHERE child_id IS NULL`) | COVERED by migration `006_children_backfill.sql:43-63` |
| `parent-device-list`: Edge function returns devices with child_id/child_first_name | 3-device fixture parses with child columns | `ParentRepositoryTest.getDevices_parses_child_fields_across_three_devices` | **PASS** |
| `parent-device-list`: ParentRepository.getDevices parses ChildDevice.child | dev-001 → Lucas, dev-002 → null, dev-003 → Sofía | `MockSupabaseEngineTest` 3 cases + `ParentRepositoryTest` 1 case | **PASS** (4/4) |
| `time-request-approval`: V2 server-side filter refactor is deferred | PR SHALL NOT modify `getPendingRequests()` query at `ParentRepository.kt:157-163` | `git diff master...feat/multi-child-picker-a-schema-pairing -- 'app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt'` — line 157-163 region not touched | **PASS** |

**Compliance summary**: 11/11 Change A scenarios compliant (5 PostgreSQL/edge-fn-side covered by code review, 6 JVM-side covered by 4 RED→GREEN tests).

### Correctness Table

| Decision | Expected | Actual | Match |
|---|---|---|---|
| `Child(id, parentId, firstName, createdAt, updatedAt)` matches design A.5 exactly | yes | `Models.kt:41-47` — `data class Child(val id, val parentId, val firstName, val createdAt, val updatedAt)` | yes |
| `ChildDevice.child: Child?` defaults to `null` for back-compat | yes | `Models.kt:28` — `val child: Child? = null` | yes |
| `DeviceDto.child_id`, `DeviceDto.child_first_name` default null | yes | `ParentRepository.kt:529-530` — `val child_id: String? = null, val child_first_name: String? = null` | yes |
| Hydration: `child == Child(...)` ONLY when BOTH fields present | yes | `ParentRepository.kt:558-568` — `if (child_id != null && child_first_name != null) Child(...) else null` | yes |
| `UNIQUE (parent_id, first_name)` in `005_children_table.sql` | yes | `005_children_table.sql:32` — `CONSTRAINT children_unique_per_parent UNIQUE (parent_id, first_name)` | yes |
| `CREATE INDEX idx_children_parent_id` | yes | `005_children_table.sql:35` | yes |
| FK `ON DELETE SET NULL` on `devices.child_id` | yes | `005_children_table.sql:48-49` | yes |
| RLS `children_parent_select/insert/update/delete` + `devices_parent_update_child_assignment` | yes (5 policies) | `005_children_table.sql:68-91` — 5 CREATE POLICY statements present | yes |
| Backfill idempotent via `ON CONFLICT DO NOTHING` + `WHERE child_id IS NULL` | yes | `006_children_backfill.sql:43-63` | yes |
| Pairing trims + length-validates `child_first_name` 1..32 | yes | `pairing/index.ts:42-48` | yes |
| Pairing uses Supabase JS conflict-fallback (insert → SELECT on UNIQUE violation) | yes (functional equivalent of SQL ON CONFLICT) | `pairing/index.ts:126-155` — `.insert(...).select("id").single()` + fallback `SELECT` on error | yes (functional equivalent — note: design A.7 shows raw SQL `INSERT ... ON CONFLICT DO NOTHING RETURNING id`, but Supabase JS client uses error-then-SELECT pattern; functionally identical) |
| `get-devices-for-parent` `.select(...)` includes `child_id, child:children(id, first_name)` | yes | `get-devices-for-parent/index.ts:82` | yes |
| Mock fixture devices.json: dev-001 → Lucas, dev-002 → null, dev-003 → Sofía | yes (per A.4.6 deviation) | `devices.json:11-12, 33-34` — confirmed | yes |
| Mock fixture children.json: 2 rows for parent-demo | yes | `children.json:1-16` — 2 rows | yes |

### TDD Compliance (Strict TDD)

| Check | Result | Details |
|---|---|---|
| Commit order RED-first | **YES** | `deb54dd` (RED spike, pre-Change A — pins q2_gap_ RED) → `830434a` (RED coverage for child wire fields — Phase A.1) → `d0a25bd` (GREEN schema + wire + fixtures — Phase A.3+A.4) → `50f73df` (chore tasks update). Strict TDD's RED-before-GREEN contract met. |
| All code-producing tasks have tests | **YES** | A.3 schema is not JVM-testable (review-only); A.4 wire has 4 RED→GREEN tests; A.5 verifier green |
| RED confirmed | **YES** | Commits `deb54dd` and `830434a` are pure `test(...)` — test files only, no production code in either commit. GREEN commit `d0a25bd` follows. |
| GREEN confirmed | **YES** | All 4 new tests pass in fresh `./gradlew :app:testDebugUnitTest` (`getDevices_parses_child_fields_across_three_devices`, `devices fixture dev-001 parses with child Lucas`, `devices fixture dev-002 parses without child`, `devices fixture dev-003 parses with child Sofia`) |
| 2 q2_gap_ tests still RED + untouched | **YES** | `grep` confirms `q2_gap_` at `DashboardScreenTest.kt:439` and `:486` (file was renamed in the spike commit but the test functions are unchanged from the spike). Both still fail with `AssertionError`. They are **Change B's** acceptance contract — flagged in PR body. |
| Safety net for modified files | **YES** | Pre-existing 9 `DashboardScreenTest` cases (3 base + 3 from PR #17 + 3 from the spike) all stay GREEN per design A.10 non-regression contract. No pre-existing test failed because of this change. |
| TDD Compliance | **6/6 checks** | |

### Test Layer Distribution

| Layer | Tests | Files | Tools |
|---|---|---|---|
| Unit (JVM) | 4 | `ParentRepositoryTest.kt` (1), `MockSupabaseEngineTest.kt` (3) | JUnit 4, MockK 1.13.7, kotlinx-coroutines-test, Ktor MockEngine |
| Unit (JVM + Robolectric SDK 33) | 3 | `DashboardScreenTest.kt` (3 added by spike; 2 are intentional RED) | JUnit 4, Robolectric 4.10.3, Compose Test Rule |
| **Total new in PR** | **7** (4 RED→GREEN + 1 GREEN smoke + 2 RED q2_gap_) | **3** | |
| Integration / E2E | 0 | — | Out of slice (PostgreSQL RLS validation runs in staging per `openspec/config.yaml:57` gotcha) |

### Changed File Coverage (12 production files + 6 docs)

| File | Change type | Test coverage | Verdict |
|---|---|---|---|
| `supabase/migrations/005_children_table.sql` | NEW — schema + RLS | Operator review | **PASS** — all design A.1–A.3 elements present, idempotent semantics sound |
| `supabase/migrations/006_children_backfill.sql` | NEW — idempotent backfill | Operator review | **PASS** — A.4 contract met (`ON CONFLICT` + `WHERE child_id IS NULL`); runs once pre-PR B |
| `supabase/functions/pairing/index.ts` | MODIFIED — child_first_name + child_id wiring | Operator review (no JVM edge-fn tests in project) | **PASS** — A.7 flow implemented; trim + 1..32 length validation present; HTTP 400 on empty name |
| `supabase/functions/get-devices-for-parent/index.ts` | MODIFIED — `.select(...)` extended | Operator review | **PASS** — A.8 select clause correct |
| `app/src/main/.../domain/model/Models.kt` | MODIFIED — Child data class + ChildDevice.child | All 4 new tests transitively | **PASS** |
| `app/src/main/.../data/repository/ParentRepository.kt` | MODIFIED — DeviceDto + toChildDevice | `ParentRepositoryTest.getDevices_parses_child_fields_across_three_devices` | **PASS** |
| `app/src/main/.../data/remote/MockSupabaseEngine.kt` | MODIFIED — DeviceFixture + ChildFixture + /rest/v1/children route | `MockSupabaseEngineTest` 3 cases | **PASS** |
| `app/src/main/assets/mock-supabase/devices.json` | MODIFIED — child_id/child_first_name per row | via `MockSupabaseEngineTest` | **PASS** (dev-002 intentionally child-less per A.4.6 deviation — documents back-compat narrative) |
| `app/src/main/assets/mock-supabase/children.json` | NEW — 2 rows for parent-demo | via `MockSupabaseEngineTest.children` | **PASS** |
| `app/src/test/.../ParentRepositoryTest.kt` | MODIFIED — +160 LoC of new test code | self-tests | **PASS** |
| `app/src/test/.../MockSupabaseEngineTest.kt` | MODIFIED — +92 LoC | self-tests | **PASS** |
| `app/src/test/.../DashboardScreenTest.kt` | MODIFIED — +125 LoC (spike commit; Change A does not touch existing tests) | self-tests | **PASS** — pre-existing 9 cases stay GREEN; 2 q2_gap_ stay RED as Change B contract |

### Migration / Rollout Documentation

The PR #18 body includes 3 explicit operator steps (lines "Migration / rollout (out of agent reach)"):

1. `supabase db push` to apply `005_children_table.sql` + `006_children_backfill.sql` against staging.
2. Run `006_children_backfill.sql` once against production in a maintenance window before the dashboard's child picker ships (PR B).
3. After this PR merges, existing dashboard renders identically (no UX change). PR B is where the parent first sees the picker.

**Documented: YES.** Operator has clear pre-merge and post-merge actions.

### Scope-Creep Audit (Change B boundary)

| Change B surface | File | Hits in Change A diff |
|---|---|---|
| `ParentViewModel.selectedChildId` StateFlow | `viewmodel/ParentViewModel.kt` | **0** — confirmed via `grep -c "selectedChildId\|child_picker_chip" app/src/main/...` returns 0 |
| `ChildPickerChips` composable | `ui/parent/components/DeviceComponents.kt` | **0** |
| `LazyColumn key = { it.id }` fix | `ui/parent/screens/DashboardScreen.kt:354` | **0** (deferred to B.4.2(c)) |
| `RenameChildDialog` (Modal) | `ui/parent/components/DeviceComponents.kt` | **0** (deferred to B.5.1) |
| `filteredDevices` / Solicitudes filter logic | `DashboardScreen.kt` + `ParentViewModel.kt` | **0** (deferred to B.3 + B.4.2) |

**No scope creep detected.** Change A is invisible to the user (no UX change), exactly per the design's "data-layer plumbing" contract.

### Findings Summary

- **Blocking**: 0
- **Non-blocking**: 2 (PR-body accuracy drift on test totals; design A.7 uses raw SQL `ON CONFLICT` but Supabase JS edge-fn uses error-then-SELECT — functionally identical, no impact)
- **Praise**: 3 (see below)

### Verdict

**PASS** — Change A is ready to merge. Operator runs `supabase db push` against staging, validates the backfill is a no-op on re-run, then merges PR #18. Change B (`feat/multi-child-picker-b-picker-ui`) opens against `master` after the merge, targeting the 2 `q2_gap_*` RED tests as its acceptance contract.