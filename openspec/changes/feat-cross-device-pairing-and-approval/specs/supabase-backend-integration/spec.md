---
delta:
  spec: supabase-backend-integration
  type: ADDED
  ref: openspec/specs/supabase-backend-integration/spec.md
---

# Delta for supabase-backend-integration

Adds the cross-device contract for the custom-access-token hook and the `fcm-send` v1 contract. These bridge requirements live in this tech-agnostic spec because every parent-scoped RLS read depends on them. The new `behavioral_events_parent_select` policy is covered by the migration `007_behavioral_events_parent_select.sql` referenced in the proposal (out of scope here — migration is a deploy artifact, not a behavioral requirement).

## ADDED Requirements

### Requirement: custom-access-token-hook injects parent_id as a first-level JWT claim

The `custom-access-token-hook` edge function SHALL read `app_metadata.parent_id` from the auth user event and SHALL inject it as a first-level JWT claim named `parent_id`. The hook MUST NOT mutate `app_metadata`; it MUST only augment the returned JWT payload.

#### Scenario: Parent JWT carries parent_id equal to app_metadata.parent_id

- **GIVEN** an `auth.users` row with `app_metadata = { parent_id: "uuid-p" }`,
- **WHEN** the hook fires on token issuance,
- **THEN** the returned JWT payload SHALL contain `"parent_id": "uuid-p"` at the top level,
- **AND** every RLS policy keyed on `parent_id = auth.uid()` SHALL match this JWT against the `uuid-p` row owner.

#### Scenario: Child JWT has no parent_id claim

- **GIVEN** an `auth.users` row with `app_metadata.parent_id` absent or null (e.g., a child anonymous account),
- **WHEN** the hook fires,
- **THEN** the returned JWT SHALL NOT contain a `parent_id` claim,
- **AND** parent-scoped RLS reads SHALL deny by default (no `parent_id = auth.uid()` match).

#### Scenario: Missing app_metadata.parent_id does not crash the hook

- **GIVEN** an `auth.users` row with `app_metadata = {}` (empty object),
- **WHEN** the hook fires,
- **THEN** the hook SHALL return the JWT without a `parent_id` claim (not throw),
- **AND** the auth response SHALL still be 200 OK.

### Requirement: fcm-send calls the FCM v1 API with OAuth Bearer token

The `fcm-send` edge function SHALL POST to `https://fcm.googleapis.com/v1/projects/{project_id}/messages:send` with an `Authorization: Bearer <oauth-token>` header derived from the service-account JSON in `supabase secrets`. The legacy `Authorization: key=<FCM_SERVER_KEY>` form is NOT set. The full OAuth derivation contract lives in the `fcm-v1-send` spec; this requirement pins the wire contract at the integration boundary.

#### Scenario: fcm-send request body matches FCM v1 shape

- **WHEN** `fcm-send` is invoked with `{ type: "grant.approved", request_id }`,
- **THEN** the POST body SHALL match `{ message: { token, data: { type, request_id }, android: { priority: "high" } } }`,
- **AND** SHALL be URL-encoded as JSON.

#### Scenario: Authorization header is Bearer, not key

- **WHEN** `fcm-send` issues the v1 POST,
- **THEN** the request SHALL include `Authorization: Bearer <oauth-token>`,
- **AND** SHALL NOT include any `Authorization: key=...` legacy header (the legacy path at `fcm-send/index.ts:97-119` is removed).

#### Scenario: Missing service-account secret returns 500 with fcm_service_account_not_configured

- **GIVEN** `supabase secrets` does not include `FCM_SERVICE_ACCOUNT_KEY`,
- **WHEN** `fcm-send` is invoked,
- **THEN** the function SHALL return 500 with body `{ "error": "fcm_service_account_not_configured" }`,
- **AND** SHALL NOT attempt to call the FCM endpoint.