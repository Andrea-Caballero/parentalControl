# Design: feat-multi-child-picker

> Chained-PR design (`stacked-to-main`) for `feat-multi-child-picker`. PR A ships the data-layer plumbing (no visible UX change); PR B lands the filter-chip row + filter behavior + RED→GREEN. Decisions cited: Q1=A, Q2=b, Q3=ii, Q4=i, Q5=i (round 1); R2-V1, R2-a, stacked-to-main (round 2); Q1=r, Q2=s, Q3=u, Q4=a, Q5=b (propose round); backfill=A (synthetic "Anónimo"), rename=s (post-dismiss screen), fixture confirmed (spec round). 2 RED Robolectric tests at `DashboardScreenTest.kt:462` and `:504` are the Change B acceptance contract.

## Architecture overview

```
              ┌──────────────────────────────────────────────────────────┐
              │  master = d10bd11  (slice-1 merged: copy + RED tests)  │
              └──────────────────────────────────────────────────────────┘
                                       │
                                       ▼  PR A (≈250 LoC) ── schema+domain+pairing
   ┌──────────────────────────────────────────────────────────────────────────┐
   │  supabase/migrations/00X_children_table.sql                             │
   │    • CREATE TABLE children (id UUID PK, parent_id UUID FK→auth.users,  │
   │      first_name TEXT NOT NULL, created_at, updated_at)                   │
   │    • UNIQUE (parent_id, first_name)                                      │
   │    • ALTER TABLE devices ADD COLUMN child_id UUID NULL FK→children.id   │
   │      ON DELETE SET NULL                                                  │
   │    • Backfill: synthetic "Anónimo" child per parent (idempotent via     │
   │      ON CONFLICT (parent_id, first_name) DO NOTHING); link all pre-     │
   │      existing devices.child_id to that row.                             │
   │    • CREATE INDEX idx_children_parent_id ON children(parent_id)          │
   │    • ENABLE RLS + children_parent_select/insert/update/delete           │
   │  supabase/functions/get-devices-for-parent/index.ts (modified)          │
   │    .select("..., child_id, child:children(id, first_name)")              │
   │  supabase/functions/pairing/index.ts (modified)                          │
   │    Accept {child_first_name} body param; INSERT children row + UPDATE   │
   │    devices.child_id in same txn. Reject empty name with 400.            │
   │  app/src/main/java/.../domain/model/Models.kt                            │
   │    • NEW data class Child(id, parentId, firstName, createdAt, updatedAt)│
   │    • ChildDevice.child: Child? (nullable, defaults to null)              │
   │  app/src/main/java/.../data/repository/ParentRepository.kt               │
   │    DeviceDto: add child_id, child_first_name. toChildDevice() hydrates  │
   │    ChildDevice.child.                                                    │
   │  app/src/main/java/.../data/remote/MockSupabaseEngine.kt                 │
   │    DeviceFixture adds child_id, child_first_name (default null).         │
   │  app/src/main/assets/mock-supabase/                                      │
   │    • devices.json — UPDATE: add child_id/child_first_name per row       │
   │    • children.json — NEW asset: 2 children (Lucas, Sofía)               │
   │  Tests: ChildRepository CRUD+RLS (new), ParentRepository wire-shape,     │
   │  MockSupabaseEngine parse cases, migration idempotency.                 │
   │  Outcome: 9 existing DashboardScreenTest cases stay GREEN (no UI change)│
   └──────────────────────────────────────────────────────────────────────────┘
                                       │  merge to main
                                       ▼  PR B (≈200 LoC) ── picker UI + filter
   ┌──────────────────────────────────────────────────────────────────────────┐
   │  ParentViewModel.kt                                                      │
   │    • NEW _selectedChildId: MutableStateFlow<String?>(null)               │
   │    • NEW fun setSelectedChild(id: String?)                               │
   │    • Derived filteredDevices (Todos→all, Child→child.id==id)              │
   │    • loadDevices() resets stale selection when id no longer matches.     │
   │  DeviceComponents.kt                                                     │
   │    • NEW @Composable ChildPickerChips(children, selected, onSelect) —    │
   │      Material 3 FilterChip row inside LazyRow (mirrors :329-335 minutes,│
   │      :431-438 age-band patterns). testTag("child_picker_chip_all") for   │
   │      "Todos"; testTag("child_picker_chip_$childId") per child.           │
   │  DashboardScreen.kt                                                      │
   │    • Render ChildPickerChips between TabRow and tab body when           │
   │      devices.distinctBy{ it.child?.id }.size >= 2.                       │
   │    • Filter listState.items (Success) + devices (Loading-with-cache).    │
   │    • Bundle the LazyColumn `key = { it.id }` fix at :354 (propose-Q5=b).│
   │    • Solicitudes tab: filter by deviceId IN childDevices (client-side,   │
   │      no new HTTP call). Badge keeps UNFILTERED count — by design.        │
   │    • DeviceCard: emit testTag("device_card_child_name") when child!=null,│
   │      literal "Sin asignar" otherwise.                                    │
   │  PairingBottomSheet (DeviceComponents.kt:363)                            │
   │    • DisposableEffect(Unit){ onDispose{ viewModel.loadDevices() } }       │
   │  RenameChildDialog (NEW composable, Material 3 Dialog usePlatformDefault │
   │    Width=false — see §3 form-factor recommendation).                     │
   │  Tests: convert 2 RED at DashboardScreenTest.kt:462 and :504 → GREEN.   │
   │  New: picker-hidden-when-N=1, picker-visible-N≥2, chip-tap-filters,     │
   │  Solicitudes-tab-filter, DisposableEffect-on-dismiss refreshes.          │
   └──────────────────────────────────────────────────────────────────────────┘
```

