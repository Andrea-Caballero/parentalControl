# Proposal: fix-auth-state-persistence-across-cold-start

> Bug-fix proposal (NOT mini-SDD lite, NOT chained). Mirrors `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` and `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` precedents: same data-layer cold-start shape, same single-PR pattern, same ~73 LoC budget. The 2 RED tests already exist at `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerAuthenticatePersistTest.kt` and are structurally correct — the apply phase flips them GREEN, does NOT delete them.

## Why

After `adb shell am force-stop com.tudominio.parentalcontrol` and reopening, the parent user lands back on the "Iniciar sesión como padre" CTA (`AuthMissingErrorBanner` on the dashboard). Auth state does NOT persist across cold-start. Discovered during the live test session on 2026-07-07 (engram `sdd/discovery/live-test-2026-07-07`).

Root cause: the role-aware `authenticateOrCreate(role: Role)` overload (`DeviceAuthManager.kt:193-213` — the synthetic parent hotfix path from `openspec/changes/hotfix-parent-auth-session/`) writes the in-memory `currentAccessToken` and `role=PARENT` to `device_auth_prefs`, but **does NOT** call `persistSession(StoredSession(...))` or any other write that would survive process death. After force-stop:

1. `loadPersistedState()` reads `role=PARENT` → falls through the `when` block at lines 481-485 to `SessionState.PAIRED` via the `hasRole` branch.
2. `loadPersistedState()` calls `restoreSession()` → returns null (no `encrypted_session` blob was ever written by the synthetic path).
3. `currentAccessToken` stays null.
4. `AppNavHost.isPaired()` returns true → `resolveInitialRoute(...)` routes to `NavRoute.Dashboard` (no Onboarding screen).
5. `ParentViewModel.init { loadDevices() }` → `ParentRepository.getDevices()` returns `Result.failure(DeviceListError.AuthMissing)` because `authManager.getAccessToken()` is null (`ParentRepository.kt:294-295`).
6. `DashboardScreen` renders the `AuthMissingErrorBanner` — "Iniciar sesión como padre" CTA (`DashboardScreen.kt:529-571`).

The 2026-07-02 fix at `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` only covered the `handleAuthSuccess` write path (which DID call `persistSession`). The synthetic parent path was missed — it is a separate code path with its own persistence gap.

