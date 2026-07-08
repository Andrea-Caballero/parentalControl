# Proposal: fix-migrate-stale-parent-id-on-load

> Bug-fix proposal (NOT mini-SDD lite, NOT chained). Mirrors `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/proposal.md` precedent: same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract pattern. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMigrationTest.kt` and is structurally correct — the apply phase flips it GREEN, does NOT delete it.

## Why

PR #27 (`fix-behavioral-event-log-fixture-loading`, merged at master `2820e59`) added a 1-line fix at `DeviceAuthManager.authenticateOrCreate(Role.PARENT)` (`DeviceAuthManager.kt:217-224`) that writes `parent_id = MOCK_PARENT_ID` to `device_auth_prefs` alongside `role` + `synthetic_access_token`. That fix works for FRESH auth, but does **NOT** help parents who already have auth state persisted from BEFORE PR #27. When the app restarts, `loadPersistedState()` (`DeviceAuthManager.kt:507-550`) reads the persisted state (with `role=PARENT`, `synthetic_access_token=...`, NO `parent_id`) and skips `authenticateOrCreate(Role.PARENT)`. So `parent_id` is NEVER written for these users. The 5th live test session (2026-07-08, post-PR-#27) confirmed the gap: parents with stale auth state still see "Sin eventos" in `BehaviorLogScreen`. Only `pm clear` or `uninstall` (which the live test session did) triggers a fresh auth. Real users can't do this in production.

Downstream: `getParentId()` (`DeviceAuthManager.kt:426-428`) returns `null`, `BehaviorLogViewModel.parentId = authManager.getParentId().orEmpty()` (`BehaviorLogViewModel.kt:50`) collapses to `""`, the DAO filter `WHERE parent_id = ''` (`BehavioralEventDao.kt:55`) matches zero rows, and the UI renders the empty state even though the 5 fixture rows owned by `parent_id = "parent-demo"` are present. The fix is a **lazy migration step**: when `loadPersistedState()` reads an existing auth state that's missing `parent_id` AND the role is PARENT, write the default `MOCK_PARENT_ID`. Idempotent, role-gated, one-shot WARN log for observability.

## What changes

Single PR, ~10-15 LoC production + ~210 test. Auth-layer only:

1. **New private helper `migrateStaleParentId(prefs: SharedPreferences)`** in `DeviceAuthManager.kt` (per Q3=(h), extracted to a dedicated function for testability + future-proofing):
   - Read `role` from `prefs`.
   - If `role != PARENT` (per Q2=(n), CHILD path is intentionally NOT migrated), return — no-op.
   - Read `parent_id` from `prefs`.
   - If `parent_id.isNullOrEmpty()` (per Q5=(e), empty string counts as missing), write `parent_id = MockSupabaseEngine.MOCK_PARENT_ID` via `prefs.edit().putString("parent_id", MOCK_PARENT_ID).apply()` (per Q1=(a), async `apply()` matches the existing pattern at `DeviceAuthManager.kt:226`).
   - Emit one-shot `Log.w(TAG, "migrated stale parent_id for pre-PR-#27 parent")` for observability (per Q4=(y), one WARN log per affected cold start; matches the OPPO edge-case log pattern at `DeviceAuthManager.kt:519-523`).
2. **Call the helper from the end of `loadPersistedState()`** (`DeviceAuthManager.kt:507-550`), after the synthetic-token hydration block at `:542-549` and before the closing brace. One-line addition: `migrateStaleParentId(prefs)`.
3. **Apply-phase tests** (apply, not this proposal):
   - Existing `DeviceAuthManagerMigrationTest.kt` (RED on master `2820e59`) has 5 cases — 2 RED (`M1` stale PARENT migration, `M4` `getParentId()` end-to-end) + 3 GREEN-PIN control cases (`M2` CHILD no-op, `M3` idempotent when already set, `M5` no role no-op). After the fix, all 5 cases must be GREEN.
   - Sister test files stay GREEN: `DeviceAuthManagerRoleTest` (6 cases), `DeviceAuthManagerColdStartTest` (4 cases), `BehavioralEventsRepositoryTest` (4 cases), `BehavioralEventsRepositoryIntegrationTest` (1 case). The fix is invisible to them because they either inject `parentId` directly or assert via APIs that don't read `parent_id`.

## Capabilities

- **New**: none.
- **Modified**: none. The migration is not documented in any spec — `device-auth-session/spec.md` (if it exists) is silent on which SharedPreferences keys each auth path writes, and silent on backfill behavior for stale state. The RED test pins the behavior; spec delta deferred unless the user wants it formalized (open question #1 below). Same precedent as the auth-fix and cold-start proposals (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` and `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/proposal.md:25`).

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `auth/DeviceAuthManager.kt:507-550` | Modified | Add `migrateStaleParentId(prefs)` helper (~10-12 LoC) + one-line call at end of `loadPersistedState`. |
| `test/.../DeviceAuthManagerMigrationTest.kt` | RED → GREEN | 2 RED cases (`M1`, `M4`) flip GREEN; 3 GREEN-PIN control cases (`M2`, `M3`, `M5`) stay GREEN. All 5 cases pass. |
| `test/.../DeviceAuthManagerRoleTest.kt` | Unchanged | 6 cases — API surface unchanged; `authenticateOrCreate` is not on this seam. |
| `test/.../DeviceAuthManagerColdStartTest.kt` | Unchanged | 4 cases — encrypted_session + session-state restore invariants; `loadPersistedState` mutation to `parent_id` is invisible to them. |
| `test/.../BehavioralEventsRepositoryTest.kt` | Unchanged | 4 cases inject `parentId` directly into `repository.refresh(...)` — fix is invisible to them. |
| `test/.../BehavioralEventsRepositoryIntegrationTest.kt` | Unchanged | 1 case — PR #27 already shipped; the migration is additive. |
| `viewmodel/BehaviorLogViewModel.kt` | Unchanged | `.orEmpty()` coercion stays; no defensive fallback per Q3=(r) of the prior proposal. |
| `data/repository/BehavioralEventsRepository.kt` | Unchanged | No `parentId.isBlank() → skip` fallback. |
| `data/remote/MockSupabaseEngine.kt` | Unchanged | `MOCK_PARENT_ID` already exists at `:369` from PR #27 — reused, not re-declared. |
| `assets/mock-supabase/behavioral_events.json` | Unchanged | Already has `parent_id = "parent-demo"` for all 5 events. |
| `openspec/specs/*` | Unchanged (deferred) | See open question #1. |