**Runtime data flow** (after PR B):

```
DashboardScreen composition
  → ParentViewModel.devices (raw, all devices)
  → derive distinctChildren = devices.distinctBy { it.child?.id }
  → if (distinctChildren.size >= 2) ChildPickerChips(...)
  → filter devices by selectedChildId (null = all)
  → LazyColumn { items(filtered, key = { it.id }) { DeviceCard(...) } }
```

Notifications badge continues to read `pendingRequests.size` directly (unfiltered) — the badge represents "you have N pending verdicts", independent of scope.

## Change A — Schema + Domain + Pairing

### A.1 `children` table (`supabase/migrations/00X_children_table.sql`)

| Column        | Type        | Constraint                                                    |
|---------------|-------------|---------------------------------------------------------------|
| `id`          | UUID        | PK, default `gen_random_uuid()`                               |
| `parent_id`   | UUID        | NOT NULL, FK → `auth.users(id)` ON DELETE CASCADE             |
| `first_name`  | TEXT        | NOT NULL, trimmed before insert (CHECK length 1-32)           |
| `created_at`  | TIMESTAMPTZ | NOT NULL, default `now()`                                     |
| `updated_at`  | TIMESTAMPTZ | NOT NULL, default `now()` (maintained by trigger or app code) |

Constraints / indexes:
- `UNIQUE (parent_id, first_name)` — prevents "two Lucas" confusion.
- `CREATE INDEX idx_children_parent_id ON children(parent_id)` — supports `parent_id = auth.uid()` RLS hot path.
- `ENABLE ROW LEVEL SECURITY` + policies (see A.3).

### A.2 `devices.child_id` FK

- `ALTER TABLE devices ADD COLUMN child_id UUID NULL` + `ADD CONSTRAINT fk_devices_child FOREIGN KEY (child_id) REFERENCES children(id) ON DELETE SET NULL`.
- Nullable so the migration is non-destructive — devices paired before this change keep `child_id = NULL` until the backfill script runs.
- `ON DELETE SET NULL` (not CASCADE) — deleting a child un-assigns its devices, preserving history.

### A.3 RLS policies (append to `002_rls_policies.sql`)

```sql
ALTER TABLE children ENABLE ROW LEVEL SECURITY;

CREATE POLICY children_parent_select ON children
    FOR SELECT USING (parent_id = auth.uid());
CREATE POLICY children_parent_insert ON children
    FOR INSERT WITH CHECK (parent_id = auth.uid());
CREATE POLICY children_parent_update ON children
    FOR UPDATE USING (parent_id = auth.uid())
                  WITH CHECK (parent_id = auth.uid());
CREATE POLICY children_parent_delete ON children
    FOR DELETE USING (parent_id = auth.uid());
```

