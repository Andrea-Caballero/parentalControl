# Design: wire-pairing-and-approval-end-to-end

> Technical design for the 5-PR chain that replaces parent-side and child-side mocks with real Supabase edge-function calls, adds QR rendering, drains the outbox from WorkManager, and lights up the parent app-block UI.

## 0. Context baseline (verified before designing)

| Dimension | Current state (verified) | Source |
|---|---|---|
| Kotlin / Compose BOM / AGP | 2.0.21 / 2024.10.01 / 8.5.2 | `gradle/libs.versions.toml` |
| Java / `compileSdk` / `minSdk` | 17 / 35 / 26 | `app/build.gradle.kts` |
| `qrose` status | **Not present** — first addition in PR 1 | absent from `gradle/libs.versions.toml` |
| `OutboxUploadWorker` | **Already exists** in `workers/Workers.kt:76-122`; calls `syncManager.drainOutbox()` which **DELETES** successful rows (not marks `processed`) | `sync/SyncManager.kt:225` |
| Local Room `OutboxEntity` | Has `intentos` (not `retries`), **no** `processed`, **no** `processed_at`, **no** `device_id` | `data/local/AppDatabase.kt:53-62` |
| Supabase `outbox` table | Has `processed`, `processed_at`, `device_id`, `dedup_key`; **no** `retries` | `supabase/migrations/001_initial_schema.sql:217-230` |
| `approve-request` edge function | Inserts `grants` with `source = "EXTRA_TIME"` (line 117); spec says `source = "MANUAL"` | `supabase/functions/approve-request/index.ts:117` vs `outbox-drain/spec.md:43` |
| `create-pairing-code` | Returns 8-char code (loop runs 8 times — comment "6 caracteres" is wrong); returns `deeplink: parentalcontrol://pair?code=…` | `supabase/functions/create-pairing-code/index.ts:96-105` |
| `pairing` edge function | FCM-to-parent is a **TODO** (lines 228-239); explicitly logged "skipping FCM to parent: parent device_id not yet wired" | `supabase/functions/pairing/index.ts:228-239` |
| `PairingManager.pairWithCode` | Mock body but `parsePairingResponse`/`buildPairingRequest` exist and are unused (`pairing/PairingManager.kt:196-307`) | file |
| `ParentRepository.createPairingCode` | Mock; calls broken reflective `Context::class.java.getMethod("getSharedPreferences"...)` (line 53-55) — broken at runtime | `data/repository/ParentRepository.kt:51-80, 185-205` |
| `ParentViewModel` | `@HiltViewModel` but `ParentRepository()` is a plain `new` (line 24) — not injected | `viewmodel/ParentViewModel.kt:22-24` |
| Worker scheduling | Triggered from `BootReceiver` → `WorkerInitializer.initialize`, **not** from `ParentalControlApp.onCreate` (proposal line is inaccurate) | `boot/BootReceiver.kt:50` |
| RLS for parents | `devices_parent_select`, `time_requests_parent_select`, `grants_parent_select` keyed by `parent_id = auth.uid()`; agent policies key on `auth.jwt() ->> 'device_id'` | `supabase/migrations/002_rls_policies.sql:37-47, 113-122, 153-161` |

These baselines drive every design decision below; **do not assume the proposal's framing is correct** — the worker, the room schema, and the edge-function field names all need reconciliation.

---

## 1. Per-PR technical approach

### PR 1 — Wire pairing backend + QR rendering

**Goal**: `ParentRepository.createPairingCode` and `PairingManager.pairWithCode` hit real Supabase; `PairingBottomSheet` step 2 renders a scannable QR from the returned deeplink; the `parentalcontrol://pair?code=…` deeplink routes to the child pairing screen with the code pre-filled.

**Files touched (4)**:

| File | Edit |
|---|---|
| `gradle/libs.versions.toml` | Add `qrose = "1.0.0"` and library entry `qrcode = { module = "io.github.alexzhirkevich:qrose", version.ref = "qrose" }` |
| `app/build.gradle.kts` | Add `implementation(libs.qrcode)` in the network/UI block |
| `app/src/main/java/com/example/parentalcontrol/data/repository/ParentRepository.kt` | Convert from plain singleton to `@Singleton class ParentRepository @Inject constructor(@ApplicationContext context: Context, private val authManager: DeviceAuthManager, private val clientProvider: SupabaseClientProvider)`. Replace the reflective `getSharedPreferences` call in `getLocalPairedDevices()` and `savePendingDevice()` with injected context. Replace `createPairingCode` mock body with real `POST /functions/v1/create-pairing-code` via `clientProvider.httpClient`. The response JSON shape: `{ code, expires_at, deeplink, qr_data }` — keep `PairingCodeResult(code, expiresAt, deeplink)`. Add `Result<PairingCodeResult>` return type (per `parent-device-list` spec precedent). |
| `app/src/main/java/com/example/parentalcontrol/pairing/PairingManager.kt` | Replace mock body in `pairWithCode` with real `POST /functions/v1/pairing` via `clientProvider.httpClient`. The existing `parsePairingResponse`/`buildPairingRequest` can be reused — the parse path already handles 200/404/409/410. Keep the function name and the deeplink extraction in `pairWithQr` (the QR can also arrive as a raw URL or prefixed code). |
| `app/src/main/java/com/example/parentalcontrol/ui/parent/components/DeviceComponents.kt` | In `PairingBottomSheet` step 2 (lines 445-508), add the QR render. Use `rememberQrCodePainter(data = code.deeplink)` from `io.github.alexzhirkevich.qrose`, render at 240dp × 240dp, with the 8-char code as monospace text below for manual fallback. Replace the existing `Icon(Icons.Default.Person, …)` placeholder (line 480-485). |
| `app/src/main/AndroidManifest.xml` | Add intent-filter to `MainActivity` (or a dedicated `PairingDeeplinkActivity`) for `parentalcontrol://pair?code=…`: `<data android:scheme="parentalcontrol" android:host="pair" />`. The receiving activity forwards the `code` query param to `PairingScreen` via `SavedStateHandle`. |

**Order of edits within PR 1** (TDD-first):

