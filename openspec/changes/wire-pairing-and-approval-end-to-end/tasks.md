# Tasks: wire-pairing-and-approval-end-to-end

> 5 chained PRs replacing parent/child mocks with real Supabase calls, adding QR rendering, draining the outbox via WorkManager, and lighting up the parent app-block UI.
> Strict TDD: write the failing test, see it red, implement, see it green, refactor.
> Branch chain: `master → pr1/pairing-and-qr → pr2/device-list → pr4/approval → pr5/app-block`. PR 3 (`outbox-drainer`) is an independent side branch that can land in parallel.

---

## PR 1 — Wire pairing backend + QR rendering

**Per-PR Review Workload Forecast**

| Field | Value |
|---|---|
| Estimated changed lines | ~310 (catalog +4, `app/build.gradle.kts` +1, `ParentRepository.kt` ~+90 / ~−40, `PairingManager.kt` ~+50 / ~−50, `DeviceComponents.kt` ~+30 / ~−10, `AppModule.kt` +6, `ParentViewModel.kt` ~+0 / ~−1, `AndroidManifest.xml` +5, `MainActivity.kt` ~+30, `ParentRepositoryTest.kt` ~+50, `PairingManagerTest.kt` ~+40, `DeviceComponentsTest.kt` ~+30, `PairingDeeplinkTest.kt` ~+30) |
| 400-line budget risk | Low |
| Linked spec | `pairing-flow` |
| Base branch | `master` |
| Direct dependencies | None |
| Unblocks | PR 2, PR 4 (Hilt-injected `ParentRepository` + MockEngine testability) |

---

## 1. Add `qrose` to version catalog and wire into app module

- [x] Step 1: In `gradle/libs.versions.toml`, under `[versions]` add `qrose = "1.0.0"`. Under `[libraries]` add `qrcode = { module = "io.github.alexzhirkevich:qrose", version.ref = "qrose" }`.
- [x] Step 2: In `app/build.gradle.kts` near the Compose block, add `implementation(libs.qrcode)`.
- [x] Step 3: Run `./gradlew :app:assembleDebug` — must compile (confirms `qrose` resolves against Compose BOM 2024.10.01). If unresolved, swap to `com.lightspark:compose-qr-code:1.0.5` per design §2 fallback.

## 2. Convert `ParentRepository` to a Hilt-injectable singleton

- [x] Step 1: In `app/src/main/java/com/example/parentalcontrol/data/repository/ParentRepository.kt:14`, change the class signature to `@Singleton class ParentRepository @Inject constructor(@ApplicationContext private val context: Context, private val authManager: DeviceAuthManager, private val clientProvider: SupabaseClientProvider)`. Add imports: `dagger.hilt.android.qualifiers.ApplicationContext`, `javax.inject.Inject`, `javax.inject.Singleton`, `com.example.parentalcontrol.auth.DeviceAuthManager`, `com.example.parentalcontrol.network.SupabaseClientProvider`.
- [x] Step 2: Remove the broken reflective `getSharedPreferences` calls in `getLocalPairedDevices()` (lines 51-80) and `savePendingDevice()` (lines 185-205) — they were only used by mocks that are being replaced. Remove the private `getLocalPairedDevices()` and `savePendingDevice()` methods entirely. Remove the `generateCode()` helper at lines 283-286 (server now generates the code).
- [x] Step 3: Run `./gradlew :app:assembleDebug` — expect a compile error in `ParentViewModel.kt:24` (the `ParentRepository()` no-arg constructor is gone). This is the expected RED state. Do NOT fix the ViewModel in this task — it is task #4.

## 3. Provide `DeviceAuthManager` and `SupabaseClientProvider` via Hilt

- [x] Step 1: In `app/src/main/java/com/example/parentalcontrol/di/AppModule.kt`, add two `@Provides @Singleton` methods: `fun provideDeviceAuthManager(@ApplicationContext context: Context): DeviceAuthManager = DeviceAuthManager.getInstance(context)` and `fun provideSupabaseClientProvider(@ApplicationContext context: Context): SupabaseClientProvider = SupabaseClientProvider.getInstance(context)`. Add the corresponding imports.
- [x] Step 2: Run `./gradlew :app:assembleDebug` — Hilt should now resolve `ParentRepository`'s constructor.

## 4. Update `ParentViewModel` to use Hilt-injected repository

- [x] Step 1: In `app/src/main/java/com/example/parentalcontrol/viewmodel/ParentViewModel.kt:22-24`, change the constructor from `@HiltViewModel class ParentViewModel @Inject constructor() : ViewModel()` with field `private val repository = ParentRepository()` to `@HiltViewModel class ParentViewModel @Inject constructor(private val repository: ParentRepository) : ViewModel()`.
- [x] Step 2: Run `./gradlew :app:assembleDebug` — must compile clean (no broken refs because all callers use the field by name).

## 5. Replace `ParentRepository.createPairingCode` mock with real HTTP call (RED → GREEN)

- [x] Step 1: Create `app/src/test/java/com/example/parentalcontrol/data/repository/ParentRepositoryTest.kt`. Add failing test `createPairingCode_posts_to_create_pairing_code_edge_function` that:
  - Builds a `MockEngine` from `io.ktor.client.mock` that responds `HttpResponseData` with `HttpStatusCode.OK` and body `{"code":"ABCDEFGH","expires_at":"2026-06-04T12:10:00Z","deeplink":"parentalcontrol://pair?code=ABCDEFGH"}`.
  - Constructs `ParentRepository` with the mocked `HttpClient` (via `SupabaseClientProvider` test double or direct field override).
  - Calls `createPairingCode(deviceName="S21", ageBand="7-12", ttlMinutes=10)`.
  - Asserts the result is `Result.success(PairingCodeResult(code="ABCDEFGH", ...))` and that the engine recorded a `POST` to a URL containing `/functions/v1/create-pairing-code` with an `Authorization: Bearer …` header.
- [x] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest.createPairingCode*"` — must be RED.
- [x] Step 3: In `ParentRepository.kt:168-183`, rewrite `createPairingCode` to `suspend fun createPairingCode(...): Result<PairingCodeResult> = withContext(Dispatchers.IO) { try { val token = authManager.getAccessToken() ?: return@withContext Result.failure(IllegalStateException("not authenticated")); val response = clientProvider.httpClient.post("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/create-pairing-code") { header("Authorization", "Bearer $token"); header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY); contentType(ContentType.Application.Json); setBody("""{"device_name":"$deviceName","age_band":"$ageBand","ttl_minutes":$ttlMinutes}""") }; if (!response.status.isSuccess()) return@withContext Result.failure(RuntimeException("HTTP ${response.status}")); val body = Json.decodeFromString<PairingCodeDto>(response.bodyAsText()); Result.success(PairingCodeResult(code=body.code, expiresAt=body.expires_at, deeplink=body.deeplink)) } catch (e: Exception) { Result.failure(e) } }`. Add a private `@Serializable data class PairingCodeDto(val code: String, val expires_at: String, val deeplink: String)`.
- [x] Step 4: Re-run the test — must be GREEN. Update `ParentViewModel.createPairingCode()` to handle `Result` (set `_pairingCode.value = result.getOrNull(); _error.value = result.exceptionOrNull()?.message`).

