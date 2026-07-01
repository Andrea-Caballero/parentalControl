# Design: fix-child-viewmodel-grant-observability

> Minimal design. The change is a one-file reactive read plus a one-line formatter check. Anything more elaborate would expand the change beyond its scope (a follow-up for a separate SDD).

## Why this change exists

The previous commit chain (5 commits: `b8206ed` → `5d6400b` → `3b2a566` → `dfc958d` → `05bd671`) wired the data path **end-to-end**:

```
parent approve → server stores APPROVED → boot-sync pulls approved →
OutboxDrainer reconciler matched local id to server id →
TimeExtraRepository.processApproval created the grant →
TimeExtraViewModel.observeExtraTimeGrants() reactive Flow refreshed the
"+15 min extra" chip and "Podrás usarlo hasta" countdown
```

But the home screen's big "Xh · minutos restantes" number comes from `ChildViewModel.remainingMinutes`, which has its own `_activeGrants: MutableStateFlow<List<ActiveGrant>>(emptyList())` field that is **never written to**. So the data path is correct, but the legacy VM that paints the large quota number never reads it.

The previous commit's `05bd671` explicitly noted this as a follow-up: "the home screen's `ChildViewModel.remainingMinutes` still uses a stub that doesn't observe the grant table, so the big '2h · minutos restantes' number does not yet reflect the +15 min grant."

## Concrete change

`ChildViewModel` reads two things today:

1. `dailyLimit` and `dailyUsage` from `ChildRepository` (stubs, hard-coded 120 min). **Untouched.**
2. `_activeGrants: MutableStateFlow<List<ActiveGrant>>(emptyList())` — initialised to empty, never written. **This is the gap.**

The fix replaces (2) with a Room Flow observation:

```kotlin
// Before
private val _activeGrants = MutableStateFlow<List<ActiveGrant>>(emptyList()) {
    val active = grants
        .filter { it.expires_at > timeProvider.wallInstant().toString() }
        .map { ActiveGrant(minutes = it.minutes, minutesRemaining = it.minutes, expiresAt = it.expires_at) }
    _activeGrants.value = active
    updateRemainingTime()
}

// After
init {
    val deviceId = authManager.deviceId.value
    if (deviceId != null) {
        viewModelScope.launch {
            grantDao.getGrantsForDeviceFlow(deviceId).collect { grants ->
                val now = timeProvider.wallInstant().toString()
                val active = grants
                    .filter { it.expires_at > now }
                    .map { ActiveGrant(minutes = it.minutes, minutesRemaining = it.minutes, expiresAt = it.expires_at) }
                _activeGrants.value = active
                updateRemainingTime()
            }
        }
    } else {
        // Cold boot before pairing — fall back to stub behavior (no grants).
        // The pair flow will re-trigger the ViewModel via screen navigation
        // and the new flow will pick up the device id on next collect.
    }
    refreshAppsUsage()
}
```

### Why this is small and safe

- The previous investigation's recommendation to keep `ChildRepository` untouched remains. The fix does not edit that file.
- The `grantDao` already exposes `getGrantsForDeviceFlow(deviceId: String): Flow<List<GrantEntity>>` (used in the previous commit for `TimeExtraViewModel`). No new DAO method.
- The mapper `GrantEntity → ActiveGrant` is the same shape that was already used in `_activeGrants` — only the source changes.
- When the device is unpaired (cold boot), the Flow is never collected, so `_activeGrants` stays empty and the screen shows the previous behavior (no regression).

### Formatter check (Task 2)

`HomeScreen` likely formats `remainingMinutes` via a `formatHoursMinutes(value: Int): String` helper. The math `value / 60` for hours and `value % 60` for minutes is the standard pattern; it does not truncate at 120. The previous investigation saw `"2h · minutos restantes"` (a string concatenating formatted hours and minutes) which suggests the formatter already handles the >120 case correctly. **Action:** verify the formatter in code, no change unless truncation is found.

## Out of design scope (per the proposal)

- Anything in `ChildRepository.kt` — kept as a stub.
- `IsAppBlockedUseCase` — empty, separate change.
- Periodic sync wiring — out of scope; the `pullApprovedRequests` change already surfaces new grants on the next sync.

## Verification

End-to-end on real OPPO + POCO devices, same flow as the previous session:

1. `pm clear` POCO (fresh start) — actually no, this is a follow-up; keep the existing pairing.
2. POCO sends Pedir tiempo.
3. OPPO approves +15 min.
4. POCO reboots (the boot sync pulls the APPROVED row, the new Flow picks it up).
5. POCO home screen shows `2h 15m` (or `135`) instead of `2h` (`120`).
6. Screenshot as proof.

The fix can be unit-tested if `ChildViewModelTest` scaffolding exists; otherwise defer (per Task 3 in `tasks.md`).