Plus an additional `WITH CHECK` on the existing `devices_parent_delete` policy to allow `parent_id`-scoped updates to `devices.child_id` (the current policy lets the parent delete; it does not yet let them assign). New policy:

```sql
CREATE POLICY devices_parent_update_child_assignment ON devices
    FOR UPDATE
    USING (parent_id = auth.uid())
    WITH CHECK (parent_id = auth.uid());
-- Appends to (does NOT replace) the existing RLS file. Existing reads/deletes
-- remain untouched.
```

### A.4 Backfill (mandatory pre-release per spec-round decision A)

Idempotent SQL script shipped in `supabase/migrations/00X_children_backfill.sql` (or applied via Supabase SQL editor pre-release):

```sql
DO $$
DECLARE
    anon_child_id UUID;
    parent_record RECORD;
BEGIN
    FOR parent_record IN
        SELECT DISTINCT parent_id FROM devices WHERE child_id IS NULL
    LOOP
        INSERT INTO children (parent_id, first_name)
        VALUES (parent_record.parent_id, 'Anónimo')
        ON CONFLICT (parent_id, first_name) DO NOTHING
        RETURNING id INTO anon_child_id;

        IF anon_child_id IS NULL THEN
            SELECT id INTO anon_child_id
            FROM children
            WHERE parent_id = parent_record.parent_id AND first_name = 'Anónimo';
        END IF;

        UPDATE devices SET child_id = anon_child_id
        WHERE parent_id = parent_record.parent_id AND child_id IS NULL;
    END LOOP;
END $$;
```

Idempotency: re-running the script is a no-op because `UNIQUE (parent_id, first_name)` makes the `INSERT ... ON CONFLICT DO NOTHING` safe and the subsequent `UPDATE` finds no NULL `child_id`. The parent renames "Anónimo" via the post-dismiss "name this child" prompt (§3) — that UPDATE goes through `children_parent_update`, RLS-guarded.

### A.5 Domain model (`app/src/main/java/com/tudominio/parentalcontrol/domain/model/Models.kt`)

```kotlin
@Serializable
data class Child(
    val id: String,            // UUID as String (matches repo convention)
    val parentId: String,
    val firstName: String,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class ChildDevice(
    // ... existing fields ...
    val child: Child? = null    // NEW: nullable for back-compat
)
```

`child: Child? = null` keeps every existing test + call site that doesn't read the field compiling without source changes. The dashboard's `device_card_child_name` testTag fires only when `child != null`.

### A.6 Wire shape (`ParentRepository.kt` and `MockSupabaseEngine.kt`)

`DeviceDto` extends with:
```kotlin
val child_id: String? = null,
val child_first_name: String? = null,
```
Hydration: `child = if (child_id != null && child_first_name != null) Child(id = child_id, parentId = "", firstName = child_first_name, createdAt = "", updatedAt = "") else null`. `parentId`/timestamps are left empty on the device payload (the dashboard only renders `first_name`; if a future screen needs full `Child`, the parent-side `getChildren()` call hydrates them).

`DeviceFixture` mirrors the same nullable defaults — `MockSupabaseEngine.devices()` parses the same JSON the real edge function returns.

`MockSupabaseEngine.httpClient` adds a `path.endsWith("/rest/v1/children")` branch returning `children.json` (parent reads it after renaming). This is required for the rename prompt's "save and refetch" path to round-trip against the mock engine in production-debug builds.

### A.7 Pairing flow migration

`supabase/functions/pairing/index.ts` accepts `{child_first_name: string}` in the body. The flow inside the edge function becomes:

```
1. Validate body: child_first_name.trim().length in 1..32, else 400.
2. INSERT INTO children (parent_id, first_name)
     VALUES (auth.uid(), child_first_name)
     ON CONFLICT (parent_id, first_name) DO NOTHING
   RETURNING id;
3. If RETURNING id is null (conflict), SELECT id from children
   WHERE parent_id = auth.uid() AND first_name = child_first_name.
4. INSERT INTO devices (..., child_id) VALUES (..., $childId).
5. Return { success, child_id, device_id }.
```