## 6. Replace `PairingManager.pairWithCode` mock with real HTTP call (RED → GREEN)

- [x] Step 1: In `app/src/test/java/com/example/parentalcontrol/pairing/PairingManagerTest.kt`, add failing test `pairWithCode_real_supabase_returns_success` using `MockEngine` that returns `200 OK` with body `{"device_id":"<uuid>","parent_id":"<uuid>"}`. Assert the result is `PairingResult.Success(deviceId=<uuid>, parentId=<uuid>)` and that `parsePairingResponse` (lines 216-270) was actually invoked (verify via a `thenAnswer` mock on `authManager.savePairedSession`).
- [x] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*PairingManagerTest.pairWithCode*"` — must be RED.
- [x] Step 3: In `app/src/main/java/com/example/parentalcontrol/pairing/PairingManager.kt:54-83`, replace the mock body with a real call: `suspend fun pairWithCode(code: String): PairingResult = withContext(Dispatchers.IO) { val token = authManager.getAccessToken() ?: return@withContext PairingResult.Error(PairingErrorType.SESSION_ERROR, "No autenticado"); try { val deviceInfo = getDeviceInfo(); val requestBody = buildPairingRequest(code, deviceInfo); val response = clientProvider.httpClient.post("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/pairing") { header("Authorization", "Bearer $token"); header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY); contentType(ContentType.Application.Json); setBody(requestBody) }; parsePairingResponse(response.status.value, response.bodyAsText()) } catch (e: Exception) { PairingResult.Error(PairingErrorType.NETWORK_ERROR, e.message ?: "Error de red") } }`. Reuses the existing `buildPairingRequest` (lines 198-211) and `parsePairingResponse` (lines 216-270).
- [x] Step 4: Re-run the test — must be GREEN. Remove the now-dead `savePairedDeviceToParentStorage` private method (lines 85-108).

## 7. Render QR in `PairingBottomSheet` step 2 (RED → GREEN)

- [x] Step 1: In `app/src/test/java/com/example/parentalcontrol/ui/parent/components/DeviceComponentsTest.kt` (create the file), add a failing Compose UI test `qr_renders_for_pairing_step_2` that pumps `PairingBottomSheet` with a `PairingCodeResult` in step 2, advances through step 1 → step 2, and asserts a Composable whose `SemanticsProperties.ContentDescription` is `null` and whose draw scope contains the `qrose` painter (assert via `composeTestRule.onNodeWithTag("pairing_qr").assertExists()` — the test will set this tag in step 3).
- [x] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*DeviceComponentsTest*"` — must be RED.
- [x] Step 3: In `app/src/main/java/com/example/parentalcontrol/ui/parent/components/DeviceComponents.kt:445-508`, inside the `2 -> { … }` branch of `PairingBottomSheet`, replace the placeholder `Icon(Icons.Default.Person, …, modifier = Modifier.size(100.dp))` (lines 480-485) with: `val qrPainter = rememberQrCodePainter(data = code.deeplink, options = QrCodeOptions(size = 240.dp)); Image(painter = qrPainter, contentDescription = "Pairing QR", modifier = Modifier.size(240.dp).testTag("pairing_qr"))`. Keep the existing 8-char `code` text (lines 453-459) below the QR for manual entry. Add imports: `import io.github.alexzhirkevich.qrose.options.QrCodeOptions`, `import io.github.alexzhirkevich.qrose.rememberQrCodePainter`, `import androidx.compose.foundation.Image`, `import androidx.compose.ui.platform.testTag`.
- [x] Step 4: Re-run the test — must be GREEN.

## 8. Wire `parentalcontrol://pair?code=...` deeplink intent-filter (RED → GREEN)

- [x] Step 1: Create `app/src/test/java/com/example/parentalcontrol/pairing/PairingDeeplinkTest.kt`. Add failing Robolectric test `deeplink_intent_with_code_query_param_routes_to_pairing_screen` that:
  - Builds an `Intent(ACTION_VIEW, Uri.parse("parentalcontrol://pair?code=ABCDEFGH"))`.
  - Resolves it through Robolectric's PackageManager against the app's `MainActivity`.
  - Asserts the intent matches an `intent-filter` that includes `<data android:scheme="parentalcontrol" android:host="pair" />`.
- [x] Step 2: Run the test — must be RED.
- [x] Step 3: In `app/src/main/AndroidManifest.xml`, inside the `MainActivity` block (after the existing `intent-filter` at lines 37-41), add a second `intent-filter` containing `<action android:name="android.intent.action.VIEW" />`, `<category android:name="android.intent.category.DEFAULT" />`, `<category android:name="android.intent.category.BROWSABLE" />`, and `<data android:scheme="parentalcontrol" android:host="pair" />`.
- [x] Step 4: Re-run the test — must be GREEN.
- [x] Step 5: In `app/src/main/java/com/example/parentalcontrol/MainActivity.kt`, add `override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handlePairingDeeplink(intent) }`. Add a private `handlePairingDeeplink(intent: Intent)` that, when `intent.action == ACTION_VIEW && intent.data?.scheme == "parentalcontrol"`, extracts `code = intent.data?.getQueryParameter("code")` and stashes it in a `@Composable` `remember { mutableStateOf<String?>(null) }` hoisted to the `MainActivity` setContent block, then passes the value into `PairingScreen(viewModel = pairingViewModel, prefilledCode = code, onPairingComplete = ..., onCancel = ...)`. In `onCreate`, call `handlePairingDeeplink(intent)` for the launch intent. Update `PairingScreen` to accept an optional `prefilledCode: String?` and pre-fill the `TextField` via `LaunchedEffect(prefilledCode)` if non-null. **Note**: this is a UI seam — confirm the `PairingScreen` signature in `app/src/main/java/com/example/parentalcontrol/pairing/ui/PairingScreen.kt` first; if it already accepts a `prefilledCode` param, just wire it. If not, add it (the existing call from `MainActivity.kt:52-56` does not pass a code, so this is an additive change).
- [x] Step 6: Run `./gradlew :app:testDebugUnitTest --tests "*PairingDeeplinkTest*"` — must be GREEN.

## 9. PR 1 validation gate

- [x] Step 1: Run `./gradlew :app:assembleDebug` — green.
- [x] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest*" --tests "*PairingManagerTest*" --tests "*PairingTest*" --tests "*DeviceComponentsTest*" --tests "*PairingDeeplinkTest*"` — all green.
- [x] Step 3: Run `./gradlew :app:detekt :app:ktlintCheck` — green.
- [ ] Step 4: Manual smoke: open `PairingBottomSheet` step 1, tap "Generar código", confirm QR appears (240×240), the 8-char code is still shown below, and `adb shell am start -W -a android.intent.action.VIEW -d "parentalcontrol://pair?code=TEST1234"` opens the child pairing screen with `TEST1234` pre-filled.