1. **Add `qrose` to version catalog + dependency** → run `./gradlew :app:dependencies | grep qrose` and `./gradlew assembleDebug` to validate the lib resolves against the current Compose BOM 2024.10.01. If it does not, see §2 (alternative).
2. **Write failing unit test** `ParentRepositoryTest.createPairingCode_returns_real_code_from_supabase` that uses `MockEngine` from `ktor-client-mock` (already in `libs.versions.toml`) to assert the request URL, the Authorization header, and the parsed `PairingCodeResult`. Run → red.
3. **Rewrite `ParentRepository.createPairingCode`** to inject `SupabaseClientProvider.httpClient` and call the function. Drop the broken `savePendingDevice()` call — the backend now owns the pairing record; the parent UI no longer needs to fake a "pending" device. Run → green.
4. **Convert `ParentRepository` to Hilt-injectable** in `di/AppModule.kt` (add `@Provides @Singleton fun provideParentRepository(...)`). Update `ParentViewModel` to receive the repository via constructor (replace `private val repository = ParentRepository()` on line 24 with the Hilt-injected field). Update all ViewModel callsites that used the old constructor.
5. **Write failing unit test** `PairingManagerTest.pairWithCode_real_supabase_returns_success` with the same MockEngine pattern. Run → red.
6. **Rewrite `PairingManager.pairWithCode`** to call `httpClient.post(...)` and route the response through the existing `parsePairingResponse`. Keep the `extractCodeFromQr` logic intact.
7. **Write failing Compose UI test** `DeviceComponentsTest.qr_renders_for_pairing_step_2` that pumps `PairingBottomSheet` and asserts a QR painter was composed. Run → red.
8. **Add QR rendering** in `DeviceComponents.kt:445-508`. Run → green.
9. **Add deeplink intent-filter** in `AndroidManifest.xml`. Add a `PairingDeeplinkActivity` (or extend `MainActivity.onNewIntent` handling) to forward the `code` query param to `PairingScreen`. Write a Robolectric test that simulates the intent and asserts the navigation argument is set.

**Dependencies inside PR 1**: `qrose` must compile (`assembleDebug`) before any UI test can pump a Composable that uses it. The Hilt module change in step 4 must precede step 5 because step 5 needs `ParentRepository` to be wired.

---

### PR 2 — Cross-device device list

**Goal**: `ParentRepository.getDevices()` returns the parent's devices from a new RLS-aware edge function; `DashboardScreen` consumes the real list with loading, error, and empty states.

**Files touched (1 new + 1 modified)**:

| File | Edit |
|---|---|
| `supabase/functions/get-devices-for-parent/index.ts` | **New**. Deno edge function: reads `Authorization` JWT, calls `supabaseAdmin.auth.getUser(token)`, then `supabaseAdmin.from("devices").select("id, device_name, device_model, os_version, app_version, device_state, last_seen_at").eq("parent_id", user.id).order("last_seen_at", { ascending: false })`. Returns 200 with array, 401/403 with `{ error }`. Does NOT use service-role to bypass RLS — the RLS policy `devices_parent_select` (`parent_id = auth.uid()`) does the filtering. Use a regular `createClient(SUPABASE_URL, SUPABASE_ANON_KEY, { global: { headers: { Authorization: ... } } })` and rely on the RLS to scope rows. **Do not** import `SUPABASE_SERVICE_ROLE_KEY` for this function — that would defeat the security model. |
| `app/src/main/java/com/example/parentalcontrol/data/repository/ParentRepository.kt` | Replace mock body of `getDevices()` with `clientProvider.httpClient.post("${SUPABASE_URL}/functions/v1/get-devices-for-parent") { … }`. Return type changes from `List<ChildDevice>` to `Result<List<ChildDevice>>` per spec. Drop the local `getLocalPairedDevices()` reflective call — superseded. |
| `app/src/main/java/com/example/parentalcontrol/ui/screen/dashboard/DashboardScreen.kt` | Consume the new `Result<List<ChildDevice>>` via a sealed UI state (`Loading | Success(list) | Error(msg) | Empty`). Render `LazyColumn` of `DeviceCard`s with name, model, and relative "last seen" string. Show `CircularProgressIndicator` while loading, an error banner with Retry above the list on failure, and an empty-state illustration with "Pair a new device" CTA that opens `PairingBottomSheet` on tap. |

**Order of edits within PR 2**:

1. **Write the edge function** with no test harness (Deno is hard to unit-test from the Android side). Add a quick curl-based smoke test in the PR description.
2. **Write failing unit test** `ParentRepositoryTest.getDevices_calls_get_devices_for_parent_with_jwt` that uses `MockEngine` to assert the URL `/functions/v1/get-devices-for-parent` and the `Authorization: Bearer <jwt>` header. Run → red.
3. **Rewrite `getDevices()`** to make the real call. Run → green.
4. **Update `ParentViewModel.loadDevices()`** to handle the `Result` return type and emit the four UI states.
5. **Write failing Compose UI test** `DashboardScreenTest` covering Loading, Success, Empty, Error states. Run → red.
6. **Rewrite `DashboardScreen.kt`** to render the four states. Run → green.
7. **Deploy the edge function** manually (see §4): `supabase functions deploy get-devices-for-parent --project-ref <ref>`. Verify in Supabase dashboard that the function is `ACTIVE`.

**Dependencies inside PR 2**: PR 1's `ParentRepository` Hilt injection must be in place for the `MockEngine`-based test to construct the repository. The edge function must be deployed before manual smoke test, but not before the unit tests pass.

---

### PR 3 — Outbox drainer (rename, replace, migrate)

**Goal**: a new `OutboxDrainer` `CoroutineWorker` (annotated `@HiltWorker`) drains the local Room `outbox` table to Supabase, marks rows `processed = TRUE` on success, retries on network failure, and gives up after 10 attempts.

**Critical decision** (the proposal says "new `OutboxDrainer`" but `OutboxUploadWorker` already exists and calls `syncManager.drainOutbox()`). Three options were considered:

| Option | Verdict | Reason |
|---|---|---|
| A. Add `OutboxDrainer` alongside, leave `OutboxUploadWorker` alone | Rejected | Two workers draining the same table races; `KEEP` policy does not serialize the work. |
| B. Refactor `OutboxUploadWorker` to mark `processed = TRUE` instead of delete, change the schema in place, rename the class to `OutboxDrainer` | Rejected | Touches the most-likely-to-regress code path (`SyncManager.drainOutbox`); high blast radius for an in-flight SDD change. |
| C. **Replace** `OutboxUploadWorker` with `OutboxDrainer` in the same `workers/Workers.kt` file; add a Room v4→v5 migration that adds `processed`, `processed_at`, and renames `intentos` → `retries`; refactor `SyncManager.drainOutbox` to call a new public `OutboxDao.markProcessed(id)` and `OutboxDao.incrementRetries(id)` | **Chosen** | Matches the spec verbatim ("`processed = TRUE`", "`retries`"), keeps the change atomic, and the Room migration is a one-step `ALTER TABLE` on a 7-row entity. |

**Files touched (2 modified + 1 new)**:

| File | Edit |
|---|---|
| `app/src/main/java/com/example/parentalcontrol/data/local/AppDatabase.kt` | Bump `@Database(... version = 5)` (was 4). Add `processed: Boolean = false`, `processed_at: String? = null` to `OutboxEntity`; rename `intentos` → `retries` (carry value forward in the migration). Add DAO methods `markProcessed(id, processedAt)`, `markFailed(id, processedAt)`, `getPending(limit)`, `getPendingForDevice(deviceId, limit)`. Drop `deleteItem(id)` — keep a separate `deleteProcessedOlderThan(cutoff)` for housekeeping. Add a `Migration(4, 5)` with the `ALTER TABLE outbox ADD COLUMN processed INTEGER NOT NULL DEFAULT 0; ADD COLUMN processed_at TEXT; RENAME COLUMN intentos TO retries;` statements. Register the migration in `AppDatabase.getInstance(...).addMigrations(MIGRATION_4_5)`. |
| `app/src/main/java/com/example/parentalcontrol/sync/SyncManager.kt` | Change `drainOutbox()` to: read via `outboxDao().getPending(MAX_ITEMS_PER_RUN)`, iterate in `created_at` ASC, call a refactored `sendOutboxItem(item, accessToken)` that returns a sealed `OutboxSendResult.Success | OutboxSendResult.RetryableFailure | OutboxSendResult.PermanentFailure` instead of a `Boolean`. On `Success` → `outboxDao().markProcessed(id, now)`. On `RetryableFailure` → `outboxDao().incrementRetries(id)`. On `PermanentFailure` (4xx other than 408/429) → `outboxDao().markFailed(id, now)` (which writes `failed: true` into `payload` JSON per spec). Stop iterating when `outboxDao().getPending(MAX_ITEMS_PER_RUN)` returns empty. Add a `MAX_RETRY_ATTEMPTS = 10` constant (was 3). |
| `app/src/main/java/com/example/parentalcontrol/workers/Workers.kt` | **New class** `OutboxDrainer @HiltWorker @AssistedInject constructor(... private val outboxDao: OutboxDao, private val syncManager: SyncManager) : CoroutineWorker(...)`. `doWork()` calls `syncManager.drainOutbox()` (now returning the new sealed result), maps to `Result.success()` / `Result.retry()` / `Result.failure()`. `companion object { const val WORK_NAME = "outbox-drain-periodic"; private const val INTERVAL_MINUTES = 15L; private const val FLEX_MINUTES = 5L; }`. **Delete** the existing `OutboxUploadWorker` class. |
| `app/src/main/java/com/example/parentalcontrol/workers/WorkScheduler.kt` | Rename `scheduleOutboxUpload` → `scheduleOutboxDrainer`, swap the `PeriodicWorkRequestBuilder<OutboxUploadWorker>` for `OutboxDrainer`, keep the `ExistingPeriodicWorkPolicy.KEEP`. **Critical**: the work name changes from `outbox_upload_work` to `outbox-drain-periodic` — any call sites that referenced the old name must update. (There are references in `scheduleSyncAfterBoot` and `WorkerInitializer.initialize` via `scheduleAllPeriodicWork`.) |
| `app/src/main/AndroidManifest.xml` | **No change**. Hilt workers do not need a manifest entry — `HiltWorkerFactory` instantiates them. |

**Order of edits within PR 3** (TDD-first):

1. **Write the Room migration** + entity field changes. Run `./gradlew :app:assembleDebug` to confirm KSP regenerates `OutboxDao_Impl`. Run `./gradlew :app:connectedAndroidTest` to confirm the migration runs (the child device has a populated outbox from any prior session). Run a small in-memory Room migration test on the JVM (`MigrationTestHelper`).
2. **Write failing unit test** `OutboxDaoTest.markProcessed_sets_processed_true` and `markFailed_increments_retries_then_marks_processed`. Run → red.
3. **Add the new DAO methods + migration**. Run → green.
4. **Write failing unit test** `SyncManagerTest.drainOutbox_marks_processed_on_2xx`, `drainOutbox_increments_retries_on_5xx`, `drainOutbox_marks_failed_on_4xx`. Use `MockEngine` to drive each branch. Run → red.
5. **Refactor `SyncManager.drainOutbox` + `sendOutboxItem`** to the new sealed result. Run → green.
6. **Write failing unit test** `OutboxDrainerTest` covering: empty outbox → `Result.success()`, success row → row marked processed, network failure → `Result.retry()`, retries budget exhausted → row marked failed. Run → red.
7. **Add `OutboxDrainer` worker, delete `OutboxUploadWorker`**. Update `WorkScheduler.scheduleOutboxDrainer` to use the new class. Run → green.
8. **Use `WorkManagerTestInitHelper`** in the test to drive a `OneTimeWorkRequest<OutboxDrainer>` against a `WorkManager` initialized with a real `AppDatabase` in `Robolectric`. Assert the row state changes after `getWorkInfoById(...).get()` returns `SUCCESS`.

**Dependencies inside PR 3**: independent of PRs 1, 2, 4, 5. The only coupling is that `syncManager.drainOutbox` is now called from both `OutboxDrainer` (new) and `SyncWorker` (existing, line 153 of `SyncManager.kt:153`) — but `SyncWorker` only calls it after a successful `pullPolicy`, so the behavior is consistent.

---

### PR 4 — Wire parent approval

**Goal**: `ParentRepository.getPendingRequests()` queries `time_requests` from Supabase; `approveRequest()` and `denyRequest()` call the `approve-request` edge function. `RequestCard` UI in `DeviceComponents.kt:213-351` needs no change (the user already wired it to the ViewModel methods).

**Files touched (1)**:

| File | Edit |
|---|---|
| `app/src/main/java/com/example/parentalcontrol/data/repository/ParentRepository.kt` | Replace mock bodies: `getPendingRequests()` calls `supabase.from("time_requests").select("*, devices(device_name)").eq("status", "PENDING")` via Supabase REST, scoped by RLS (`time_requests_parent_select` enforces `parent_id = auth.uid()`). `approveRequest(requestId, minutes, response)` calls `POST /functions/v1/approve-request` with `{ request_id, minutes, response_text }`. `denyRequest(requestId, reason)` calls `POST /functions/v1/approve-request` with `{ request_id, decision: "DENIED", minutes: 0, response_text: reason }` — note: the existing edge function at `approve-request/index.ts:38` only reads `request_id`, `minutes`, and `response_text` from the body; it has no `decision` field. **See open question §7** for the asymmetry. |

**Order of edits within PR 4**:

1. **Write failing unit test** `ParentRepositoryTest.getPendingRequests_filters_by_pending_status`. Use `MockEngine` to return a JSON array with 2 rows (one PENDING, one APPROVED) and assert the parser drops the APPROVED one. Run → red.
2. **Rewrite `getPendingRequests()`** to call Supabase REST with `eq("status", "PENDING")`. Run → green.
3. **Write failing unit test** `ParentRepositoryTest.approveRequest_posts_to_approve_request_edge_function`. Use `MockEngine` to assert URL, method, body, and `Authorization` header. Run → red.
4. **Rewrite `approveRequest()`** to call the edge function. Run → green.
5. **Write failing unit test** `ParentRepositoryTest.denyRequest_posts_with_zero_minutes` to lock in the wire format. Run → red.
6. **Rewrite `denyRequest()`**. Run → green.
7. **Run `testDebugUnitTest`** to confirm no regression in PR 1/2/3 tests.

**Dependencies inside PR 4**: requires PR 1's Hilt-injected `ParentRepository` (mock-engine tests need the constructor). Independent of PR 3 and PR 5 in code, but PR 4 benefits from PR 2's `Result<List<…>>` precedent — the same `Result` pattern should be used for `getPendingRequests`.

---

### PR 5 — Parent app-block UI

**Goal**: `AppsScreen` (currently an empty `class AppsScreen {}` at `ui/screen/apps/AppsScreen.kt:3`) becomes a real list of launchable apps on the parent's reference device, with toggle to BLOCK/ALLOW; `DeviceDetailScreen` Policy tab gets an "Add to block list" affordance that navigates to `AppsScreen` pre-filtered to the originating child.

**Files touched (3)**:

| File | Edit |
|---|---|
| `app/src/main/java/com/example/parentalcontrol/ui/screen/apps/AppsScreen.kt` | Rewrite from empty class to a real `@Composable fun AppsScreen(deviceId: String?, viewModel: AppsViewModel, onBack: () -> Unit)`. On first composition, call `viewModel.loadLaunchableApps()`. Render inside `LazyColumn` (per spec scenario "Lists every launchable package" + "Large list stays responsive"). Each row: `Icon(packageIcon)`, `Text(appLabel)`, `Text(packageName)`, `Switch(checked = isBlocked)`. Switch onClick → `viewModel.togglePolicy(deviceId, packageName, newState)`. |
| `app/src/main/java/com/example/parentalcontrol/ui/screen/apps/AppsViewModel.kt` | New ViewModel. Holds `MutableStateFlow<List<LaunchableApp>>` and `MutableStateFlow<Set<String>>` of currently BLOCKED package names for the active `deviceId`. `loadLaunchableApps()` calls `context.packageManager.queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER), 0)` on `Dispatchers.IO` and maps to `LaunchableApp(packageName, label, icon)`. `togglePolicy(deviceId, pkg, newState)` calls `appPolicyDao.upsertAppPolicy(AppPolicyEntity(device_id = deviceId, package_name = pkg, state = newState.name, …))` — this is the existing DAO method at `data/local/AppDatabase.kt:122`. **Optimistic update** in the StateFlow before the DAO call, revert on failure. |
| `app/src/main/java/com/example/parentalcontrol/ui/parent/screens/DeviceDetailScreen.kt` | In `PolicyTab` (lines 405-484), add an "Add to block list" `OutlinedButton` that calls `onNavigateToApps(deviceId)`. The button sits in its own `Card` between the "Recompensa" card and the "Cambiar plantilla" card. The `DeviceDetailScreen` receives a new `onNavigateToApps: (String) -> Unit` parameter, wired from the parent NavGraph to `navController.navigate("apps/${deviceId}")`. The `deviceId` is required by the spec ("AppsScreen honors the incoming device id" — line 60-62 of `app-block-policy/spec.md`). |

**Order of edits within PR 5** (TDD-first):

1. **Write failing unit test** `AppsViewModelTest.loadLaunchableApps_uses_PackageManager_queryIntentActivities`. Use Robolectric to provide a fake `PackageManager` returning 3 fake activities. Assert the StateFlow contains all 3. Run → red.
2. **Add `loadLaunchableApps()` and the `LaunchableApp` data class**. Run → green.
3. **Write failing unit test** `AppsViewModelTest.togglePolicy_calls_upsertAppPolicy_with_correct_state`. Use `appPolicyDao` test double. Run → red.
4. **Add `togglePolicy()` with optimistic update + rollback on failure**. Run → green.
5. **Write failing Compose UI test** `AppsScreenTest.renders_launchable_apps_in_lazy_column`. Pump with a fake ViewModel returning 3 apps. Assert all 3 rows are visible. Run → red.
6. **Rewrite `AppsScreen.kt`** from the empty stub. Run → green.
7. **Write failing Compose UI test** `DeviceDetailScreenTest.policy_tab_has_add_to_block_list_button`. Run → red.
8. **Add the "Add to block list" button in `PolicyTab`**. Run → green.
9. **Wire navigation** in the parent NavGraph to pass `deviceId` as a route argument. Update the calling site to provide `onNavigateToApps`.

**Dependencies inside PR 5**: independent of PRs 1-4 in code. Requires the `AppsViewModel` to receive an `AppPolicyDao` (already in `AppDatabase`); the only new DI binding is `AppsViewModel` itself (Hilt-injectable).

---

## 2. Architectural decisions

### Why `qrose` over ZXing Android Embedded / zxing-cpp / Voyage

| Library | Type | Compose-friendly? | Min Compose version | Verdict |
|---|---|---|---|---|
| `io.github.alexzhirkevich:qrose` 1.0.0 | Pure Kotlin | Yes (native `QrCodePainter`) | 1.7+ | **Chosen** — matches the project's `compose-bom = 2024.10.01` (ships Compose 1.7.x), no JNI, ~250KB AAR, MIT license |
| `journeyapps:zxing-android-embedded` | Java + native (zxing) | No (uses `ImageView`) | n/a | Rejected — would need an `AndroidView` interop wrapper, defeats Compose-first architecture |
| `com.google.zxing:core` (zxing-cpp) | C++ JNI | No | n/a | Rejected — requires NDK, desugaring, and ABI filters; overkill for a single 240×240 QR |
| `com.lightspark:compose-qr-code` | Pure Kotlin | Yes | 1.6+ | Backup — last release Apr 2024, less actively maintained than qrose |

**Why `qrose` specifically**: it's a single-line Compose API (`rememberQrCodePainter(data = …)`), returns a `Painter` that drops into any `Image` composable, and has zero Android-specific dependencies. The library author maintains a release cadence aligned with Compose BOM updates; 1.0.0 stable shipped Oct 2024 and is compatible with Compose 1.7 (which is what BOM 2024.10.01 resolves to).

