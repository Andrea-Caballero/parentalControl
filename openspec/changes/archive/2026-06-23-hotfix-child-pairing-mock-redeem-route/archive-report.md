# Archive Report: hotfix-child-pairing-mock-redeem-route

**Change name**: `hotfix-child-pairing-mock-redeem-route`
**Archived on**: 2026-06-23
**Archived to**: `openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/`
**Archive mode**: `openspec` (filesystem, with engram mirror for cross-session recovery)
**Mode**: Strict TDD (2 commits: RED `5cd437a` + GREEN `c444464`)
**Persistence**: openspec + Engram hybrid
**PR**: #6 — https://github.com/Andrea-Caballero/parentalControl/pull/6
**Merged commit SHA**: `4a5ec9a` (squash merge of `5cd437a` + `c444464`)
**Status**: ✅ COMPLETE (PASS WITH WARNINGS — pre-existing ktlint gate, see §6)

---

## 1. Outcome summary

The child-side `POST /functions/v1/pairing` route is now wired in `MockSupabaseEngine`. After PR #6, debug builds with `USE_MOCK_SUPABASE=true` return 200 with `device_id` and `parent_id` from the new fixture, and `PairingManager.pairWithCode()` no longer falls into the `else` branch's 404. This is the child-side counterpart to the 2026-06-22 hotfix (engram obs #82) that wired the parent's `POST /functions/v1/create-pairing-code` route. Together, both sides of the pairing flow are end-to-end testable against the mock engine.

### What was archived

```
openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/
├── archive-report.md          ← this file
├── proposal.md                ← proposal (4.0 KB, 47 lines)
├── design.md                  ← technical design (7.0 KB, 56 lines)
├── tasks.md                   ← task list (6.0 KB, 77 lines)
└── verify-report.md           ← verification report (18.6 KB, 172 lines)
```

No `specs/` subdirectory — the change has no spec delta (see §2).

### Source of truth

**No main spec was modified.** The change is a mock-engine infra fix; the production HTTP path is untouched and the existing `pairing-flow` capability already documents the `POST /functions/v1/pairing` requirement. The `openspec/specs/` tree is unchanged by this archive.

---

## 2. Spec sync

**NO spec delta — explicit decision.** This change did not run `sdd-spec` (skipped per orchestrator). The proposal documents the reasoning:

> `pairing-flow` spec | Unchanged | Existing "Child completes pairing via code or QR scan" requirement already mandates `POST /functions/v1/pairing`; no delta spec required.

Verification:

- The change folder `openspec/changes/hotfix-child-pairing-mock-redeem-route/` contained only `proposal.md`, `design.md`, `tasks.md`, `verify-report.md` — no `specs/` subdirectory.
- `openspec/specs/` was inspected: no `pairing-flow/spec.md` modifications were required, none were made.
- The 4 success criteria in the proposal §Success criteria explicitly require "pairing-flow spec unchanged; no delta spec produced". Verify-report §Spec Compliance Matrix confirms 4/4 success criteria compliant.

Per the SDD archive contract, this is an intentional-with-warnings (or rather, intentional-without-spec) archive. The `pairing-flow` capability is the source of truth for the wire contract, and the new test (`MockSupabaseEngineTest.pairing POST returns device_id and parent_id response shape`) is the engine-level guard that keeps the mock layer in sync with that contract.

---

## 3. Phase outcomes

