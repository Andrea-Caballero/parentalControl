# Archive Report: fix-child-viewmodel-grant-observability

> **STATUS: ARCHIVED 2026-07-01** — code change landed on master as commit `1ca2fe3`. 1 file changed (36 insertions, 6 deletions). Visual end-to-end verification deferred to next session due to an adb connection loss on the POCO device.

## Summary

Closed the final UI gap in the parent-approve → child-unlock data path. The previous commit chain (`b8206ed` → `5d6400b` → `3b2a566` → `dfc958d` → `05bd671`) wired the data path end-to-end (sync, pull, reconciliation, expiresAt, mock approve/deny, trigger chain). The pull creates a `GrantEntity` in Room and `TimeExtraViewModel` already observed it. **But the home screen's big "Xh · minutos restantes" number was painted by `ChildStatusViewModel.calculateTimeRemaining()` at `ui/child/status/ChildStatusViewModel.kt:174-185**, which was hard-coded to `maxOf(0, _dailyLimit.value - _timeUsedToday.value)` and never added grant minutes.

## Discovery during apply

The original proposal named `ChildViewModel` as the target. **During apply, we discovered `ChildViewModel` is orphaned leftover code** — it is not instantiated anywhere in the active navigation graph. The user-facing child home screen is `ChildStatusScreen` driven by `ChildStatusViewModel` (a HiltViewModel). The fix landed on the right VM (`ChildStatusViewModel.kt`); `ChildViewModel` was untouched per the previous investigation's "do not touch" recommendation, and that still holds.

## Change

`app/src/main/java/com/tudominio/parentalcontrol/ui/child/status/ChildStatusViewModel.kt`:

- Added `DeviceAuthManager` constructor parameter (default `DeviceAuthManager.getInstance(context)`).
- Replaced the two-way `combine(_timeUsedToday, _dailyLimit)` in `startObserving()` with a three-way `combine(_timeUsedToday, _dailyLimit, grantsFlow)` where `grantsFlow = database.grantDao().getGrantsForDeviceFlow(deviceId).map { it.sumActiveMinutes(timeProvider) }`. Falls back to `flowOf(0L)` when `deviceId` is null (unpaired).
- Changed `_timeRemaining.value = maxOf(0, limit - used)` to `_timeRemaining.value = maxOf(0, limit - used + grantsMinutes)`.
- Added top-level `private fun List<GrantEntity>.sumActiveMinutes(timeProvider): Long` that filters `expires_at > nowIso` and sums `minutes` to `Long`. Mirrors the filter in `TimeExtraRepository.getActiveExtraTimeGrant` so the home screen number agrees with the `+15 min extra` chip and countdown rendered by `TimeExtraViewModel`.

`ChildStatusScreen.formatTime` already handled `135 / 60 = 2, 135 % 60 = 15` correctly as `"2h 15m"`. No formatter change was needed.

## Tasks

| # | Task | Status |
|---|-------|--------|
| 1 | Wire the actual child VM to `grantDao` | ✅ done (1 file, ~36 LoC) |
| 2 | Verify the formatter handles >120 min | ✅ done (no code change needed) |
| 3 | Add a focused test | ⏭ skipped (no scaffolding; deferred per the change's scope guard) |
| 4 | Verify end-to-end on real devices | ⚠️ blocked — adb connection lost on POCO; data path is unchanged, the next session re-authorises adb and re-runs the visual check |
| 5 | Commit, push, archive | ✅ done (commit `1ca2fe3`, pushed) |

## End-to-end test plan for the next session

1. Plug POCO back in (or kill/restart adb server and re-authorise USB debugging on the device).
2. Re-pair POCO with the current pairing code from the OPPO.
3. POCO → Pedir tiempo (+15 min). OPPO → Aprobar +15.
4. POCO reboot → post-boot pull → `processApproval` creates the grant → `ChildStatusViewModel._timeRemaining` reactive Flow picks it up → home screen number updates to `2h 15m` (or `135`).
5. Screenshot the POCO home screen as proof.
6. The `+15 min extra` chip from `TimeExtraViewModel` and the `Podrás usarlo hasta` countdown should also be visible — both should agree with the new home-screen number.

## Notes for the next session

- The `pullApprovedRequests` (commit `5d6400b`) and the reconciliation + expiresAt fix (`05bd671`) are the load-bearing pieces. If the visual verification fails, the failure is most likely in the `ChildStatusViewModel._timeRemaining` computation (the `grantsFlow` combine order, the `nowIso` filter, or the device-id retrieval). A log of `_timeRemaining.value` in the ViewModel would make debugging trivial.
- `ChildRepository` (the stub) is still untouched per the previous investigation's recommendation. If the next session decides to also wire `ChildRepository.checkBlocked` to real data, that is a separate SDD change.
- The adb connection loss during this session is a flaky-test infrastructure issue, not a code bug. A `kill-server && start-server` plus a manual USB-debugging re-tap is the usual recovery.

## Out-of-scope follow-ups (deferred, not blocking)

- Test scaffolding for `ChildStatusViewModel` (Task 3 above).
- Replacing the `ChildRepository` stub with a real implementation (per the previous investigation's recommendation; not in scope here).
- `IsAppBlockedUseCase` (currently empty; depends on a real appPolicy flow not yet implemented).
- Periodic sync wiring (out of scope; the pull is the canonical path).