Parent-side `PairingBottomSheet` flow stays the same on the child-side (QR scan, no name capture). On the parent-side, after the QR is generated and the child-side completes the pairing (the child's HTTP call to `pairing/index.ts` now reads `child_first_name` from a header the child-side already sends — kept simple: parent-side inserts the name BEFORE the child scans, stored in a `pairing_codes.child_first_name` column added in the same migration). Concretely:

| File | Change |
|---|---|
| `supabase/migrations/001_initial_schema.sql` (or new `00X`) | `ALTER TABLE pairing_codes ADD COLUMN child_first_name TEXT` |
| `supabase/functions/pairing/index.ts:71-115` | Read `child_first_name` from the pairing_codes row; INSERT children + UPDATE devices.child_id atomically. Reject empty → 400. |
| `app/src/main/java/.../ui/parent/components/DeviceComponents.kt:363` | `PairingBottomSheet` step 1 adds an OutlinedTextField for "Nombre del niño" (`testTag("pairing_child_first_name")`). Validation: non-blank. Calls existing `createPairingCode` which now sends the name through. |

### A.8 `get-devices-for-parent` edge function (`.select(...)` extended)

```ts
.select(
  "id, device_name, device_model, os_version, app_version, " +
  "device_state, policy_version, last_seen_at, " +
  "child_id, child:children(id, first_name)"
)
```

Supabase's resource embedding returns `child` as a nested object (or `null` for unassigned). The Kotlin parser handles both shapes.

### A.9 Mock fixtures (Decision Q4=a — read from assets)

`app/src/main/assets/mock-supabase/devices.json` (UPDATE):

```json
[
  {
    "id": "dev-001", "device_name": "Galaxy Tab S6 Lite", "device_model": "SM-P610",
    "os_version": "34", "app_version": "1.0.0", "device_state": "ACTIVE",
    "policy_version": 3, "last_seen_at": "2026-06-19T20:55:00Z",
    "child_id": "child-lucas", "child_first_name": "Lucas"
  },
  {
    "id": "dev-002", "device_name": "Moto G32", "device_model": "moto g32",
    "os_version": "33", "app_version": "1.0.0", "device_state": "DOWNTIME",
    "policy_version": 1, "last_seen_at": "2026-06-19T20:58:00Z",
    "child_id": "child-lucas", "child_first_name": "Lucas"
  },
  {
    "id": "dev-003", "device_name": "Pixel 7a", "device_model": "GWKK3",
    "os_version": "35", "app_version": "1.0.0", "device_state": "LOCKED",
    "policy_version": 7, "last_seen_at": "2026-06-19T20:59:30Z",
    "child_id": "child-sofia", "child_first_name": "Sofía"
  }
]
```

`app/src/main/assets/mock-supabase/children.json` (NEW):

```json
[
  {"id": "child-lucas", "parent_id": "parent-demo", "first_name": "Lucas",
   "created_at": "2026-06-01T00:00:00Z", "updated_at": "2026-06-01T00:00:00Z"},
  {"id": "child-sofia", "parent_id": "parent-demo", "first_name": "Sofía",
   "created_at": "2026-06-01T00:00:00Z", "updated_at": "2026-06-01T00:00:00Z"}
]
```

Coverage matrix:
- N=2 children → picker visible (Q5=i satisfied: `distinctBy { it.child?.id }.size = 2 >= 2`).
- N=2 devices per Lucas → grouping visible in devices tab.
- Sofía alone (filter "Sofía") → 1 card; Lucas alone → 2 cards; "Todos" → 3 cards.
- Production-debug builds see the picker (Q4=a) so the rename prompt is testable against the real MockSupabaseEngine.

### A.10 Change A test strategy

| Layer | What | Where |
|---|---|---|
| Unit | `DeviceDto.toChildDevice()` hydrates `child` from `child_id`+`child_first_name` | `ParentRepositoryTest.kt` (extend `:223-274`) |
| Unit | `DeviceDto.toChildDevice()` leaves `child = null` when both fields absent | same file |
| Unit | `MockSupabaseEngine.devices()` parses the 3-device fixture with child fields | `MockSupabaseEngineTest.kt` |
| Unit | Migration idempotency: synthetic "Anónimo" not duplicated on re-run | new migration test using `MigrationTestHelper` + a pre-populated `devices` table |
| Integration | `children_parent_insert` RLS: parent A cannot insert child with `parent_id = B` | SQL test against staging project |

## Change B — Picker UI

### B.1 State in `ParentViewModel` (Decision R2-V1: in-memory only)

```kotlin
private val _selectedChildId = MutableStateFlow<String?>(null)  // null = Todos
val selectedChildId: StateFlow<String?> = _selectedChildId.asStateFlow()

fun setSelectedChild(id: String?) { _selectedChildId.value = id }

// Pure derivation — no extra fetch.
val filteredDevices: StateFlow<List<ChildDevice>> =
    combine(_devices, _selectedChildId) { list, id ->
        if (id == null) list else list.filter { it.child?.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

Stale-selection reset (per spec scenario "Stale selection is reset after a fetch"):

```kotlin
fun loadDevices() { /* ... after _devices.value = list ... */
    val validIds = list.mapNotNull { it.child?.id }.toSet()
    if (_selectedChildId.value !in validIds) {
        _selectedChildId.value = null
    }
}
```

Default `null` on every cold start — no DataStore, no persistence (R2-V1).

### B.2 `ChildPickerChips` composable (NEW — `DeviceComponents.kt`)

Mirrors the established `FilterChip` pattern at `:329-335` (minutes) and `:431-438` (age bands). Wraps chips in a `LazyRow` so N≥5 scrolls horizontally.

```kotlin
@Composable
fun ChildPickerChips(
    children: List<Child>,           // distinct children, sorted by first_name
    selected: String?,               // null = "Todos"
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("child_picker_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Todos") },
                modifier = Modifier.testTag("child_picker_chip_all")
            )
        }
        items(children, key = { it.id }) { child ->
            FilterChip(
                selected = selected == child.id,
                onClick = { onSelect(child.id) },
                label = { Text(child.firstName) },
                modifier = Modifier.testTag("child_picker_chip_${child.id}")
            )
        }
    }
}
```

Snake-case testTags match the project's convention (`device_card`, `pairing_qr`, `auth_missing_sign_in_cta`).

### B.3 DashboardScreen integration

Between `TabRow` and the `when (selectedTab)` branch in `DashboardScaffold`:

```kotlin
val distinctChildren = devices
    .mapNotNull { it.child }
    .distinctBy { it.id }
    .sortedBy { it.firstName }
