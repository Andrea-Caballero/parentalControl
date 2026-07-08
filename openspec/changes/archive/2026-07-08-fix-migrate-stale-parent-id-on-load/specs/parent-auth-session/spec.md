# parent-auth-session (delta)

## ADDED Requirements

### Requirement: Stale-auth-state migration on cold start

The auth state MUST be migrated on cold start when `role = PARENT` and `parent_id` is null or empty: `parent_id` is written to the demo default `MOCK_PARENT_ID` so the BehavioralEventLog can surface the fixture's events. The migration MUST be role-gated (PARENT only) and idempotent.

#### Scenario: parent with pre-existing auth state, no parent_id

- WHEN the app cold-starts with SharedPreferences containing `role=PARENT` and `synthetic_access_token` but no `parent_id`
- THEN the migration writes `parent_id = MOCK_PARENT_ID` and emits a WARN log
- AND the BehavioralEventLog screen surfaces the fixture's events on next navigation

#### Scenario: child role, no parent_id

- WHEN the app cold-starts with `role=CHILD` and no `parent_id`
- THEN the migration is a no-op (no `parent_id` written)