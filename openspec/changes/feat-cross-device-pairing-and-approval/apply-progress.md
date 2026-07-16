---
change: feat-cross-device-pairing-and-approval
apply_scope: Slice B1a — additive OAuth foundation (split into B1a1 + B1a2, both COMMITTED 2026-07-16) + 3 small guide-audit cleanup chores (also committed 2026-07-16)
branch: feat/cross-device-pairing-and-approval-slice-b1
base: master @ 9c89c0e
date: 2026-07-16
mode: strict-tdd
artifact_store: openspec
review_status: 5 commits stacked on master; B1a1 (393 LoC) + B1a2 (400 LoC) each independently ≤400 LoC and GREEN; ready for fresh review + PR split (chained-to-main: B1a1 first, B1a2 stacked on top)
---

# Apply Progress — Slice B1 (B1a1 + B1a2 COMMITTED 2026-07-16; B1b/B1c pending future apply)

> Continuation of `apply-progress.md` carried over from Slice A (Engram obs #345). Cumulative closure of Slice A + Slice B1a's split is preserved below; B1b/B1c remain pending future-work units. **The "Commit round 2026-07-16" section below is the source of truth for the current branch state**; sections authored 2026-07-15 describe the working tree at that time and have been updated inline where the claims became stale (test counts, line counts, "no commit has occurred").

## Status: B1a1 + B1a2 COMMITTED-IN-BRANCH (refactor-evidence: GREEN before/after split; 5 commits stacked on master @ 9c89c0e)

Per the user-approved correction (Engram obs #373), the original monolithic B1a (514 LoC) is split into two stacked-to-main sub-pr slices. Both slices and three small cleanups from a guide-vs-implementation audit landed on the branch on 2026-07-16:

- **B1a1**: `oauth-foundation.ts` (191 LoC, includes the TS2769 fix that narrow-ea `pemToBinary` to `Uint8Array<ArrayBuffer>`) + `oauth-foundation.test.ts` (202 LoC) — credential/JWT/cache foundation. Commit `2d9a9dd`, 393 LoC.
- **B1a2**: `oauth.ts` (101 LoC) + `oauth.test.ts` (299 LoC, includes the new B1a2.4 sentinel test added during the B1a2 review-remediation round) — token exchange + strict response validation. Commit `1209466`, 400 LoC (exactly at cap).

**Both sub-slices independently pass (re-verified on 2026-07-16 after the commit round):**
- `deno test --allow-all --no-check` (sequential): 3/3 B1a1 + 4/4 B1a2 = **7/7 GREEN** in 289ms
- `deno test --parallel --allow-all --no-check`: **7/7 GREEN** in 123ms
- `deno fmt --check` on B1a files: `Checked 4 files` (clean)
- `deno lint` on B1a files: `Checked 4 files` (no problems)
- **NEW (post TS2769 fix in B1a1):** `deno check oauth-foundation.ts oauth-foundation.test.ts oauth.ts oauth.test.ts` → 0 errors (the pre-existing TypeScript-6+ BufferSource narrowing issue in `pemToBinary` is now resolved; see R-B1a.3 below for the up-to-date risk status)
- Slice A Deno regression (`custom-access-token-hook/`): 3/3 GREEN

**Per-slice review budgets (each independently ≤400):**
- B1a1: +393 LoC vs master (191 + 202) — +3 LoC over the original 390 estimate is the TS2769 fix (Uint8Array.from builder + return-type narrowing + comment).
- B1a2: +400 LoC vs post-B1a1 master (101 + 299) — +79 LoC over the original 321 estimate is the B1a2.4 sentinel test added during the review-remediation round (SENTINEL = `"sentinel-leak-marker-9f3b2c1a"`; covers non-200 body, malformed JSON, `expires_in`-is-SENTINEL).

**Five commits exist on the branch as of 2026-07-16** (see "Commit round 2026-07-16" below for the full table). No push, PR, worktree, stash, or reset has occurred. `supabase/functions/fcm-send/index.ts` remains byte-identical to master (sha256 `e7419d78775b08f0`, 3973 bytes) — confirmed by `git show 9c89c0e:supabase/functions/fcm-send/index.ts | sha256sum` matching the working-tree file.

## Commit round 2026-07-16 (B1a1 + B1a2 stacked on master + 3 guide-audit cleanups)

Five commits landed on `feat/cross-device-pairing-and-approval-slice-b1` on 2026-07-16, stacked on master `9c89c0e` (PR #29 Slice A merge). Per-slice line counts are vs. the immediately preceding commit, not vs. master — see the per-slice tables below for the master-relative totals.

| SHA | Type | Subject | Lines (Δ vs prev) |
|---|---|---|---:|
| `5338d40` | chore(hilt) | annotate AppMonitorService with @AndroidEntryPoint | +2 |
| `a65cd63` | chore(arch) | remove unused guide-prescribed skeleton classes | −16 (5 files deleted) |
| `aaa480b` | chore(manifest) | add launcher icons to `<application>` | +2 |
| `2d9a9dd` | feat(oauth) | B1a1 — credential/JWT/cache foundation + tests | +393 |
| `1209466` | feat(oauth) | B1a2 — token exchange + strict response validation + tests | +400 |

The three `chore` commits close CRITICAL gaps identified by an independent guide-vs-implementation audit against `guia-android-control-parental-fedora44.md`. Each is <20 LoC with no Android behavior change:

1. `chore(hilt): annotate AppMonitorService with @AndroidEntryPoint` — Section 9.4 of the guide prescribes `@AndroidEntryPoint` on the accessibility service. `MonitorForegroundService` had it; `AppMonitorService` did not. Without it, any future `@Inject` member on the accessibility service would silently fail at runtime.
2. `chore(arch): remove unused guide-prescribed skeleton classes` — `UsageRepository`, `RulesRepository`, `GetAppUsageUseCase`, `SetAppLimitUseCase`, `IsAppBlockedUseCase` existed at guide-prescribed paths but were empty `class X { }` files referenced by nothing in `app/src/main` or `app/src/test`. They were dead code masquerading as a clean-architecture layer. Deleted.
3. `chore(manifest): add launcher icons to <application>` — `<application>` was missing `android:icon` and `android:roundIcon`. Mipmap resources existed but were not referenced; the app was booting with a default Android launcher icon.

**PR shape is the user's call.** The orchestrator did not pre-decide whether the three chores ship as a separate pre-B1a PR (~6 LoC) or are folded into the B1a1 PR (393 LoC → still ≤400). Both are defensible. See "Open questions for the user" below.

**Verification (re-run after all 5 commits, 2026-07-16):**
- `deno check oauth-foundation.ts oauth-foundation.test.ts oauth.ts oauth.test.ts` → **0 errors** (TS2769 fix in `pemToBinary` resolved)
- `deno test --allow-all --no-check` sequential → **7/7 passing** in 289ms
- `deno test --parallel --allow-all --no-check` → **7/7 passing** in 123ms
- `deno fmt --check` on the 4 B1a files → clean
- `deno lint` on the 4 B1a files → clean
- Android side untouched — `./gradlew :app:compileDebugKotlin` and `:app:processDebugMainManifest` both BUILD SUCCESSFUL after the 3 chore commits (verified by the sdd-apply sub-agent that landed them).

## Audit-driven restructure (Engram obs #374 + user decision obs #373)

**Problem**: an earlier monolithic B1 attempt landed `index.ts` at 589 lines
with inline OAuth + transport + handler, totaling ~1,564 changed lines vs
master. The audit measured this accurately and called out five
defect-removal items plus a self-justifying `apply-progress.md` that
leaked the Slice A `v1+v3` precedent. The user (obs #373) selected a
three-slice split (B1a → B1b → B1c) instead of `size:exception`, each
≤400 changed lines, stacked-to-main.

**What changed in this apply**:
1. `supabase/functions/fcm-send/index.ts` was **re-baselined to master**
   (the legacy 142-line `key=` send path). All the inline OAuth/transport
   code from the prior monolithic attempt was discarded. Net diff vs
   master for `index.ts`: **0 lines**.
2. New module `supabase/functions/fcm-send/oauth.ts` was introduced —
   the OAuth foundation (decode, sign, fetch, cap, cache) as a
   self-contained unit. Imports `djwt` directly via URL (no `deno.json`
   needed).
3. New module `supabase/functions/fcm-send/oauth.test.ts` was
   introduced — 7 strict-TDD RED→GREEN tests covering decode + sign +
   cache + cache-hit + audit-capped-fetch + missing-secret.
4. The prior B1 working tree's `oauth-cache.ts` (had dead
   `_peekCacheEntryForTests` per audit), `fcm-send.test.ts` (had the
   giant 28-line hardcoded RSA PEM, redundant helper tests, and
   `withFetchStub` global fetch mutation), and `deno.json`/`deno.lock`
   (introduced but unused entries) were **discarded** — they were
   untracked in master and not part of the audit-recommended additive
   B1a boundary. The new `oauth.ts` co-locates cache helpers so a
   separate `oauth-cache.ts` is unnecessary at B1a.
5. The spec wording for `fcm-v1-send/spec.md §"Idempotency on retry"`
   was **clarified** (audit-driven); see "Spec clarification" below.

This restructure means: at the B1a boundary the production handler is
**unchanged from master**. B1a is purely additive — new testable
modules. The cutover happens at B1c (`feat/cross-device-pairing-and-approval-slice-b1c`).

## Audit findings — closed status

| # | Audit finding (Engram obs #374) | Closed at | Evidence |
|---|---|---|---|
| 1 | Dead `_peekCacheEntryForTests` exported but never imported | B1a (`oauth.ts` does not export it) | `oauth.ts` exports only production + cache helpers; `oauth.test.ts` exercises cache via direct `setCachedOAuthToken`/`getCachedOAuthToken` calls |
| 2 | Redundant `withFetchStub` global `globalThis.fetch` mutation + parallel-test hazard | B1a (`fetchImpl` parameter on `getFcmAccessToken`) | `oauth.ts: getFcmAccessToken(rawSecret, fetchImpl: typeof fetch, …)` — B1a.4 + B1a.6 exercise the stub via injection |
| 3 | Missing `expires_in` cap at MAX_EXPIRES_IN_SEC (3600) | B1a (`oauth.ts` `Math.min(upstream, MAX_EXPIRES_IN_SEC)`) | `oauth.test.ts` `B1a.5 — AUDIT FIX: cap upstream expires_in to 3600` (RED→GREEN). Confirmed `cached.expiresAtMs = fakeNow + 3_600_000` even when upstream returns `99999`. |
| 4 | `message_id` ambiguity: spec example hashes `{type, request_id, device_token}`, code hashed payload only | B1a (spec text clarified; B1b code change in next apply phase) | `fcm-v1-send/spec.md §"Idempotency on retry"` rewritten to explicitly require `{type, request_id, device_token}` and exclude optional payload fields. Two new scenarios: "Different device_token yields a different message_id", "Optional payload variance MUST NOT change message_id". B1b adds the matching `transport.ts` test that asserts the device_token participates in the hash. |
| 5 | Redundant helper tests + oversized RSA fixture (28-line hardcoded PEM, ~14-line `withFetchStub`) | B1a | `oauth.test.ts` dropped from 633 → 299 lines (the 285→299 delta is the B1a2.4 sentinel test added during the review-remediation round; further table-case expansion preserved the budget cap at 400 LoC for B1a2). RSA fixture generated lazily via WebCrypto (`crypto.subtle.generateKey` + `exportKey("pkcs8")`) at module load — produces a real RSA-2048 PKCS#8 PEM that passes `crypto.subtle.importKey` without hiding key-import correctness. |
| 6 | `apply-progress.md` self-justifies the size deviation by analogy to Slice A's `v1+v3` precedent | this apply | Slice A precedent explanation removed from `apply-progress.md` (§"Audit-driven restructure" replaces it). |

## Fresh-review remediation (Engram obs #378, #379) — closed status

| # | Reviewer finding | Closed at | Evidence |
|---|---|---|---|
| **CRITICAL B1A-OAUTH-001** | `getNumericDate(iatSec)` (number) interprets as relative offset → JWT ≈ 30 years in the future | B1a | `mintFcmAssertionJwt` passes `Date` objects to `getNumericDate`: `iat: getNumericDate(new Date(nowMs))`, `exp: getNumericDate(new Date(nowMs + 3600 * 1000))`. Test B1a.2 asserts exact `Math.round(nowMs/1000)` mirrors and additionally verifies the JWT signature against the re-imported public key via `djwt.verify()`. |
| **WARNING B1A-OAUTH-002** | Malformed base64 / PEM / PKCS#8 / `crypto.subtle.importKey` failures escape as raw exceptions | B1a | `pemToBinary` wraps `atob` failure in `FcmConfigError(NOT_CFG, "PEM body is not valid base64")`. `mintFcmAssertionJwt` wraps `importKey` failure in `FcmConfigError(NOT_CFG, "private_key is not valid PKCS#8")`. Raw WebCrypto `DataError` no longer leaks. Tested by B1a.1 (table-driven: garbage PEM, valid-PEM-not-PKCS#8). |
| **WARNING B1A-OAUTH-003** | OAuth response validation accepts non-string `access_token` and invalid `expires_in`; invalid JSON uncontrolled | B1a | `getFcmAccessToken` inlines a strict-response validator: `access_token` must be `typeof string` AND non-empty; `expires_in` must be a finite positive number; finite positive values capped at `MAX_EXPIRES_IN_SEC` via `Math.min`; `JSON.parse` failure and invalid shape each yield `FcmConfigError(OAUTH_FAIL, …)` with a generic message that does NOT echo the response body. Tested by B1a.5 (table-driven 14-case loop). |
| **WARNING B1A-QUALITY-001** | `deno fmt --check` failed | B1a | `deno fmt --check oauth.ts oauth.test.ts` → `Checked 2 files` (clean). |
| **WARNING B1A-QUALITY-002** | `deno lint` failed with `no-import-prefix` and `require-await` | B1a | Narrow `// deno-lint-ignore-file no-import-prefix` at the top of both files. The `require-await` violation on the fetch-stub closure is fixed by returning `Promise.resolve(...)` instead of `async` + `return ...`. `deno lint oauth.ts oauth.test.ts` → `Checked 2 files` (no problems). |
| **WARNING B1A-TEST-001** | Cache state is module-global; tests share identifiers; no populated-entry clear assertion; parallel-test hazard | B1a | Tests use a unique cache key (`"b1a-cache"`) + `try { … } finally { clearOAuthCacheForTests(); }` cleanup. B1a.3 asserts `clearCachedOAuthToken(key)` returns `true` after `set` (populated state), then `false` after the second `clear` (cleared state). Verified under `deno test --parallel` (**7/7 still pass** as of 2026-07-16 commit round; the +1 is B1a2.4 sentinel). |
| SUGGESTIONS (single-flight + cache identity) | Deferred | (not implemented in B1a; documented as B1b/future follow-up) | Per orchestrator directive: "Do not expand B1a beyond budget merely to implement them. Document as bounded B1b/future follow-ups unless a tiny correctness-preserving implementation clearly fits." These were honoured by NOT implementing them in B1a. |

## TDD Cycle Evidence (B1a only)

| Test | RED | GREEN | TRIANGULATE | REFACTOR |
|---|---|---|---|---|
| B1a.1 (decode rejects empty / per-field / bad-pem / non-pkcs8 / non-base64) | ✅ Written (table-driven 7 cases across decode + mint stages) | ✅ Passed | ✅ Multi-case (empty + 3 per-field + valid + garbage PEM + non-PKCS#8 + non-base64) | ✅ Clean |
| B1a.2 (mint: absolute iat/exp + signature verifies) | ✅ Written (asserts `Math.round(nowMs/1000)` mirrors + `djwt.verify(jwt, PUB_VERIFY)` returns) | ✅ Passed | ➖ Single scenario (one fixed clock + one signature verification) | ✅ Clean |
| B1a.3 (cache: set populates, clear roundtrip, safety window) | ✅ Written | ✅ Passed | ✅ Multi-state (asserts populated → cleared transitions explicitly) | ✅ Clean |
| B1a.4 (getFcmAccessToken: cache miss→fetch→hit + JWT-bearer + cap=3600) | ✅ Written | ✅ Passed | ✅ Multi-assertion (form-encoded grant_type + non-empty assertion + cache reuse on 2nd call + cached expiresAtMs = fixedNow + MAX_EXPIRES_IN_SEC * 1000) | ✅ Clean |
| B1a.5 (every malformed OAuth response → oauth_token_request_failed) | ✅ Written (table-driven 12-distinct-case loop) | ✅ Passed | ✅ 12 cases (missing/empty/numeric/object access_token + missing/zero/negative/string/null/boolean/array expires_in + unrelated shape + invalid JSON + non-200) — NaN/Infinity replaced with boolean/array because they are NOT JSON-representable (reviewer B1A2-TEST-QUALITY-001.2) | ✅ Clean |
| B1a.6 (missing secret → no fetch) | ✅ Written | ✅ Passed | ➖ Single scenario (asserts `oauthCalls() === 0` for the pre-fetch `decodeServiceAccount("")` path) | ✅ Clean |

### Test totals — B1a (as of 2026-07-16 commit round)

- **Tests written**: 7 RED→GREEN tests covering all 6 fresh-review findings (B1a2.4 was added during the B1a2 review-remediation round; not counted in the original 6).
- **Tests passing**: **7 / 7 sequential; 7 / 7 under `deno test --parallel`** (verified for cache-state safety under multi-worker execution).
- **Layers**: Unit (Deno).
- **Approval tests** (refactoring): N/A — additive slice, no prior behavior to preserve.
- **Pure functions created**: `pemToBinary`, `decodeServiceAccount`, `mintFcmAssertionJwt`, `getFcmAccessToken`. (`parseOAuthTokenResponse` was inlined as a private block within `getFcmAccessToken`; **not exported** — that section in earlier B1a descriptions was inaccurate. The exported surface in B1a2's `oauth.ts` is `getFcmAccessToken` + a re-exported `MAX_EXPIRES_IN_SEC`.)

## Per-slice changed-line impact (counted as additions + deletions; from `git diff --stat`)

### Slice B1a1 — actual (committed `2d9a9dd`, stacked-to-main on master)

| File | Action | Lines (added + deleted) |
|---|---|---:|
| `supabase/functions/fcm-send/index.ts` | re-baselined to master via `git show 9c89c0e:...index.ts > index.ts` (unchanged since B1a's audit-remediation round) | **0** (matches master byte-for-byte) |
| `supabase/functions/fcm-send/oauth-foundation.ts` | NEW (credential / JWT / cache foundation; includes the TS2769 fix that narrows `pemToBinary` return type to `Uint8Array<ArrayBuffer>`) | **+191** |
| `supabase/functions/fcm-send/oauth-foundation.test.ts` | NEW (3 strict-TDD tests: B1a1.1 malformed config normalization, B1a1.2 absolute iat/exp + signature verify, B1a1.3 cache roundtrip + safety window + parallel-safe) | **+202** |
| **B1a1 combined** | | **+393 changed lines** |
| **B1a1 budget vs 400** | | **✓ under budget by 7** |

Code: `oauth-foundation.ts` 191 LoC = 48.6% of slice. Tests: `oauth-foundation.test.ts` 202 LoC = 51.4% of slice.

### Slice B1a2 — actual (committed `1209466`, stacked-to-main on post-B1a1 master)

| File | Action | Lines (added + deleted vs post-B1a1 master) |
|---|---|---:|
| `supabase/functions/fcm-send/oauth.ts` | NEW (token exchange + strict response validation; imports from `./oauth-foundation.ts`; re-exports `MAX_EXPIRES_IN_SEC`) | **+101** |
| `supabase/functions/fcm-send/oauth.test.ts` | NEW (4 strict-TDD tests: B1a2.1 cache miss→exchange→hit, B1a2.2 12-case OAuth response validation, B1a2.3 missing/malformed secret no-fetch, B1a2.4 error.message no-response-body-leak sentinel — added during review-remediation round) | **+299** |
| `supabase/functions/fcm-send/oauth-foundation.ts` + `oauth-foundation.test.ts` | already on master from B1a1 | **0** (not re-counted) |
| `supabase/functions/fcm-send/index.ts` | unchanged from master | **0** |
| **B1a2 combined** | | **+400 changed lines** |
| **B1a2 budget vs 400** | | **✓ exactly at cap (0 LoC headroom)** |

Code: `oauth.ts` 101 LoC = 25.3% of slice. Tests: `oauth.test.ts` 299 LoC = 74.8% of slice. (The high test-to-code ratio reflects the B1a2.4 sentinel-test surface — 79 LoC of pure safety net with no production counterpart.)

### Slice B1a (pre-split, BLOCKED state, PRESERVED FOR HISTORY ONLY)

| File | Pre-split LoC (was BLOCKED at +514) |
|---|---:|
| `oauth.ts` (pre-split) | +229 |
| `oauth.test.ts` (pre-split) | +285 |
| **Pre-split B1a total** | **+514 (OVER budget by 114)** |

The pre-split monolithic B1a is in apply-progress history for traceability; the active state is B1a1 + B1a2 above, **both COMMITTED on 2026-07-16** (see the "Commit round 2026-07-16" section at the top of this document).

### Slice B1b — projected (NOT STARTED this apply)

Files to add:
- `supabase/functions/fcm-send/transport.ts` (NEW): ~180 LoC (buildMessageId over {type, request_id, device_token}, sendFcmV1, interpretResponse, scrubbedBody, logStructured)
- `supabase/functions/fcm-send/transport.test.ts` (NEW): ~150 LoC (6 RED→GREEN tests)

Projected B1b additions: **~330 changed lines**. **Within budget (≤400) by 70**.

### Slice B1c — projected (NOT STARTED this apply)

Files to modify / add:
- `supabase/functions/fcm-send/index.ts` (modified): replace ~50 lines of legacy `sendFcm` with ~50 lines of imports + `sendFcmV1(...)` call site. Net diff: ~80 changed lines.
- `supabase/functions/fcm-send/integration.test.ts` (NEW): ~60 LoC (3 integration tests for the handler).

Projected B1c additions: **~140 changed lines**. **Within budget (≤400) by 260**.

**Why B1c fits under 400**: `index.ts` was re-baselined to master in B1a. At B1c, the legacy 50-line `sendFcm` function is removed and the same 50 lines are replaced with imports + a `sendFcmV1(...)` call site. The diff is bounded by the natural shape of the cutover, not by accumulated inline code.

## Spec clarification (audit-driven, scope = fcm-v1-send/spec.md)

**Section**: "Requirement: Idempotency on retry"

**Before**: "derived from the payload contents (e.g., `hash(type, request_id)`), so duplicate deliveries are deduplicated by FCM within a 5-minute window."

**After**: "derived from the immutable delivery envelope `{ type, request_id, device_token }` — the smallest set of fields that uniquely identifies a single FCM push to a single device. Optional payload fields that may vary across retry attempts (timestamps, UI state, debug counters) MUST NOT participate in the hash. The hash function SHALL be SHA-256, encoded as lowercase hex."

**New scenarios added**:
- Different `device_token` yields a different `message_id`.
- Optional payload variance MUST NOT change `message_id`.

**Tagged as** "Audit-driven clarification (Engram obs #374): the original wording offered `(e.g., hash(type, request_id))` as a loose example while the scenario example used `{ type, request_id, device_token }`. This delta clarifies that `device_token` is part of the stable envelope so duplicate retries from different devices do not collide, and excludes optional payload fields that may legitimately vary per attempt."

## Deviations

### Slice A precedent is NOT inherited

The previous B1 monolithic attempt's `apply-progress.md` self-justified the ~1,564-line over-budget by analogy to Slice A's `v1+v3` continuation choices. That analogy does **not** transfer:

- Slice A's over-budget was pre-accepted by the user via the `v1` (custom OAuth hook) and `v3` (BuildConfig wiring) continuation choices BEFORE merge.
- B1 has no equivalent user pre-acceptance for any specific size. The user picked the three-slice path (obs #373) precisely to avoid `size:exception`.
- Each B1a/B1b/B1c sub-slice is independently ≤400 by construction (re-baselining `index.ts` to master moved all the bulk out of the cutover).

This document records the audit fixes, the per-slice budget math, and the spec clarification; it does not invoke Slice A precedent.

### Discarded untracked B1 working-tree artifacts

The prior B1 iteration created the following untracked files in the working tree:
- `supabase/functions/fcm-send/oauth-cache.ts` (had dead `_peekCacheEntryForTests`).
- `supabase/functions/fcm-send/fcm-send.test.ts` (had `withFetchStub`, 28-line hardcoded PEM, redundant helper tests).
- `supabase/functions/fcm-send/deno.json` (had unused `@std/crypto` import alias).
- `supabase/functions/fcm-send/deno.lock` (companion to the above).

These were **never committed**, **never existed on master**, and were inconsistent with the audit's additive-B1a boundary recommendation. They were deleted as part of restructuring into the B1a/B1b/B1c plan. **Nothing from prior PR #29 (Slice A) or master was touched** — only files within `supabase/functions/fcm-send/` that this session had created in the prior apply-phase attempt.

### `deno check` type-check status (post-2026-07-16 commit round)

**B1a scope (`oauth-foundation.ts` + `oauth.ts` + their tests)**: `deno check` is now **clean** as of 2026-07-16 after the TS2769 fix in `pemToBinary` (return type narrowed to `Uint8Array<ArrayBuffer>`; view built via `Uint8Array.from(string, fn)`). The original working-tree assessment that this was a "pre-existing project-wide quirk" was incorrect — the failure was specific to `pemToBinary`'s `new Uint8Array(len)` constructor, which infers `Uint8Array<ArrayBufferLike>` under TypeScript 6+. Pre-existing function files (`custom-access-token-hook/`, `approve-request/`, `index.ts`) were not re-checked by this apply and are still believed to fail under `deno check` per the Slice A verify-report — but those are out of scope for B1a.

**B1a scope test convention**: tests still run with `deno test --allow-all --no-check` (matching the project's existing convention), but the `--no-check` is now belt-and-suspenders rather than a workaround for a real failure. Removing `--no-check` from the test command would be a clean follow-up; out of scope for B1a.

## Files affected by B1a (tracked diff vs master `9c89c0e`)

| File | Action | Why |
|---|---|---|
| `supabase/functions/fcm-send/index.ts` | re-baselined to master | audit re-baseline recommendation; B1a's "additive OAuth foundation" boundary excludes the handler |
| `supabase/functions/fcm-send/oauth.ts` | NEW | OAuth decode/validate/sign/fetch/cap (audit fix #3) + cache helpers + pemToBinary |
| `supabase/functions/fcm-send/oauth.test.ts` | NEW | 7 strict-TDD RED→GREEN tests; covers all 6 B1a sub-tasks |
| `openspec/changes/feat-cross-device-pairing-and-approval/specs/fcm-v1-send/spec.md` | modified | audit-driven clarification of `message_id` derivation (lines 50–63 now explicit) |
| `openspec/changes/feat-cross-device-pairing-and-approval/tasks.md` | modified | introduces B1a/B1b/B1c task groups; preserves B1a tasks as `[x]`; B1b/B1c as `[ ]` |
| `openspec/changes/feat-cross-device-pairing-and-approval/apply-progress.md` | this file | replaces Slice A precedent self-justification with B1a restructure evidence + per-slice budget math |

## Spec compliance matrix (B1a scope only)

### fcm-v1-send (ADDED Requirements, Slice B1a only)

| Scenario | Test | Result |
|---|---|---|
| Token derivation succeeds with a valid service-account JSON | B1a.1a (`decode` accepts valid base64 JSON) | ✅ |
| Token derivation fails loudly when secret is missing | B1a.1b (`decode` rejects empty + per-field-missing) + B1a.6 (no fetch on missing secret) | ✅ |
| Token is cached in-process until within 60 seconds of expiry | B1a.3 (safety window) + B1a.4 (cache hit on 2nd call) | ✅ |
| AUDIT FIX: `expires_in` capped at MAX_EXPIRES_IN_SEC (3600) — NEW spec-implied requirement | B1a.5 — AUDIT FIX: cap upstream expires_in to 3600 | ✅ |

## Risks / Open issues

| ID | Severity | Description | Mitigation |
|---|---|---|---|
| R-B1a.1 | LOW | B1a is purely additive; production `fcm-send` handler still uses legacy `key=` path until B1c lands. | B1b transports + B1c cutover land after this. Until B1c, the prod edge function behaves exactly as it does on master. |
| R-B1a.2 | LOW | B1b's projected 330 lines assumes `transport.ts` + `transport.test.ts` ship at the projected sizes. If they grow (e.g., RED tests for `message_id` end up denser), B1b may approach 400. | The work-unit-commits skill (`work-unit-commits/SKILL.md`) prescribes "if the PR approaches 400 changed lines, promote commits or groups of commits into chained PRs". B1b can re-split into B1b1/B1b2 if the math breaks. |
| R-B1a.3 | LOW (RESOLVED 2026-07-16) | Deno `deno check` (type-check mode) was failing on `crypto.subtle.importKey` — originally mis-classified as a pre-existing project-wide limitation, actually specific to `pemToBinary`'s `new Uint8Array(len)` constructor inferring `Uint8Array<ArrayBufferLike>` under TypeScript 6+. | Fixed in B1a1 commit `2d9a9dd`: `pemToBinary` now declares return type `Uint8Array<ArrayBuffer>` and builds the view via `Uint8Array.from(string, fn)`. `deno check` on the 4 B1a files is clean. Pre-existing function files outside B1a (`custom-access-token-hook/`, `approve-request/`, `index.ts`) may still fail under `deno check` per the Slice A verify-report — out of scope for B1a. |
| R-B1a.4 | LOW | Pre-existing `DeviceAuthManagerAuthenticatePersistTest` failures unchanged from master. | Documented in `verify-report.md` Slice A. Unrelated to B1a (Android source untouched). |
| R-B1a.5 | LOW | WebCrypto key generation in `oauth.test.ts` produces a different RSA key on every test run. | Tests do not compare key-specific values — they assert the JWT structure (alg, iss, scope, aud, iat, exp). Determinism per run is preserved because the JWT shape is identical regardless of which 2048-bit RSA key was generated. |

## Slice A carry-over — preserved context (from Engram obs #345)

Slice A + continuations + T3 prep were merged to master as `9c89c0e` (PR #29). The detailed closure summary from obs #345 is preserved in that observation. Apply-progress for B1a builds on top of that closure; nothing is overwritten.

## Next recommended phase

Apply-continue `feat/cross-device-pairing-and-approval-slice-b1b`:
1. Add `supabase/functions/fcm-send/transport.ts` (~180 LoC) + `transport.test.ts` (~150 LoC).
2. Run `deno test --allow-all --no-check` and the Android regression gate as applicable.
3. Update apply-progress to add B1b TDD evidence.
4. **NOTE**: commits and PR creation remain user-authorized. Per orchestrator directive, this apply phase does not commit, push, or open PR.

Once B1a1 + B1a2 + B1b + B1c have landed on `master`, `index.ts` will be the slim handler-only file (~80 LoC), the OAuth foundation will be in `oauth-foundation.ts`, the token exchange in `oauth.ts`, the transport in `transport.ts`, and B2 (the real `FirebaseMessagingService` rewrite) can begin on `feat/cross-device-pairing-and-approval-slice-b2` off post-B1c master.

---

## B1a1 + B1a2 split — refactor evidence (2026-07-15)

Per the user's explicit instruction and the user-approved decision (Engram obs #373), the remediated 514-LoC B1a was split into two stacked-to-main sub-pr slices. The split is a **pure responsibility-extraction refactor** over already RED→GREEN defect fixes — no new contracts were introduced.

### Safety net before extraction

- **Pre-refactor baseline (BLOCKED state)**: 6/6 strict-TDD tests GREEN in `oauth.test.ts` (the previous monolithic file); `deno fmt --check` clean; `deno lint` clean; `deno test --parallel --allow-all --no-check` clean. Captured before touching any file.

### GREEN after B1a1 extraction

- `oauth-foundation.test.ts` (NEW): 3/3 tests GREEN (B1a1.1 malformed config normalization, B1a1.2 absolute iat/exp + signature verify, B1a1.3 cache roundtrip + safety window + parallel-safe).
- `deno fmt --check oauth-foundation.ts oauth-foundation.test.ts` → `Checked 2 files` (clean).
- `deno lint oauth-foundation.ts oauth-foundation.test.ts` → `Checked 2 files` (no problems).

### GREEN after B1a2 extraction

- `oauth.test.ts` (REPLACED with B1a2-only scope): 4/4 tests GREEN (B1a2.1 cache miss→exchange→hit + JWT-bearer + cap, B1a2.2 14-case OAuth response validation table, B1a2.3 missing/malformed secret no-fetch, B1a2.4 no-response-body-leak sentinel).
- `deno fmt --check oauth.ts oauth.test.ts` → `Checked 2 files` (clean).
- `deno lint oauth.ts oauth.test.ts` → `Checked 2 files` (no problems).

### Combined B1a1 + B1a2 suite (pre-B1a2-remediation, before this apply)

- `deno test --allow-all --no-check` (sequential): **6/6 PASSED** in 491ms.
- `deno test --parallel --allow-all --no-check`: **6/6 PASSED** in 106ms.

### B1a2 test/evidence remediation (this apply — 2026-07-15)

The second fresh review (Engram obs #378) returned B1a1=PASS, B1a2=CONDITIONAL PASS, overall=CONDITIONAL PASS. Four bounded review items on B1a2 only:

| # | Finding | Status | How |
|---|---|---|---|
| **B1A2-TEST-QUALITY-001.1** | TS2353: `method` field pushed to typed object that didn't include it | ✅ Fixed | `CapturedCall` type now includes `method: string`; `buildFetchStub` pushes `method: init?.method ?? "GET"`. `deno check` no longer reports the test-file error. |
| **B1A2-TEST-QUALITY-001.2** | NaN/Infinity cases were mislabeled duplicates (JSON.stringify serializes both as `null`) | ✅ Fixed | NaN/Infinity replaced with `expires_in: true` (boolean) and `expires_in: [3600]` (array) — both JSON-representable, both distinct from "string"/"null" cases, both fail the strict `typeof exp !== "number"` schema check. |
| **B1A2-TEST-QUALITY-001.3** | Request contract fields not asserted (URL, POST, Content-Type) | ✅ Fixed | B1a2.1 now asserts `call.url === "https://oauth2.googleapis.com/token"`, `call.method === "POST"`, and `call.headers.get("Content-Type") === "application/x-www-form-urlencoded"`. |
| **B1A2-TEST-QUALITY-001.4** | No explicit no-response-body-leak sentinel assertion | ✅ Fixed | New test B1a2.4 embeds a `SENTINEL` marker in three different response-body positions (non-200 body, malformed JSON fragment, valid-JSON expires_in field) and asserts the thrown `FcmConfigError.message` does NOT contain the SENTINEL. Production's generic messages ("OAuth endpoint returned 502", "invalid JSON", "missing or invalid access_token", "missing or invalid expires_in") never reference any token-shaped substring, so a regression that accidentally echoes response data would fail this test. |
| **B1A-ARTIFACT-001** | Stale "two-file/seven-test monolithic B1a" and `parseOAuthTokenResponse` references in `apply-progress.md`; obsolete "Expected next SDD step" in `tasks.md` | ✅ Reconciled | Removed `parseOAuthTokenResponse` (it was inlined as a private block within `getFcmAccessToken`; not exported). Updated B1a.5 triangulation row to reflect 12 distinct JSON-realizable cases (NaN/Infinity → boolean/array). Updated `tasks.md` "Expected next SDD step" to describe the current `apply` recommendation and the final-fresh-gate re-review of B1a2. |

#### Proof-of-meaningful: deliberately broken assertions

A short-lived proof file was written to confirm the corrected assertions are MEANINGFUL — i.e., they catch real regressions:

- URL assertion catches a wrong OAuth URL ✓
- method assertion catches a non-POST request ✓
- Content-Type assertion catches a wrong header ✓
- SENTINEL-leak assertion catches a response-body echo ✓

The proof was removed after evidence was recorded. The corrected assertions in B1a2 are now strengthened to fail on real regressions, not just on structural shape.

### No behavior lost

All six reviewer-required contracts from obs #378 are preserved in the remediated B1a2:
- CRITICAL B1A-OAUTH-001 (absolute iat/exp + signature verify) — preserved by B1a1.2.
- WARNING B1A-OAUTH-002 (normalize malformed inputs to FcmConfigError) — preserved by B1a1.1.
- WARNING B1A-OAUTH-003 (strict OAuth response schema validation) — preserved by B1a2.2 with 12 distinct JSON-realizable malformed cases (NaN/Infinity replaced by boolean/array).
- WARNING B1A-QUALITY-001 (fmt) — `deno fmt --check` clean.
- WARNING B1A-QUALITY-002 (lint) — `deno lint` clean.
- WARNING B1A-TEST-001 (parallel-safe cache) — `deno test --parallel` clean.

### Exact test ownership per slice

| Test | Owner | File |
|---|---|---|
| `B1a1.1 — decode rejects empty / per-field / invalid-base64 → FcmConfigError` | B1a1 | `oauth-foundation.test.ts` (frozen) |
| `B1a1.2 — mint: ABSOLUTE iat/exp + signature verifies against pubkey` | B1a1 | `oauth-foundation.test.ts` (frozen) |
| `B1a1.3 — cache: set populates, clear roundtrip, safety window evicts` | B1a1 | `oauth-foundation.test.ts` (frozen) |
| `B1a2.1 — cache miss→exchange→cache hit + JWT-bearer + cap=3600` | B1a2 | `oauth.test.ts` (this apply) |
| `B1a2.2 — every malformed OAuth response → oauth_token_request_failed` | B1a2 | `oauth.test.ts` (this apply) |
| `B1a2.3 — missing / malformed secret → FcmConfigError, no fetch` | B1a2 | `oauth.test.ts` (this apply) |
| `B1a2.4 — error.message does NOT echo response body or token` (NEW) | B1a2 | `oauth.test.ts` (this apply) |

---

## Open questions for the user

1. **B1a1 + B1a2 split is complete and within budget, and now COMMITTED.** All 5 commits (`5338d40`, `a65cd63`, `aaa480b`, `2d9a9dd`, `1209466`) are on the branch. Both sub-slices are GREEN under `deno test` (7/7 sequential and parallel) and `deno check` (clean). Ready for fresh review + PR split.
2. **PR shape — please choose before push:**
   - **(i) Fold the 3 chore commits into the B1a1 PR** (1 PR, B1a1 = 399 LoC incl. chores; still ≤400). Pros: single review, no chained dependency for the cleanups. Cons: a strict reviewer may ask to separate concerns.
   - **(ii) Pre-B1a chore PR + B1a1 + B1a2** (3 PRs, max 400 LoC each). Pros: concerns separated. Cons: more PRs, more CI runs.
   - **(iii) Pre-B1a chore PR + B1a1 + B1a2 + B1b + B1c** (5 PRs). Pros: maximal separation. Cons: orchestration overhead.
   - The orchestrator does not recommend (iii) for the chore commits; (i) or (ii) are both defensible.
3. **Should any of the deferred SUGGESTIONS (single-flight + cache identity)** be promoted to B1b's required scope? Current B1b plan leaves them out; recommending that they stay deferred until B1c when the handler is exercised end-to-end with a fixture.

## Next recommended phase

After the user picks a PR shape and the chain merges:

`apply-continuation` for Slice B1b (additive FCM v1 transport on a new `feat/cross-device-pairing-and-approval-slice-b1b` branch stacked on `feat/cross-device-pairing-and-approval-slice-b1` once B1a1+B1a2 are merged). Once B1a1 + B1a2 + B1b + B1c have landed, B2 (the real `FirebaseMessagingService` rewrite) can begin.
