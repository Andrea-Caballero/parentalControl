# Design: Restore session in BootReceiver before scheduling SyncWorker

## 1. Architecture overview

This 2-piece, 1-test change slots into the existing boot flow without altering `SyncManager`, `WorkScheduler`, or `SupabaseClientProvider`. It is the direct follow-up to the 2026-06-23 PR #7 (`04b955a`, "fix(work): disable WorkManager auto-init"), which made `SyncWorker` instantiate correctly on boot and in doing so made the pre-existing offline-gate noise — `SyncWorker D Offline, reintentando...` with `tags={SyncWorker, after_boot}` — visible in logcat. PR #7 fixed the *instantiation*; this change fixes the *boot sequencing* so the worker enqueued by `WorkScheduler.scheduleSyncAfterBoot(...)` no longer hits the `DISCONNECTED` short-circuit at `SyncManager.kt:174`. The fix promotes `DeviceAuthManager.restoreSession()` from `private` to `internal`, calls it from `BootReceiver.onBootCompleted()` before `WorkerInitializer.initialize(...)`, and pins the invariant with a Robolectric test in the existing `BootReceiverTest.kt`.

## 2. Component design

### 2.1 Visibility change — `DeviceAuthManager.restoreSession()`

Current at `DeviceAuthManager.kt:437`:

    private fun restoreSession(): StoredSession?

New (single line, no body change):

    internal fun restoreSession(): StoredSession?

**Why this is safe:** the method already implements the no-network-on-cache-hit path — it decrypts `device_auth_prefs.encrypted_session` via AndroidKeyStore and returns `null` on missing / expired / decryption-failed. It does not touch `_sessionState`, `_deviceId`, `currentAccessToken`, or any Supabase endpoint. Promoting visibility only widens who can read the stored session.

**Why `internal` over `public`:** the sole new caller is `BootReceiver` in the same `:app` module. `internal` is sufficient and deliberately narrows the API surface; the proposal's "public" recommendation is tightened during design review because no consumer outside the module is foreseen. A doc-comment will explicitly state "no network, no side effects; returns the stored session if any."

### 2.2 `BootReceiver.onBootCompleted()` — wrap `WorkerInitializer.initialize` in a `restoreSession()` gate

Wrap the existing `WorkerInitializer.initialize(context, isAfterBoot = true)` call in `GlobalScope.launch { ... }`. Inside the coroutine: call `DeviceAuthManager.getInstance(context).restoreSession()`. On non-null → call `WorkerInitializer.initialize(context, isAfterBoot = true)`. On `null` → log `Log.w(TAG, "no stored session, skipping boot workers")` and `return@launch`. On the null branch the WHOLE `WorkerInitializer.initialize` call is skipped — i.e., neither the sync chain (`scheduleSyncAfterBoot`) NOR the periodic works (`scheduleAllPeriodicWork`) are enqueued. This is intentional per the locked-scope decision "2a: abort the sync" (the orchestrator resolved the fork in favor of option 1: one wrapped call, full skip on null). `onPackageReplaced(...)` is covered automatically because it delegates to `onBootCompleted(context)`.

**Coroutine scope — `GlobalScope` over `BroadcastReceiver.goAsync()`:** the boot path is fire-and-forget; the receiver instance lives milliseconds and may be GC'd before the suspend call returns; `goAsync()` imposes a 10 s ANR window that is uncomfortable given SharedPreferences + Keystore decrypt is untestable on this dev box. The codebase already uses `GlobalScope` for one-shot fire-and-forget suspend work (`IntegrityResponseHandler.kt:212`); introducing an Hilt-provided `@ApplicationScope CoroutineScope` is a separate refactor and out of scope.

### 2.3 `BootReceiverTest.kt` — append 2 tests

The existing file at `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` (Robolectric + `WorkManagerTestInitHelper`, `@Config(sdk = [33])`) already covers `onBootCompleted_schedules_outbox_drainer_periodically`. **Append, not create** — the proposal's separate `BootReceiverRestoreSessionTest.kt` is tightened to mirror the project's "one receiver, one test file" convention.

Add:

- `onBootCompleted with valid restored session enqueues SyncWorker after_boot` — `mockkObject(DeviceAuthManager.Companion); every { DeviceAuthManager.getInstance(any()).restoreSession() } returns fixedStoredSession`; fire the boot intent; assert `WorkManager.getInstance(context).getWorkInfosForUniqueWork("sync_work_after_boot").get()` has size 1.
- `onBootCompleted with null restored session skips SyncWorker` — stub `restoreSession()` returns `null`; assert the unique-work query is empty; assert the warning via Robolectric `ShadowLog`.

## 3. Architecture decisions

| # | Choice | Alternative | Rationale |
|---|---|---|---|
| **A** | `restoreSession()` direct call | `authenticateOrCreate()` (would fall through to `createAnonymousSession()` → Supabase call on null) | Boot must be offline-tolerant. `restoreSession()` is the only suspend entry on `DeviceAuthManager` that does not hit Supabase; `refreshSession()` requires an existing `currentRefreshToken`. |
| **B** | `GlobalScope.launch` | `BroadcastReceiver.goAsync()` (10 s ANR budget); new Hilt `@ApplicationScope CoroutineScope` | Boot is fire-and-forget; receiver lives ms; no app-scoped scope wired today; codebase precedent (`IntegrityResponseHandler.kt:212`) uses `GlobalScope`. |
| **C** | `internal` | `public` (proposal default) | Call site is in the same `:app` module; `internal` narrows the API surface; only one new caller. |
| **D** | Robolectric + `WorkManagerTestInitHelper` | Pure JVM with a `BroadcastReceiver` wrapper | `BroadcastReceiver` is framework-injected; Robolectric is already used by `BootReceiverTest` and `DeviceAuthManagerRoleTest`. |

## 4. Apply hints

- **Strict TDD, 2 commits:**
  1. **RED** — append 2 tests to `BootReceiverTest.kt`. They fail: visibility is still `private` (compile error) AND the receiver does not yet call `restoreSession()` at all.
  2. **GREEN** — promote `restoreSession()` to `internal` (1 line in `DeviceAuthManager.kt`) AND wrap the existing `WorkerInitializer.initialize(context, isAfterBoot = true)` call in `GlobalScope.launch { restoreSession(); if (success) WorkerInitializer.initialize(context, isAfterBoot = true) else Log.w(...) return@launch }` in `onBootCompleted()` (~+6 lines). The wrap is around the WHOLE `WorkerInitializer.initialize` call — on the null branch both the sync chain AND the periodic works are skipped (option 1, locked-scope 2a). Both new tests pass; existing `outbox_drainer_periodically` test still passes.
- **Quality gates** (no new deps, permissions, Ktor config, or manifest entries):
  `./gradlew testDebugUnitTest && ./gradlew assembleDebug && ./gradlew detekt && ./gradlew ktlintCheck`.
- **Files touched (3 total, exact paths):**
  - `app/src/main/java/com/tudominio/parentalcontrol/receiver/BootReceiver.kt` (modify, +~6 lines).
  - `app/src/main/java/com/tudominio/parentalcontrol/auth/DeviceAuthManager.kt` (modify, 1 line — visibility modifier only).
  - `app/src/test/java/com/tudominio/parentalcontrol/receiver/BootReceiverTest.kt` (modify, +~50 lines for the 2 new tests).
- **The visibility change and the receiver change MUST land together** — neither alone keeps the test in a buildable state.

## 5. Verification approach

- The 2 new RED tests are the primary verification — they pin (a) success → `sync_work_after_boot` is enqueued; (b) failure → it is not, and the warning is logged via `ShadowLog`.
- The 638 prior unit tests must remain green (regression target).
- `./gradlew assembleDebug`, `./gradlew detekt`, `./gradlew ktlintCheck` must all succeed (no new detekt rules; ktlint "no-wildcard-imports" satisfied by existing imports).
- Manual smoke is OUT OF SCOPE for this PR — dev box has no `adb`/emulator. Post-merge, CI's instrumented runner on API 28/31/35 will validate that no `Offline, reintentando...` logcat appears on a fresh boot with a stored session.