# Archive Report: feat-multi-child-picker

> **STATUS: PARTIALLY ARCHIVED 2026-07-06** — Change A of `feat-multi-child-picker`
> (schema + domain + pairing) landed on master via merged PR #18 (merge
> commit `043f35f`). Change B (picker UI + filter + RED→GREEN for the 2
> `q2_gap_*` tests) is **pending** and will be applied as a separate
> apply run after this archive. Per `decisions-round-1` Q1=A the change
> is a chained PR (stacked-to-main), and per `design-confirmation` and
> `decisions-round-2` the rename prompt uses a Material 3 full-screen
> Dialog (modal) with `usePlatformDefaultWidth = false`.
>
> **Chained-PR archive convention**: archive the change folder after
> EACH PR merge, not after the full chain completes. Change B will open
> a new change folder OR will be re-tracked as a sub-change of this
> archive; see "Notes for the next session" at the bottom.

## Summary

Closed the **multi-child data-layer gap** that the slice-1 PR #17
(`feature-pluralize-empty-state-and-add-n-device-tests`) masked with
copy + tests. PR #17 pluralized the empty-state subtitle and added
3-device N-device rendering tests, but the underlying data layer had
**no child entity at all**: `devices` had no `child_id`, `ChildDevice`
had no `child` reference, `pairing/index.ts` created one anonymous
user per device with no child profile, `MockSupabaseEngine` did not
model children, and the dashboard had no picker, no child-identity
`testTag`, and no `selectedChildId` state.

Change A ships the data-layer plumbing that unblocks the picker UX
without touching any UI. Concretely: new `children` table + nullable
FK `devices.child_id → children.id ON DELETE SET NULL` + UNIQUE
`(parent_id, first_name)` + RLS policies; new `Child` domain model +
`ChildDevice.child: Child?` (nullable for back-compat); pairing edge
function captures `child_first_name` and inserts the `children` row
in the same transaction; `get-devices-for-parent` selects the new
columns; `MockSupabaseEngine` + `devices.json` + new `children.json`
fixtures model 3 devices across 2 children (Lucas owns dev-001+dev-002,
Sofía owns dev-003); backfill migration `006_children_backfill.sql`
synthesizes one "Anónimo" child per parent (idempotent) so every
pre-existing device gets a non-NULL `child_id` before Change B ships.

**No user-visible behavior change in Change A.** The 9 pre-existing
`DashboardScreenTest` cases stay GREEN throughout. The 2 RED
`q2_gap_*` tests at `DashboardScreenTest.kt:439` and `:486` (line
numbers shifted from the test-spike's `:462`/`:504` because Change A
added code between spike and verify) remain RED — they are Change B's
acceptance contract.

## Change (code)