This is also a blocker for the V1 Solicitudes cache fix (PR #20, merged): the cache only populates when the parent is authenticated, so the cold-start auth bug means the cache is never populated from a cold start either.

## What Changes

1. **`DeviceAuthManager.authenticateOrCreate(role: Role)` writes a cleartext `synthetic_access_token` SharedPreferences key** alongside the existing `role` write (~+2 LoC at `DeviceAuthManager.kt:203-206`). Deterministic placeholder value: `"synthetic-${role.name.lowercase()}"` (e.g., `"synthetic-parent"`, `"synthetic-child"`) — the token is not used for real network calls, only for cold-start restoration. Mirrors the cleartext SharedPreferences pattern in `data/local/PairedDevicesStore.kt:20-25,100` (no Keystore, no encryption). Synthetic tokens have no real security value; the eventual `parent-auth-flow` change will replace them with real sign-up/sign-in.
2. **`DeviceAuthManager.loadPersistedState()` reads `synthetic_access_token`** when role is present, and pushes the value into `currentAccessToken` (~+5 LoC, parallel to the existing `restoreSession()?.let { stored -> ... }` block at `DeviceAuthManager.kt:498-502`). `sessionExpiresAt = 0` matches the synthetic path's expiry semantics; the existing expiry check in `restoreSession()` (line 465) is bypassed when `expiresAt = 0`, so the new read path mirrors that contract.
3. **`DeviceAuthManager.clearSession()` — no production code change.** The existing `.clear()` at line 415 already removes ALL `device_auth_prefs` entries, including the new `synthetic_access_token` key. Apply-phase adds a GREEN test pin to defend against future refactors.
4. **Tests (apply phase):**
   - **RED → GREEN**: the 2 existing cases in `DeviceAuthManagerAuthenticatePersistTest.kt` (`authenticateOrCreate(PARENT) persists access token to prefs`, `cold start after authenticateOrCreate(PARENT) restores accessToken`) flip GREEN.
   - **NEW GREEN (symmetry, per Q2=y)**: `cold start after authenticateOrCreate(CHILD) restores accessToken` — `Role.CHILD` uses the same overload (line 193) and has the same persistence gap. Mirror the PARENT test with `Role.CHILD`. ~15 LoC.
   - **NEW GREEN (defense-in-depth, per Q3=y)**: `clearSession() also clears synthetic_access_token` — after `authenticateOrCreate(PARENT)` + `clearSession()`, the `synthetic_access_token` key is absent from `device_auth_prefs`. ~5 LoC.
   - **NEW GREEN (round-trip)**: `authenticateOrCreate(PARENT) → clearSession() → authenticateOrCreate(PARENT) round-trips without stale-token leak` — defends against ordering bugs between in-memory and on-disk writes. ~15 LoC.
   - **NEW GREEN (negative case)**: `cold start with synthetic_access_token present but role missing leaves token null` — pins the invariant that BOTH keys must be present (the synthetic path writes both atomically; the negative case catches a partial-write regression). ~10 LoC.

## Spec Changes

Per Q4=d (defer), no `openspec/specs/` delta is written. The 2 existing RED tests in `DeviceAuthManagerAuthenticatePersistTest.kt` ARE the acceptance contract — they pin the behavior at the test level. Same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:20` ("parent-auth-session/spec.md unchanged; in-memory restore ordering is not documented") and the recent `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (no `Modified` capability listed).

If the spec phase later decides to formalize, the natural seam is `openspec/specs/parent-auth-session/spec.md` — the new requirement is "the synthetic parent hotfix path must persist a recoverable access token across process death". Deferred to user judgment at spec phase per the existing engram decision.

## Test Acceptance

**Acceptance contract** — `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerAuthenticatePersistTest.kt` (the RED file from the explore phase, engram #278).

RED on `master = f54f2b0`:
- `authenticateOrCreate with PARENT persists access token to prefs for cold-start hydration` (line 121)
- `cold start after authenticateOrCreate with PARENT restores accessToken` (line 175)

GREEN after the fix (apply phase):
- Both RED cases above flip GREEN.
- `cold start after authenticateOrCreate with CHILD restores accessToken` (NEW, ~15 LoC).
- `clearSession() also clears synthetic_access_token` (NEW, ~5 LoC).
- `authenticateOrCreate(PARENT) → clearSession() → authenticateOrCreate(PARENT) round-trips` (NEW, ~15 LoC).
- `cold start with synthetic_access_token but missing role leaves token null` (NEW, ~10 LoC).

Siblings that must stay GREEN:
- `DeviceAuthManagerRoleTest` (5 cases) — existing role-detection coverage.
- `DeviceAuthManagerColdStartTest` (4 cases) — 2026-07-02 fix coverage for the `handleAuthSuccess` path.

Build/lint gates:
- `./gradlew :app:assembleDebug` green.
- `./gradlew :app:testDebugUnitTest` green.
- `./gradlew detekt` + `ktlintCheck` add no new violations on the 1 touched production file.

## Impact

- **User-facing**: parent no longer has to re-authenticate on every cold start. The "Iniciar sesión como padre" CTA no longer appears post-force-stop when the user had already chosen the synthetic parent path.
- **Data**: a single new cleartext SharedPreferences key `synthetic_access_token` in the existing `device_auth_prefs` namespace. No migration needed; first-deploy users see no change.
- **Security**: none — synthetic tokens are throwaway identifiers, not sensitive material. The eventual `parent-auth-flow` change will introduce real auth tokens with Keystore-encrypted persistence; that's a separate future change.
- **API surface / DI / Hilt / DB / Compose / nav**: zero change. No new constructor parameters, no new Hilt modules, no new Room entities, no Compose surface.

## Capabilities

- **New**: none.
- **Modified**: none. `parent-auth-session/spec.md` does not currently document cold-start hydration of the synthetic path; the test pins the behavior. Same precedent as the 2026-07-02 fix (Q4=d defer).

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `auth/DeviceAuthManager.kt:193-213` | Modified | `authenticateOrCreate(role: Role)` writes `synthetic_access_token` key (~+2 LoC). |
| `auth/DeviceAuthManager.kt:475-503` | Modified | `loadPersistedState()` reads `synthetic_access_token` and populates `currentAccessToken` (~+5 LoC). |
| `test/.../DeviceAuthManagerAuthenticatePersistTest.kt` | RED → GREEN | 2 existing RED cases flip GREEN. |
| `test/.../DeviceAuthManagerAuthenticatePersistTest.kt` | +4 new GREEN cases | CHILD symmetry, clearSession test pin, round-trip, negative case (~45 LoC). |
| `data/local/PairedDevicesStore.kt` | Read-only | Cleartext SharedPreferences pattern reference (mirror the write style at line 100). |
| `specs/parent-auth-session/spec.md` | Unchanged (deferred) | See `## Spec Changes`. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Cleartext token ends up in an unintended backup/log path | Low | Synthetic token is `"synthetic-parent"`/`"synthetic-child"` literal — no PII, no credential material. Backup paths log no SharedPreferences contents. |
| `loadPersistedState` reads the synthetic token even when the device-auth `encrypted_session` blob is also present | Low | Apply-phase picks one source: prefer `encrypted_session` (existing) when present, fall back to `synthetic_access_token` only when absent. The 2 sources are mutually exclusive in practice (different entry points). |
| `clearSession()` stops clearing `device_auth_prefs` due to a future refactor | Low | New GREEN test pins the invariant. The `.clear()` call already covers it; the test catches any future `remove("...")` regression. |
| Child path gets a "logged in" state visible to the parent UI by accident | Low | Child UI never renders `AuthMissingErrorBanner` (different route). The fix only affects `getAccessToken()` hydration; no UI surface change. |
| Race between `init { loadPersistedState() }` and the first `getAccessToken()` consumer | Low | Kotlin object init is single-threaded before any consumer reads. `sessionMutex` guards subsequent writes. Same precedent as the 2026-07-02 fix. |

## Rollback

Single PR. `git revert` restores prior behavior. No data migration, no feature flag. The new `synthetic_access_token` key in `device_auth_prefs` becomes a no-op orphan after revert (the synthetic path ignores it on read); the existing `.clear()` in `clearSession()` removes it on the next logout.

## Out of scope

- Migration of the synthetic path to Keystore-encrypted `encrypted_session` (overkill for throwaway tokens; the eventual `parent-auth-flow` will replace them with real auth + Keystore).
- Real `parent-auth-flow` implementation (separate change).
- `DeviceAuthService.start()` ordering rework.
- Splash/loading state on cold start (the synchronous `loadPersistedState()` runs before any consumer reads the token, so no UI surface required).
- Updating the V1 archived `fix-auth-session-restore-on-cold-start` spec (per Q5=l leave).

## Success criteria

- [ ] RED: `DeviceAuthManagerAuthenticatePersistTest` 2 cases fail on `master = f54f2b0` with the current in-memory-only behavior.
- [ ] GREEN: same 2 cases pass after the `authenticateOrCreate(role)` + `loadPersistedState` change.
- [ ] GREEN: `cold start after authenticateOrCreate(CHILD) restores accessToken` passes.
- [ ] GREEN: `clearSession() also clears synthetic_access_token` passes.
- [ ] GREEN: `authenticateOrCreate(PARENT) → clearSession() → authenticateOrCreate(PARENT) round-trips` passes.
- [ ] GREEN: `cold start with synthetic_access_token but missing role leaves token null` passes.
- [ ] `DeviceAuthManagerRoleTest` (5) + `DeviceAuthManagerColdStartTest` (4) stay green.
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the 1 touched production file.
- [ ] `parent-auth-session/spec.md` UNCHANGED (deferred per Q4=d).

## Open questions

None for this phase. All 5 questions answered in engram #280 (Q1=c, Q2=y, Q3=y, Q4=d, Q5=l).

## References

- **Diagnosis**: engram **#278** `sdd/fix-auth-state-persistence-across-cold-start/explore` — full read/write surface trace; proves the synthetic path doesn't persist.
- **Decisions**: engram **#280** `sdd/fix-auth-state-persistence-across-cold-start/decisions` — Q1=c (cleartext), Q2=y (CHILD), Q3=y (clearSession pin), Q4=d (defer spec), Q5=l (leave archive).
- **Live test discovery**: engram `sdd/discovery/live-test-2026-07-07` — surfaced the bug on real hardware.
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerAuthenticatePersistTest.kt:121,175` — 2 cases RED on `master = f54f2b0`.
- **Bug surface**: `DeviceAuthManager.kt:193-213` (`authenticateOrCreate(role: Role)` — synthetic path), `:475-503` (`loadPersistedState` — cold-start hydration), `:405-419` (`clearSession` — auto-clears the new key via `.clear()`).
- **Consumer surface**: `getAccessToken()` at `ParentRepository.kt:294-295` (returns null → `DeviceListError.AuthMissing`), `AppNavHost.kt:65-67` (`isPaired()` routes to Dashboard), `DashboardScreen.kt:529-571` (`AuthMissingErrorBanner` CTA).
- **Prior art on same surface**: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` — covered the device-auth `handleAuthSuccess` path; this fix covers the synthetic path that PR #8 / the 2026-07-02 fix missed.
- **Pattern reference**: `data/local/PairedDevicesStore.kt:20-25,100` — cleartext SharedPreferences write/read pattern (per Q1=c).
- **Format precedent**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (same data-layer cold-start shape, same single-PR pattern).
- **Format precedent (older)**: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` (the cold-start shape this proposal extends).
- **Cache dependency**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (PR #20) — the V1 Solicitudes cache only populates when auth persists; this fix unblocks the cache from working on cold start.