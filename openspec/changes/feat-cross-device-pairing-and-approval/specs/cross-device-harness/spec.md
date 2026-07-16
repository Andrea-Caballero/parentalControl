# Spec: cross-device-harness

## Purpose

Covers the local validation stack and CI contract that prove real cross-device end-to-end behavior: parent sign-up → child pairing → FCM push to parent → verdict → child reflects. Built on `supabase start` + Firebase emulator + adb-reverse for two devices. CI deploys edge functions on merge.

## Requirements

### Requirement: tools/cross-device/start-stack.sh boots the local Supabase + Firebase emulator stack

The script SHALL boot `supabase start` and the Firebase Auth + Messaging emulators in parallel, wait for both to reach `ready`, and configure `adb-reverse` for two connected devices so they reach `localhost` services. Exit `0` only when both stacks report `ready` within a 5-minute timeout.

#### Scenario: Supabase + Firebase emulator reach ready state

- **WHEN** `tools/cross-device/start-stack.sh` is invoked on the dev machine,
- **THEN** `supabase start` SHALL reach `API URL` ready within 5 minutes,
- **AND** `firebase emulators:start --only auth,messaging` SHALL print `All emulators ready!` within 5 minutes,
- **AND** the script SHALL exit `0`.

#### Scenario: Timeout surfaces a non-zero exit code

- **WHEN** either `supabase start` or the Firebase emulator fails to reach `ready` within 5 minutes,
- **THEN** the script SHALL exit non-zero with a clear stderr message,
- **AND** SHALL NOT proceed to invoke `smoke-test.sh`.

#### Scenario: adb-reverse is configured for both devices

- **GIVEN** two devices (`$PARENT_DEVICE_ID` and `$CHILD_DEVICE_ID`) are connected via USB,
- **WHEN** the stack boots,
- **THEN** `adb -s $PARENT_DEVICE_ID reverse tcp:54321 tcp:54321` (Supabase) and the Firebase emulator ports SHALL be applied,
- **AND** the same SHALL be applied for `$CHILD_DEVICE_ID`.

### Requirement: smoke-test.sh runs end-to-end 2-device flow and exits 0

The script SHALL drive: parent signs up → child pairs via 8-char code → child requests time → FCM pushes to parent → parent approves → child reflects verdict. Exit `0` on full success, non-zero on any failure (structured stderr).

#### Scenario: Happy path exits 0

- **WHEN** `tools/cross-device/smoke-test.sh` is invoked against a ready stack + 2 adb-connected devices,
- **THEN** parent sign-up completes and `auth.users` row exists,
- **AND** child pairs via 8-char code,
- **AND** child submits a time_request,
- **AND** parent's device receives the FCM push within 5 seconds,
- **AND** parent's approve verdict propagates to the child,
- **AND** the script exits `0`.

#### Scenario: Failure at any stage exits non-zero

- **WHEN** any stage in the flow fails (sign-up, pairing, FCM, verdict),
- **THEN** the script SHALL exit non-zero with a stage-tagged stderr line,
- **AND** SHALL preserve any partial state for debugging (no cleanup of `devices` rows mid-flow).

### Requirement: Edge function CI deploy on merge to master

The workflow `.github/workflows/deploy-edge-functions.yml` SHALL run on push to `master`, SHALL deploy all 11 edge functions to the production Supabase project, and SHALL use `SUPABASE_ACCESS_TOKEN` + `FCM_SERVICE_ACCOUNT_KEY_B64` from GitHub secrets. The service-account JSON SHALL be base64-decoded at runtime and piped via stdin to `supabase secrets set --no-verify`. It SHALL NEVER be echoed to logs.

#### Scenario: Workflow triggers on push to master

- **GIVEN** a commit lands on `master`,
- **WHEN** GitHub Actions evaluates the workflow file,
- **THEN** the `push` trigger matches `branches: [master]`,
- **AND** the job runs `supabase functions deploy <name>` for all 11 edge functions.

#### Scenario: Secrets are sourced from GitHub Actions secrets, never echoed

- **WHEN** the workflow sets `FCM_SERVICE_ACCOUNT_KEY`,
- **THEN** it SHALL decode `$FCM_SERVICE_ACCOUNT_KEY_B64` (GitHub secret) and pipe it via stdin to `supabase secrets set --no-verify`,
- **AND** the decoded value SHALL NOT appear in any log line.

#### Scenario: Cross-device smoke CI runs against an ephemeral Supabase project

- **GIVEN** the workflow `.github/workflows/cross-device-smoke.yml` runs on PR open,
- **WHEN** the workflow provisions an ephemeral Supabase project (via `supabase projects create` + `supabase db reset`),
- **THEN** `smoke-test.sh` SHALL be invoked against that ephemeral project,
- **AND** the ephemeral project SHALL be torn down at workflow end (success or failure).