if (distinctChildren.size >= 2) {
    ChildPickerChips(
        children = distinctChildren,
        selected = selectedChildId,
        onSelect = viewModel::setSelectedChild
    )
}
```

Filter applied to BOTH tabs at the UI layer (Decision Q3=ii):

```kotlin
val filteredDevices = remember(devices, selectedChildId) {
    if (selectedChildId == null) devices
    else devices.filter { it.child?.id == selectedChildId }
}
val filteredRequests = remember(pendingRequests, selectedChildId, filteredDevices) {
    if (selectedChildId == null) pendingRequests
    else {
        val allowedDeviceIds = filteredDevices.map { it.id }.toSet()
        pendingRequests.filter { it.deviceId in allowedDeviceIds }
    }
}
```

**LazyColumn key fix** (Decision Q5=b, bundle the pre-existing debt):

```kotlin
LazyColumn(...) {
    items(filteredDevices, key = { it.id }) { device ->  // ← key = it.id
        DeviceCard(device = device, ...)
    }
}
```

Notifications badge at `DashboardScreen.kt:176-190` keeps reading the UNFILTERED `pendingRequests.size` — by design (the badge represents "you have N pending verdicts", independent of the current scope).

### B.4 `DeviceCard` child-identity surface

In `DeviceComponents.kt:28-77`, after the device name + model row:

```kotlin
device.child?.let { child ->
    Text(
        text = child.firstName,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("device_card_child_name")
    )
} ?: Text(
    text = "Sin asignar",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.outline
)
```

This is the contract the 2 RED tests pin (DashboardScreenTest.kt:462 + the new DashboardScreenTest.kt:504 picker test).

### B.5 `PairingBottomSheet` refresh hook (Decision R2-a)

`DeviceComponents.kt:363` PairingBottomSheet gains:

```kotlin
DisposableEffect(Unit) {
    onDispose {
        viewModel.loadDevices()  // Refresh once on dismiss (success OR cancel).
    }
}
```

PairingBottomSheet itself does NOT collect the dismiss signal — the sheet's `ModalBottomSheet` is composed inside `DashboardScaffold` (`DashboardScreen.kt:259`), so its `onDispose` runs whenever `showPairingSheet` flips false. The refresh is unconditional on dismiss per the spec scenario "Dismissing the sheet without pairing is a no-op" (the `loadDevices()` call is cheap; the chip row simply re-renders with the unchanged list).

### B.6 Rename prompt — form-factor decision (Recommendation)

Per the spec-round decision (s), the rename/assign flow is a post-dismiss screen. Three options were considered for the form factor:

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| **(top)** top-level Compose `screen` route | Cleanest separation, full focus | Forces a navigation re-architecture (current `DashboardScreen` uses a private `NavTarget` sealed class; adding a third target + a back-stack edge case is scope creep for V1) | **Rejected** |
| **(dialog)** Material 3 `AlertDialog` overlay | Lightest UX, modal context | The rename needs a `TextField` + validation message; an `AlertDialog` becomes cramped on small screens and fights with the existing `PairingBottomSheet` modal stack | **Rejected** |
| **(modal)** Material 3 `Dialog` with `usePlatformDefaultWidth = false` | Full-screen focus for name entry, Cancel button is unambiguous (no trap), matches the modal-surface pattern already established by `PairingBottomSheet`, trivially testable via a `showRenameDialog: Boolean` flag in `DashboardScaffold` | Slightly heavier than a dialog | **RECOMMENDED** |

**Recommendation: Material 3 full-screen `Dialog` with `properties = DialogProperties(usePlatformDefaultWidth = false)`** containing a single `OutlinedTextField` ("Nombre del niño") + Cancel + Guardar buttons. Cancel must dismiss without throwing an exception; the data-driven trigger (any device arriving with `child_id = NULL`) re-opens it on the next load. The dialog state is owned by `DashboardScaffold` as `var showRenameDialog by remember { mutableStateOf(false) }` and only opens when `devices.any { it.child == null }`. The rename calls a new `ParentRepository.renameChild(childId: String?, newFirstName: String)` which `UPDATE children SET first_name = $newFirstName WHERE id = $childId AND parent_id = auth.uid()` — RLS-guarded by `children_parent_update`. After rename, `loadDevices()` refreshes the dashboard.

If the user prefers a top-level screen instead, this becomes a follow-up change in a separate SDD cycle (route registration + back-stack handling) — out of scope here.

### B.7 Change B test strategy

| Test | File | Pins |
|---|---|---|
| Convert `:462` to GREEN: `child_name`/`child_first_name` testTag present | `DashboardScreenTest.kt:439` | `device_card_child_name` renders Lucas + Sofía |
| Convert `:504` to GREEN: picker testTag present | `DashboardScreenTest.kt:486` | `child_picker_chip_all` + per-child chips visible |
| NEW: `picker_hidden_when_one_child` | `DashboardScreenTest.kt` | `distinctChildren.size <= 1` → no `child_picker_*` in composition tree |
| NEW: `chip_tap_filters_devices_tab_to_one_child` | same | Tap Sofía → only dev-003 renders |
| NEW: `chip_tap_filters_solicitudes_tab_to_one_child` | same | Tap Lucas → only requests for dev-001/dev-002 |
| NEW: `todos_chip_restores_unfiltered_list` | same | Tap Todos → 3 cards visible |
| NEW: `pairing_dismiss_triggers_loadDevices_via_disposable_effect` | same | Mock VM records `loadDevices()` count after sheet dismiss |
| NEW: `lazycolumn_key_keeps_item_identity_on_filter_switch` | same | Snapshot test: item IDs stable across filter changes |

## Cross-cutting

### File changes summary

| File | Action | Change A | Change B |
|---|---|---|---|
| `supabase/migrations/00X_children_table.sql` | Create | ✓ | |
| `supabase/migrations/002_rls_policies.sql` | Modify | ✓ (append policies) | |
| `supabase/migrations/00X_children_backfill.sql` | Create | ✓ | |
| `supabase/functions/pairing/index.ts:71-115` | Modify | ✓ | |
| `supabase/functions/get-devices-for-parent/index.ts:73-78` | Modify | ✓ | |
| `app/src/main/java/.../domain/model/Models.kt` | Modify | ✓ (Child + child field) | |
| `app/src/main/java/.../data/repository/ParentRepository.kt:513-546` | Modify | ✓ (wire + parser) | ✓ (renameChild helper) |
| `app/src/main/java/.../data/remote/MockSupabaseEngine.kt:182-191` | Modify | ✓ (DeviceFixture + children.json route) | |
| `app/src/main/java/.../viewmodel/ParentViewModel.kt` | Modify | | ✓ (selectedChildId + filteredDevices) |
| `app/src/main/java/.../ui/parent/components/DeviceComponents.kt` | Modify | ✓ (PairingBottomSheet name field + DisposableEffect) | ✓ (ChildPickerChips + RenameChildDialog + DeviceCard child row) |
| `app/src/main/java/.../ui/parent/screens/DashboardScreen.kt` | Modify | | ✓ (chip row + filter on both tabs + LazyColumn key) |
| `app/src/main/assets/mock-supabase/devices.json` | Modify | ✓ | |
| `app/src/main/assets/mock-supabase/children.json` | Create | ✓ | |
| `app/src/test/.../DashboardScreenTest.kt:462,:504` | Modify | | ✓ (RED→GREEN + new tests) |
| `app/src/test/.../ParentRepositoryTest.kt:223-274` | Modify | ✓ | ✓ |
| `app/src/test/.../MockSupabaseEngineTest.kt` | Modify | ✓ | |

### Interfaces / contracts

```kotlin
// NEW — Models.kt
data class Child(
    val id: String, val parentId: String, val firstName: String,
    val createdAt: String, val updatedAt: String
)