| Phase | Status | Evidence |
|---|---|---|
| **propose** | ✅ OK | `proposal.md` written 2026-06-23 09:36 (engram obs #86). Mirrors the 22/06 `create-pairing-code` hotfix structure. Risk section flags `routesKnownByMockEngine` guard test as follow-up. |
| **spec** | ⏭️ Skipped (intentional) | Per orchestrator; `pairing-flow` capability already documents `POST /functions/v1/pairing`. No `specs/` subfolder produced. |
| **design** | ✅ OK | `design.md` written 2026-06-23 09:43 (engram obs #87). 3 architecture decisions (success-only fixture, single-dispatch `when`, alphabetical ordering). 2-commit RED-then-GREEN apply hint. |
| **tasks** | ✅ OK | `tasks.md` written 2026-06-23 09:47 (engram obs #88). Review Workload Forecast: low risk, single PR, 30–50 lines. All 10 implementable tasks marked complete. |
| **apply** | ✅ OK (2 commits) | RED `5cd437a` (test only, 35 insertions) + GREEN `c444464` (fixture + 2-line `when` branch). Engram obs #89 (apply-progress). |
| **verify** | ✅ PASS WITH WARNINGS | `verify-report.md` written 2026-06-23 10:34 (engram obs #90). 638/638 tests, 0 regressions. Single WARNING is pre-existing ktlint gate (see §6). |
| **pr** | ✅ OK | PR #6 opened, reviewed, squash-merged to master as `4a5ec9a`. Engram obs #91. |

**SDD cycle complete** (with the spec phase intentionally skipped per orchestrator).

---

## 4. Code outcomes

Reference: merged commit `4a5ec9a` (squash of RED `5cd437a` + GREEN `c444464`).

| File | Action | Lines | Details |
|---|---|---|---|
| `app/src/main/assets/mock-supabase/pairing.json` | New | 3 lines (1 effective) | Fixture: `{"device_id":"device-child-emulator-001","parent_id":"parent-uuid-aaaa-bbbb-cccc"}`. Body starts with `{`, so `MockSupabaseEngine.kt:81-85` maps it to `HttpStatusCode.OK` automatically. |
| `app/src/main/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngine.kt` | Modified | +2 lines | New `when` branch at lines 76–77: `path.endsWith("/functions/v1/pairing") -> readAsset("mock-supabase/pairing.json")`. Inserted alphabetically between `get-templates` (lines 73–75) and `/rest/v1/time_requests` (line 78), honoring the `/functions/v1/`-then-`/rest/v1/` super-grouping convention. |
| `app/src/test/java/com/tudominio/parentalcontrol/data/remote/MockSupabaseEngineTest.kt` | Modified | +35 lines (1 new test) | `MockSupabaseEngineTest.pairing POST returns device_id and parent_id response shape` (lines 198–221). 3 behavioral assertions: `response.status.value == 200`, body contains `"device_id"`, body contains `"parent_id"`. Drives the production `httpClient.post` against the real Ktor `MockEngine` (the engine IS the SUT — not mocked). |

**Net diff**: 3 files, 41 insertions, 0 deletions. Within the 400-line PR budget (well under).

**No production HTTP path touched.** Confirmed by `git diff --stat c18e6b0..master`: no changes to `ParentRepository`, `PairingManager`, `PairingViewModel`, `PairingScreen`, `NetworkModule`, or `SupabaseClientProvider`.

**No new dependencies, no Gradle changes, no manifest changes.** No `app/build.gradle.kts`, `gradle.properties`, or `AndroidManifest.xml` modifications.

**Engine state after the change**: `MockSupabaseEngine.kt:68-82` has 5 wired routes in alphabetical order:
1. `create-pairing-code` (parent)
2. `get-devices-for-parent` (parent)
3. `get-templates` / `/rest/v1/templates` (parent)
4. **`pairing`** (child) ← NEW
5. `/rest/v1/time_requests` (parent)

Plus the `else` branch for genuinely unknown paths (returns 404 `{"error":"unknown route $path"}`).

---

## 5. Quality gates (final state, master @ `4a5ec9a`)

| Gate | Command | Exit | Result |
|---|---|---|---|
| Unit tests | `./gradlew testDebugUnitTest --rerun-tasks` | 0 | **638/638 tests pass** across 162 files. 0 failures, 0 errors, 0 skipped. Master pre-batch = 637. **+1 new test, 0 regressions.** |
| Build | `./gradlew assembleDebug` | 0 | BUILD SUCCESSFUL in 27s. APK builds cleanly. |
| Static analysis | `./gradlew detekt` | 0 | BUILD SUCCESSFUL in 12s (UP-TO-DATE on rerun). No new violations on the 2 changed files. |
| Lint | `./gradlew ktlintCheck` | 1 (NON-ZERO) | **WARNING** — pre-existing project issue (see §6). |
| Coverage | n/a | — | kover/jaCoCo not configured (`sdd-init/parentalcontrol` gotcha). |

### TDD evidence (strict mode)

| Check | Result |
|---|---|
| TDD evidence reported | YES — apply-progress obs #89 (TDD Cycle Evidence table) |
| All tasks have tests | YES — 1/1 new test written |
| RED confirmed (tests exist) | YES — `git show 5cd437a --stat` = 1 file changed (test only), 35 insertions. Re-applied at run time → FAILS with `expected:<200> but was:<404>` at `MockSupabaseEngineTest.kt:207`. **RED is real, not synthetic.** |
| GREEN confirmed (tests pass) | YES — full suite 638/638. |
| Triangulation adequate | N/A — single wire-shape contract (200 + device_id + parent_id). 3 assertions cover it. Error-shape coverage (404/409/410) lives in `parsePairingResponse` unit tests. |
| Safety net for modified files | YES — 5 prior `MockSupabaseEngineTest` tests all continued passing. |
| Refactor step | N/A — 2-line additive change. |
| **TDD compliance** | **6/6 checks** |

---

## 6. Pre-existing WARNING (NOT introduced by this change)

**`./gradlew ktlintCheck` is non-zero on master.** This is a project-level condition that pre-dates PR #6:

- The 4 wildcard-import violations in `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt` (lines 4, 6, 7, 8) were introduced in commit `accc002` (initial commit) and last touched in commit `c18e6b0` (22/06 fix-ui). **Neither is in this batch.**
- The same 4 lines fail on the `c18e6b0` baseline (pre-batch). `app/config/ktlint/baseline.xml` exists but is **not wired** into the ktlint plugin's `ktlint { }` block at `app/build.gradle.kts:224` (no `baseline = file(...)` line).
- The 2 files changed in this PR (`MockSupabaseEngine.kt`, `MockSupabaseEngineTest.kt`) are **not in any violation list** (verified via `grep` on the ktlint report). Path-normalized diff between this PR's ktlint report and master's report = **empty** (zero new violations).
- Main source set: 1040 violations total (all pre-existing). Test source set: 479 violations across 24 files (all pre-existing).

**Per the orchestrator's hard constraints and the proposal's Out-of-scope section, this WARNING is out of scope for this hotfix.** The change adds **zero new ktlint violations** on the 2 files it touched. See §7 follow-up #2 for the project-level fix.

---

## 7. Follow-up (out of scope)

These are gaps and risks knowingly accepted when the change was archived. They are tracked here so future changes can pick them up.

1. **`routesKnownByMockEngine` guard test** — flagged in proposal §Risks. After this hotfix, `MockSupabaseEngine` has 5 wired branches. A meta-test asserting that every production `httpClient.post` path consumed by `ParentRepository`, `PairingManager`, or any future repository has a matching `when` branch + fixture would prevent a recurrence of the same omission pattern that broke the parent side (22/06, obs #82) and the child side (23/06, this hotfix). Recommended as a separate SDD change. Trivial scope: 1 new test file + 1 `Set<String>` constant on the engine.

2. **`DeviceComponents.kt` ktlint refactor OR wire the baseline** — pre-existing on master, NOT introduced by this change. Two valid paths:
   - (a) Refactor the 4 wildcard imports in `DeviceComponents.kt` to explicit imports.
   - (b) Wire `baseline = file("config/ktlint/baseline.xml")` into the `ktlint { }` block at `app/build.gradle.kts:224`.
   - Both are out of scope for this hotfix. Tracked here so the next cleanup pass picks it up.

3. **Backlog: `BootReceiver` should `restoreSession()` before enqueuing `SyncWorker` on `BOOT_COMPLETED`** (engram obs #93, topic_key `backlog/boot-restore-session-before-sync`). Logcat evidence from 2026-06-23 session — after PR #7 (WorkManager auto-init) landed, the user's `SyncWorker` correctly instantiated and ran, then logged `Offline, reintentando...` with `tags={SyncWorker, after_boot}`. Without `restoreSession()` first, `clientProvider.connectionState` is still `DISCONNECTED` (default in `SupabaseClientProvider.kt:88`), and the sync short-circuits to `SyncResult.Offline` + WorkManager retry loop until the user opens the app. **User chose backlog over immediate fix on 2026-06-23 — re-evaluate next session or when the boot retry loop becomes a UX complaint.** Two valid approaches: (a) move `restoreSession()` earlier in `BootReceiver` (preferred — small, local); (b) gate the work enqueue on auth presence at boot time.

4. **Manual smoke in CI** (per tasks.md §5 / proposal §Success criteria). Dev box has no `adb`/emulator per `openspec/config.yaml:57`. CI should run `:app:connectedDebugAndroidTest` on API 28/31/35 to exercise the end-to-end `PairingBottomSheet.generateCode()` → `PairingManager.pairWithCode()` happy path with `USE_MOCK_SUPABASE=true`. The 4 validation gates above pass automatically, but on-device round-trip was never exercised.

---

## 8. Related archived changes

This hotfix is part of an ongoing mock-engine + auth reliability track. Sibling changes:

| Date | Change | What it fixed | PR |
|---|---|---|---|
| 2026-06-17 | `wire-pairing-and-approval-end-to-end` | Replaced parent/child mocks with real Supabase calls; QR rendering; outbox drain; parent app-block UI. 9 commits. | (merged via chained PRs) |
| 2026-06-19 | `hotfix-parent-auth-session` | (earlier auth reliability work) | — |
| 2026-06-20 | `hotfix-parent-auth-cta-reload` | Parent "Iniciar sesión como padre" CTA now reloads devices on auth success. Gradle reads `USE_MOCK_SUPABASE` from `local.properties` under `debug`. | #5 |
| 2026-06-22 | (unarchived) `create-pairing-code` mock route hotfix | Wired parent's `POST /functions/v1/create-pairing-code` in `MockSupabaseEngine`. Predecessor of this change. Engram obs #82. | (committed directly to master) |
| **2026-06-23** | **`hotfix-child-pairing-mock-redeem-route`** | **This change.** Wired child's `POST /functions/v1/pairing` in `MockSupabaseEngine`. Counterpart to 22/06 fix. | **#6** |

Together, the 22/06 + 23/06 hotfixes close the mock-engine gap for the pairing flow end-to-end. The 22/06 fix unblocked the parent side; this change unblocks the child side. The `routesKnownByMockEngine` guard test (follow-up #1) is the natural next step to prevent a third occurrence.

---

## 9. References

| Resource | Path / ID |
|---|---|
| Proposal | `openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/proposal.md` |
| Design | `openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/design.md` |
| Tasks | `openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/tasks.md` |
| Verify-report | `openspec/changes/archive/2026-06-23-hotfix-child-pairing-mock-redeem-route/verify-report.md` |
| Merged PR | https://github.com/Andrea-Caballero/parentalControl/pull/6 |
| Merged commit | `4a5ec9a fix(mock): wire /functions/v1/pairing route in MockSupabaseEngine (#6)` |
| RED commit | `5cd437a test(p): add failing roundtrip for /functions/v1/pairing mock route` |
| GREEN commit | `c444464 fix(mock): wire /functions/v1/pairing route in MockSupabaseEngine` |
| Engram — proposal | `#86` — "Proposal: hotfix-child-pairing-mock-redeem-route" |
| Engram — design | `#87` — "Design: hotfix-child-pairing-mock-redeem-route" |
| Engram — tasks | `#88` — "Tasks: hotfix-child-pairing-mock-redeem-route" |
| Engram — apply-progress | `#89` — "Apply-Progress: hotfix-child-pairing-mock-redeem-route" |
| Engram — verify-report | `#90` — "Verify-Report: hotfix-child-pairing-mock-redeem-route" |
| Engram — PR #6 opened | `#91` — "PR #6 opened: fix(mock) wire /functions/v1/pairing" |
| Engram — 22/06 create-pairing-code precedent | `#82` — "Wired create-pairing-code route in MockSupabaseEngine hotfix" |
| Engram — backlog follow-up | `#93` — "Backlog: BootReceiver should restoreSession before SyncWorker" |

---

## 10. SDD cycle complete

The change has been fully planned, implemented, verified, archived, and merged to master. Ready for the next change.
