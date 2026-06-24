# Delta for outbox-drain

## ADDED Requirements

### Requirement: OutboxDrainer boot re-arm is gated on a restored session
The boot-time re-arm of the post-boot chain (which includes the `OutboxDrainer` work) MUST only occur when `DeviceAuthManager.restoreSession()` returns a non-null session at the time `BootReceiver` handles `BOOT_COMPLETED`. When the result is null, the boot re-arm MUST be skipped and any previously-persisted `OutboxDrainer.WORK_NAME` unique work originating from a prior boot MUST be cancelled.

#### Scenario: Session restored at boot re-arms the chain
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns a non-null session
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL be invoked
- AND the chain SHALL include `OutboxDrainer.WORK_NAME`.

#### Scenario: No session at boot skips the OutboxDrainer re-arm
- GIVEN the device has just emitted `BOOT_COMPLETED`
- AND `DeviceAuthManager.restoreSession()` returns `null`
- WHEN `BootReceiver.onReceive` runs
- THEN `scheduleSyncAfterBoot(context)` SHALL NOT be invoked from the boot path
- AND no `OutboxDrainer` work SHALL be enqueued from `BootReceiver`.

### Requirement: Persisted OutboxDrainer unique work is cancelled when no session exists
When `BootReceiver` runs without a restored session, it MUST cancel any previously-persisted `OutboxDrainer.WORK_NAME` unique work that originated from a prior boot, so the worker does not retry indefinitely while logged out.

#### Scenario: Cancellation at boot with no session
- GIVEN `OutboxDrainer.WORK_NAME` unique work persisted from a prior boot
- AND `DeviceAuthManager.restoreSession()` returns `null` at the current boot
- WHEN `BootReceiver.onReceive` runs
- THEN `WorkScheduler.cancelWork(context, OutboxDrainer.WORK_NAME)` SHALL be called
- AND the persisted `OutboxDrainer` unique work SHALL be removed from the WorkManager database.

#### Scenario: Periodic OutboxDrainer schedule is preserved across reboot
- GIVEN `OutboxDrainer` is scheduled as a periodic worker via `ExistingPeriodicWorkPolicy.KEEP`
- AND `DeviceAuthManager.restoreSession()` returns `null` at the current boot
- WHEN `BootReceiver.onReceive` runs
- THEN the periodic entry SHALL remain in the WorkManager database
- AND only the boot-specific unique work name (if previously enqueued from boot) SHALL be cancelled.