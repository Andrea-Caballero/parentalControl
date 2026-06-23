# Tasks: hotfix-child-pairing-mock-redeem-route

## Goal

`MockSupabaseEngine` (`app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt:68-80`) is missing the child-side `POST /functions/v1/pairing` route. In any debug build with `USE_MOCK_SUPABASE=true`, the call from `PairingManager.pairWithCode` (`pairing/PairingManager.kt:85-93`) hits the `else` branch, receives `404 {"error":"unknown route …"}`, and `parsePairingResponse` (line 231-237) maps it to `INVALID_CODE` → "Error de emparejamiento". This is the same omission pattern that broke the parent flow two days ago (obs #82). Success looks like: the new RED→GREEN roundtrip test passes, the full unit suite stays green, detekt + ktlintCheck + assembleDebug stay clean, and no other behavior changes.

## Phase 1: RED — failing roundtrip test

- [x] 1.1 Add a 5th test to `app/src/test/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngineTest.kt` named `` `pairing POST returns device_id and parent_id` ``, immediately after the existing `create-pairing-code POST returns pairing code response shape` test (line 148-187). Mirror that test's roundtrip pattern: `client.post("/functions/v1/pairing")` with `contentType(ContentType.Application.Json)` and a body `{"code":"ABCDEFGH"}`. Assert exactly three contracts: `response.status.value == 200`, body contains `"device_id"`, body contains `"parent_id"`. No regex extraction (presence-only, unlike the create-pairing-code test).
- [x] 1.2 Run `./gradlew testDebugUnitTest --tests "*MockSupabaseEngineTest.pairing POST returns device_id and parent_id"` and confirm it FAILS with a clear assertion — status `404` (current `else` branch returns `NotFound`) vs. expected `200`, or body not containing `device_id`. Capture the failure message.
- [x] 1.3 Commit: `test(p): add failing roundtrip for /functions/v1/pairing mock route`. Prerequisite: 1.2 RED confirmed on disk.

## Phase 2: GREEN — wire the missing route

- [x] 2.1 Create the fixture `app/src/main/assets/mock-supabase/pairing.json` with a single object `{"device_id":"device-child-emulator-001","parent_id":"parent-uuid-aaaa-bbbb-cccc"}`. Mirror the 5-line `create-pairing-code.json` style; reuse UUID-shaped stable values. The body starts with `{`, so `MockSupabaseEngine.kt:81-85` maps it to `HttpStatusCode.OK`. Prerequisite: 1.3.
- [x] 2.2 In `MockSupabaseEngine.kt:68-80`, insert one new `when` branch: `path.endsWith("/functions/v1/pairing") -> readAsset("mock-supabase/pairing.json")`. **CRITICAL POSITION**: between the existing `get-templates` / `/rest/v1/templates` branch (line 73-75) and the `/rest/v1/time_requests` branch (line 76). The final `/functions/v1/` super-grouping is `create-pairing-code` < `get-devices-for-parent` < `get-templates` < **`pairing`** < `/rest/v1/time_requests`. Re-verify the line numbers on disk before editing. Prerequisite: 2.1.
- [x] 2.3 Re-run the test from 1.1 and confirm GREEN (status 200, both fields present). Run the full unit suite `./gradlew testDebugUnitTest` and confirm all 5 `MockSupabaseEngineTest` tests plus every other suite pass (no regression on the 4 prior engine tests). Prerequisite: 2.2.
- [x] 2.4 Commit: `fix(mock): wire /functions/v1/pairing route in MockSupabaseEngine`. Prerequisite: 2.3 full-suite GREEN.

## Phase 3: Quality gates

- [x] 3.1 Run `./gradlew detekt`. Must pass — new code must not add to the existing baseline. Prerequisite: 2.4.
- [x] 3.2 Run `./gradlew ktlintCheck`. Must pass. If any new violations, run `./gradlew ktlintFormat` and re-run 3.2. Prerequisite: 3.1.
- [x] 3.3 Run `./gradlew assembleDebug`. Must succeed. Prerequisite: 3.2.

## Phase 4: Optional — PR / review

- [ ] 4.1 (Only if the user explicitly requests PR creation — out of scope per orchestrator unless asked.) Open a PR titled `fix(mock): wire /functions/v1/pairing route in MockSupabaseEngine` referencing `openspec/changes/hotfix-child-pairing-mock-redeem-route/proposal.md`.

## Review Workload Forecast

| Field | Value |
|-------|-------|
| Estimated changed lines | ~30-50 (1 new fixture JSON, 1 modified engine `when` branch, 1 modified test file) |
| 400-line budget risk | Low |
| Chained PRs recommended | No |
| Suggested split | Single PR |
| Delivery strategy | single-pr |
| Chain strategy | n/a |

Decision needed before apply: No
Chained PRs recommended: No
Chain strategy: n/a
400-line budget risk: Low

### Suggested Work Units

| Unit | Goal | Likely PR | Notes |
|------|------|-----------|-------|
| 1 | Single PR: RED test + fixture + `when` branch + GREEN + quality gates | PR 1 | 2 commits (Phase 1 RED then Phase 2 GREEN); base = `master`. Manual smoke in success criteria is post-merge. |

Files touched (3 total, exact paths):

- `app/src/main/assets/mock-supabase/pairing.json` — new, ~3 lines.
- `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` — modified, +2 lines in the `when` block.
- `app/src/test/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngineTest.kt` — modified, +~25 lines for the new test.

## Out of scope (explicit)

- Multi-case fixture (no 404 / 409 / 410 error shapes).
- `PairingManager` unit tests.
- `PairingViewModel` / `PairingScreen` tests.
- Instrumented tests (dev box has no `adb` / emulator per `openspec/config.yaml:57`).
- Refactor of `MockSupabaseEngine` into an exhaustive routing table.
- A `routesKnownByMockEngine` guard test (flagged in proposal Risks as a follow-up).
- No new dependencies, no new permissions, no Ktor config changes.

## Rollback

Revert the 2 commits (Phase 1 RED + Phase 2 GREEN). No data migration. No feature flag. No compatibility concern.

## Success criteria

- The new `pairing POST returns device_id and parent_id` test passes.
- Full unit suite is green: `./gradlew testDebugUnitTest`.
- `./gradlew detekt`, `./gradlew ktlintCheck`, `./gradlew assembleDebug` are green.
- Manual smoke (post-merge): in a debug build with `USE_MOCK_SUPABASE=true`, parent generates a code, child enters it manually, pairing completes and the child navigates to its home screen.
