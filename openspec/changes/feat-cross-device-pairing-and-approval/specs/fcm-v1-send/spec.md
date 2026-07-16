# Spec: fcm-v1-send

## Purpose

Covers the OAuth Bearer-token derivation + v1 endpoint contract for Supabase edge functions that push via Firebase Cloud Messaging. The legacy `Authorization: key=<FCM_SERVER_KEY>` path is fully removed; only the v1 API is supported. Used by `fcm-send`, `approve-request`, and `pairing` (the latter for the parent `child.paired` notification added by the pairing-flow delta).

## Requirements

### Requirement: OAuth Bearer token derived from service-account JSON

The function SHALL derive a short-lived OAuth 2.0 access token from the service-account JSON stored in `supabase secrets FCM_SERVICE_ACCOUNT_KEY`. The token SHALL be signed with the service account's private RSA key using the JWT bearer flow (`https://oauth2.googleapis.com/token` `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`). The signed JWT SHALL include `iss`, `scope = "https://www.googleapis.com/auth/firebase.messaging"`, `aud`, `iat`, and `exp` (max 1 hour).

#### Scenario: Token derivation succeeds with a valid service-account JSON

- **GIVEN** `FCM_SERVICE_ACCOUNT_KEY` is a valid Google service-account JSON with `client_email`, `private_key`, and `project_id`,
- **WHEN** `getFcmAccessToken()` is called,
- **THEN** a Bearer token string is returned, valid for ≤ 1 hour,
- **AND** the token is cached in-process until within 60 seconds of expiry.

#### Scenario: Token derivation fails loudly when secret is missing

- **GIVEN** `FCM_SERVICE_ACCOUNT_KEY` is not set in `supabase secrets`,
- **WHEN** `getFcmAccessToken()` is called,
- **THEN** it SHALL throw `FcmConfigError("fcm_service_account_not_configured")`,
- **AND** the calling function (`fcm-send`, `approve-request`, `pairing`) SHALL translate this to a 500 response with the matching error code.

### Requirement: v1 endpoint call shape + auth header

The function SHALL POST to `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` with `Authorization: Bearer <oauth-token>`. The body SHALL conform to the FCM v1 `Message` resource shape. The `notification` field SHALL NOT be set (data-only messages; Firebase emulator support).

#### Scenario: Body matches FCM v1 Message resource

- **WHEN** `fcm-send` is invoked with payload `{ type: "grant.approved", request_id, device_token }`,
- **THEN** the POST body SHALL be `{ message: { token: device_token, data: { type: "grant.approved", request_id }, android: { priority: "high" } } }`,
- **AND** `project_id` SHALL be read from the service-account JSON (not hardcoded).

#### Scenario: Authorization header uses Bearer token

- **WHEN** the v1 POST is issued,
- **THEN** the request SHALL include `Authorization: Bearer <oauth-token>`,
- **AND** SHALL NOT include any `key=...` legacy header or query parameter.

#### Scenario: Token refresh triggers on 401

- **WHEN** the FCM endpoint returns 401 (`UNAUTHENTICATED`),
- **THEN** the function SHALL clear the cached access token,
- **AND** SHALL retry the request ONCE with a freshly-derived token,
- **AND** SHALL surface the second 401 (if it persists) to the caller as an error.

### Requirement: Idempotency on retry

The function SHALL be safe to retry on transient network failures. Idempotency SHALL be enforced at the FCM message level via a deterministic `message_id` field derived from the immutable delivery envelope `{ type, request_id, device_token }` — the smallest set of fields that uniquely identifies a single FCM push to a single device. Optional payload fields that may vary across retry attempts (timestamps, UI state, debug counters) MUST NOT participate in the hash. The hash function SHALL be SHA-256, encoded as lowercase hex. Duplicate deliveries SHALL be deduplicated by FCM within a 5-minute window.

> **Audit-driven clarification** (Engram obs #374): the original wording offered `(e.g., hash(type, request_id))` as a loose example while the scenario example used `{ type, request_id, device_token }`. This delta clarifies that `device_token` is part of the stable envelope so duplicate retries from different devices do not collide, and excludes optional payload fields that may legitimately vary per attempt.

#### Scenario: Same payload retry produces a deterministic message_id

- **GIVEN** two `fcm-send` invocations with the same `{ type, request_id, device_token }`,
- **WHEN** both calls reach the v1 endpoint,
- **THEN** both POST bodies SHALL contain the same `message.message_id`,
- **AND** FCM SHALL deduplicate the second delivery within the FCM-side retry window.

#### Scenario: Different device_token yields a different message_id

- **GIVEN** two `fcm-send` invocations with the same `{ type, request_id }` but different `device_token`,
- **WHEN** both calls reach the v1 endpoint,
- **THEN** the POST bodies SHALL contain different `message.message_id` values,
- **AND** neither delivery is deduplicated by FCM.

#### Scenario: Optional payload variance MUST NOT change message_id

- **GIVEN** two `fcm-send` invocations with the same `{ type, request_id, device_token }` but differing optional payload fields (e.g., `sent_at`),
- **WHEN** both calls reach the v1 endpoint,
- **THEN** both POST bodies SHALL contain the same `message.message_id`,
- **AND** FCM SHALL deduplicate the second delivery within the FCM-side retry window.