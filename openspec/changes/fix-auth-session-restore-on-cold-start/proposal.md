# Proposal: fix-auth-session-restore-on-cold-start

## Why

Post-archive cross-device E2E on `master = 7cd7092` threw `IllegalStateException("no access token")` at `SyncManager.kt:493` on the first outbox POST after cold start; shared mock server (`tools/shared-mock-server/server.py:8787`) received zero traffic. The orphan-cleanup epic's archive-report documented the ceiling as "device state" — empirically tighter: a pre-existing session-restore bug. Diagnosis: **Engram obs #196** (`auth/session-restore-cold-start-bug`).

- **Not a regression of orphan-cleanup.** Deleted files (`ChildViewModel.kt`, `HomeScreen.kt`, `BlockScreen.kt`, `ChildComponents.kt` Composables, `calculateTimeRemaining` dedupe) are not in the auth path.
- **Prior art on same surface:** `archive/2026-06-23-feature-boot-restore-session-before-sync/` (PR #8) fixed the **boot path** (`BootReceiver` calls `restoreSession()` before sync chain). PR #8 does NOT cover the **application-start path** (process death + launcher reopen). `DeviceAuthManager.init { loadPersistedState() }` (lines 148-150, body 475-486) only repopulates `_deviceId` and `_sessionState` and never decrypts `encrypted_session`. `currentAccessToken` stays null until `DeviceAuthService.start()` → `authenticateOrCreate()` runs; any of the 8+ `getAccessToken()` consumers reading before that ordering gate completes throws.
- **OPPO edge case.** `_sessionState.value` at lines 481-485 falls through to `SessionState.NONE` when `device_id == null`, ignoring `is_paired=true` or persisted `role`. App boots into role-selection.

## What changes

1. **`DeviceAuthManager.loadPersistedState()` decrypts and pushes `currentAccessToken`.** After the existing `_sessionState.value = when { ... }` block, call `restoreSession()` and assign `currentAccessToken`, `currentRefreshToken`, `sessionExpiresAt` from the returned `StoredSession` (mirrors canonical populate at lines 156-160 of `authenticateOrCreate()`; `restoreSession()` is already `internal` from PR #8).
2. **OPPO parent-role branch.** Strengthen the `when` at lines 481-485: `is_paired=true` + missing `device_id` + persisted `role` resolves to `SessionState.PAIRED` instead of `NONE`. Emit one `Log.w` to surface the degraded state.
3. **Tests (RED first).** Add `DeviceAuthManagerColdStartTest.kt` (Robolectric + `@Config(sdk = [33])`, `runTest`, sibling to `DeviceAuthManagerRoleTest`). Four cases (bodies in `tasks.md` Phase 1): `init_with_valid_encrypted_session_populates_accessToken` (RED), `init_without_encrypted_session_leaves_token_null` (GREEN pin), `init_with_isPaired_but_missing_deviceId_sets_PAIRED_when_role_persisted` (RED), `init_with_expired_encrypted_session_does_not_throw_and_leaves_token_null` (GREEN pin).

## Capabilities

- **New**: none.
- **Modified**: none. `parent-auth-session/spec.md` unchanged; in-memory restore ordering is not documented.

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `auth/DeviceAuthManager.kt` | Modified | `loadPersistedState()`: +8 lines (decrypt+populate) +4 lines (OPPO branch + `Log.w`). |
| `test/.../DeviceAuthManagerColdStartTest.kt` | New | +4 Robolectric tests pinning the cold-start invariant. |
| `specs/parent-auth-session/spec.md` | Unchanged | Restore ordering not documented. |

## Impact

- **Behavior**: post-process-death, `currentAccessToken` is non-null as soon as any consumer instantiates `DeviceAuthManager`. Eliminates the throw at `SyncManager.kt:493` and the 8+ consumer call sites. OPPO boots into parent context instead of role-selection. No-blob path identical (null → existing re-auth).
- **API surface / DI / Hilt / DB / Compose / nav**: zero change.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Main-thread decrypt in `init` blocks app start | Low | Same cost class as existing `loadPersistedState()` SharedPreferences reads (sub-ms). Robolectric surface regression. If profile shows hot path, apply wraps in `runBlocking { withContext(Dispatchers.IO) { ... } }` (mirrors `clearSession()` at line 406). |
| Race between init-populate and `authenticateOrCreate()` | Low | `sessionMutex` (line 146) guards writes; Kotlin object init is single-threaded before any consumer reads. |
| Key rotation breaks decrypt / OPPO PAIRED-without-device_id causes server-side auth rejection | Med | `restoreSession()` already swallows decrypt errors (line 470-472). OPPO branch is net improvement over NONE→re-pair today; document trade-off in `apply-progress.md`. User can downgrade to NONE in 2 lines if preferred. RED test 1.4 covers expiry. |
| Hidden cold-path consumer reads token pre-init | Low | Phase 1.2 consumer sweep re-confirms the 8 known call sites. New consumer visible via grep gate. |

## Rollback

Two commits (RED + GREEN). `git revert` restores prior behavior. No data migration, no feature flag.

## Out of scope

Real `parent-auth-flow`; `boot-receiver` capability spec; `DeviceAuthService.start()` ordering rework; Keystore-only session storage; DataStore migration; OTP/2FA.

## Success criteria

- [ ] RED tests 1 + 3 fail on `master = 7cd7092`.
- [ ] All 4 new tests pass after the fix.
- [ ] Existing `DeviceAuthManagerRoleTest` (5) + `BootReceiverTest` (7) stay green.
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the 2 touched files. Pre-existing `DeviceComponents.kt` baseline issue NOT in scope (per PR #8 archive-report §6).

## References

- **Diagnosis**: Engram obs **#196** — `auth/session-restore-cold-start-bug`, project `parentalcontrol`.
- **Prior boot-path fix**: `archive/2026-06-23-feature-boot-restore-session-before-sync/` (PR #8).
- **Ceiling source**: `archive/2026-07-02-chore-delete-orphan-vm-and-screens/archive-report.md` (master @ `7cd7092`).
- **Bug surface**: `DeviceAuthManager.kt:475-486` (`loadPersistedState`), `:457-473` (`restoreSession`), `:152-172` (canonical populate block).
- **Consumer surface**: `getAccessToken()` at `SyncManager.kt:233,293,357,425,491` (throwing site `:491-494`), `SupabaseClientProvider.kt:232,259,278,299,322,354`, `ParentRepository.kt:103,154,231,281,323`, `PairingManager.kt:76`, `RealtimeManager.kt:126`, `PlayIntegrityManager.kt:153`.