## Impact

- **User-facing**: parents with pre-PR-#27 stale auth state will see the 5 fixture events in `BehaviorLogScreen` on next cold start. No need to clear app data or uninstall.
- **Migration**: lazy on-load. The helper is a no-op for fresh installs (no prefs), post-PR-#27 parents (`parent_id` already set), CHILD users (role-gated), and orphan-token prefs (no role). Only pre-PR-#27 parents with stale state get the WARN log + backfill.
- **Idempotency**: `isNullOrEmpty()` guard on `parent_id` ensures the helper never overwrites an existing value. If `parent_id = "parent-custom"` is ever set by a future code path, the migration skips it (per the GREEN-PIN `M3` control case).
- **Data shape**: unchanged on the wire. The fixture and the Repository URL builder keep the `parent_id=eq.<id>` shape — only the `<id>` value flows through correctly now.
- **DI/Hilt/DB/Compose/nav**: zero change. The fix is one new helper + one call site in one existing class; no new dependencies, no migrations, no nav updates.
- **Coupling concern**: `DeviceAuthManager` already imports `MockSupabaseEngine.MOCK_PARENT_ID` (from PR #27, `DeviceAuthManager.kt:223`). The migration reuses the same import — no new coupling.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Migration fires for CHILD users by accident | Low | Q2=(n) is enforced via `if (role == Role.PARENT)` guard inside the helper. `M2` control case pins the role gate. |
| Migration overwrites a non-default `parent_id` set by a future code path | Low | Q5=(e) `isNullOrEmpty()` guard skips when key is present. `M3` control case pins the idempotent no-op. |
| Migration fires on every cold start and spams the WARN log | Low | `isNullOrEmpty()` guard is the gate — once `parent_id` is written, the helper returns early on the next cold start. One-shot per affected device, not per cold start. |
| Helper is called from a hot path and slows cold start | Low | `apply()` is async (Q1=a), matches the existing pattern at `DeviceAuthManager.kt:226`. One extra `prefs.getString` + one extra `prefs.edit` per cold start — negligible. |
| WARN log leaks sensitive data | Low | The log message is a fixed string with no PII; matches the OPPO edge-case log at `DeviceAuthManager.kt:519-523`. |
| Sister test files (`DeviceAuthManagerRoleTest`, `DeviceAuthManagerColdStartTest`, `BehavioralEventsRepositoryTest`, `BehavioralEventsRepositoryIntegrationTest`) break because of the new migration step | Low | None of them seed the `role=PARENT` + missing-`parent_id` shape that the migration targets. `DeviceAuthManagerRoleTest` exercises `authenticateOrCreate` (which already writes `parent_id` from PR #27). `DeviceAuthManagerColdStartTest` exercises `loadPersistedState` with the OPPO edge case (no role-keyed stale state). `BehavioralEventsRepositoryTest` injects `parentId` directly. |

## Rollback

Single PR. `git revert` of the `migrateStaleParentId` helper + the one-line call site in `loadPersistedState` restores prior behavior (stale PARENT prefs stay stale; UI shows "Sin eventos" again for affected parents). No schema migration, no feature flag, no data loss. The existing GREEN tests stay GREEN either way.

## Out of scope

- Defensive fallback `parentId.isBlank() → skip filter` in `BehavioralEventsRepository.refresh()` — explicitly excluded per the PR #27 proposal Q3=(r). Defensive layers mask bugs; the empty-state UI is a clean failure mode (and the migration closes that failure mode for the stale-auth case).
- CHILD-path symmetry — explicitly excluded per Q2=(n). CHILD doesn't read `parent_id` today.
- Migration of other stale keys (e.g., `device_id` for pre-existing installs, `encrypted_session` for users with cleared Keystore) — out of scope. The helper is dedicated to the `parent_id` gap; a wider backfill audit would warrant a separate proposal.
- Hardening the `loadPersistedState` method into an enumerated-key migration pipeline — out of scope. The helper is intentionally narrow; future migrations (if any) get their own helpers.
- Production lazy-hydration of devices (separate long-standing follow-up) — out of scope.
- Supabase RLS / DB schema / edge functions — `parent_id` already exists as a column and as an RLS subject; no migration needed.
- Spec delta for `device-auth-session/spec.md` or equivalent — see open question #1.

## Success criteria

- [ ] **RED baseline on master**: `DeviceAuthManagerMigrationTest.loadPersistedState migrates stale PARENT prefs by writing parent_id` fails at `assertEquals("parent-demo", prefs().getString("parent_id", null))` because `loadPersistedState` does not touch `parent_id`. `DeviceAuthManagerMigrationTest.getParentId returns MOCK_PARENT_ID after migration` fails at `assertEquals("parent-demo", coldStart.getParentId())` because `getParentId()` returns `null`.
- [ ] **GREEN after fix**: both RED cases pass after the `migrateStaleParentId(prefs)` helper is added and called from the end of `loadPersistedState`.
- [ ] **No regressions in `DeviceAuthManagerRoleTest`** (6 cases) — unchanged GREEN.
- [ ] **No regressions in `DeviceAuthManagerColdStartTest`** (4 cases) — unchanged GREEN.
- [ ] **No regressions in `BehavioralEventsRepositoryTest`** (4 cases) — unchanged GREEN; they inject `parentId` directly.
- [ ] **No regressions in `BehavioralEventsRepositoryIntegrationTest`** (1 case) — unchanged GREEN; PR #27 fix still in effect.
- [ ] **CHILD no-op pinned**: `M2` `loadPersistedState does NOT migrate CHILD prefs` stays GREEN — the role gate prevents CHILD migration.
- [ ] **Idempotent pinned**: `M3` `loadPersistedState is idempotent when parent_id already set` stays GREEN — the `isNullOrEmpty()` guard prevents overwrite.
- [ ] **No-role no-op pinned**: `M5` `loadPersistedState does NOT migrate prefs without role` stays GREEN — the role gate prevents orphan-token migration.
- [ ] **Live verification**: after the fix, opening `BehaviorLogScreen` on a debug build with stale pre-PR-#27 PARENT auth state shows the 5 fixture events instead of "Sin eventos".
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the touched file.

## Open questions

1. **Spec delta — yes or defer?** The migration ("`loadPersistedState` must backfill `parent_id` for stale PARENT prefs") is not documented in any spec. The RED test pins the behavior. Recommend: defer the spec delta unless the user wants it formalized (same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` and `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/proposal.md:86`).
2. **WARN log message — fixed string or include device fingerprint?** Per Q4=(y) the log is a one-shot WARN. A fixed string ("migrated stale parent_id for pre-PR-#27 parent") is enough for observability. Including a device fingerprint would help post-mortem correlation but risks PII leakage. Recommend: fixed string, no fingerprint, unless the user wants more.
3. **Migration gate — `isNullOrEmpty()` vs `isNullOrBlank()`** — locked at Q5=(e) (`isNullOrEmpty()`). Empty string = missing; whitespace-only = treat as present (a future code path that sets `parent_id = " "` would be preserved, not overwritten). Confirm at apply time if the user wants the stricter `isNullOrBlank()` behavior.

## References

- **Diagnosis**: engram `sdd/fix-migrate-stale-parent-id-on-load/explore` (stale `parent_id` migration missing in `loadPersistedState`; RED tests confirmed at master `2820e59`).
- **Decisions**: engram `sdd/fix-migrate-stale-parent-id-on-load/decisions` — Q1=(a) `apply()` async, Q2=(n) no CHILD-path migration, Q3=(h) helper extracted, Q4=(y) one-shot WARN log, Q5=(e) `isNullOrEmpty()` guard.
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/auth/DeviceAuthManagerMigrationTest.kt` — 5 cases: 2 RED (`M1` stale PARENT migration, `M4` `getParentId()` end-to-end) + 3 GREEN-PIN control cases (`M2` CHILD no-op, `M3` idempotent, `M5` no role no-op).
- **Bug surface**: `DeviceAuthManager.kt:507-550` (`loadPersistedState` — reads `device_id`, `is_paired`, `role`, `encrypted_session`, `synthetic_access_token`, but never writes anything); `DeviceAuthManager.kt:217-224` (`authenticateOrCreate(Role.PARENT)` — the PR #27 fix that only fires on FRESH auth); `DeviceAuthManager.kt:519-523` (OPPO edge-case WARN log pattern that the new log mirrors); `DeviceAuthManager.kt:426-428` (`getParentId()` — returns `null` for stale state, which `BehaviorLogViewModel.init` reads).
- **Consumer surface**: `BehaviorLogViewModel.kt:50` (`parentId = authManager.getParentId().orEmpty()`); `BehavioralEventDao.kt:55` (`WHERE parent_id = :parentId`); `BehavioralEventsRepository.kt:65-66` (URL builder appends `parent_id=eq.<id>`).
- **Constant reused**: `MockSupabaseEngine.kt:369` (`MOCK_PARENT_ID = "parent-demo"`) — already imported by `DeviceAuthManager` from PR #27.
- **Sister test files (must stay GREEN)**: `DeviceAuthManagerRoleTest.kt` (6 cases), `DeviceAuthManagerColdStartTest.kt` (4 cases), `BehavioralEventsRepositoryTest.kt` (4 cases), `BehavioralEventsRepositoryIntegrationTest.kt` (1 case).
- **Prior change context**: `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/` (PR #27 — the 1-line fix that introduced the asymmetry the migration closes).
- **Format precedent**: `archive/2026-07-08-fix-behavioral-event-log-fixture-loading/proposal.md` (same author, same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract).