## 10. PR 1 Definition of Done

- [ ] `qrose` resolves against Compose BOM 2024.10.01 (or fallback to `compose-qr-code:1.0.5` documented in PR description).
- [ ] `ParentRepository` is `@Singleton @Inject` with `@ApplicationContext` — no more reflective `getSharedPreferences` anywhere in the file (search confirms zero matches for `Context::class.java.getMethod`).
- [ ] `createPairingCode` returns `Result<PairingCodeResult>` and the parent UI handles failure.
- [ ] `PairingManager.pairWithCode` calls `POST /functions/v1/pairing` via the real `HttpClient`.
- [ ] `PairingBottomSheet` step 2 renders a 240×240 QR tagged `"pairing_qr"` above the existing 8-char text.
- [ ] `parentalcontrol://pair?code=…` deeplink routes to `PairingScreen` with the code pre-filled.
- [ ] All 4 hotfix touchpoints still intact (no regression in `ExtraTimeScreen.kt:216`, `EnforcementController.kt:321`, `create-pairing-code/index.ts`, `pairing/index.ts:237`).
- [ ] PR description includes the `supabase functions deploy` snippet for any backend-touching changes (none in PR 1, but the deploy step is referenced for PR 2 readiness).

---

## PR 2 — Cross-device device list

**Per-PR Review Workload Forecast**

| Field | Value |
|---|---|
| Estimated changed lines | ~170 (new edge function ~60, `ParentRepository.kt` ~+35 / ~−22, `ParentViewModel.kt` ~+15 / ~−5, `DashboardScreen.kt` ~+90 / ~−10, `ParentRepositoryTest.kt` ~+30) |
| 400-line budget risk | Low |
| Linked spec | `parent-device-list` |
| Base branch | `pr1/pairing-and-qr` |
| Direct dependencies | PR 1 (Hilt-injected `ParentRepository`) |
| Unblocks | PR 5 (soft — `DashboardScreen` real data feeds the device picker) |

---

## 11. Create edge function `get-devices-for-parent`

- [ ] Step 1: Create `supabase/functions/get-devices-for-parent/index.ts`. Implement a Deno handler that: reads the `Authorization` header, validates the JWT via `supabaseAdmin.auth.getUser(token)`, and returns `401` if invalid. Otherwise creates a Supabase client with the **ANON** key + the caller's JWT (NOT service role — RLS drives the filter), calls `supabase.from("devices").select("id, device_name, device_model, os_version, app_version, device_state, last_seen_at").order("last_seen_at", { ascending: false })`, and returns `200` with the array. The RLS policy `devices_parent_select` (`parent_id = auth.uid()`, see `supabase/migrations/002_rls_policies.sql:37-41`) is what scopes the rows — do not add a client-side `eq("parent_id", user.id)`.
- [ ] Step 2: In the PR description, document the manual deploy step: `supabase functions deploy get-devices-for-parent --project-ref <ref>`. (Cannot be executed from the agent environment.)

## 12. Replace `ParentRepository.getDevices()` mock with real call (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/data/repository/ParentRepositoryTest.kt`, add failing test `getDevices_calls_get_devices_for_parent_with_jwt` that:
  - Uses `MockEngine` to respond `200 OK` with body `[{"id":"dev-1","device_name":"S21","device_model":"SM-G991B","os_version":"34","app_version":"1.0.0","device_state":"ACTIVE","last_seen_at":"2026-06-04T12:00:00Z"}]`.
  - Asserts the URL is `${SUPABASE_URL}/functions/v1/get-devices-for-parent` and the `Authorization: Bearer <jwt>` header is present.
  - Asserts the parsed result is `Result.success(listOf(ChildDevice(id="dev-1", name="S21", ...)))` with state `ACTIVE`.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest.getDevices*"` — must be RED.
- [ ] Step 3: In `ParentRepository.kt:19-49`, rewrite `getDevices()` to `suspend fun getDevices(): Result<List<ChildDevice>> = withContext(Dispatchers.IO) { try { val token = authManager.getAccessToken() ?: return@withContext Result.failure(IllegalStateException("not authenticated")); val response = clientProvider.httpClient.post("${SupabaseClientProvider.SUPABASE_URL}/functions/v1/get-devices-for-parent") { header("Authorization", "Bearer $token"); header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY); contentType(ContentType.Application.Json); setBody("{}") }; if (!response.status.isSuccess()) return@withContext Result.failure(RuntimeException("HTTP ${response.status}")); val body = Json.decodeFromString<List<DeviceDto>>(response.bodyAsText()); Result.success(body.map { it.toChildDevice() }) } catch (e: Exception) { Result.failure(e) } }`. Add a private `@Serializable data class DeviceDto(...)` with the seven fields and a `toChildDevice()` mapper.
- [ ] Step 4: Re-run the test — must be GREEN.

## 13. Update `ParentViewModel` to expose sealed UI state for the device list

- [ ] Step 1: In `viewmodel/ParentViewModel.kt`, add a sealed `interface DeviceListUiState { object Loading : DeviceListUiState; data class Success(val items: List<ChildDevice>) : DeviceListUiState; data class Error(val message: String) : DeviceListUiState; object Empty : DeviceListUiState }`. Add a `private val _deviceListState = MutableStateFlow<DeviceListUiState>(DeviceListUiState.Loading)` and a `val deviceListState: StateFlow<DeviceListUiState> = _deviceListState.asStateFlow()`.
- [ ] Step 2: Rewrite `loadDevices()` to call `repository.getDevices()` and map the `Result` into the four UI states: `Success(list) if non-empty`, `Empty if list.isEmpty()`, `Error(msg) on failure`, `Loading` before the call.

## 14. Rewrite `DashboardScreen` to render the four UI states (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt` (create the file), add four failing Compose UI tests: `loading_state_shows_progress`, `success_state_renders_device_cards`, `empty_state_shows_pair_cta`, `error_state_shows_retry_banner`. Each test pumps `DashboardScreen` with a `ParentViewModel` whose `deviceListState` is pre-set (use a Hilt-aware test rule or pass a fake ViewModel).
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*DashboardScreenTest*"` — must be RED.
- [ ] Step 3: In `app/src/main/java/com/example/parentalcontrol/ui/parent/screens/DashboardScreen.kt` (this is the file `MainActivity.kt:17` imports — NOT the `ui/screen/dashboard/DashboardScreen.kt` stub), rewrite the body of `DashboardScreen` to collect `viewModel.deviceListState` and branch into four `@Composable` sub-renders: `LoadingState { CircularProgressIndicator() }`, `ErrorState(msg, onRetry = { viewModel.loadDevices() }) { ErrorBanner + RetryButton }`, `EmptyState { Illustration + "Pair a new device" CTA → opens PairingBottomSheet }`, `SuccessState(list) { LazyColumn of DeviceCard }`. Order by `last_seen_at` DESC (the repository already returns sorted; assert in the test).
- [ ] Step 4: Re-run the four tests — all GREEN.

