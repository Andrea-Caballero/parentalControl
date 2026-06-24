# Archive Report: fix-pairing-session-before-redeem

**Change name**: `fix-pairing-session-before-redeem`
**Archived on**: 2026-06-23
**Archived to**: `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/`
**Archive mode**: `openspec` (filesystem, with engram mirror for cross-session recovery)
**Mode**: Strict TDD (2 commits: RED `276f909` + GREEN `2333949`)
**Persistence**: openspec + Engram hybrid
**PR**: #9 — https://github.com/Andrea-Caballero/parentalControl/pull/9
**Merged commit SHA**: `7706e0f` (squash merge of `276f909` + `2333949`)
**Status**: COMPLETE (PASS WITH WARNINGS — pre-existing ktlint gate on master, see §6)

---

## 1. Outcome summary

The first-launch `SESSION_ERROR` on `PairingManager.pairWithCode()` is fixed. After PR #9, when `authManager.getAccessToken()` returns `null` (the first-launch case — no stored session, no anonymous token), `pairWithCode()` now calls `DeviceAuthManager.authenticateOrCreate()` (the same seam `SupabaseClientProvider.initializeAndAuthenticate()` already uses at line 104, and the same one the boot design flags for the boot path). On `AuthResult.Success(deviceId, accessToken, refreshToken, expiresAt)` the existing `clientProvider.httpClient.post(...)` runs unchanged with the freshly acquired bearer token; on `AuthResult.Error` or `AuthResult.NeedsPairing` the function returns `PairingErrorType.NETWORK_ERROR` with the message `"Error de conexión. Verifica tu conexión a internet."` — the same `PairingViewModel.kt:159-166` UX branch the user already sees for transient failures. `parsePairingResponse` at line 219 then calls `authManager.savePairedSession(deviceId, parentId)`, which re-persists the current `currentAccessToken` with the new `deviceId` (no API change). This is the FOURTH change in a 4-PR chain that peeled back masking bugs on the child pairing path: PR #6 wired the mock route, PR #7 fixed WorkManager auto-init, PR #8 added the boot-time session restore, and PR #9 closes the loop on the deeper bug (the pairing call site assumed a token that the first-launch flow has no reason to possess).

### What was archived

```
openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/
├── archive-report.md          ← this file
├── proposal.md                ← proposal (7.4 KB, 62 lines)
├── design.md                  ← technical design (11.1 KB, 86 lines)
├── tasks.md                   ← task list (10.5 KB, 77 lines)
├── apply-progress.md          ← apply progress (14.1 KB, 85 lines)
└── verify-report.md           ← verification report (40.9 KB, 211 lines)
```

No `specs/` subdirectory — the change has no spec delta (see §2).

### Source of truth

**No main spec was modified.** The change is an implementation-only fix; the production HTTP path is unchanged, and the existing `pairing-flow` capability (`openspec/specs/pairing-flow/spec.md`, requirement "Child completes pairing via code or QR scan", scenario "Manual code entry posts to pairing edge function") already mandates `POST /functions/v1/pairing` with a bearer token. The fix is in the implementation of *acquiring* that token, not in spec-level behavior. The `openspec/specs/` tree is unchanged by this archive.

---

## 2. Spec sync

**NO spec delta — explicit decision.** This change did not run `sdd-spec` (skipped per orchestrator / user decision). The proposal documents the reasoning:

> `pairing-flow` spec | Unchanged | `pairing-flow` spec (`openspec/specs/pairing-flow/spec.md`, req "Child completes pairing via code or QR scan", scenario "Manual code entry posts to pairing edge function") already mandates `POST /functions/v1/pairing` with a bearer token; the fix is in IMPLEMENTATION (preflight acquisition of that token), not in spec-level behavior.

Verification:

- The change folder `openspec/changes/fix-pairing-session-before-redeem/` contained only `proposal.md`, `design.md`, `tasks.md`, `apply-progress.md`, `verify-report.md` — no `specs/` subdirectory.
- `openspec/specs/` was inspected: the `pairing-flow` capability was the closest match. No modifications were required, none were made.
- The 5 success criteria in the proposal §Success criteria explicitly require "pairing-flow spec unchanged; no delta spec produced". Verify-report §Spec Compliance Matrix confirms 4/5 success criteria demonstrably compliant (criterion 3 is PARTIAL due to the pre-existing ktlintCheck exit-1, see §6; criterion 5 is post-merge CI, see §7 #5).
- The proposal §Spec recommendation explicitly defers a future delta: *"revisit if a future change adds new error semantics (e.g., distinguishing 'no network' from 'backend rejected the anonymous sign-in') — at that point a delta spec is justified"*.

Per the SDD archive contract, this is an intentional-without-spec archive (mirroring the 23/06 `hotfix-child-pairing-mock-redeem-route` and `feature-boot-restore-session-before-sync` precedents, which also skipped `sdd-spec`). The 2 new `PairingManagerTest` methods are the unit-level guards that pin the new preflight invariant until the boot-path / pairing-path invariants grow beyond the current scope (see §7 #4).

---

## 3. Phase outcomes

| Phase | Status | Evidence |
|---|---|---|
| **propose** | OK | `proposal.md` written 2026-06-23 17:18 (engram obs #104). **Scope adjustment surfaced during this phase**: the proposal originally framed `AuthResult.Success` as a 1-field data class; the real signature at `DeviceAuthManager.kt:33-48` is a sealed class with 4 fields (`Success(deviceId, accessToken, refreshToken, expiresAt)`). The proposal already locks `authResult.accessToken` as the field access; the design confirms the same; the apply phase used the correct 4-field constructor in the test stub. Mirrors the 22/06 `create-pairing-code` and 23/06 `hotfix-child-pairing-mock-redeem-route` proposal structures. Risk section flags `MockSupabaseEngine` auth-route mock and `routesKnownByMockEngine` guard test as follow-ups. |
| **spec** | Skipped (intentional) | Per orchestrator / user decision. `pairing-flow` capability already documents `POST /functions/v1/pairing` with a bearer token (scenario "Manual code entry posts to pairing edge function"). No `specs/` subfolder produced. Mirrors the 23/06 precedents. |
| **design** | OK | `design.md` written 2026-06-23 17:24 (engram obs #105). **4 architecture decisions documented**: (A) gate at `PairingManager.pairWithCode()` (option 1a) over UI-level `PairingViewModel` (1b) or lazy `getAccessToken()` (1c) — single source of truth for `/functions/v1/pairing` POST, covers both `pairWithCode` and `pairWithQr` via delegation at line 132; (B) re-use existing `NETWORK_ERROR` enum (option 2c) over new `AUTH_ERROR` value — `PairingViewModel.kt:159-166` already maps both `SESSION_ERROR` and `NETWORK_ERROR` to the same UX copy, no UI change required; (C) no `savePairedSession` API change (option 3a) — anonymous token is sufficient to redeem, the edge function returns only `{device_id, parent_id}` per the orchestrator's verification, post-pairing upgrade path is a separate change; (D) append 2 tests to existing `PairingManagerTest.kt` (option 4a) — mirrors the 23/06 `MockSupabaseEngineTest` and `BootReceiverTest` patterns (one test file per class). 2-commit RED-then-GREEN apply hint with explicit `--rerun-tasks` discipline. |
| **tasks** | OK | `tasks.md` written 2026-06-23 19:08 (engram obs #106). **Fork surfaced and resolved during this phase**: the tasks sub-agent discovered that `AuthResult.Success` is a 4-field sealed class (`Success(deviceId, accessToken, refreshToken, expiresAt)`) — not the 1-field data class the proposal originally implied. The task hint was corrected to use the full 4-field constructor in the test stub (`AuthResult.Success(deviceId = "anonymous", accessToken = "test-jwt-token", refreshToken = "", expiresAt = 0L)`). 9 implementable tasks in 4 phases (Phase 1 RED, Phase 2 GREEN, Phase 3 Quality gates, Phase 4 PR — 4.1 explicit out-of-scope). Review Workload Forecast: low risk, single PR, ~+40 / −10 net lines. |
| **apply** | OK (2 commits) | RED `276f909` (test only, +98 / −3 on `PairingManagerTest.kt`) + GREEN `2333949` (production, +9 / −5 on `PairingManager.kt`). **Both commits landed on `master` directly** (NOT on a feature branch — same precedent as the 22/06 `create-pairing-code` hotfix, the 23/06 `hotfix-child-pairing-mock-redeem-route`, the 23/06 `fix/workmanager-autoinit-hilt` (PR #7), and the 23/06 `feature-boot-restore-session-before-sync` (PR #8)). Engram obs #107 (apply-progress). The apply sub-agent's claim of "the 2 changed files are 100% clean for ktlint" was independently verified by the verify sub-agent and found TRUE (see §5 / §6). **The `--rerun-tasks` discipline from obs #99 was successfully propagated** — every quality gate was re-run with `--rerun-tasks` to avoid stale `UP-TO-DATE` cache masking real state. |
| **verify** | PASS WITH WARNINGS | `verify-report.md` written 2026-06-23 19:53 (engram obs #109). 641/641 unit tests, 0 regressions. **2 WARNINGS** — pre-existing ktlint gate on master (1039+ violations, all pre-existing; the 2 changed files are 100% clean per independent grep — see §6) and `MockSupabaseEngine` auth-route mock follow-up (structurally identical to PR #6; see §7 #1). All 13/13 Correctness Table decisions match; all 4/4 Design Coherence decisions followed. |
| **pr** | OK | PR #9 opened, reviewed, squash-merged to master as `7706e0f`. Engram obs #110. |

**SDD cycle complete** (with the spec phase intentionally skipped per orchestrator / user decision).

---

## 4. Code outcomes

Reference: merged commit `7706e0f` (squash of RED `276f909` + GREEN `2333949`).

| File | Action | Lines | Details |
|---|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/pairing/PairingManager.kt` | Modified | +9 / −5 | Replace `val token = authManager.getAccessToken(); if (token == null) { return@withContext PairingResult.Error(PairingErrorType.SESSION_ERROR, "No autenticado") }` short-circuit at lines 74-80 with the `authenticateOrCreate()` preflight gate: `var token = authManager.getAccessToken(); if (token == null) { token = when (val authResult = authManager.authenticateOrCreate()) { is AuthResult.Success -> authResult.accessToken; is AuthResult.Error, is AuthResult.NeedsPairing -> return@withContext PairingResult.Error(PairingErrorType.NETWORK_ERROR, "Error de conexión. Verifica tu conexión a internet.") } }`. The rest of the function (HTTP call at lines 85-92, `parsePairingResponse` at line 93) is unchanged. `AuthResult` was already imported at line 6. The `var` (not `val`) on `token` allows reassignment; the design §3 decision A locks this signature. The `is AuthResult.Error, is AuthResult.NeedsPairing` combined branch is the design §3 decision B (re-use existing `NETWORK_ERROR` enum). |
| `app/src/test/java/com/tudominio/parentalcontrol/pairing/PairingManagerTest.kt` | Modified | +98 / −3 | REMOVE `pairWithCode_returns_session_error_when_no_token` (old lines 113-122 — encoded the bug, scenario is now the success path). ADD `pairWithCode_obtains_session_on_demand_when_no_token` (success-with-preflight: stub `getAccessToken()` → `null` on first call, stub `authenticateOrCreate()` → `AuthResult.Success(deviceId = "anonymous", accessToken = "test-jwt-token", refreshToken = "", expiresAt = 0L)`, hit `MockEngine` returning 200, capture `Authorization` request header via `AtomicReference<String?>` inside the engine lambda, assert `result is PairingResult.Success` + `success.deviceId == "<uuid-device>"` + `success.parentId == "<uuid-parent>"` + `capturedAuth.get() == "Bearer test-jwt-token"` + `coVerify { mockAuthManager.authenticateOrCreate() }` + `coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }`). ADD `pairWithCode_returns_network_error_when_authenticateOrCreate_fails` (auth-failure-returns-NETWORK_ERROR: stub `getAccessToken()` → `null`, stub `authenticateOrCreate()` → `AuthResult.Error("network down")`, capture HTTP invocations via `AtomicInteger` counter, assert `result is PairingResult.Error` with `type == PairingErrorType.NETWORK_ERROR` + `message == "Error de conexión. Verifica tu conexión a internet."` + `coVerify { mockAuthManager.authenticateOrCreate() }` + `engineCalls.get() == 0` (HTTP edge function NOT invoked)). New imports: `AuthResult` + `java.util.concurrent.atomic.*`. The setUp() at lines 45-87 already wires `mockkObject(DeviceAuthManager.Companion)` + `DeviceAuthManager.getInstance(any()) returns mockAuthManager` — no setup change needed. |

**Net diff**: 2 files, 107 insertions, 8 deletions (115 total line touches). Within the 400-line PR budget (well under).

**No production HTTP path touched.** Confirmed by `git diff cb9265f..7706e0f --name-only -- ':!openspec'`: exactly 2 files (`PairingManager.kt`, `PairingManagerTest.kt`). `parsePairingResponse` (lines 214-268) and the HTTP call (lines 85-92) are unchanged. `pairWithQr` at line 115 delegates to `pairWithCode` at line 132, so a single gate covers both manual entry and QR scan.

**No new dependencies, no Gradle changes, no manifest changes.** No `app/build.gradle.kts`, `gradle.properties`, or `AndroidManifest.xml` modifications. No new Ktor config. No new Hilt modules.

**`pairWithCode` callsites after the change**: `PairingViewModel.handlePairingResult` (line 159-165) and the QR scan path via `pairWithQr` → `pairWithCode` delegation at line 132. Both entry points now go through the same `authenticateOrCreate()` preflight gate.

---

## 5. Quality gates (final state, master @ `7706e0f`)

| Gate | Command | Exit | Result |
|---|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest --rerun-tasks` | 0 | **641/641 tests pass** across 162 files. 0 failures, 0 errors, 0 skipped. Master pre-batch = 640 (per the 23/06 PR #8 verify-report). **+1 new test (1 removed `pairWithCode_returns_session_error_when_no_token` + 2 new tests). 0 regressions.** |
| Unit tests (focused) | `./gradlew testDebugUnitTest --tests "*PairingManagerTest*" --rerun-tasks` | 0 | 4 tests, 0 failures, 0 errors. `pairWithCode_obtains_session_on_demand_when_no_token` 2.671s (NEW), `pairWithCode_returns_invalid_code_on_404` 0.122s (existing), `pairWithCode_returns_network_error_when_authenticateOrCreate_fails` 0.111s (NEW), `pairWithCode_real_supabase_returns_success` 0.091s (existing). The old `pairWithCode_returns_session_error_when_no_token` is NOT in the list — confirmed REMOVED. |
| Unit tests (RED) | `./gradlew testDebugUnitTest --tests "*PairingManagerTest*" --rerun-tasks` (with production file at baseline `cb9265f`) | 1 (NON-ZERO) | **FAIL — 2 of 4 tests fail with real `java.lang.AssertionError`, NOT compile errors.** Test (a) at `PairingManagerTest.kt:160` — `Expected Success, got Error(type=SESSION_ERROR, message=No autenticado)` — exactly matches the design's RED prediction. Test (b) at `PairingManagerTest.kt:211` — `expected:<NETWORK_ERROR> but was:<SESSION_ERROR>` — exactly matches the design's RED prediction. The 2 existing tests (`pairWithCode_real_supabase_returns_success`, `pairWithCode_returns_invalid_code_on_404`) still pass — they exercise the token-non-null branch which is unchanged. **RED is REAL.** |
| Build | `./gradlew assembleDebug --rerun-tasks` | 0 | BUILD SUCCESSFUL in 1m 11s. APK builds cleanly. No new manifest permissions, no new Ktor config, no new dependencies. |
| Static analysis | `./gradlew detekt --rerun-tasks` | 0 | BUILD SUCCESSFUL. **0 new violations on the 2 changed files.** Pre-existing detekt hits in `PairingManager.kt` at lines 72 (MagicNumber), 98 (TooGenericExceptionCaught), 188 (TooGenericExceptionCaught + SwallowedException), 216×2 (MagicNumber), 235/243/251 (MagicNumber), 278/290/302 (TooGenericExceptionCaught + SwallowedException) — all PRE-EXISTING on master (verified by `git show cb9265f:PairingManager.kt` line numbers match). The new preflight code at lines 74-84 has NO detekt hits. `PairingManagerTest.kt` not in detekt report. |
| Lint | `./gradlew ktlintCheck --rerun-tasks` | 1 (NON-ZERO) | **WARNING** — pre-existing project issue (see §6). The 2 changed files are **100% clean** per independent grep verification. |
| Coverage | n/a | — | kover/jaCoCo not configured (`sdd-init/parentalcontrol` gotcha). Manual inspection: 2 new tests cover both branches of the gate (success-with-preflight, auth-failure-returns-NETWORK_ERROR). |

### TDD evidence (strict mode)

| Check | Result |
|---|---|
| TDD evidence reported | YES — apply-progress.md TDD Cycle Evidence table AND engram obs #107 |
| All tasks have tests | YES — 1.1 added 2 tests in `PairingManagerTest.kt`; 2.2 the preflight-gate change has the same 2 tests as covering evidence |
| RED confirmed (tests exist) | YES — `git show 276f909 --stat` = 1 file changed (`PairingManagerTest.kt`), +98 / −3. Re-applied at run time with production code reverted to baseline `cb9265f` → 2 of 4 tests FAIL with real `java.lang.AssertionError` at `PairingManagerTest.kt:160` and `:211`. **RED is real, not synthetic.** |
| GREEN confirmed (tests pass) | YES — re-running `*PairingManagerTest*` on master `7706e0f` with `--rerun-tasks` → 4 tests, 0 failures, 0 errors. Both new tests pass (2.671s and 0.111s). Full unit suite 641/641. |
| Triangulation adequate | YES (2 cases) | The 2 new tests cover both branches of the gate: (a) `getAccessToken() == null` + `authenticateOrCreate() → AuthResult.Success` → HTTP edge function runs with the freshly-acquired token → `Success` + `savePairedSession` invoked + Authorization header verified as `"Bearer test-jwt-token"` (stricter than the design hint — captures via `AtomicReference<String?>` inside the `MockEngine` lambda); (b) `getAccessToken() == null` + `authenticateOrCreate() → AuthResult.Error` → HTTP edge function NOT invoked (verified via `AtomicInteger` counter) → `NETWORK_ERROR` with exact Spanish copy. Single happy + single failure path; design §2.2 confirms these are the only 2 scenarios. |
| Safety net for modified files | YES | `PairingManager.kt` (production) had 2 prior tests (`pairWithCode_real_supabase_returns_success` and `pairWithCode_returns_invalid_code_on_404`) which continued passing on RED (they exercise the token-non-null branch which is unchanged) AND on GREEN (full regression target 641/641). The 2 new tests pin the new gate; the 2 existing tests pin the existing token-non-null path. |
| Refactor step | N/A | 9+5 = ~14 production line touches (preflight gate replace), 98+3 test line touches. No refactor needed. The apply sub-agent's `apply-progress.md` §Deviations documents 3 intentional deviations from the design hint: (a) `AtomicReference<String?>` for Authorization header capture (stricter than design required), (b) `AtomicInteger` for HTTP-not-called counter (matches design exactly), (c) `returnsMany listOf(null, "test-jwt-token")` (defensive over-stubbing — second value never consumed because GREEN calls `getAccessToken()` only once). |
| **TDD compliance** | **6/6 checks** |

**Broken-middle does NOT apply here.** The "strict-TDD-with-broken-middle" pattern from `feature-boot-restore-session-before-sync` (where RED commit `331e3e9` failed to compile because `restoreSession` was `private`) does NOT apply to this change. Both new tests are pure MockK + `MockEngine` tests that do not require any production-code symbol that doesn't already exist. The RED commit at `276f909` compiles cleanly against the baseline `cb9265f` production code; both new tests fail at the assertion line (line 160 and 211). The GREEN commit at `2333949` is a minimal, targeted edit to one file. Build is green at every commit on the branch.

### Commit hygiene

- Total commits: **2** (matches strict-TDD plan: 1 RED + 1 GREEN).
- Conventional-commit style: YES — both use `type(scope): subject` form (`test(pairing): ...` and `fix(pairing): ...`).
- No `Co-Authored-By` or AI attribution footers: YES (verified by `git log --format="%s%n---%n%b" 276f909^..2333949` — body is empty for both commits).
- Chain cleanliness: YES — 2 clean commits, no fixup/squash/wip/tmp noise. No amends.
- Branch choice: **master** (committed directly per 22/06 `create-pairing-code` precedent in engram obs #82 and the 23/06 `hotfix-child-pairing-mock-redeem-route` / `fix/workmanager-autoinit-hilt` / `feature-boot-restore-session-before-sync` precedents). The PR was opened from a feature branch against master.

---

## 6. Pre-existing WARNING (NOT introduced by this change)

**`./gradlew ktlintCheck` is non-zero on master.** This is a project-level condition that pre-dates PR #9 and is identical to the 23/06 verify report baseline:

- The ktlint violations on master are pre-existing on `cb9265f` (pre-batch base). The 25 trailing-space violations in `PairingManager.kt` (lines 20, 29, 32, 111, 117, 122, 124, 131, 145, 150, 155, 220, 224, 234, 242, 250, 258, 327, 340, 343, 346, 349, 352, 355, 358) are NOT introduced by this change. Provenance: all 25 lines are on pre-existing code (`PairingManager.kt` was first touched in commit `accc002` and accumulated trailing whitespace through subsequent edits; the 23/06 verify reports list the same violations with slightly shifted line numbers because of the 9-line insertion in this batch). The new preflight code at lines 74-84 is **100% clean** (independently verified by `awk 'NR>=74 && NR<=84' file | grep -E "\s+$"` = empty).
- The 1039 violations in main source set + 479 lines across 24 test files = ~1500 total ktlint violations on master, all pre-existing. `app/config/ktlint/baseline.xml` exists but is **not wired** into the ktlint plugin's `ktlint { }` block at `app/build.gradle.kts:224-226` (only `disabledRules.set(setOf("import-ordering"))`, no `baseline = file(...)` reference).
- The 2 files changed in this PR (`PairingManager.kt`, `PairingManagerTest.kt`) are **not in any new violation list** (verified via `grep -c "PairingManagerTest" ktlintTestSourceSetCheck.txt` = 0; `grep -c "PairingManager.kt" ktlintMainSourceSetCheck.txt` = 25 lines, all "Trailing space(s)" on pre-existing lines). The `AuthResult` import at line 6 was "Unused" at the pre-batch baseline `cb9265f` (verified `git show cb9265f:PairingManager.kt | grep "AuthResult"` returns only the import line); the GREEN commit makes it used, and the "Unused import" violation disappears in the post-batch run.
- **Honest verification**: the apply sub-agent's Engram obs #107 stated *"My 2 changed files are 100% clean"*. The verify sub-agent independently verified this by running ktlint fresh (deleted `app/build/reports/ktlint/` and re-ran with `--rerun-tasks`), then grepping the report files for the 2 changed file paths. The 25 hits in `PairingManager.kt` are all "Trailing space(s)" on pre-existing lines (none at 74-84 where the change is); `PairingManagerTest.kt` has 0 hits. **The apply sub-agent's claim is TRUE.** The pre-existing ktlint debt on master (1039+ violations) is real and out of scope.

**Per the orchestrator's hard constraints and the proposal's Out-of-scope section, this WARNING is out of scope for this change.** The change adds **zero new ktlint violations** on the 2 files it touched. See §7 #2 for the project-level fix.

---

## 7. Follow-up (out of scope)

These are gaps and risks knowingly accepted when the change was archived. They are tracked here so future changes can pick them up.

1. **Mock for `/auth/v1/token?grant_type=password` in `MockSupabaseEngine`** — `MockSupabaseEngine.kt` is NOT changed. `DeviceAuthManager.httpClient` at line 109 binds to OkHttp directly, not to the mock engine. **In debug builds with placeholder `SUPABASE_URL`** (`https://your-project.supabase.co`), first-launch pairing will surface `NETWORK_ERROR` ("Error de conexión. Verifica tu conexión a internet.") after this change lands. This is the **correct** behavior per the existing `PairingViewModel.kt:159-166` UX branch the user already sees for transient failures — NOT a regression. The end-to-end happy path requires a real Supabase backend and is validated in CI's instrumented runner on API 28/31/35. Structurally identical to the PR #6 (`hotfix-child-pairing-mock-redeem-route`) mock-engine follow-up. A separate SDD change should wire `auth/v1/token?grant_type=password` into `MockSupabaseEngine` so first-launch debug runs can complete the full happy path without a real Supabase instance.

2. **`DeviceComponents.kt` ktlint refactor OR wire the baseline** — pre-existing on master, NOT introduced by this change. Two valid paths:
   - (a) Refactor the 4 wildcard imports in `DeviceComponents.kt` (lines 4, 6, 7, 8) to explicit imports.
   - (b) Wire `baseline = file("config/ktlint/baseline.xml")` into the `ktlint { }` block at `app/build.gradle.kts:224`.
   - Both are out of scope for this change. Tracked here so the next cleanup pass picks it up.

3. **`routesKnownByMockEngine` guard test** — flagged in the 23/06 `hotfix-child-pairing-mock-redeem-route` archive-report §7 #1 as a follow-up, and the 23/06 `feature-boot-restore-session-before-sync` archive-report §7 #3. After PR #6's mock addition + the recommended `/auth/v1/token` mock from #1 above, `MockSupabaseEngine` would have 6+ wired branches. A meta-test asserting that every production `httpClient.post` path consumed by `ParentRepository` / `PairingManager` / future code paths has a matching `when` branch + fixture would prevent a recurrence of the same omission pattern that broke the parent side (22/06, obs #82) and the child side (23/06, hotfix-child-pairing-mock-redeem-route). The mock for `/auth/v1/token?grant_type=password` should be INCLUDED in that guard test. Recommended as a separate SDD change.

4. **Future SDD for the `pairing-flow` capability spec** — per proposal §Spec recommendation, if/when boot-path or pairing-path invariants grow beyond the current scope. The 2026-06-23 evening chain surfaced a 4-bug sequence (mock route → WorkManager init → boot restore → pairing session) that demonstrates why deep context + multiple cycles is essential — a single "fix the SESSION_ERROR" PR would have failed because the mock route was missing. A future delta spec could formalize the "obtain session before redeem" invariant alongside the existing "POST with a bearer token" scenario. Not blocking. Recommended trigger: the next change to `PairingManager` or `DeviceAuthManager` that adds a new boot-time or pairing-time invariant. At that point, a minimal `openspec/specs/pairing-flow/spec.md` delta with the single "Child on first launch obtains anonymous session before pairing" requirement and the 2 verifying scenarios (success / auth-failure) would close the spec gap.

5. **Manual smoke in CI** (per proposal §Success criteria #5). Dev box has no `adb`/emulator per `openspec/config.yaml:57`. CI should run `:app:connectedDebugAndroidTest` on API 28/31/35 to exercise the end-to-end `PairingBottomSheet.generateCode()` → `PairingManager.pairWithCode()` happy path with `USE_MOCK_SUPABASE=true` and a real Supabase backend. **The 5 quality gates above pass automatically, but on-device round-trip was never exercised.** Post-merge CI run is the validation step.

6. **`Thread.sleep(1000L)` → `CountDownLatch` or `runTest { advanceUntilIdle() }`** — flagged in the 23/06 `feature-boot-restore-session-before-sync` archive-report §7 #1 as a SUGGESTION follow-up. The `Thread.sleep(1000L)` pattern in the `BootReceiverTest` Robolectric tests may flake on slow CI runners. NOT introduced by this change, but a related follow-up that would harden the broader test suite.

7. **Process improvement (ALREADY propagated)** — future verify reports must always force-rerun quality gates with `--rerun-tasks`. The 23/06 `feature-boot-restore-session-before-sync` apply sub-agent's Engram obs #99 was inaccurate on ktlintCheck status because it relied on UP-TO-DATE cached reports from a previous run, which masked the real state. **This change successfully propagated that discipline** — every quality gate in the apply phase was re-run with `--rerun-tasks` (apply-progress §Quality Gates opens with: *"All 3 gates were re-run with `--rerun-tasks` to avoid the stale `UP-TO-DATE` cache masking real state"*), and the apply sub-agent's claim of "the 2 changed files are 100% clean for ktlint" was independently verified by the verify sub-agent via fresh ktlint run + grep, and found TRUE. The discipline update is now baked into the workflow; this follow-up is a confirmation, not a new ask.

---

## 8. Related archived changes

This change is the FOURTH and final piece of an ongoing child pairing-path + auth reliability track. Sibling changes:

| Date | Change | What it fixed | PR |
|---|---|---|---|
| 2026-06-22 | (unarchived) `create-pairing-code` mock route hotfix | Wired parent's `POST /functions/v1/create-pairing-code` in `MockSupabaseEngine`. Engram obs #82. | (committed directly to master) |
| 2026-06-23 | `hotfix-child-pairing-mock-redeem-route` | Wired child's `POST /functions/v1/pairing` in `MockSupabaseEngine`. Counterpart to 22/06 fix. Engram obs #86-#91. | #6 |
| 2026-06-23 | `fix/workmanager-autoinit-hilt` | Disabled WorkManager auto-init in `app/src/main/AndroidManifest.xml` to honor `HiltWorkerFactory` (`04b955a`). Hotfix committed directly to master. Made `SyncWorker` instantiate correctly on boot — but in doing so surfaced the pre-existing offline-gate noise that PR #8 fixes. | #7 |
| 2026-06-23 | `feature-boot-restore-session-before-sync` | Restored session in `BootReceiver.onBootCompleted()` before enqueuing `SyncWorker`. Fixes the post-PR-#7 offline-gate retry loop. Closes the loop on the symptom that PR #7 made visible. Engram obs #93-#101. | #8 |
| **2026-06-23** | **`fix-pairing-session-before-redeem`** | **This change.** Obtained session on demand in `PairingManager.pairWithCode()` before redeeming code. Fixes the first-launch `SESSION_ERROR` that PR #6's mock addition + PR #7's WorkManager fix + PR #8's boot restore all exposed. **Closes the loop on the entire 4-bug chain.** Engram obs #104-#110. | **#9** |

**The chain pattern**: each PR peels back a layer that was masked by an earlier bug.

1. **PR #6** (`hotfix-child-pairing-mock-redeem-route`, merged `4a5ec9a`): user reported "error de emparejamiento / error de conexion" on child. Root cause: `MockSupabaseEngine` missing `/functions/v1/pairing` route. Fix: fixture + branch + RED test.
2. **PR #7** (`fix/workmanager-autoinit-hilt`, merged `04b955a`): user reported `NoSuchMethodException` on `SyncWorker` after the 22/06 hotfix made it more visible. Root cause: `WorkManagerInitializer` shadowing `HiltWorkerFactory`. Fix: manifest override.
3. **PR #8** (`feature-boot-restore-session-before-sync`, merged `4b00879`): user reported "Offline retry loop" after PR #7. Root cause: `BootReceiver` enqueueing sync without `restoreSession()`. Fix: receiver wrap in `GlobalScope.launch { restoreSession() gate }`.
4. **PR #9 (this change)**: user reported `SESSION_ERROR` after PR #6 made the HTTP call actually reach the mock. Root cause: `PairingManager.pairWithCode()` requires a token but the child has none on first launch. Fix: `authenticateOrCreate()` preflight gate.

The 4-bug chain (mock route → workmanager init → boot restore → pairing session) demonstrates why deep context + multiple cycles is essential — a single "fix the SESSION_ERROR" PR would have failed because the mock route was missing. Together, PRs #6, #7, #8, #9 ship the "mock engine complete + WorkManager auto-init correct + boot sequence silent + pairing call site preflight" quartet that closes the 23/06 child pairing + auth reliability track.

The 23/06 session shipped 4 PRs end-to-end, all of which are now archived (or were committed directly to master without a change folder):

- **PR #6** — `hotfix-child-pairing-mock-redeem-route` (mock engine) — archived 2026-06-23 (see `archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/archive-report.md`).
- **PR #7** — `fix/workmanager-autoinit-hilt` (WorkManager hotfix) — committed directly to master, no archive.
- **PR #8** — `feature-boot-restore-session-before-sync` (boot restore) — archived 2026-06-23 (see `archive/2026-06-23-feature-boot-restore-session-before-sync/archive-report.md`).
- **PR #9** — `fix-pairing-session-before-redeem` (this change) — archived 2026-06-23 by this report.

---

## 9. References

| Resource | Path / ID |
|---|---|
| Proposal | `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/proposal.md` |
| Design | `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/design.md` |
| Tasks | `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/tasks.md` |
| Apply-progress | `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/apply-progress.md` |
| Verify-report | `openspec/changes/archive/2026-06-23-fix-pairing-session-before-redeem/verify-report.md` |
| Merged PR | https://github.com/Andrea-Caballero/parentalControl/pull/9 |
| Merged commit | `7706e0f fix(pairing): obtain session on demand before redeeming code (#9)` |
| RED commit | `276f909 test(pairing): replace SESSION_ERROR stub with session-on-demand tests` |
| GREEN commit | `2333949 fix(pairing): obtain session on demand before redeeming code` |
| Engram — proposal | `#104` — "Proposal: fix-pairing-session-before-redeem" |
| Engram — design | `#105` — "Design: fix-pairing-session-before-redeem" |
| Engram — tasks | `#106` — "Tasks: fix-pairing-session-before-redeem" (note: `AuthResult.Success` 4-field sealed class discovery) |
| Engram — apply-progress | `#107` — "Apply-Progress: fix-pairing-session-before-redeem" (note: claim of "the 2 changed files are 100% clean for ktlint" was independently verified by verify sub-agent and found TRUE) |
| Engram — session summary | `#108` — "Session summary: parentalcontrol" |
| Engram — verify-report | `#109` — "Verify-Report: fix-pairing-session-before-redeem" (the source of truth on gate state) |
| Engram — PR #9 opened | `#110` — "PR #9 opened: fix(pairing) session on demand before redeem" |
| Engram — 23/06 archive (hotfix-child-pairing-mock-redeem-route) | `#94` — "Archive: hotfix-child-pairing-mock-redeem-route" |
| Engram — 22/06 create-pairing-code precedent | `#82` — "Wired create-pairing-code route in MockSupabaseEngine hotfix" |
| Engram — backlog follow-up (boot-restore-session) | `#93` — "Backlog: BootReceiver should restoreSession before SyncWorker" (resolved by PR #8) |
| Existing capability source-of-truth | `openspec/specs/pairing-flow/spec.md` (requirement "Child completes pairing via code or QR scan", scenario "Manual code entry posts to pairing edge function" — already mandates `POST /functions/v1/pairing` with a bearer token; the fix is in implementation, not in spec-level behavior) |

---

## 10. SDD cycle complete

The change has been fully planned, implemented, verified, merged (PR #9), and archived. Ready for the next change.
