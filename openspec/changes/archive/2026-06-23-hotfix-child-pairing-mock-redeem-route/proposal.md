# Proposal: Hotfix child-side mock pairing route in MockSupabaseEngine

## Why

After the 2026-06-22 hotfix (obs #82) restored the parent's `POST /functions/v1/create-pairing-code` route, the child-side counterpart `POST /functions/v1/pairing` is still unwired in `MockSupabaseEngine`. The engine's `when` block (`MockSupabaseEngine.kt:68-80`) dispatches only the 4 already-fixed routes; any other path falls into the `else` branch and returns `404 {"error":"unknown route …"}`. In debug builds (`USE_MOCK_SUPABASE=true`) the child's "Emparejar" call always fails — `PairingManager.pairWithCode` (`pairing/PairingManager.kt:85-93`) hits the 404, `parsePairingResponse` maps it to `INVALID_CODE` (line 231-237), and `PairingViewModel.handlePairingResult` (line 128-174) renders "Error de emparejamiento / Error de conexión". This is the same omission pattern that broke the parent flow two days ago, and it blocks end-to-end manual QA of the child pairing path on debug builds.

## What changes

- **New fixture** `app/src/main/assets/mock-supabase/pairing.json` — 200-shape success body with `device_id` and `parent_id` (the fields `PairingManager.extractDeviceId` / `extractParentId` consume at lines 269-289). Single success case only.
- **New `when` branch** in `MockSupabaseEngine.kt:68-80` for `path.endsWith("/functions/v1/pairing")` reading the new fixture, inserted alphabetically under `create-pairing-code` per the convention from obs #82's regroup.
- **New RED test** in `MockSupabaseEngineTest.kt` (`pairing POST returns device_id and parent_id`) that posts a minimal `{ "code": "ABCDEFGH" }` body via the real Ktor `httpClient`, asserts `200`, and pins the response shape. Sits next to the existing `create-pairing-code POST` test (line 148-187).

## Impact

| Area | Impact | Description |
|---|---|---|
| `MockSupabaseEngine.kt:68-80` | Modified | 1 new `when` branch (~2 lines) |
| `app/src/main/assets/mock-supabase/pairing.json` | New | 200-shape fixture (~4 lines) |
| `MockSupabaseEngineTest.kt` | Modified | 1 new roundtrip test (~25 lines) |
| `pairing-flow` spec | Unchanged | Existing "Child completes pairing via code or QR scan" requirement already mandates `POST /functions/v1/pairing`; no delta spec required |

Debug-only behavior (`USE_MOCK_SUPABASE=true`); no production HTTP path touched.

## Out of scope

- Multi-case fixture (404/409/410 error shapes) — `parsePairingResponse` already has unit coverage; only the happy path needs wiring at the mock layer to unblock manual QA.
- New `PairingManager.parsePairingResponse` tests — covered by prior work; not in scope per orchestrator.
- Instrumented / Compose UI tests — dev box has no `adb` / emulator; engine test is the unit-level guard.
- Real Supabase endpoint behavior — production code path unchanged.
- Parent-side FCM registration — pre-existing TODO, separate follow-up.

## Risks

- **Same omission pattern recurs**: any future endpoint added without a matching `MockSupabaseEngine` branch + fixture will silently 404 in debug. The next `MockSupabaseEngineTest` roundtrip test for any new endpoint will fail loudly until wired. Follow-up candidate: a `routesKnownByMockEngine` guard test asserting every production `httpClient` POST path has a fixture.
- **Fixture drifts from real edge function shape**: a real-schema change in `supabase/functions/pairing/index.ts` will first break the new test (good) but won't auto-update the fixture.
- **Risk level: low.** Contained to mock engine, an asset, and a test. No production HTTP path, no schema, no migration.

## Rollback

Revert the single PR. Reverts the fixture, the `when` branch, and the test atomically; no other code depends on the new route.

## Success criteria

- [ ] Child `POST /functions/v1/pairing` against the mock returns 200 with non-empty `device_id` and `parent_id`.
- [ ] New `MockSupabaseEngineTest` test passes; existing 5 tests still pass.
- [ ] `MockSupabaseEngine` has 5 wired routes; `else` branch still reachable for genuinely unknown paths.
- [ ] `pairing-flow` spec unchanged; no delta spec produced.
