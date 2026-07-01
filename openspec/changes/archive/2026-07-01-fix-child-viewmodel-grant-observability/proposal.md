# Proposal: fix-child-viewmodel-grant-observability

## Intent

The parent-approve → child-unlock data path is now fully wired (5 commits landed: reconciliation, pull, sync trigger, mock approve/deny, OutboxManager wire format). The pull creates a `GrantEntity` in Room and `TimeExtraViewModel` is now reactive to that table. **But the home screen's big "Xh · minutos restantes" number still comes from `ChildViewModel.remainingMinutes`, which is a stub that does not observe `grantDao`.** So the +15 min grant lives in Room and the reactive ViewModel sees it, but the home screen still shows "2h" because the legacy child VM is the one painting the number.

The previous commit (`05bd671`) explicitly deferred this to keep its scope small and called out the gap in its body. This change closes that gap.

After this change, a parent approving a time request updates the child's home screen minutes-restantes number within the next sync cycle (no app restart), and the bloqueo / desbloqueo cycle is observable end-to-end on the device.

## Scope

### In Scope

- Make `ChildViewModel.remainingMinutes` reactive to `grantDao` (so the big number reflects approved time).
- Reconcile `ChildViewModel._activeGrants` against the actual Room grants (the same `GrantEntity` rows that `TimeExtraViewModel` already observes). The legacy field was initialized to `emptyList()` and never written — that is the root cause of the "0 granted minutes" symptom.
- Keep the existing `ChildRepository.checkBlocked` / `dailyLimit` / `dailyUsage` paths untouched (they are intentionally stubs per the previous investigation's recommendation).
- Add a unit test in `app/src/test/.../ChildViewModelTest.kt` (or whichever path exists) that exercises the reactive path: insert a `GrantEntity` in a fake repo and assert `remainingMinutes` goes up.

### Out of Scope

- The legacy `ChildRepository` stub itself — the investigation flagged it as "do not touch"; this change adds observation without rewriting it.
- `IsAppBlockedUseCase` (currently empty) — separate change, and it depends on a real `appPolicy` flow that is intentionally not implemented yet.
- Any new edge function or DB schema. The grant table is already there and `grantDao` already exposes `getGrantsForDeviceFlow(deviceId)`.
- A full periodic-sync wiring. The `pullApprovedRequests` change (`5d6400b`) is what surfaces new grants to the child, and this change makes the UI react to those grants.

## Capabilities

### Modified Capabilities

- `time-request-approval` (existing, last touched in `05bd671`): the parent's approval now also moves the child's home-screen minutes-restantes counter up. The data path is `parent approve → server stores APPROVED → post-boot pull → processApproval → grantDao insert → ChildViewModel observes grantDao → home screen re-renders`.

## Approach

1. **Inject `GrantDao` (or a higher-level reactive read) into `ChildViewModel`.** The previous investigation's recommendation was to leave `ChildViewModel` mostly alone; this change is a narrow exception, scoped to the single field `remainingMinutes`. Concretely:
   - Replace the `_activeGrants` `MutableStateFlow(emptyList())` with one seeded from `grantDao.getGrantsForDeviceFlow(deviceId).map { it.filter { grant -> grant.expires_at > nowIso } }`.
   - In `updateRemainingTime`, replace the stub read of `_activeGrants` with the new reactive read (the value is already up to date because Room pushes it).
2. **Device id source for the Flow.** `ChildViewModel` does not have a device id today. Use `authManager.deviceId.value` (the same source the pull uses) — inject `DeviceAuthManager` or read it once on init.
3. **Handle the "no device id yet" case** (cold boot before pairing). Fall back to empty list / zero contribution; the previous behavior.
4. **Test** in `ChildViewModelTest` (if the test path exists) using a fake `ChildRepository` that emits `grantDao` updates. If there is no test scaffolding yet, just add a single Robolectric or unit test that constructs a ViewModel and asserts the Flow contribution.

## Risks

- `ChildViewModel` has no test scaffolding in the repo. The fix can land without a new test, but a regression there is silent. If adding the test is a multi-file scaffolding effort, defer the test to a follow-up and just add a `// TODO` pointer in the test path.
- The `ChildRepository` stub has hard-coded `dailyLimit = 120`. After this change, `remainingMinutes` may correctly exceed 120 (120 + 15 = 135). The home screen renders the number as "Xh" via `formatHoursMinutes`; this needs to handle values > 120 correctly. The previous investigation showed the formatter is `formatHoursMinutes(value: Int): String` — verify it handles 135 without truncation. If it does not, fix the formatter in the same commit (single file).