// MODIFIED — Models.kt
data class ChildDevice(
    // ... existing fields ...
    val child: Child? = null
)

// NEW — ParentViewModel.kt
val selectedChildId: StateFlow<String?>
val filteredDevices: StateFlow<List<ChildDevice>>
fun setSelectedChild(id: String?)

// NEW — ParentRepository.kt
suspend fun renameChild(
    childId: String,
    newFirstName: String
): Result<Child>

// NEW — DeviceComponents.kt
@Composable fun ChildPickerChips(
    children: List<Child>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
)

@Composable fun RenameChildDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
)
```

### Out of scope (frozen)

- V2 server-side Solicitudes filter on `ParentRepository.getPendingRequests()` (`ParentRepository.kt:157-163` static-query refactor). Deferred to a later change; current V1 filter is client-side in `DashboardScaffold`.
- Persistence of `selectedChildId` across cold start (R2-V1).
- Real-time pairing notifications when the child pairs without the parent opening the sheet (acceptable V1 stale window).
- Renaming a child from a top-level screen (the rename dialog suffices for V1; a dedicated rename screen is a follow-up).
- Deleting a child from the parent UI (RLS permits it; UI affordance deferred).
- Manual sort controls on the device list (only `last_seen_at` DESC).

### Migration / Rollout

| Phase | Action | Owner |
|---|---|---|
| Pre-release | Apply `00X_children_table.sql` migration to production via Supabase SQL editor. | User (out of agent reach) |
| Pre-release | Apply `00X_children_backfill.sql` script — every `devices.child_id` becomes non-NULL, linked to a synthetic "Anónimo" child per parent. | User |
| PR A merge | Code lands; existing dashboard renders identically (no UX change). | Agent |
| PR B merge | Picker appears for parents with N≥2 children; for parents with N≤1, dashboard is unchanged. | Agent |
| Rollback A | `git revert` of PR A + `DROP TABLE children CASCADE; ALTER TABLE devices DROP COLUMN child_id;` — destructive on the `child_id` data but acceptable since V1 data is recoverable from the backfill log. |
| Rollback B | Pure UI revert; `selectedChildId` is in-memory. `git revert` restores pre-change dashboard without data loss. |

### Open questions

**None.** All forks frozen by the spec-round engram (#226): backfill=A (synthetic "Anónimo"), rename=s (post-dismiss screen), fixtures confirmed, rename form-factor recommended above (modal — Material 3 Dialog). No new decisions needed before `sdd-tasks`.