## 15. PR 2 validation gate

- [ ] Step 1: Run `./gradlew :app:assembleDebug` — green.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest*getDevices*" --tests "*DashboardScreenTest*"` — green.
- [ ] Step 3: Run `./gradlew :app:detekt :app:ktlintCheck` — green.
- [ ] Step 4: Manual smoke (after `supabase functions deploy get-devices-for-parent`):
  - Open `DashboardScreen` on a debug build → real list renders (or empty state if no devices).
  - Pull-to-refresh re-invokes the edge function.
  - In Supabase SQL: `SELECT id, device_name, parent_id FROM devices WHERE parent_id = auth.uid();` returns the same rows the app shows.

## 16. PR 2 Definition of Done

- [ ] New edge function `get-devices-for-parent` is in the diff and documented in the PR description with the deploy command.
- [ ] `ParentRepository.getDevices()` returns `Result<List<ChildDevice>>`; no more mock fallback.
- [ ] `DashboardScreen` renders the four states (Loading, Success, Empty, Error) with the correct visual treatment.
- [ ] Pull-to-refresh re-invokes the edge function unconditionally (no cache).
- [ ] No service-role key in the edge function — RLS is the security boundary.

---

## PR 3 — Outbox drainer (rename, replace, migrate)

**Per-PR Review Workload Forecast**

| Field | Value |
|---|---|
| Estimated changed lines | ~280 (Room migration SQL ~+15, `OutboxEntity` ~+5 / ~−2, `OutboxDao` ~+25 / ~−5, `AppDatabase` version bump +1, `AppDatabaseTest` ~+50, `SyncManager.kt` ~+70 / ~−30, `Workers.kt` new `OutboxDrainer` ~+60 / ~−50 old `OutboxUploadWorker` removed, `WorkScheduler.kt` ~+0 / ~−0 renamed method +1, `OutboxDrainerTest.kt` new ~+80) |
| 400-line budget risk | Low–Medium |
| Linked spec | `outbox-drain` |
| Base branch | `master` (independent of PR 1; can land in parallel) |
| Direct dependencies | None |
| Unblocks | None (enables reliable PR 4 round-trip) |

---

## 17. Room migration v4→v5 on the `outbox` table