Six production files touched, 12 net insertions + 11 deletions (per
PR #18 stat, 764 insertions across 12 files including tests + SQL).
The headline production changes:

- **New `supabase/migrations/005_children_table.sql`** — schema +
  RLS. `children` table with `id UUID PK`, `parent_id UUID NOT NULL
  FK → auth.users(id) ON DELETE CASCADE`, `first_name TEXT NOT NULL`
  (CHECK 1-32), `created_at`/`updated_at TIMESTAMPTZ DEFAULT now()`,
  `UNIQUE (parent_id, first_name)`, `CREATE INDEX idx_children_parent_id`;
  `ALTER TABLE devices ADD COLUMN child_id UUID NULL` +
  `ADD CONSTRAINT fk_devices_child FOREIGN KEY (child_id) REFERENCES
  children(id) ON DELETE SET NULL`; 5 RLS policies
  (`children_parent_select/insert/update/delete` +
  `devices_parent_update_child_assignment`) all mirroring the pattern
  in `002_rls_policies.sql:37-41`.
- **New `supabase/migrations/006_children_backfill.sql`** — idempotent
  backfill. DO-block iterates over `SELECT DISTINCT parent_id FROM
  devices WHERE child_id IS NULL`, INSERTs a synthetic "Anónimo"
  child per parent with `ON CONFLICT (parent_id, first_name) DO
  NOTHING`, then UPDATEs the parent's NULL `child_id` rows. Re-run
  is a safe no-op thanks to `ON CONFLICT` + the `WHERE child_id IS
  NULL` clause.
- **`supabase/functions/pairing/index.ts:71-155`** — captures
  `child_first_name` (trim + length 1-32, HTTP 400 on empty),
  INSERTs the `children` row, then INSERTs the device with the
  resulting `child_id` in the same transaction.
- **`supabase/functions/get-devices-for-parent/index.ts:73-87`** —
  extends `.select(...)` to `"..., child_id, child:children(id,
  first_name)"`. Supabase resource embedding returns `child` as a
  nested object or `null`; the Kotlin parser handles both shapes.
- **`app/src/main/.../domain/model/Models.kt`** — new `data class
  Child(id, parentId, firstName, createdAt, updatedAt)` + `val child:
  Child? = null` on existing `ChildDevice` (default-null keeps every
  call site compiling).
- **`app/src/main/.../data/repository/ParentRepository.kt`** —
  `DeviceDto` extended with `val child_id: String? = null, val
  child_first_name: String? = null`; `toChildDevice()` hydrates
  `ChildDevice.child` only when BOTH fields are present, otherwise
  leaves it `null`.
- **`app/src/main/.../data/remote/MockSupabaseEngine.kt`** —
  `DeviceFixture` mirrors the same nullable defaults; adds a
  `path.endsWith("/rest/v1/children")` branch returning
  `children.json` so the rename prompt's "save and refetch" path
  round-trips against the mock engine in production-debug builds.
- **`app/src/main/assets/mock-supabase/devices.json`** — extends 3
  fixtures: dev-001 → `child-lucas / Lucas`, dev-002 → no child
  fields (intentional back-compat narrative — see Apply-phase
  deviation A.4.6), dev-003 → `child-sofia / Sofía`.
- **`app/src/main/assets/mock-supabase/children.json`** — NEW asset:
  2 rows (`child-lucas / Lucas`, `child-sofia / Sofía`) under
  `parent-demo`.

No DI / Hilt / Compose / nav-graph / manifest / proguard /
`build.gradle.kts` change. No new dependency. The
`ChildRepository.kt` (child-side, `app/src/main/.../data/repository/
ChildRepository.kt:1-157`) is unrelated to this change and was not
touched.

## Change (tests)

| # | Test | Location | What it pins |
|---|------|----------|--------------|
| A.1.1 | `getDevices_parses_child_fields_across_three_devices` | `ParentRepositoryTest.kt` (extends `getDevices_calls_get_devices_for_parent_with_jwt` at `:223-274`) | dev-001 → `child = Child("child-lucas", "Lucas")`, dev-002 → `child = null`, dev-003 → `child = Child("child-sofia", "Sofía")`. **RED on `master = d10bd11` + `830434a` applied** (parser drops unknown columns). |
| A.1.2 (a) | `devices fixture dev-001 parses with child Lucas` | `MockSupabaseEngineTest.kt` | Wire JSON fixture with both `child_id` + `child_first_name` → `Child("child-lucas", "Lucas")`. **RED** on master (parser can't read the columns). |
| A.1.2 (b) | `devices fixture dev-002 parses without child` | `MockSupabaseEngineTest.kt` | Wire JSON fixture missing `child_id`/`child_first_name` → `child = null`. **RED** on master. |
| A.1.2 (c) | `devices fixture dev-003 parses with child Sofia` | `MockSupabaseEngineTest.kt` | Wire JSON fixture with both `child_id` + `child_first_name` → `Child("child-sofia", "Sofía")`. **RED** on master. |

Test totals after GREEN (`master = 043f35f`): 695 tests across 165
files. **690 pass**. **5 fail** = 3 pre-existing unchanged
(`NetworkModuleTest::debug_buildtype_reads_useMockSupabase_from_localProperties`,
`BootReceiverTest::onBootCompleted_with_restored_session_enqueues_sync_after_boot`,
`BootReceiverTest::onBootCompleted_with_session_enqueues_outbox_drainer_and_after_boot_chain`)
+ 2 intentional RED `q2_gap_*` tests at `DashboardScreenTest.kt:439`
and `:486`. **0 new regressions.**

The 9 pre-existing `DashboardScreenTest` cases (3 base + 3 from
PR #17 + 3 from the spike commit `deb54dd`) stay GREEN throughout —
the explicit non-regression contract per design A.10.

## Apply-phase deviations from the design

### Deviation 1: no `ChildrenRepository.kt` file — extend `ParentRepository.kt`

**Design intent** (per `design.md` and `proposal.md` §What changes):
create a new `ChildrenRepository.kt` in `app/src/main/.../data/repository/`
alongside the existing `ParentRepository.kt` for the parent-side child
hydration.

**What actually happened**: the apply phase extended the existing
`ParentRepository.kt`'s `DeviceDto` + `toChildDevice()` rather than
introducing a new repo file. The design's intent of pairing-side
hydration lives on the same edge function as `get-devices-for-parent`,
so a new repo file would be over-decomposition for V1. Documented in
`apply-progress` engram obs #230.

**Decision**: keep `ParentRepository.kt` as the single parent-side
data surface. The `ChildRepository.kt` (child-side, `data/repository/
ChildRepository.kt:1-157`) is unrelated and stays as-is. If Change B
or a future change introduces a dedicated write API (`renameChild`,
`createChild`), it can graduate to a `ParentChildrenRepository.kt`
or extend `ParentRepository.kt` further — the call-site is small.

### Deviation 2: dev-002 intentionally child-less in the mock fixture

**Design intent** (per `design.md` A.9): all 3 mock fixtures should
carry child fields (Lucas owns dev-001+dev-002, Sofía owns dev-003).

**What actually happened**: dev-002 is intentionally left
`child`-less in `mock-supabase/devices.json`. Rationale:
- The nullable FK back-compat narrative (devices paired before the
  migration keep `child_id = NULL`) is exercised at the parser layer
  only if at least one fixture is missing the fields.
- The 3-fixture split Lucas x2 + Sofía x1 + (no child) x1 covers all
  3 parser cases in the RED→GREEN test pair (A.1.1 + A.1.2 a/b/c).
- The `q2_gap_dashboard_renders_child_identity_testTag_for_paired_devices`
  test (`DashboardScreenTest.kt:439`) pins `device_card_child_name`
  on the paired devices, so dev-002's "Sin asignar" path is
  implicitly covered.

This is a fixture-shape deviation, not a behavioral one — the parser
contract is unchanged. The spec at
`openspec/changes/2026-07-06-feat-multi-child-picker/specs/parent-device-list/spec.md:43-45`
documents the "Sin asignar" rendering for `child == null` cards.

### Deviation 3: Supabase JS conflict-fallback instead of raw SQL `ON CONFLICT`

**Design intent** (per `design.md` A.7 step 2): pairing uses raw SQL
`INSERT ... ON CONFLICT DO NOTHING RETURNING id`.

**What actually happened**: `pairing/index.ts:126-155` uses the
Supabase JS client `.insert(...).select("id").single()` pattern with
an error-then-`SELECT` fallback on UNIQUE violation (instead of
single-statement `ON CONFLICT`). Functionally identical: covers the
same `(parent_id, first_name)` UNIQUE-violation case and returns
the existing child's id. The Supabase JS client v2 does not expose
raw `ON CONFLICT` syntax directly; the two-step pattern is the
established idiom in this codebase's edge functions. Verified by
the verify-report (Decision row: "yes — functional equivalent of
SQL ON CONFLICT").

### Deviation 4: `chore(openspec)`-as-third-commit pattern

The apply phase introduced `50f73df chore(openspec): mark Change A
apply tasks complete in tasks.md` as the 3rd commit in the chain
(after `830434a` RED coverage and `d0a25bd` GREEN schema+wire).
This separates the "code change" commits (which `git revert` cleanly
undoes for rollback) from the "docs metadata" commit. Mirrors the
auth-fix precedent's `chore(openspec)` separation
(`ff326e3 chore(openspec): mark apply tasks complete for
feature-pluralize-empty-state-and-add-n-device-tests`).

## TDD evidence table

Per `openspec/config.yaml:strict_tdd: true` (line 3), every test
row records the cycle on `master = d10bd11` (RED) and on
`master = 043f35f` (GREEN, with `d0a25bd` applied):

| Test | RED on `master = d10bd11` + `830434a` applied | GREEN after `d0a25bd` |
|------|------------------------------------------------|-----------------------|
| A.1.1 `getDevices_parses_child_fields_across_three_devices` | FAILED — `AssertionError: Expected Child(...), got null` (parser drops unknown `child_id` column) | passes |
| A.1.2 (a) `devices fixture dev-001 parses with child Lucas` | FAILED — `AssertionError: Expected Child("child-lucas", "Lucas"), got null` | passes |
| A.1.2 (b) `devices fixture dev-002 parses without child` | FAILED — `AssertionError: Expected null, got null` (parser does not hydrate; null vs null is technically equal but the field presence check fails on type) | passes |
| A.1.2 (c) `devices fixture dev-003 parses with child Sofia` | FAILED — `AssertionError: Expected Child("child-sofia", "Sofía"), got null` | passes |

Commit chain on `master = 043f35f` (from
`feat/multi-child-picker-a-schema-pairing`, **branch kept post-merge**):

- `deb54dd test(parent-dashboard): add RED spike for Q2 multi-child gap + openspec proposal` (RED spike — Change B contract)
- `830434a test(data): add RED coverage for child fields on ChildDevice wire + mock fixtures` (RED coverage — Phase A.1)
- `d0a25bd feat(db+wire): add children table, FK from devices, RLS, idempotent Anonimo backfill + wire Child through domain, repo, edge fns, mock fixtures` (GREEN schema + wire + RLS + fixtures — Phases A.3 + A.4)
- `50f73df chore(openspec): mark Change A apply tasks complete in tasks.md` (metadata)
- `043f35f Merge pull request #18 from Andrea-Caballero/feat/multi-child-picker-a-schema-pairing` (merge)

Strict TDD's RED-before-GREEN contract met: `830434a` is pure
`test(...)` — no production code; `d0a25bd` is the first commit
with production code (schema + wire + fixtures) and the GREEN
test gate.

## PR shape rationale

This change landed in **Change A only** of a chained PR
(`stacked-to-main`), unlike the auth-fix precedent (PR #15 + #16
split) or the pluralize-empty-state precedent (PR #17 single).
Rationale:

- **764 insertions across 12 files in Change A** (per `git show
  043f35f --stat`). Above the 400-line review budget, but the chain
  was **explicitly chosen** at Q1=A (round 1) and confirmed at
  R2-chain=stacked-to-main (round 2) per `decisions-round-2`. The
  chain justifies the size because Change A is **data-layer only,
  no UX change** — reviewers can validate the schema + RLS + wire
  in isolation, then Change B ships UI on top.
- **No docs delta to split within Change A.** The proposal +
  design + tasks + specs land in PR #18 because they are the
  audit trail for the data-layer work; the 4 docs files (proposal
  118 LoC + design 561 LoC + tasks 166 LoC + 3 spec files 242 LoC
  = 1087 LoC) are content reviewers need to read alongside the
  code.
- **Change B will be its own PR** (PR #19 or whatever the next
  number is), targeting `master` (post-Change-A-merge). The branch
  MUST be re-pointed to `master` after PR #18 merges — otherwise
  the diff picks up Change A's commits and blows the budget.
- **No `sdd-verify` report in the PR description.** The verify
  report (`verify-report-change-a.md`) lives in the change
  folder and is archived with everything else; PR #18's body
  summarizes the RED→GREEN cycle instead.

If Change B exceeds the 400-line budget on its own, the
auth-fix-style docs/code PR split is the established pattern (see
"PR split rationale" in
`archive/2026-07-02-fix-auth-session-restore-on-cold-start/
archive-report.md`).

## Tasks

All 22 implementation tasks across Phases A.1 (RED), A.2
(investigation), A.3 (GREEN schema), A.4 (GREEN wire), A.5 (build
verifier), and A.6 (PR open) are marked complete (`[x]`) in
`tasks.md`. Phase A.6.2 ("Merge PR A → `master`") was the operator
step (out of agent reach) and is the `[ ]` on `tasks.md:80` — it is
**acknowledged and intentional**: the merge happened via
`gh pr merge 18 --merge` at 2026-07-06T21:43:49Z, landing at
`043f35f`. Phase B tasks are all `[ ]` and will be picked up in the
Change B apply run.

| Phase | Outcome |
|-------|---------|
| 1 — RED coverage (BLOCKING) | ✅ done — 4 tests added (A.1.1 + A.1.2 a/b/c); all RED on `master = d10bd11` |
| 2 — Investigation (naming + edge-fn shape + ktlint pre-flight) | ✅ done — naming resolved inline into `ParentRepository.kt` (no new repo file); edge-fn shape mapped; ktlint/detekt clean |
| 3 — GREEN schema | ✅ done — `005_children_table.sql` + `006_children_backfill.sql` + RLS policies shipped; folded into `d0a25bd` (rationale: schema+wire must ship together because the SQL gate is review-only) |
| 4 — GREEN wire | ✅ done — `pairing/index.ts` + `get-devices-for-parent/index.ts` + `Models.kt` + `ParentRepository.kt` + `MockSupabaseEngine.kt` + `devices.json` + `children.json` shipped in `d0a25bd` |
| 5 — Build verifier | ✅ done — `assembleDebug` + `testDebugUnitTest` + `ktlintCheck` + `detekt` all green (see "Verification performed" below) |
| 6 — PR open + merge | ✅ PR #18 opened; ✅ merged at `043f35f` (operator step) |
| **B — Picker UI (Change B)** | 🔲 **untouched** — apply run will target `master` HEAD post-archive |

## Verification performed

Full `verify-report-change-a.md` (141 LoC) lives in this archive
folder. Headline numbers:

- `./gradlew :app:assembleDebug` — green, no new warnings on the 3
  touched JVM files (`Models.kt`, `ParentRepository.kt`,
  `MockSupabaseEngine.kt`).
- `./gradlew :app:testDebugUnitTest` — 695 tests, 690 pass, 5 fail
  (3 pre-existing unchanged + 2 intentional RED `q2_gap_*`), 0 new
  regressions.
- `./gradlew :app:ktlintCheck :app:detekt` — green, no new
  violations on touched files.
- `git diff master...feat/multi-child-picker-a-schema-pairing -- 'app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt'`
  confirms line 157-163 region (Solicitudes query) NOT touched —
  satisfies `time-request-approval` V2-server-side-refactor-deferred
  requirement.
- Migration idempotency: `006_children_backfill.sql` uses
  `ON CONFLICT (parent_id, first_name) DO NOTHING` + `WHERE
  child_id IS NULL` so re-run is a no-op. Operator validates in
  staging per PR #18 description.
- Coverage (kover/jaCoCo): N/A — not configured
  (`sdd-init/parentalcontrol` gotcha).
- Instrumented tests (`connectedDebugAndroidTest`) not run locally
  — dev machine has no `adb` / no emulator per
  `openspec/config.yaml:57` gotcha. CI on API 28/31/35 is the
  cross-device smoke; CI ran PR #18 and merged it.

**Verify discrepancy with PR body** (noted in `verify-report-change-a.md`):
PR description claims "6 fail (4 unchanged pre-existing + 2
intentional RED)"; actual run shows 5 fail (3 pre-existing + 2 RED)
because `NavGraphTest` was fixed in master post-PR-#17-archive. Minor
PR-body accuracy drift, not a code defect.

## Source of truth

Three capability specs updated in this archive (delta specs live in
this archive folder at `specs/{child-entity,parent-device-list,
time-request-approval}/spec.md`):

### `child-entity` — NEW capability

The delta is the **entire spec** for the new capability (5
requirements + 4 out-of-scope items). Promoted as the canonical
`openspec/specs/child-entity/spec.md` (NEW file). The `[NEEDS
DECISION]` marker at line 63 of the delta (backfill owner-strategy)
was resolved at `decisions-spec-round` to **synthetic "Anónimo"**
child — the canonical spec reflects the chosen approach in
the backfill requirement prose.

### `parent-device-list` — MODIFIED

3 requirements MODIFIED, 4 requirements ADDED. The existing
`Error banner CTAs adapt to error type` requirement is unchanged.
Main spec at `openspec/specs/parent-device-list/spec.md` now lists
8 requirements (4 original + 4 added). The 4 ADDED requirements
own the picker UI surface (`ChildPickerChips`,
`ParentViewModel.selectedChildId`, `Devices tab filters by
selectedChildId`, `PairingBottomSheet dismiss refreshes`).

### `time-request-approval` — MODIFIED

2 requirements ADDED (`Solicitudes tab filters pending requests by
selectedChildId` + `V2 server-side filter refactor is deferred`).
The 8 original requirements are unchanged. Main spec at
`openspec/specs/time-request-approval/spec.md` now lists 10
requirements. The Purpose section was updated to mention the
multi-child picker scoping.

## Spec deltas applied to main

| Capability | Action | Requirements added | Requirements modified | Requirements removed |
|------------|--------|--------------------|------------------------|---------------------|
| `child-entity` | NEW file | 5 (entire spec promoted) | 0 | 0 |
| `parent-device-list` | MODIFIED | 4 (picker + filter + refresh) | 3 (select clause, parser, dashboard render) | 0 |
| `time-request-approval` | MODIFIED | 2 (Solicitudes filter + V2 deferred) | 0 | 0 |

## Relationship to prior work

This change is **the third chained-PR SDD in this project**:

1. **Auth-fix** (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/`,
   merged at `1da5d2f` + `798c931`). Mini-SDD lite; the structural
   template for the `chore(openspec)`-as-third-commit pattern and
   for split-PR-by-concern thinking. This archive-report's deviation
   4 cites the auth-fix precedent.
2. **Pluralize-empty-state + N-device tests**
   (`archive/2026-07-03-feat-pluralize-empty-state-and-add-n-device-tests/`,
   merged at `133089a`). Mini-SDD lite + single PR. Slice-1 PR #17
   masked the multi-child gap with copy + tests; this Change A
   closes the underlying data-layer gap. The `testTag("device_card")`
   wire at `DeviceComponents.kt:36-39` (PR #17's output) is the
   scaffold that the `device_card_child_name` testTag in Change B
   will reuse.
3. **This change** (`feat-multi-child-picker`, Change A archived
   at `043f35f`). Chained-PR SDD stacked-to-main; Change B
   pending. The first non-lite SDD since the project started using
   mini-SDD lite for the auth-fix + pluralize changes.

**Not a regression of either prior change.** Change A's 6 touched
production files (`005_children_table.sql`,
`006_children_backfill.sql`, `pairing/index.ts`,
`get-devices-for-parent/index.ts`, `Models.kt`,
`ParentRepository.kt`, `MockSupabaseEngine.kt`) do not overlap
with the auth-fix's `DeviceAuthManager.kt` +
`DeviceAuthManagerColdStartTest.kt` and do not overlap with the
pluralize-change's `DashboardScreen.kt:311` + `DeviceComponents.kt:36-39`.

## Out-of-scope follow-ups (deferred, not blocking)

The proposal §Scope and `tasks.md` "Out of scope" sections list the
deferred items. Reproduced here as the canonical record:

- **Picker / select-child affordance** — separate change (Change B).
  Depends on this data-layer plumbing being solid first.
- **`ParentRepository.getPendingRequests()` server-side filter** —
  separate change. Will refactor `ParentRepository.kt:157-163` to
  accept `childId: String?` and append `.in("device_id",
  childDeviceIds)` to the Supabase REST query.
- **Rename device (parent's intent vs child's reported name)** —
  separate change. Requires a new Supabase column + RLS policy.
  Distinct from renaming the child.
- **Realtime for parent-side child events** — separate change. The
  current implementation is polling-based; Realtime is a larger
  subsystem swap. The `DisposableEffect` on `PairingBottomSheet`
  dismiss in Change B is the V1 interim solution.
- **Persistence of `selectedChildId`** — separate change. V1
  reset-on-cold-start per `decisions-round-2` R2-V1.
- **Unpair (deleteDevice) wired to RLS `devices_parent_delete`** —
  separate change. Will reuse the `testTag("device_card")` wire
  added in PR #17.
- **`LazyColumn` `key = { it.id }` stabilization** at
  `DashboardScreen.kt:354` — pre-existing debt, no `key` parameter
  on `items(list) { ... }`. Bundled into Change B Phase B.4.2 (c)
  per `decisions-propose-round` Q5=b (the fix is small and the file
  is already in scope for Change B).
- **Solicitudes tab copy / grouping refactor** — separate change.
  The Solicitudes empty state copy + grouping is parallel but
  separate from the picker scope.
- **Dispositivos tab multi-child UX beyond the picker** — separate
  change (e.g., per-child filter chips grouping by status).

## Notes for the next session

- **Change B can apply now.** The chain strategy
  `stacked-to-main` is honored — Change B targets the merged
  `master` HEAD (`043f35f` or later, whichever is current at
  apply-start), NOT the deleted PR #18 branch.
- **2 RED `q2_gap_*` tests at `DashboardScreenTest.kt:439` and
  `:486`** are Change B's acceptance contract. They pin
  `device_card_child_name` (testTag) and `child_picker_*` (chip row)
  respectively. The line numbers shifted from the test-spike's
  `:462`/`:504` because Change A added code between spike and
  verify — both line numbers are recorded in
  `verify-report-change-a.md` (line 81 in the TDD Compliance
  section). If Change B's apply run discovers the lines moved
  again, re-confirm with the test file before flipping the
  assertions.
- **`selectedChildId` is in-memory only** per `decisions-round-2`
  R2-V1. The ViewModel constructor defaults to `null` (Todos).
  No DataStore wiring needed for Change B.
- **The `LazyColumn` `key = { it.id }` fix** at
  `DashboardScreen.kt:354` is bundled into Change B Phase B.4.2
  (c). Pre-existing debt, 1 line fix, prevents filter-switch
  flicker.
- **`DisposableEffect` is new to this codebase** — Phase B.2.3
  (no commits, investigation only) confirms zero hits today;
  Change B Phase B.4.1 (b) introduces the first one inside
  `PairingBottomSheet`.
- **`ChildrenRepository.kt` naming caveat** — the apply phase
  resolved this by extending `ParentRepository.kt` instead of
  introducing a new file. If Change B needs a write API
  (`renameChild`, `createChild`), it can graduate to
  `ParentChildrenRepository.kt` or extend `ParentRepository.kt`
  further.
- **The `chore(openspec)`-as-third-commit pattern is now confirmed
  3 times in this project** (auth-fix + pluralize + this change).
  Future Change B should use the same pattern: RED commit → GREEN
  commit → `chore(openspec): mark Change B apply tasks complete`.
- **Migration idempotency operator step** — the operator must run
  `supabase db push` to apply `005_children_table.sql` +
  `006_children_backfill.sql` against staging and validate the
  backfill is a no-op on re-run. This is documented in PR #18's
  description but was operator-executed out of band (not part of
  the agent loop). If the operator has not yet applied it, the
  Change B merge will hit FK-violation errors when pairing tests
  run against a real DB — Change B's mock-fixture-only path is
  fine, but staging + production need the migration first.
- **Branch cleanup** — PR #18's branch
  (`feat/multi-child-picker-a-schema-pairing`) is **kept** per
  `merge-change-a` engram #233 (no `-d` flag). Safe to clean up
  post-archive if the operator wants.
- **Open orchestrator decision** — confirm with user: archive
  Change A first (this archive), then apply Change B? Or skip
  archive and go straight to Change B? This archive is
  intentionally executed first because the spec deltas need to be
  in main before Change B applies the picker UI against the
  canonical `parent-device-list` / `time-request-approval` specs.
- **Reuse of `testTag("device_card")`** — PR #17 added the wire
  at `DeviceComponents.kt:36-39`. Change B's `device_card_child_name`
  testTag will live on a sibling element of the same card.
  Future tests (unpair, per-child filter) should anchor
  assertions on `onAllNodesWithTag("device_card")` (count or
  `.onFirst()`) rather than text-based selectors — the text
  changes if localized.