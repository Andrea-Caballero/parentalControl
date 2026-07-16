---
delta:
  spec: pairing-flow
  type: ADDED
  ref: openspec/specs/pairing-flow/spec.md
---

# Delta for pairing-flow

Lifts the "Out of scope" deferral at `pairing-flow/spec.md:50` ("Sending FCM to the parent on successful pairing"). After this change, a successful pairing SHALL notify the parent device via FCM data-only message within 5 seconds. The deferral item at line 50 is removed (see `## REMOVED Requirements` below).

## ADDED Requirements

### Requirement: Pairing success delivers an FCM notification to the parent

After a child successfully pairs via the `pairing` edge function, the parent's device SHALL receive a data-only FCM message of type `child.paired` within 5 seconds. The notification MUST contain `device_id` and `child_first_name` in its `data` payload. The notification MUST NOT be sent if the parent has no registered FCM tokens.

#### Scenario: Successful pairing triggers parent FCM within 5 seconds

- **GIVEN** the parent has at least one row in `device_push_tokens` (or `parent_push_tokens` per Clarification §5),
- **WHEN** the `pairing` edge function inserts a `devices` row with `parent_id = auth.uid()` (see `pairing/index.ts:288-298`),
- **THEN** `pairing` SHALL call `fcm-send` with payload `{ type: "child.paired", device_id, child_first_name }`,
- **AND** the parent device receives the FCM within 5 seconds,
- **AND** `FcmPushService.onMessageReceived` (per `firebase-messaging-service-receiver` spec) parses the `data` payload and surfaces a local notification.

#### Scenario: Missing parent push tokens logs a skip, no silent failure

- **GIVEN** the parent has zero rows in `device_push_tokens`,
- **WHEN** a successful pairing is committed,
- **THEN** `pairing` SHALL log `info("parent has no push tokens; skipping child.paired notification")`,
- **AND** SHALL NOT throw,
- **AND** SHALL still return a 200 response to the child (pairing itself succeeds; notification is best-effort).

#### Scenario: Parent notification is data-only (not notification payload)

- **WHEN** `fcm-send` is invoked from `pairing`,
- **THEN** the FCM v1 message body SHALL use the `data` field (not `notification`),
- **AND** the Firebase emulator (which supports data-only messages per `fcm-send/index.ts:97-119` blocker) SHALL deliver it end-to-end.

## REMOVED Requirements

### Requirement: Sending FCM to the parent on successful pairing

(Reason: deferral lifted by this delta. The `pairing` edge function gains the `child.paired` notification as a first-class behavior.)
(Migration: see `## ADDED Requirements` above. The TODO at `pairing/index.ts:237` is removed; the existing `Out of scope` bullet at `pairing-flow/spec.md:50` is deleted by archive.)