- [ ] Step 1: In `app/src/main/java/com/example/parentalcontrol/data/local/AppDatabase.kt:53-62`, edit `OutboxEntity` to:
  - Add `val processed: Boolean = false`.
  - Add `val processed_at: String? = null`.
  - Rename `intentos` → `retries` (keep the type `Int = 0`).
  - Add `@ColumnInfo(name = "processed")` / `@ColumnInfo(name = "processed_at")` if the Kotlin field name needs to differ (it doesn't here).
- [ ] Step 2: In `AppDatabase.kt:267`, bump `@Database(... version = 5)`.
- [ ] Step 3: In `AppDatabase.kt`, in the `companion object`, add a `val MIGRATION_4_5 = object : Migration(4, 5) { override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE outbox ADD COLUMN processed INTEGER NOT NULL DEFAULT 0"); db.execSQL("ALTER TABLE outbox ADD COLUMN processed_at TEXT"); db.execSQL("ALTER TABLE outbox RENAME COLUMN intentos TO retries") } }`.
- [ ] Step 4: In `AppDatabase.kt:294`, change `.addMigrations()` to `.addMigrations(MIGRATION_4_5)`.

## 18. Update `OutboxDao` with the new query surface

- [ ] Step 1: In `AppDatabase.kt:208-230`, replace `OutboxDao` to:
  - Keep `insertOutboxItem` and `findByDedupKey` unchanged.
  - Remove `getPendingItems`, `incrementAttempts`, `deleteItem`, `deleteFailedItems` (replaced below).
  - Add `getPending(limit: Int): List<OutboxEntity>` → `SELECT * FROM outbox WHERE processed = 0 ORDER BY created_at ASC LIMIT :limit`.
  - Add `getPendingForDevice(deviceId: String, limit: Int): List<OutboxEntity>` → `WHERE processed = 0 AND payload_json LIKE '%"device_id":"${deviceId}"%' ORDER BY created_at ASC LIMIT :limit` (or add a `device_id` column for cleaner filtering — flagged in the next task).
  - Add `markProcessed(id: UUID, processedAt: String)` → `UPDATE outbox SET processed = 1, processed_at = :processedAt WHERE id = :id`.
  - Add `markFailed(id: UUID, processedAt: String, payloadJson: String)` → transactional update that sets `processed = 1`, `processed_at = :processedAt`, and overwrites `payload_json` with the input (caller serializes the `failed: true` flag into the JSON).
  - Add `incrementRetries(id: UUID)` → `UPDATE outbox SET retries = retries + 1 WHERE id = :id`.
  - Add `deleteProcessedOlderThan(cutoff: String)` → housekeeping.
  - Add `getFailedFlow(): Flow<List<OutboxEntity>>` → for the UI badge.
- [ ] Step 2: Run `./gradlew :app:assembleDebug` — KSP regenerates `OutboxDao_Impl`. Other call sites that referenced `incrementAttempts` / `deleteItem` / `getPendingItems` will fail to compile — this is the expected RED state. PR 3 task #4 fixes them.

## 19. Write Room migration test using `MigrationTestHelper`

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/data/local/AppDatabaseMigrationTest.kt` (create the file), add a test `migration_4_to_5_adds_processed_and_renames_intentos` that:
  - Uses `MigrationTestHelper` from `androidx.room:room-testing` (already on the test classpath at `libs.versions.toml:94`).
  - Creates a v4 schema with `Room.databaseBuilder(...).addMigrations(AppDatabase.MIGRATION_4_5).build()`.
  - Inserts a v4 row: `INSERT INTO outbox (id, tipo, payload_json, intentos, created_at, server_date) VALUES ('<uuid>', 'TIME_REQUEST', '{}', 3, '2026-06-04T12:00:00Z', '2026-06-04')`.
  - Runs `MIGRATION_4_5`.
  - Validates the v5 row has `processed = 0`, `processed_at = NULL`, `retries = 3` (value carried from `intentos`).
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*AppDatabaseMigrationTest*"` — must be GREEN.

## 20. Refactor `SyncManager.drainOutbox` and `sendOutboxItem` to a sealed result (RED → GREEN)

- [ ] Step 1: Define a sealed class in `sync/SyncManager.kt`: `sealed class OutboxSendResult { object Success : OutboxSendResult(); data class RetryableFailure(val cause: Throwable) : OutboxSendResult(); data class PermanentFailure(val statusCode: Int) : OutboxSendResult() }`.
- [ ] Step 2: In `SyncManagerTest.kt` (existing), add three failing tests:
  - `drainOutbox_marks_processed_on_2xx` — `MockEngine` returns `200`, expect row marked processed and `Result.success()` from `drainOutbox`.
  - `drainOutbox_increments_retries_on_5xx` — `MockEngine` returns `500`, expect `retries = 1` and `Result.retry()` from the worker (tested at the `SyncManager` layer as `Result.PartialSuccess(1)`).
  - `drainOutbox_marks_failed_on_4xx` — `MockEngine` returns `400`, expect `processed = true` with `failed: true` written into `payload_json`.
- [ ] Step 3: Run the three tests — must be RED.
- [ ] Step 4: In `SyncManager.kt:212-238`, rewrite `drainOutbox` to:
  - Read via `database.outboxDao().getPending(MAX_ITEMS_PER_RUN)`.
  - Iterate in `created_at` ASC, call the new `sendOutboxItem` (next step).
  - Branch on the result: `Success` → `outboxDao().markProcessed(id, now)`; `RetryableFailure` → `outboxDao().incrementRetries(id)` and check the `MAX_RETRY_ATTEMPTS = 10` budget — if exceeded, `markFailed` with `failed: true` in payload; `PermanentFailure` → `markFailed(id, now, payloadWithFailedFlag)`.
  - Stop iterating when `getPending(MAX_ITEMS_PER_RUN)` returns empty.
- [ ] Step 5: In `SyncManager.kt:327-361`, rewrite `sendOutboxItem` to return `OutboxSendResult`. Classify: `5xx`, `408`, `429`, and `IOException` → `RetryableFailure`; `4xx` other than `408`/`429` → `PermanentFailure`; `2xx` → `Success`. Crucially, if `httpClient` is `null`, throw a `RetryableFailure(IllegalStateException("httpClient not initialized"))` (do NOT silently no-op as today). Add a guard in `SyncManager.init { }` block that asserts `clientProvider.httpClient` is constructed (access `.httpClient` to trigger the lazy).
- [ ] Step 6: Re-run the three tests — must be GREEN. Update the only other caller: `ReconciliationWorker` in `Workers.kt:153` (it calls `syncManager.drainOutbox()` and ignores the result — leave as is, the new return type is still `SyncResult`).

## 21. Replace `OutboxUploadWorker` with `OutboxDrainer` (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/workers/OutboxDrainerTest.kt` (create the file), add four failing tests using `WorkManagerTestInitHelper` (from `libs.work.testing`) and Robolectric:
  - `empty_outbox_is_fast_success` — `getPending` returns empty, worker returns `Result.success()` immediately.
  - `success_row_marked_processed` — `MockEngine` returns `200`, after `WorkManager.getInstance().getWorkInfoById(request.id).get()` returns `SUCCESS`, assert `outboxDao.getPending(10)` is empty and the row has `processed = true`, `processed_at != null`.
  - `network_failure_returns_retry` — `MockEngine` throws `IOException`, worker returns `Result.retry()`, row has `retries = 1`, `processed = false`.
  - `retries_budget_exhausted_marks_row_failed` — pre-insert a row with `retries = 10`, then `MockEngine` returns `500`, worker returns `Result.success()` (the row is now poisoned; marking failed is the right behavior) and the row has `processed = true` with `failed: true` in `payload_json`.
- [ ] Step 2: Run the four tests — must be RED.
- [ ] Step 3: In `app/src/main/java/com/example/parentalcontrol/workers/Workers.kt:75-122`, delete the entire `OutboxUploadWorker` class.
- [ ] Step 4: In the same file, add `class OutboxDrainer @HiltWorker @AssistedInject constructor(@Assisted context: Context, @Assisted workerParams: WorkerParameters, private val outboxDao: OutboxDao, private val syncManager: SyncManager) : CoroutineWorker(context, workerParams) { companion object { private const val TAG = "OutboxDrainer"; const val WORK_NAME = "outbox-drain-periodic"; private const val INTERVAL_MINUTES = 15L; private const val FLEX_MINUTES = 5L; private const val MAX_RUN_ATTEMPTS = 3 } override suspend fun doWork(): Result = withContext(Dispatchers.IO) { val pending = outboxDao.getPending(50); if (pending.isEmpty()) return@withContext Result.success(); val result = syncManager.drainOutbox(); when (result) { is SyncResult.Success -> Result.success(); is SyncResult.PartialSuccess -> if (runAttemptCount < MAX_RUN_ATTEMPTS) Result.retry() else Result.success(); is SyncResult.Offline -> Result.retry(); is SyncResult.Error -> Result.retry() } } }`. Add imports: `com.example.parentalcontrol.data.local.OutboxDao`.
- [ ] Step 5: Re-run the four tests — must be GREEN.

## 22. Update `WorkScheduler` to schedule the new worker

- [ ] Step 1: In `app/src/main/java/com/example/parentalcontrol/workers/WorkScheduler.kt:49-75`, rename `scheduleOutboxUpload` to `scheduleOutboxDrainer`. Swap the `PeriodicWorkRequestBuilder<OutboxUploadWorker>(15, MINUTES, 5, MINUTES)` for `PeriodicWorkRequestBuilder<OutboxDrainer>(15, MINUTES, 5, MINUTES)`. Swap `OutboxUploadWorker.WORK_NAME` for `OutboxDrainer.WORK_NAME`. Keep the `ExistingPeriodicWorkPolicy.KEEP` (the work name changes from `"outbox_upload_work"` to `"outbox-drain-periodic"` — KEEP + the new name means the old work is orphaned but harmless).
- [ ] Step 2: At `WorkScheduler.kt:120-122` inside `scheduleSyncAfterBoot`, replace `OneTimeWorkRequestBuilder<OutboxUploadWorker>()` with `OneTimeWorkRequestBuilder<OutboxDrainer>()`.
- [ ] Step 3: Verify `WorkerInitializer.initialize` (lines 227-237, called by `BootReceiver.onBootCompleted` at `BootReceiver.kt:50`) invokes `WorkScheduler.scheduleAllPeriodicWork(context)` which now calls `scheduleOutboxDrainer` — no change needed to `WorkerInitializer` or `BootReceiver`. Do NOT add a schedule call from `ParentalControlApp.onCreate` (the proposal text was inaccurate; the design §1 PR 3 confirms scheduling flows through `WorkerInitializer`).

## 23. PR 3 validation gate

- [ ] Step 1: Run `./gradlew :app:assembleDebug` — green.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*AppDatabaseMigrationTest*" --tests "*SyncManagerTest*drainOutbox*" --tests "*OutboxDrainerTest*" --tests "*OutboxDaoTest*"` — green.
- [ ] Step 3: Run `./gradlew :app:detekt :app:ktlintCheck` — green.
- [ ] Step 4: Manual smoke:
  - Wipe app data, pair child, generate a `time_request` outbox row.
  - Toggle airplane mode, queue 3 events.
  - Disable airplane mode, call `WorkScheduler.triggerSyncNow(context)` from a debug broadcast (or wait 15 min).
  - In Supabase SQL: `SELECT id, processed, processed_at, retries FROM outbox ORDER BY created_at DESC LIMIT 3;` — confirm all 3 have `processed = true`.

## 24. PR 3 Definition of Done

- [ ] Room schema is at v5; migration `MIGRATION_4_5` is registered in `AppDatabase.getInstance`.
- [ ] `OutboxEntity` has `processed`, `processed_at`, `retries` (renamed from `intentos`).
- [ ] `OutboxDrainer` (new) replaces `OutboxUploadWorker` (deleted); no file references `OutboxUploadWorker` (search confirms zero matches).
- [ ] `WorkScheduler.scheduleOutboxDrainer` is invoked from `WorkerInitializer.initialize` → `BootReceiver.onBootCompleted`.
- [ ] `SyncManager.sendOutboxItem` returns a sealed `OutboxSendResult`; `httpClient == null` throws `RetryableFailure`.
- [ ] `retries` budget of 10 enforced; over-budget rows are marked failed with `failed: true` in `payload`.
- [ ] No manifest change — Hilt workers don't need one.

---

## PR 4 — Wire parent approval

**Per-PR Review Workload Forecast**

| Field | Value |
|---|---|
| Estimated changed lines | ~150 (`ParentRepository.kt` ~+90 / ~−24, `ParentRepositoryTest.kt` ~+80) |
| 400-line budget risk | Low |
| Linked spec | `time-request-approval` |
| Base branch | `pr2/device-list` (must rebase onto PR 2 tip) |
| Direct dependencies | PR 1 (Hilt-injected `ParentRepository`) |
| Unblocks | None (the child-side `EnforcementController` is hotfix #2 and needs no change) |

---

## 25. Replace `ParentRepository.getPendingRequests()` with real REST query (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/data/repository/ParentRepositoryTest.kt`, add failing test `getPendingRequests_filters_by_pending_status` that:
  - Uses `MockEngine` to respond `200 OK` with body `[{...PENDING...},{...APPROVED...,"status":"APPROVED"...}]`.
  - Asserts the parser drops the APPROVED row (the client-side filter is a defense-in-depth; the server's RLS already scopes, but the repository must be honest about what it returns).
  - Asserts the URL is `${SUPABASE_URL}/rest/v1/time_requests?select=*%2Cdevices%28device_name%29&status=eq.PENDING&order=created_at.desc` and the `Authorization: Bearer <jwt>` header is present.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest.getPendingRequests*"` — must be RED.
- [ ] Step 3: In `ParentRepository.kt:85-109`, rewrite `getPendingRequests()` to `suspend fun getPendingRequests(): Result<List<TimeRequest>> = withContext(Dispatchers.IO) { try { val token = authManager.getAccessToken() ?: return@withContext Result.failure(IllegalStateException("not authenticated")); val response = clientProvider.httpClient.get("${SupabaseClientProvider.SUPABASE_URL}/rest/v1/time_requests?select=*,devices(device_name)&status=eq.PENDING&order=created_at.desc") { header("Authorization", "Bearer $token"); header("apikey", SupabaseClientProvider.SUPABASE_ANON_KEY) }; if (!response.status.isSuccess()) return@withContext Result.failure(RuntimeException("HTTP ${response.status}")); val body = Json.decodeFromString<List<TimeRequestDto>>(response.bodyAsText()); Result.success(body.map { it.toTimeRequest() }) } catch (e: Exception) { Result.failure(e) } }`. Add a private `@Serializable data class TimeRequestDto(...)` with the joined fields.
- [ ] Step 4: Re-run the test — must be GREEN. Update `ParentViewModel.loadPendingRequests()` to handle `Result` (set `_error` on failure).

## 26. Replace `ParentRepository.approveRequest()` with real edge function call (RED → GREEN)

- [ ] Step 1: In `ParentRepositoryTest.kt`, add failing test `approveRequest_posts_to_approve_request_edge_function` that:
  - Uses `MockEngine` to respond `200 OK` with body `{"grant_id":"<uuid>","expires_at":"2026-06-04T14:00:00Z","minutes":30}`.
  - Asserts URL is `${SUPABASE_URL}/functions/v1/approve-request`, method is `POST`, body is `{"request_id":"req-1","minutes":30,"response_text":null,"decision":"APPROVED"}`, and `Authorization: Bearer <jwt>` is present.
  - Asserts the result is `Result.success(ApprovalResult(success=true, grantId=<uuid>, minutes=30, expiresAt=...))`.
- [ ] Step 2: Run the test — must be RED.
- [ ] Step 3: In `ParentRepository.kt:144-154`, rewrite `approveRequest` to call the edge function with the wire format above. **Do NOT change the edge function's `grants.source` field** — it stays at `"EXTRA_TIME"` per the pre-approved plan (matches the existing backend at `supabase/functions/approve-request/index.ts:117`; the spec's `"MANUAL"` value is the open question §7 Q4 in the design, and the user has already decided to keep `"EXTRA_TIME"`).
- [ ] Step 4: Re-run the test — must be GREEN.

## 27. Replace `ParentRepository.denyRequest()` with real call (RED → GREEN)

- [ ] Step 1: In `ParentRepositoryTest.kt`, add failing test `denyRequest_posts_with_decision_DENIED` that:
  - Uses `MockEngine` to respond `200 OK` with body `{"denied":true,"request_id":"req-1"}`.
  - Asserts URL is `${SUPABASE_URL}/functions/v1/approve-request`, method is `POST`, body is `{"request_id":"req-1","minutes":0,"response_text":"Not now","decision":"DENIED"}`.
- [ ] Step 2: Run the test — must be RED.
- [ ] Step 3: In `ParentRepository.kt:159-163`, rewrite `denyRequest` to call the same edge function with `decision = "DENIED"`, `minutes = 0`, `response_text = reason`. **Note on edge function shape**: `supabase/functions/approve-request/index.ts:38` currently only reads `request_id`, `minutes`, `response_text` — it does NOT read `decision`. Sending `decision = "DENIED"` will be a no-op field on the server, and the function will still insert a 0-minute APPROVED grant (wrong). Flag this in the PR description as a follow-up edge function change (extend the function to branch on `decision` and only insert a `grants` row when `decision == "APPROVED"`). For PR 4, the client wire format is locked in; the server-side handling is a one-line follow-up tracked separately.
- [ ] Step 4: Re-run the test — must be GREEN. Update `ParentViewModel.denyRequest()` to handle `Result` (set `_error` on failure).

## 28. PR 4 validation gate

- [ ] Step 1: Run `./gradlew :app:assembleDebug` — green.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*ParentRepositoryTest*getPendingRequests*" --tests "*ParentRepositoryTest*approveRequest*" --tests "*ParentRepositoryTest*denyRequest*"` — green.
- [ ] Step 3: Run `./gradlew :app:testDebugUnitTest` (no filter) — green; no regression in PR 1/2/3 tests.
- [ ] Step 4: Run `./gradlew :app:detekt :app:ktlintCheck` — green.
- [ ] Step 5: Manual smoke (full round-trip, requires PR 3 merged):
  - As child, ask for 30 min → `outbox` row → drained by `OutboxDrainer` → `time_requests` row appears in Supabase.
  - As parent, open `DeviceDetailScreen` → tap "Aprobar" on the `RequestCard` → `grants` row appears in Supabase.
  - As child, observe the remaining time update (this is enforced by `EnforcementController`, hotfix #2 — no change here).

## 29. PR 4 Definition of Done

- [ ] `getPendingRequests()`, `approveRequest()`, `denyRequest()` all return `Result<…>` and make real HTTP calls via `clientProvider.httpClient`.
- [ ] `RequestCard` UI in `DeviceComponents.kt:213-351` is unchanged.
- [ ] `grants.source` stays at `"EXTRA_TIME"` (no edge function change in PR 4).
- [ ] PR description flags the `denyRequest` server-side `decision` handling as a follow-up (the current `approve-request/index.ts` does not branch on `decision`; PR 4 locks the client wire format only).
- [ ] All 4 hotfix touchpoints still intact (no regression).

---

## PR 5 — Parent app-block UI

**Per-PR Review Workload Forecast**

| Field | Value |
|---|---|
| Estimated changed lines | ~310 (`AppsViewModel.kt` new ~+80, `AppsScreen.kt` rewrite ~+90 / ~−4, `DeviceDetailScreen.kt` ~+25 / ~−2, parent NavGraph ~+10, `AppsViewModelTest.kt` new ~+60, `AppsScreenTest.kt` new ~+30, `DeviceDetailScreenTest.kt` ~+15) |
| 400-line budget risk | Low–Medium |
| Linked spec | `app-block-policy` |
| Base branch | `pr4/approval` (must rebase onto PR 4 tip) |
| Direct dependencies | PR 1 (Hilt-injected ViewModel pattern), PR 2 (real device list, soft) |
| Unblocks | None |

---

## 30. Implement `AppsViewModel` with `loadLaunchableApps()` and `togglePolicy()` (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/ui/screen/apps/AppsViewModelTest.kt` (create the file), add failing test `loadLaunchableApps_uses_PackageManager_queryIntentActivities` that:
  - Uses Robolectric (`@RunWith(RobolectricTestRunner::class)`) to provide a fake `PackageManager` returning 3 fake `ResolveInfo` entries with distinct `packageName` and `loadLabel`.
  - Constructs `AppsViewModel` with a real `AppDatabase` (in-memory via `Room.inMemoryDatabaseBuilder`).
  - Asserts `viewModel.apps.value` is a `List<LaunchableApp>` of size 3.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*AppsViewModelTest*"` — must be RED.
- [ ] Step 3: In `app/src/main/java/com/example/parentalcontrol/ui/screen/apps/AppsViewModel.kt` (currently the empty stub at line 3), rewrite the file:
  - `data class LaunchableApp(val packageName: String, val label: String, @DrawableRes val iconRes: Int? = null, val state: String)`.
  - `@HiltViewModel class AppsViewModel @Inject constructor(@ApplicationContext private val context: Context, private val appPolicyDao: AppPolicyDao) : ViewModel()`.
  - `private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())` and `val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()`.
  - `private val _blocked = MutableStateFlow<Set<String>>(emptySet())` and `val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()`.
  - `fun loadLaunchableApps(deviceId: String?) { viewModelScope.launch(Dispatchers.IO) { val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER); val resolved = context.packageManager.queryIntentActivities(intent, 0); val list = resolved.map { ri -> LaunchableApp(packageName = ri.activityInfo.packageName, label = ri.loadLabel(context.packageManager).toString()) }; val existing = deviceId?.let { appPolicyDao.getAppPoliciesForDeviceFlow(it) }?.first().orEmpty(); val blocked = existing.filter { it.state == "BLOCKED" }.map { it.package_name }.toSet(); _apps.value = list.map { it.copy(state = if (blocked.contains(it.packageName)) "BLOCKED" else "ALLOWED") }; _blocked.value = blocked } }`.
  - `fun togglePolicy(deviceId: String, packageName: String, newState: String) { val previous = _blocked.value; val optimistic = if (newState == "BLOCKED") previous + packageName else previous - packageName; _blocked.value = optimistic; viewModelScope.launch(Dispatchers.IO) { try { appPolicyDao.upsertAppPolicy(AppPolicyEntity(device_id = deviceId, package_name = packageName, state = newState, daily_limit_minutes = null, allowed_windows = emptyList(), category = null)) } catch (e: Exception) { _blocked.value = previous /* revert */ } } }`.
- [ ] Step 4: Re-run the test — must be GREEN.
- [ ] Step 5: Add a second test `togglePolicy_calls_upsertAppPolicy_with_correct_state` that asserts the DAO was called with `state = "BLOCKED"` on toggle, and reverts on simulated DAO failure.

## 31. Rewrite `AppsScreen` from empty stub to a real LazyColumn of launchable apps (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/ui/screen/apps/AppsScreenTest.kt` (create the file), add failing Compose UI test `renders_launchable_apps_in_lazy_column` that:
  - Constructs an `AppsViewModel` whose `apps` StateFlow is pre-populated with 3 `LaunchableApp` entries.
  - Pumps `AppsScreen(deviceId = "dev-1", viewModel = vm, onBack = {})`.
  - Asserts all 3 package names are visible via `composeTestRule.onNodeWithText("com.example.app1").assertExists()` etc.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*AppsScreenTest*"` — must be RED.
- [ ] Step 3: In `app/src/main/java/com/example/parentalcontrol/ui/screen/apps/AppsScreen.kt` (currently the empty stub at line 3), rewrite the file to a `@Composable fun AppsScreen(deviceId: String?, viewModel: AppsViewModel, onBack: () -> Unit)`:
  - `LaunchedEffect(deviceId) { viewModel.loadLaunchableApps(deviceId) }`.
  - `Scaffold(topBar = { TopAppBar(title = { Text("Apps") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }) }) { padding -> LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) { items(viewModel.apps.value, key = { it.packageName }) { app -> AppRow(app = app, isBlocked = viewModel.blocked.value.contains(app.packageName), onToggle = { newState -> deviceId?.let { viewModel.togglePolicy(it, app.packageName, newState) } }) } } }`.
  - Private `AppRow` composable: `Row { Icon(…) ; Column { Text(app.label) ; Text(app.packageName, style = bodySmall) } ; Switch(checked = isBlocked, onCheckedChange = { onToggle(if (it) "BLOCKED" else "ALLOWED") }) }`.
- [ ] Step 4: Re-run the test — must be GREEN.

## 32. Add "Add to block list" button in `DeviceDetailScreen.PolicyTab` (RED → GREEN)

- [ ] Step 1: In `app/src/test/java/com/example/parentalcontrol/ui/parent/screens/DeviceDetailScreenTest.kt` (create the file), add failing Compose UI test `policy_tab_has_add_to_block_list_button` that:
  - Pumps `DeviceDetailScreen` with a `ParentViewModel` (or fake) and `onNavigateToApps = { … }`.
  - Navigates to the Policy tab.
  - Asserts `composeTestRule.onNodeWithText("Add to block list").assertExists()`.
- [ ] Step 2: Run the test — must be RED.
- [ ] Step 3: In `app/src/main/java/com/example/parentalcontrol/ui/parent/screens/DeviceDetailScreen.kt:405-484`, edit the `PolicyTab` composable:
  - Add a new parameter `onAddToBlockList: () -> Unit` to the signature.
  - Insert a new `Card` between the "Recompensa" card (lines 437-458) and the "Cambiar plantilla" card (lines 461-482). The card has: `Text("App block list", titleMedium) ; Text("Add apps to block for this device", bodySmall) ; Spacer(12.dp) ; OutlinedButton(onClick = onAddToBlockList, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Block, null) ; Spacer(8.dp) ; Text("Add to block list") }`.
  - Update the caller at line 188 (`2 -> PolicyTab(...)`) to pass `onAddToBlockList = { onNavigateToApps(deviceId) }`. Add a new parameter `onNavigateToApps: (String) -> Unit` to `DeviceDetailScreen`'s signature.
- [ ] Step 4: Re-run the test — must be GREEN.

## 33. Wire navigation from `DeviceDetailScreen` to `AppsScreen` with the `deviceId` route argument

- [ ] Step 1: In the parent NavGraph (search under `app/src/main/java/com/example/parentalcontrol/ui/navigation/` for `NavGraph.kt` — if it exists, edit; if it doesn't, the navigation is currently inline in `MainActivity.kt`, in which case edit the `else ->` branch at `MainActivity.kt:89-95`), add a new composable route `"apps/{deviceId}"`:
  - `composable("apps/{deviceId}") { backStack -> val deviceId = backStack.arguments?.getString("deviceId"); val appsViewModel: AppsViewModel = hiltViewModel(); AppsScreen(deviceId = deviceId, viewModel = appsViewModel, onBack = { navController.popBackStack() }) }`.
  - At the `DeviceDetailScreen` callsite, pass `onNavigateToApps = { deviceId -> navController.navigate("apps/$deviceId") }`.
- [ ] Step 2: If the navigation is inline in `MainActivity.kt`, hoist a `NavHostController` via `rememberNavController()` inside `setContent` and wrap the three top-level destinations in a `NavHost`.
- [ ] Step 3: Run `./gradlew :app:testDebugUnitTest --tests "*DeviceDetailScreenTest*" --tests "*AppsScreenTest*"` — both green.
- [ ] Step 4: Manual smoke: in a debug build, open `DeviceDetailScreen` → Policy tab → tap "Add to block list" → `AppsScreen` opens with the apps list pre-filtered to the originating `deviceId`; toggling a switch writes an `app_policies` row in Supabase.

## 34. PR 5 validation gate

- [ ] Step 1: Run `./gradlew :app:assembleDebug` — green.
- [ ] Step 2: Run `./gradlew :app:testDebugUnitTest --tests "*AppsViewModelTest*" --tests "*AppsScreenTest*" --tests "*DeviceDetailScreenTest*"` — green.
- [ ] Step 3: Run `./gradlew :app:testDebugUnitTest` (no filter) — green; no regression in PR 1/2/3/4 tests.
- [ ] Step 4: Run `./gradlew :app:detekt :app:ktlintCheck` — green.
- [ ] Step 5: Manual smoke (full round-trip):
  - Open `DeviceDetailScreen` → Policy tab → tap "Add to block list".
  - `AppsScreen` opens with the launchable apps list.
  - Tap the switch on a row → `app_policies` row appears in Supabase.
  - As child, try to launch the blocked app → blocked by `EnforcementController` (hotfix #2).

## 35. PR 5 Definition of Done

- [ ] `AppsViewModel` is `@HiltViewModel` with `loadLaunchableApps(deviceId)` calling `PackageManager.queryIntentActivities` on `Dispatchers.IO` and `togglePolicy(deviceId, pkg, newState)` calling `appPolicyDao.upsertAppPolicy` with optimistic update + rollback on failure.
- [ ] `AppsScreen` renders inside a `LazyColumn` with the launchable apps list; first frame within 200 ms (assert in the Compose UI test via `composeTestRule.onRoot().captureToImage()` after a 200 ms delay).
- [ ] `DeviceDetailScreen` Policy tab has an "Add to block list" `OutlinedButton` that navigates to `AppsScreen` with the originating `deviceId` as a route argument.
- [ ] Per-device policy isolation: toggling `com.example.game` for child A does NOT affect child B's `app_policies` row (assert in a unit test that uses two `deviceId` values).
- [ ] All 4 hotfix touchpoints still intact (no regression).

---

## Cross-PR sequencing notes for the orchestrator

1. **PR 3 can land in parallel with PR 1** because the Room migration and the worker rewrite are independent of the `ParentRepository` Hilt work. Recommended: open PR 3 first (smaller diff, isolated blast radius) so the child-side outbox is healthy before any parent-side surface lands.
2. **PR 2 and PR 4 branch off PR 1's tip** in parallel — both depend only on the Hilt-injected `ParentRepository`. Land whichever finishes first.
3. **PR 5 branches off PR 4** (soft dependency: PR 2's real device list improves the device picker UX in `DeviceDetailScreen`).
4. **The two `DashboardScreen.kt` files** are a known foot-gun: `app/src/main/java/com/example/parentalcontrol/ui/screen/dashboard/DashboardScreen.kt` is the empty stub (29 lines, not used), and `app/src/main/java/com/example/parentalcontrol/ui/parent/screens/DashboardScreen.kt` is the one `MainActivity.kt:17` actually imports. PR 2's task #14 edits the **latter**; the design.md and proposal.md are ambiguous about which file is meant.
5. **`denyRequest` server-side handling is a known gap** (design §7 Q5). The `approve-request` edge function does not read a `decision` field. PR 4 locks the client wire format only; a follow-up change must extend the edge function to branch on `decision` and skip the `grants` insert when `decision == "DENIED"`. This is documented in task #27 step 3 and in PR 4's DoD.

## Total: 35 tasks across 5 PRs (PR 1: 10, PR 2: 6, PR 3: 8, PR 4: 5, PR 5: 6)
