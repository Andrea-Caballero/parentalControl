# Proposal: fix-behavioral-event-log-fixture-loading

> Bug-fix proposal (NOT mini-SDD lite, NOT chained). Mirrors `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` precedent: same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract pattern. The RED test already exists at `app/src/test/java/com/tudominio/parentalcontrol/data/repository/BehavioralEventsRepositoryIntegrationTest.kt:118-159` and is structurally correct — the apply phase flips it GREEN, does NOT delete it.

## Why

The fifth Live Test session (`feat-parent-behavioral-event-log`, post-PR #25 + PR #26) shows `BehaviorLogScreen` rendering the "Sin eventos" empty state instead of the 5 seeded events from `app/src/main/assets/mock-supabase/behavioral_events.json`. Root cause is at the **auth boundary**, not the repository or DAO: `DeviceAuthManager.authenticateOrCreate(Role.PARENT)` (`DeviceAuthManager.kt:195-216`) writes `role` and `synthetic_access_token` to `device_auth_prefs` but **NEVER writes `parent_id`**. `savePairedSession` at `DeviceAuthManager.kt:381` does write `parent_id`, but the role-aware synthetic overload was a separate code path that inherited only a subset of the canonical `handleAuthSuccess` invariants. Downstream: `BehaviorLogViewModel.parentId = authManager.getParentId().orEmpty()` (`BehaviorLogViewModel.kt:50`) resolves to empty string (because `getParentId()` at `DeviceAuthManager.kt:409-411` reads `device_auth_prefs.parent_id` and finds null), the DAO read filter `WHERE parent_id = ''` (`BehavioralEventDao.kt:55`) matches zero rows, and the UI shows the empty state even though the mock successfully wrote 5 rows with `parent_id = "parent-demo"`.

PR A's 4 GREEN unit tests in `BehavioralEventsRepositoryTest.kt` (PR #25) covered the wire-shape contract in isolation — they inject `parentId = "parent-demo"` directly into `repository.refresh(parentId)` (`BehavioralEventsRepositoryTest.kt:142,158,174,196-197`), bypassing the auth-manager read path. PR B's 8 GREEN Compose tests (PR #26) seeded the DAO directly via `dao.insertAll(...)`. Neither covered the `authenticateOrCreate(Role.PARENT)` → `vm.events.first()` end-to-end seam. Three independent lines of evidence for the root cause (auth write surface gap / VM `.orEmpty()` coercion / DAO-fixture `parent_id` asymmetry) are traced in engram **#316**.

## What changes

Single PR, ~3-5 LoC production + 0 net test change. Data-layer only:

1. **New constant `MOCK_PARENT_ID = "parent-demo"`** in `MockSupabaseEngine.kt` (companion object, ~3 LoC including `const val` declaration + import-row expansion). The constant matches the existing fixture at `behavioral_events.json:11,22,33,44,55` — single source of truth per engram decision Q1=(m).
2. **One new line in `DeviceAuthManager.authenticateOrCreate(role: Role)`** at `DeviceAuthManager.kt:205-209` — extend the existing `edit().putString("role", ...).putString(KEY_SYNTHETIC_ACCESS_TOKEN, ...).apply()` block to also write `parent_id = MOCK_PARENT_ID` (imported from `MockSupabaseEngine`). Per decision Q2=(n), the write applies ONLY in the PARENT role path; no CHILD-path symmetry. `clearSession()` already uses `.clear()` which wipes the new key — no follow-up needed there.
3. **Apply-phase tests** (apply, not this proposal):
   - Existing `BehavioralEventsRepositoryIntegrationTest.kt:118-159` (RED today on `assertEquals(5, events.size)`) flips GREEN.
   - Existing 4 cases in `BehavioralEventsRepositoryTest.kt:139,154,170,192` stay GREEN unchanged — they inject `parentId = "parent-demo"` directly into `repository.refresh(...)` and do NOT depend on `authManager.getParentId()`, so the fix is invisible to them.
   - `DeviceAuthManagerRoleTest` (6 cases) and `DeviceAuthManagerColdStartTest` (4 cases) stay GREEN — the role-aware overload's existing API surface is preserved; only one additional `putString` is added to the existing edit block.

## Capabilities

- **New**: none.
- **Modified**: none. The integration gap is not documented in any spec — `device-auth-session/spec.md` (if it exists) is silent on which SharedPreferences keys each auth path writes. The RED test pins the behavior; spec delta deferred unless the user wants it formalized (open question #1 below). Same precedent as the auth-fix and cold-start proposals (`archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md:21` and `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md:25-27`).

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `data/remote/MockSupabaseEngine.kt` | Modified | Add `const val MOCK_PARENT_ID = "parent-demo"` in companion object (~3 LoC including import expansion). |
| `auth/DeviceAuthManager.kt:205-209` | Modified | Add `.putString("parent_id", MOCK_PARENT_ID)` to the existing edit block of `authenticateOrCreate(role: Role)` (~1 LoC + 1 import). |
| `test/.../BehavioralEventsRepositoryIntegrationTest.kt:118-159` | RED → GREEN | Existing RED case flips (asserts `events.size == 5`). |
| `test/.../BehavioralEventsRepositoryTest.kt:139,154,170,192` | Unchanged | 4 cases inject `parentId` directly — fix is invisible to them. |
| `test/.../DeviceAuthManagerRoleTest.kt` | Unchanged | 6 cases — API surface unchanged. |
| `test/.../DeviceAuthManagerColdStartTest.kt` | Unchanged | 4 cases — `clearSession()` already covers the new key via `.clear()`. |
| `viewmodel/BehaviorLogViewModel.kt` | Unchanged | `.orEmpty()` coercion stays; no defensive fallback per Q3=(r). |
| `data/repository/BehavioralEventsRepository.kt` | Unchanged | No `parentId.isBlank() → skip` fallback per Q3=(r). |
| `assets/mock-supabase/behavioral_events.json` | Unchanged | Already has `parent_id = "parent-demo"` for all 5 events. |
| `openspec/specs/*` | Unchanged (deferred) | See open question #1. |

## Impact

- **User-facing**: parent opens `BehaviorLogScreen` and sees the 5 fixture events instead of "Sin eventos". The fixture-loading integration gap is closed.
- **Migration**: none. `device_auth_prefs.parent_id` is a new key; first-launch parents have it written by the next `authenticateOrCreate(Role.PARENT)` call. Existing parents who already completed auth before the fix have no `parent_id`; they will get one on next sign-out / re-auth (no migration logic — acceptable brief-stale window).
- **Data shape**: unchanged on the wire. The fixture and the Repository URL builder keep the `parent_id=eq.<id>` shape — only the `<id>` value flows through correctly now.
- **DI/Hilt/DB/Compose/nav**: zero change. The fix is two additional lines in two existing classes; no new dependencies, no migrations, no nav updates.
- **Coupling concern**: `DeviceAuthManager.MOCK_PARENT_ID` import from `MockSupabaseEngine` couples production code to a mock-engine constant. Mitigation: the constant is a clearly-named `MOCK_*` value, lives next to the fixture it matches, and the eventual `parent-auth-flow` change will retire both the synthetic path and this coupling together. Acceptable per the precedent of `archive/2026-06-19-hotfix-parent-auth-session/`.

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Existing parents in the wild signed-in BEFORE the fix have no `parent_id` and the app shows "Sin eventos" until re-auth | Med | Same class of issue resolved by re-running `authenticateOrCreate(Role.PARENT)`; same precedent as the `auth-state-persistence-across-cold-start` cycle (`archive/2026-07-07-fix-auth-state-persistence-across-cold-start/`). Acceptable brief-stale window. |
| `DeviceAuthManagerRoleTest` fails because the new key changes the SharedPreferences state observed by reflection-based assertions | Low | The test asserts via the public API (`getRole()`, `getAccessToken()`, `getSyntheticAccessToken()`); `getParentId()` is unused. RED integration test is the only place that reads it. |
| CHILD-path is intentionally NOT writing `parent_id` — a future child-facing feature that needs the parent identity will hit the same gap | Low | Per Q2=(n) the proposal is locked; a future child-path symptom would warrant a separate proposal. YAGNI today (no child reader of `parent_id` exists). |
| Existing `BehavioralEventsRepositoryTest.kt` breaks because of the new `MockSupabaseEngine` import | Low | That file does not import `MockSupabaseEngine`; it uses a hand-rolled `MockEngine`. The new const is not consumed by it. |

## Rollback

Single PR. `git revert` of the 2-line change in `DeviceAuthManager.kt` and the 1-line const in `MockSupabaseEngine.kt` restores prior behavior (parentId stays empty; UI shows "Sin eventos" again). No schema migration, no feature flag, no data loss. The existing GREEN tests stay GREEN either way.

## Out of scope

- Defensive fallback `parentId.isBlank() → skip filter` in `BehavioralEventsRepository.refresh()` — explicitly excluded per Q3=(r). Defensive layers mask bugs; the empty-state UI is a clean failure mode.
- CHILD-path symmetry — explicitly excluded per Q2=(n). CHILD doesn't read `parent_id` today.
- Hardening the `authenticateOrCreate(role)` overload into an enumerated-key checklist (analogous to `savePairedSession`) — separate refactor, out of scope.
- `BehaviorLogViewModel` exposing the `parentId` constructor surface — unchanged.
- Supabase RLS / DB schema / edge functions — `parent_id` already exists as a column and as an RLS subject; no migration needed.
- Spec delta for `device-auth-session/spec.md` or equivalent — see open question #1.

## Success criteria

- [ ] **RED baseline on master**: `BehavioralEventsRepositoryIntegrationTest.fixture_events_surface_in_viewmodel_after_synthetic_parent_auth` fails at `assertEquals(5, events.size)` because the DAO filter `WHERE parent_id = ''` matches 0 rows while the mock wrote 5 rows with `parent_id = "parent-demo"`.
- [ ] **GREEN after fix**: same case passes after the 1-line `putString("parent_id", MOCK_PARENT_ID)` addition to `DeviceAuthManager.authenticateOrCreate(role: Role)`.
- [ ] **No regressions in `BehavioralEventsRepositoryTest`** (4 cases) — unchanged GREEN.
- [ ] **No regressions in `DeviceAuthManagerRoleTest`** (6 cases) — unchanged GREEN.
- [ ] **No regressions in `DeviceAuthManagerColdStartTest`** (4 cases) — unchanged GREEN; `clearSession()` continues to wipe the new `parent_id` key.
- [ ] **Live verification**: after the fix, opening `BehaviorLogScreen` on a debug build with the mock engine active shows the 5 fixture events instead of "Sin eventos".
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on the 2 touched files.
- [ ] Spec delta deferred (default) unless open question #1 resolves to "formalize now".

## Open questions

1. **Spec delta — yes or defer?** The integration gap ("synthetic parent auth path must write `parent_id` alongside `role` + `synthetic_access_token`") is not documented in any spec. The RED test pins the behavior. Recommend: defer the spec delta unless the user wants it formalized (same precedent as `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` and `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md:80`).
2. **Migration for already-signed-in parents** — production parents who completed `authenticateOrCreate(Role.PARENT)` before this fix will have no `parent_id` in `device_auth_prefs` until they re-auth. Acceptable brief-stale window? (Default: yes; no proactive migration. The next `clearSession()` + `authenticateOrCreate(Role.PARENT)` will heal them. Confirm at apply time.)
3. **Naming — `MOCK_PARENT_ID` vs `DEMO_PARENT_ID`** — locked at Q1=(m), but at apply time confirm the constant's `KDoc` clearly says "matches the fixture's `parent_id` value; only used by the synthetic auth path until `parent-auth-flow` lands".

## References

- **Diagnosis**: engram **#316** `sdd/fix-behavioral-event-log-fixture-loading/explore` (auth-layer `parent_id` write gap, VM `.orEmpty()` coercion, DAO-fixture asymmetry — 3 independent lines of evidence).
- **Decisions**: engram **#317** `sdd/fix-behavioral-event-log-fixture-loading/decisions` — Q1=(m) `MOCK_PARENT_ID` in `MockSupabaseEngine`, Q2=(n) no CHILD-path symmetry, Q3=(r) root-cause-only, no defensive fallback in repo.
- **RED test (acceptance contract)**: `app/src/test/java/com/tudominio/parentalcontrol/data/repository/BehavioralEventsRepositoryIntegrationTest.kt:118-159` (`fixture_events_surface_in_viewmodel_after_synthetic_parent_auth` — asserts `events.size == 5` after `authManager.authenticateOrCreate(Role.PARENT)` + `vm.events.first()`).
- **Bug surface**: `DeviceAuthManager.kt:195-216` (`authenticateOrCreate(role: Role)` — writes `role` + `synthetic_access_token` at `:207-208` but NOT `parent_id`); `DeviceAuthManager.kt:409-411` (`getParentId()` reads the never-written key); `DeviceAuthManager.kt:381` (`savePairedSession` — the canonical path that DOES write `parent_id`).
- **Consumer surface**: `BehaviorLogViewModel.kt:50` (`parentId = authManager.getParentId().orEmpty()`); `BehavioralEventDao.kt:55` (`WHERE parent_id = :parentId`); `BehavioralEventsRepository.kt:65-66` (URL builder appends `parent_id=eq.<id>`).
- **Fixture (already correct)**: `app/src/main/assets/mock-supabase/behavioral_events.json:11,22,33,44,55` — all 5 events carry `parent_id = "parent-demo"`.
- **Sister test files (must stay GREEN)**: `BehavioralEventsRepositoryTest.kt:139,154,170,192` (4 GREEN cases — inject `parentId` directly into `repository.refresh(...)`, do not depend on `getParentId()`).
- **Prior change context**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/` (sister PR — cold-start hydration of `pendingRequestsFlow`; both target the parent's "log de eventos" surface).
- **Format precedent**: `archive/2026-07-06-fix-parent-log-events-cleared-on-reopen/proposal.md` (same data-layer shape, same single-PR pattern, same RED-test-as-acceptance-contract).
- **Related archived work**: `archive/2026-07-07-feat-parent-behavioral-event-log/` (PR A + PR B cycle — the integration gap was introduced when PR B's Compose tests seeded the DAO directly, bypassing the auth seam).