**Alternative if `qrose` fails to resolve**: fall back to `com.lightspark:compose-qr-code:1.0.5` (pure Kotlin, no native, same Compose 1.6+ baseline). The wrapping code in `DeviceComponents.kt:445-508` is one composable change — no other file is affected.

### How the outbox drainer coordinates with the network

| Concern | Decision | Rationale |
|---|---|---|
| **Trigger cadence** | `PeriodicWorkRequest` every 15 minutes with 5-minute flex | Matches the existing `OutboxUploadWorker` schedule (line 85 of `workers/Workers.kt`); the spec scenario "Periodic work is scheduled at app startup" is satisfied by `WorkScheduler.scheduleOutboxDrainer` being called from `WorkerInitializer.initialize` |
| **Reactive trigger** | **Add a one-time `OutboxDrainer` after any `outboxDao().insertOutboxItem` call** — implemented by calling `WorkScheduler.triggerOutboxDrainNow(context)` from `SyncManager.enqueue()` | The spec scenario "Offline request is sent later" implies a drain runs as soon as connectivity returns; periodic alone could leave 15-minute gaps. The reactive trigger complements (does not replace) the periodic schedule. |
| **Single-writer gate** | `ExistingPeriodicWorkPolicy.KEEP` (already in `WorkScheduler.scheduleOutboxUpload`) — WorkManager's `CoroutineWorker` is serialized at the OS level, no explicit `Mutex` needed | The proposal mentions "single-writer gate (existing pattern)" — this is the existing pattern. Adding a `Mutex` inside the worker would be over-engineering. |
| **Retry strategy** | `BackoffPolicy.EXPONENTIAL` starting at `WorkRequest.MIN_BACKOFF_MILLIS` (10s) → ~10 min after 5 retries; combined with per-row `retries` budget of 10 | WorkManager's built-in exponential backoff handles transient failures; the per-row counter protects against permanently-bad rows that WorkManager would otherwise retry forever |
| **Permanent vs transient classification** | Inside the refactored `sendOutboxItem`: HTTP 4xx (except 408/429) → `PermanentFailure` (mark `processed = TRUE` with `failed: true` in payload per spec); 5xx, 408, 429, network exception → `RetryableFailure` (increment `retries`) | The spec distinguishes these explicitly (lines 41-51 of `outbox-drain/spec.md`). The classification is on the edge-function call result, not the worker itself. |
| **Idempotency** | Each `outbox` row carries `dedup_key` (UUID); the worker includes it in the request payload so the receiving edge function can `INSERT ... ON CONFLICT (dedup_key) DO NOTHING` | The spec scenario "Crash between send and mark-processed re-sends safely" requires this. The local Room schema already has `dedup_key`; the Supabase `outbox` table also has it. The middleware side (the receiving REST endpoint / edge function) must implement the conflict resolution — **this is "out of scope" in the spec but a real gap to flag in §6**. |
| **Empty fast path** | `Result.success()` immediately if `getPending(MAX_ITEMS_PER_RUN)` returns 0 items | Spec scenario "Empty outbox is a fast success" — no network call. |

### How the parent app authenticates to Supabase

The parent app reuses the existing `DeviceAuthManager` singleton (`auth/DeviceAuthManager.kt:84-467`). Key facts:

- `DeviceAuthManager.authenticateOrCreate()` calls `auth/v1/token?grant_type=password` with a `placeholder.local` email and a random password, creating an anonymous user in `auth.users`. The JWT returned has `sub = <user_uuid>` and **does not** carry the `device_id` claim (because no `device_id` was ever written to that user's `app_metadata` — the FCM TODO at `pairing/index.ts:228-239` is precisely this gap).
- This means RLS policies that gate on `auth.jwt() ->> 'device_id' IS NOT NULL` (the **agent** policies) **deny** the parent, and policies that gate on `parent_id = auth.uid()` (the **parent** policies) **allow** the parent. This is the correct asymmetry and is enforced by the RLS file `002_rls_policies.sql`.
- All Supabase calls from `ParentRepository` must include the parent's bearer token. Use `authManager.getAccessToken()` (line 325 of `DeviceAuthManager.kt`) and pass as `header("Authorization", "Bearer $token")` in every `httpClient` call.
- The parent's `auth.uid()` and the child's `auth.uid()` are different UUIDs (parent's anonymous user vs the per-device `device_<hash>@parentalcontrol.local` user created in `pairing/index.ts:79-95`). The `devices.parent_id` FK in the schema points to the parent's UUID, and `devices_parent_select` enforces that filter.

### How RLS-aware queries are structured

The parent app **must** use its own JWT, never the service role key, for the queries in PR 2, PR 4, PR 5. Specifically:

- **`get-devices-for-parent`** (new in PR 2): prefer **RLS-driven** (don't pass service role) by initializing the function with the parent's JWT and querying via `supabase.from("devices").select(...)` with no `eq("parent_id", user.id)` filter — the RLS policy `devices_parent_select` does the filtering. This is the right pattern because:
  1. It makes the security model auditable from a single SQL file (`002_rls_policies.sql`).
  2. If a parent tries to query with a forged token, the RLS denies — there's no bypass path to accidentally leave open.
  3. The function signature stays simple — no need to extract and re-pass `parent_id` server-side.
- **`time_requests` parent fetch** (PR 4): same pattern — REST query with no client-side filter, RLS scopes via the `EXISTS` subquery in `time_requests_parent_select`.
- **`approve-request`** (PR 4): this function **does** use the service role (line 47-50 of `approve-request/index.ts:47-50`) because it needs to INSERT into `grants` on behalf of the parent, and RLS would let the parent insert but the function also needs to read `time_requests` joined with `devices(parent_id)` to verify ownership (line 53-71). The ownership check is duplicated in code (line 67-72) — a service-role client is acceptable here, with the explicit ownership check as a defense-in-depth.

### How FCM tokens are registered (parent device_id gap)

The proposal flagged this as hotfix #4 / a follow-up, not blocking. For completeness:

- The current `approve-request/index.ts:166-203` looks up the **child's** FCM token from `device_push_tokens` keyed by `device_id` — this works because the child registered its token at startup via `SupabaseClientProvider.registerPushToken()` (`network/SupabaseClientProvider.kt:191-207`).
- The `pairing/index.ts:228-239` "FCM to parent" code path is a no-op with a `console.log` — the parent has no row in `device_push_tokens` because the parent app has never called `registerPushToken`.
- **This change does NOT fix the gap.** It is documented as a separate follow-up in the proposal ("Parent-side FCM token registration against `device_push_tokens` — separate follow-up"). The design must NOT silently paper over it. PR 1's edge function `get-devices-for-parent` returns a 200 with the new device after pairing — the parent sees the result via pull-to-refresh, not via push.

---

## 3. PR dependencies and ordering

```
PR 1 (pairing + QR)  ─────┐
                           ├──► PR 2 (device list) ──┐
                           │                          ├──► PR 5 (app-block UI)
                           └──► PR 4 (approval) ──────┤
                                                      │
                            PR 3 (outbox drainer) ────┘  (independent)
```

| PR | Blocks | Blocked by |
|---|---|---|
| 1 | 2, 4 (real `ParentRepository` Hilt module) | — |
| 2 | 5 (`DashboardScreen` real data feeds the device picker) | 1 |
| 3 | — (independent file set) | — |
| 4 | — (only needs `ParentRepository`, which is wired in PR 1) | 1 |
| 5 | — (UI-only, but cleaner after PR 2 so the device picker works) | 2 (soft) |

**Recommended merge order** (respects the dependencies and minimizes review churn):

1. **PR 1 first** (highest blast radius — new dependency, new code path, manifest deeplink). Once green, `ParentRepository` is Hilt-injectable and downstream tests can use `MockEngine`.
2. **PR 3 next** (independent, can be reviewed in isolation). Lower risk; clear single-file diff. If PR 1 takes long, PR 3 can land first to unblock the child-side flow.
3. **PR 2 in parallel with PR 4** (both depend only on PR 1, not on each other). PR 2 needs a manual `supabase functions deploy`; PR 4 is pure client. Land whichever finishes first.
4. **PR 5 last** (UI-only, depends on PR 2's real device list, biggest LOC at ~300 lines).

**Review slicing**: per the `chained-pr` skill, each PR keeps a clean diff by rebasing onto the previous PR's tip before opening. The branch chain is `master → pr1/pairing-and-qr → pr2/device-list → pr3/outbox-drainer → pr4/approval → pr5/app-block`. PR 3 is a side branch that can be merged independently into `master` without breaking PRs 2/4/5.

---

## 4. Validation per PR

### PR 1
- **Compile**: `./gradlew :app:assembleDebug` — confirms `qrose` resolves against Compose BOM 2024.10.01
- **Unit tests**: `./gradlew :app:testDebugUnitTest --tests "*PairingTest*" --tests "*PairingManagerTest*" --tests "*ParentRepositoryTest*"`
- **Static analysis**: `./gradlew :app:detekt :app:ktlintCheck`
- **Manual smoke**: open the parent PairingBottomSheet on a debug build, tap "Generate code", confirm a QR appears; scan the QR with the child app's QR scanner (or paste the deeplink `parentalcontrol://pair?code=ABCDEFGH`); confirm the child pairing screen pre-fills the code
- **Expected outcome**: all four steps green, no DI errors at runtime (the reflective `getSharedPreferences` bug is gone)

### PR 2
- **Compile**: `./gradlew :app:assembleDebug`
- **Unit tests**: `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest*getDevices*"`
- **Static analysis**: `./gradlew :app:detekt :app:ktlintCheck`
- **Manual smoke**: 
  1. `supabase functions deploy get-devices-for-parent --project-ref <ref>` (must be run by the user — out of agent's reach)
  2. In Supabase SQL editor: `SELECT id, device_name, parent_id FROM devices WHERE parent_id = auth.uid();` — confirm the parent's devices
  3. Open DashboardScreen on the parent app — confirm the list renders with relative timestamps
- **Expected outcome**: list is non-empty if pairing already happened; empty state with "Pair a new device" CTA otherwise

### PR 3
- **Compile**: `./gradlew :app:assembleDebug`
- **Unit tests**: `./gradlew :app:testDebugUnitTest --tests "*OutboxDaoTest*" --tests "*SyncManagerTest*drainOutbox*" --tests "*OutboxDrainerTest*"`
- **Migration test**: `./gradlew :app:testDebugUnitTest --tests "*AppDatabaseMigrationTest*"` (new test, uses `MigrationTestHelper` from `androidx.room:room-testing`)
- **Static analysis**: `./gradlew :app:detekt :app:ktlintCheck`
- **Manual smoke**: 
  1. Wipe app data, pair child, let it generate an outbox event by asking for time
  2. Toggle airplane mode, queue 3 events
  3. Disable airplane mode, wait 15 min OR call `WorkScheduler.triggerOutboxDrainNow(context)` from a debug broadcast
  4. In Supabase SQL editor: `SELECT id, processed, processed_at, retries FROM outbox ORDER BY created_at DESC LIMIT 3;` — confirm all 3 have `processed = true` and `processed_at` populated
- **Expected outcome**: clean migration, drainer runs, rows marked processed, no infinite retry loop

### PR 4
- **Compile**: `./gradlew :app:assembleDebug`
- **Unit tests**: `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest*getPendingRequests*" --tests "*ParentRepositoryTest*approveRequest*" --tests "*ParentRepositoryTest*denyRequest*"`
- **Static analysis**: `./gradlew :app:detekt :app:ktlintCheck`
- **Manual smoke**:
  1. As child, request extra time → outbox drain creates a `time_requests` row
  2. As parent, open `RequestCard` → tap "Aprobar" → confirm a `grants` row appears in Supabase
  3. As child, observe remaining time update (this is enforced by `EnforcementController`, hotfix #2)
- **Expected outcome**: approve round-trip works end-to-end

### PR 5
- **Compile**: `./gradlew :app:assembleDebug`
- **Unit tests**: `./gradlew :app:testDebugUnitTest --tests "*AppsViewModelTest*" --tests "*AppsScreenTest*" --tests "*DeviceDetailScreenTest*"`
- **Static analysis**: `./gradlew :app:detekt :app:ktlintCheck`
- **Manual smoke**:
  1. Open DeviceDetailScreen → Policy tab → tap "Add to block list"
  2. AppsScreen opens with the launchable apps list
  3. Tap the switch on a row → `app_policies` row appears in Supabase
  4. As child, try to launch the blocked app → blocked by `EnforcementController` (hotfix #2)
- **Expected outcome**: per-device policy isolation works; toggling `com.example.game` for child A does not affect child B

---

## 5. Rollback plan per PR

| PR | If it fails | Rollback action |
|---|---|---|
| 1 | `qrose` doesn't resolve against Compose BOM 2024.10.01 | `git revert <sha>`; OR swap `qrose` for `compose-qr-code:1.0.5` and re-PR. The QR composable is the only file affected. |
| 1 | Reflective `getSharedPreferences` regression | The Hilt injection in PR 1 **removes** the reflective call — rolling back reintroduces it. Document in the PR description that any revert must re-run `./gradlew testDebugUnitTest` to catch re-regressions. |
| 2 | Edge function deploy fails (e.g., function name collision, missing `corsHeaders` import) | The edge function lives in `supabase/functions/get-devices-for-parent/index.ts` only — `supabase functions delete get-devices-for-parent --project-ref <ref>`. The Android client is unaffected; `getDevices()` returns `Result.failure(NetworkError)` and the dashboard shows the error banner per spec. |
| 3 | Infinite retry loop on a poison row | The `retries > 10` check in `OutboxDrainer.doWork()` is the safety net. If it still loops (e.g., bug in classification), disable the worker: `WorkScheduler.cancelOutboxDrainer(context)`. The hotfix path is a 1-line `git revert` followed by a hotfix-PR. **Risk-mitigation**: the new test `OutboxDrainerTest.retries_budget_exhausted_marks_row_failed` is the contract that catches this. |
| 3 | Room migration corrupts existing outbox | The migration is `ALTER TABLE ADD COLUMN ... DEFAULT 0` (additive only, no data loss). The `intentos → retries` rename preserves values. If the migration fails on a real device, the `MigrationTestHelper` test must be expanded to cover the v4→v5 path with realistic v4 data. |
| 4 | `approve-request` edge function is missing or has a new field requirement | MockEngine test catches the wire mismatch before deploy. If the edge function changes shape mid-flight, update the test first, then the implementation. |
| 5 | `PackageManager.queryIntentActivities` is too slow on a real device | Wrap the call in `withContext(Dispatchers.Default)`, cache the result in the ViewModel. The spec already covers this ("LazyColumn + 200ms first frame"). If still slow, add pagination — but the expected device count is small (< 200 apps) so this is unlikely. |

**Full rollback**: `git revert <pr5> <pr4> <pr3> <pr2> <pr1>` in reverse merge order. The new edge function stays deployed in Supabase (harmless if the app stops calling it). The Room v4→v5 migration is also harmless on rollback — Android does not auto-downgrade, and v5 columns are simply unused by the v4 entity if the entity is also reverted.

---

## 6. Risk mitigation

| Risk | Likelihood | Mitigation in this design |
|---|---|---|
| `qrose` version incompatible with Compose BOM 2024.10.01 | Medium | PR 1 step 1 runs `./gradlew assembleDebug` immediately after adding the dependency, before any other edits. If unresolved, the alternative `compose-qr-code:1.0.5` is a one-line catalog swap. |
| Edge function not deployed to Supabase | Medium | PR 2's PR description includes the `supabase functions deploy get-devices-for-parent` command. The edge function source is in the PR diff, so deploy is just `cd supabase && supabase functions deploy get-devices-for-parent --project-ref <ref>`. |
| Outbox drainer races with `SyncManager` | Medium | PR 3 deletes `OutboxUploadWorker` (the source of the race) and replaces it with the new `OutboxDrainer`. The `SyncWorker` still calls `syncManager.drainOutbox()` after a successful `pullPolicy` (line 153), so the two paths converge on a single drain implementation. |
| FCM not delivered to parent on successful pairing | Medium (pre-existing) | NOT in scope. The pairing succeeds; the parent sees the new device on next pull-to-refresh. The `pairing/index.ts:228-239` TODO is documented in §2 above and remains for a follow-up change. |
| `PackageManager.queryIntentActivities` is heavy on `AppsScreen` open | Low | PR 5 wraps the call in `Dispatchers.Default`, caches in ViewModel StateFlow, renders inside `LazyColumn`. The "Large list stays responsive" scenario in the spec is covered by a Compose UI test that asserts the first frame within 200 ms (use `composeTestRule.onRoot().captureToImage()` after a 200 ms delay). |
| Chained PR diffs show prior slices in GitHub view | Medium | Each PR rebases onto the previous PR's tip before opening. The PR description must include the file count and line count from `git diff --stat <prev-pr-sha>..HEAD` to make the scope obvious. The `chained-pr` skill (already in the project's skill registry) governs this. |
| RLS lets a parent read another parent's devices | Low | The `devices_parent_select` policy is `parent_id = auth.uid()` — strictly scoped. PR 2's new edge function is RLS-driven (no service role), so any tampering has to happen at the JWT layer (Supabase's responsibility). A future security test could verify this with two parent users. |
| Spec says `outbox.retries` but neither Room nor Supabase has that column | High | **Resolved in PR 3**: Room v4→v5 migration renames `intentos` → `retries`. The Supabase `outbox` table's `processed`/`processed_at` columns are present and used. The spec's "retries" semantics map to the Room counter. |
| Spec says `grants.source = "MANUAL"` but edge function inserts `"EXTRA_TIME"` | Medium | **Flagged in §7 (open questions)**. The mismatch is not blocking — the receiving child doesn't read `source` to enforce. The spec is the source of truth for the new contract; the edge function must be updated to match. Either change the edge function in this PR 4 (preferred — small, contained) or amend the spec. |
| Parent `parent_outbox`/FCM gap blocks push notifications | Medium (pre-existing) | Explicitly out of scope per the proposal. Documented in §2 above. PR 4 verification uses pull-to-refresh, not push. |
| `WorkerInitializer.initialize` is called from `BootReceiver`, not from `ParentalControlApp.onCreate` as the proposal claims | Low | The proposal's line "schedule from `ParentalControlApp.onCreate`" is inaccurate. The design schedules from `WorkerInitializer.initialize` (called by `BootReceiver` and by `reinitializeAfterPairing`). This matches the existing pattern and is the correct place to add the new `scheduleOutboxDrainer` call. |
| Hilt workers not picked up by `HiltWorkerFactory` | Low | `ParentalControlApp` already implements `Configuration.Provider` and provides `workerFactory`. New `@HiltWorker` classes work automatically — no manifest change needed. Verified by `WorkersTest.kt` (existing test, line 1 of the workers test list). |

---

## 7. Open questions

1. **Outbox drainer cadence: periodic 15 min vs reactive to network** — recommendation: BOTH. The periodic `PeriodicWorkRequest` covers cold-start and idle cases; the reactive trigger (call `scheduleOutboxDrainNow` from `SyncManager.enqueue()` and from a `ConnectivityManager.NetworkCallback`) covers the "just came back online" case. This deviates from the spec slightly (which only says "Periodic work is scheduled at app startup") but matches the user intent captured in proposal §3 ("`OutboxDrainer` runs at least once in a foreground session, drains pending `outbox` rows, and does not duplicate-send on retry"). **Decision needed: do you want the reactive trigger in this PR, or only the periodic?**

2. **Parent app-block UI: per-device or global** — recommendation: **per-device**, matching the spec verbatim ("Policies SHALL be scoped by `device_id`"). The parent app already needs to pick a device (it has a device list from PR 2), so the UX is: pick device → see its block list → toggle. A "global" mode would require a separate `app_policies` row per (parent, package) and complicates the schema. **Confirm per-device is acceptable; if global is needed, that's a larger change (new table + new edge function).**

3. **QR code: show the raw 8-char code alongside the QR** — recommendation: **YES, show both**. The QR is for camera scanning; the text is for manual entry / debugging. The spec says "an 8-char `code` plus a scannable QR" (line 12 of `pairing-flow/spec.md`) — the "+" confirms the user wants both. The current `PairingBottomSheet` step 2 already shows the code (lines 453-459 of `DeviceComponents.kt`); PR 1 adds the QR above the existing code text. **Confirm: keep the existing code text or move it to a smaller monospace subtitle below the QR?**

4. **Edge function `approve-request` `source` field** — recommendation: **change the edge function to use `source = "MANUAL"`** (matching the spec, `outbox-drain/spec.md:43`). The current code has `source = "EXTRA_TIME"` (line 117). This is a 1-line edge-function change that must be deployed as part of PR 4. **Decision needed: amend the spec to match the existing function, or change the function to match the spec? Changing the function is safer because "MANUAL" semantically matches the parent's UI action.**

5. **`denyRequest` wire format** — the existing edge function does NOT read a `decision` field. PR 4 will call it with `{ request_id, minutes: 0, response_text: reason }`, which sets `status = "APPROVED"` with 0 minutes — that's wrong (it should set `status = "DENIED"`). **Decision needed: do we (a) extend the edge function to read a `decision` field, (b) create a new `deny-request` edge function, or (c) keep the mock and let the parent UI hide the Deny button for now? Option (a) is the cleanest — one extra `if/else` branch in the existing function.**

6. **`parent_paired_devices` SharedPreferences in `ParentRepository`** — the mock in `savePendingDevice` writes to a local SharedPreferences. PR 1 deletes this method. **Confirm: no other code reads from `parent_paired_devices`.** (A grep confirms: only the mock writes; `getLocalPairedDevices` is the only reader, also in the mock. Safe to remove.)

---

## 8. New concerns discovered during design (not in the proposal)

1. **Spec/implementation mismatch: `grants.source`**. The spec says `"MANUAL"`; the edge function says `"EXTRA_TIME"`. Resolved in §7 Q4.

2. **Spec/implementation mismatch: `outbox.retries`**. The spec says `retries`; the local Room has `intentos`; the Supabase `outbox` table has neither (it has `processed`/`processed_at` but no retry counter). Resolved in PR 3 via Room migration.

3. **Spec/implementation mismatch: `outbox` semantics**. The spec says "mark `processed = TRUE`" (keep the row); the existing `SyncManager.drainOutbox` calls `deleteItem(id)` (drop the row). The change in PR 3 is a behavior change visible in the Supabase `outbox` table — rows accumulate over time. Recommend a separate housekeeping job (out of scope for this change) to delete `outbox` rows where `processed = TRUE AND processed_at < NOW() - INTERVAL '30 days'`.

4. **Spec edge-function deployment risk**. The spec assumes `get-devices-for-parent` is deployable. The new edge function in PR 2 is the only one in this change that does not yet exist in `supabase/functions/`. If the user cannot run `supabase functions deploy` from this environment, the PR 2 acceptance is blocked on a manual step.

5. **`PairingManager` is not Hilt-injectable** (it's a singleton with `getInstance(context)`). The existing `DeviceAuthManager` is also a singleton. PR 1's `PairingManager.pairWithCode` keeps the singleton pattern (it already uses `authManager` and `clientProvider` via `getInstance`). The design does NOT migrate `PairingManager` to Hilt in this change — that would be a much larger refactor. **Flagging for a follow-up if a Hilt-based approach is preferred.**

6. **`SyncManager.httpClient` is null until `setHttpClient` is called** (line 103 of `sync/SyncManager.kt`). The current `drainOutbox` uses `httpClient?.post(...)` which silently no-ops if null. The refactored `sendOutboxItem` in PR 3 must throw on null so the worker can classify the failure as `RetryableFailure` (rather than silently treating it as success). **Add a startup check in `SyncManager.init` to ensure `httpClient` is set before the worker runs.**

7. **`getPolicy` edge function is used by the CHILD, not the parent**. The proposal says the parent uses PR 2's new `get-devices-for-parent` edge function. The existing `get-policy` is unrelated to this change — flagged so the reviewer doesn't conflate the two.

8. **The proposal's "`schedule from `ParentalControlApp.onCreate`" line is inaccurate** (line 17 of the proposal). Scheduling happens from `WorkerInitializer.initialize`, which is called by `BootReceiver.onBootCompleted` and by `WorkerInitializer.reinitializeAfterPairing`. The design follows the existing pattern.

---

## 9. Summary

- **PR 1** adds `qrose` and replaces two mock methods in `ParentRepository` and `PairingManager` with real Supabase calls; renders a QR in `PairingBottomSheet`; wires the `parentalcontrol://pair?code=…` deeplink.
- **PR 2** introduces the new `get-devices-for-parent` edge function (RLS-driven, no service role) and rewires `ParentRepository.getDevices()` to consume it.
- **PR 3** replaces the existing `OutboxUploadWorker` with a new `OutboxDrainer` that marks rows `processed = TRUE` (not delete), with a Room v4→v5 migration adding `processed`, `processed_at`, and renaming `intentos → retries`.
- **PR 4** replaces the `getPendingRequests`, `approveRequest`, and `denyRequest` mocks with real Supabase calls. The existing `RequestCard` UI needs no change.
- **PR 5** fills the empty `AppsScreen` stub with a `LazyColumn` of launchable apps toggled via `appPolicyDao.upsertAppPolicy`, and adds the "Add to block list" affordance to `DeviceDetailScreen`'s Policy tab.

**The 7 biggest gotchas** the design forces the implementer to confront (not papers over):
1. `qrose` 1.0.0 + Compose BOM 2024.10.01 (verify with `assembleDebug` first)
2. `ParentRepository` reflective `getSharedPreferences` is broken — Hilt conversion is the only way out
3. `OutboxUploadWorker` already exists and DELETES rows — must be replaced, not added alongside
4. Local Room `OutboxEntity` has `intentos` not `retries`, and lacks `processed` / `processed_at` — needs a Room v4→v5 migration
5. `get-devices-for-parent` is the only edge function in this change that does not yet exist — manual deploy is required
6. `approve-request` edge function uses `source = "EXTRA_TIME"`, spec says `"MANUAL"` — resolve in PR 4
7. Parent-side FCM to parent doesn't work — pre-existing TODO, flagged as follow-up, NOT blocking this change

---

*End of design — proceed to `tasks.md` (next phase) only after the 7 open questions in §7 are answered.*
