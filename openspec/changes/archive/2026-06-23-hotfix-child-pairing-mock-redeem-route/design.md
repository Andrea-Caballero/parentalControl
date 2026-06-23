# Design: Hotfix child-side mock pairing route in MockSupabaseEngine

## 1. Architecture overview

This 3-piece change mirrors the 2026-06-22 `create-pairing-code` hotfix (Engram obs #82) on the child side. After the 22/06 fix the parent route `POST /functions/v1/create-pairing-code` is wired in the mock engine, but the child counterpart `POST /functions/v1/pairing` — emitted by `PairingManager.pairWithCode()` at `pairing/PairingManager.kt:85-92` — is not. Any child device in a `USE_MOCK_SUPABASE=true` debug build hits the `else` branch of `MockSupabaseEngine.kt:68-80`, receives `404 {"error":"unknown route …"}`, and `parsePairingResponse` (line 231) maps it to `INVALID_CODE` → "Error de emparejamiento".

The change slots a 4th `/functions/v1/` route into the existing `when` block (currently 3 `/functions/v1/` routes + 1 `/rest/v1/` route), adds a matching fixture, and adds a RED roundtrip test pinning the success wire shape. No production HTTP path is touched; no `PairingManager` / `PairingViewModel` / `PairingScreen` code changes; no instrumented test needed (dev box has no `adb`/emulator per `openspec/config.yaml:57`).

## 2. Component design

### 2.1 Fixture — `app/src/main/assets/mock-supabase/pairing.json`

5-line JSON object mirroring the `create-pairing-code.json` style. Top-level fields: `device_id` and `parent_id` (both non-empty strings) — the two fields consumed by `PairingManager.extractDeviceId` (line 269-277) and `extractParentId` (line 282-289) via regex. Suggested stable values: `"device-child-emulator-001"` and `"parent-uuid-aaaa-bbbb-cccc"`. No `expires_at` / `code` / `deeplink` — the child redeems a code sent in the request body, doesn't generate one. Status 200 is implicit: the engine's existing check at `MockSupabaseEngine.kt:81-85` returns `NotFound` only when the body starts with `{"error"`, so a clean `{...}` body serves as 200.

### 2.2 `MockSupabaseEngine` `when` branch

One new branch in the `when` block at `MockSupabaseEngine.kt:68-80`. The 22/06 regroup convention (obs #82) is `/functions/v1/` first (alphabetical by endpoint name), then `/rest/v1/`. Within `/functions/v1/`: `create-pairing-code` (c) < `get-devices-for-parent` (g) < `get-templates` (g) < **`pairing` (p)** < `/rest/v1/time_requests` (the only `/rest/v1/` route). The new branch goes **immediately after the `get-templates` / `/rest/v1/templates` branch (line 73-75) and before the `/rest/v1/time_requests` branch (line 76)**, not after `get-devices-for-parent`.

Exact condition: `path.endsWith("/functions/v1/pairing")` (matches the existing `endsWith` style for the other 3 routes). Body: `readAsset("mock-supabase/pairing.json")`. Two new lines, no other changes.

### 2.3 RED test — `MockSupabaseEngineTest`

A 5th test in `MockSupabaseEngineTest.kt`, next to the 4th test (`create-pairing-code POST returns pairing code response shape` at line 148-187). Name: `` `pairing POST returns device_id and parent_id` ``. Mirrors the 4th test's roundtrip pattern (`client.post` with `Content-Type: application/json` and a `{ "code": "ABCDEFGH" }` body) but asserts only three contracts: `response.status.value == 200`, body contains `"device_id"`, body contains `"parent_id"`. No regex extraction needed (presence-only, unlike the 4th test's `parentalcontrol://pair?code=` prefix check).

## 3. Architecture decisions

### Decision A — Success-only fixture vs. multi-case

**Choice:** Success-only (200 with `device_id` + `parent_id`). **Alternatives:** Single JSON with a `status` / `body` discriminator returning 404/409/410 shapes, or four separate fixture files. **Rationale:** Scope locked to "1a" by the user. Child error paths (404 → `INVALID_CODE`, 409 → `ALREADY_USED`, 410 → `EXPIRED_CODE`) are already covered by `parsePairingResponse` unit tests; the mock layer only needs the success path to unblock manual QA. Cost of NOT doing multi-case: a future error-path test will need a separate fixture (or a routing-table refactor — see Decision B). Cost of doing it now: 4× fixture size and 3× test count, out of scope.

### Decision B — Single-dispatch `when` vs. exhaustive routing table

**Choice:** Keep the existing single-dispatch `when` and add one branch. **Alternatives:** `routesKnownByMockEngine: Set<String>` checked at startup, or per-route `(path, fixture)` lookup table. **Rationale:** Current pattern is the 22/06 precedent (obs #82) and the proposal does not request a refactor. A routing table would prevent future omissions (the same bug bit us on 22/06 and 23/06), but is out of scope for a hotfix. The proposal's Risks section already flags a `routesKnownByMockEngine` guard test as a follow-up — the new RED test partially closes the gap for this specific route, but the systemic guard remains a separate follow-up.

### Decision C — Alphabetical ordering of `when` branches vs. chronological

**Choice:** Alphabetical, matching the 22/06 regroup. **Rationale:** With 4 `/functions/v1/` routes now in play, alphabetical-by-endpoint-name keeps the visual diff of future additions predictable. The new `pairing` branch sorts AFTER `get-templates` (`g` < `p`) and BEFORE `/rest/v1/time_requests` (the only `/rest/v1/` route in the file). Inserting it after `get-devices-for-parent` would break the `/functions/v1/`-then-`/rest/v1/` super-grouping convention — verified on disk at `MockSupabaseEngine.kt:68-80`.

## 4. Apply hints

- **Strict TDD, 2 commits:**
  1. **RED** — add the new test in `MockSupabaseEngineTest.kt`. Confirm it fails: `assertEquals(200, …)` fails because the engine currently returns 404 from the `else` branch. Run `./gradlew testDebugUnitTest --tests "*.MockSupabaseEngineTest.pairing POST returns device_id and parent_id"` and capture the failure.
  2. **GREEN** — add the fixture `app/src/main/assets/mock-supabase/pairing.json` and the new `when` branch. The same test now passes. Run the full `./gradlew testDebugUnitTest` to confirm the 4 prior tests still pass (regression).
- **Quality gates** (no new dependencies, permissions, or Ktor config):
  `./gradlew testDebugUnitTest && ./gradlew assembleDebug && ./gradlew detekt && ./gradlew ktlintCheck`.
- **Files touched (3 total, exact paths):**
  - `app/src/main/assets/mock-supabase/pairing.json` (new, ~5 lines)
  - `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` (modify, +2 lines in `when`)
  - `app/src/test/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngineTest.kt` (modify, +~25 lines for new test)

## 5. Verification approach

- The new RED test (after GREEN) is the primary verification — pins status 200 + presence of `device_id` and `parent_id`.
- The 4 prior `MockSupabaseEngineTest` tests must still pass (regression on `devices`, `pendingRequests`, `templates`, `create-pairing-code`).
- `./gradlew testDebugUnitTest` must remain green end-to-end.
- Manual smoke (post-merge, out of apply scope): build debug APK with `USE_MOCK_SUPABASE=true`, enter a code on the child pairing screen, confirm "Emparejamiento exitoso" instead of the previous "Error de emparejamiento / Error de conexión".
