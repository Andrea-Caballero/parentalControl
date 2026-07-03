# Proposal: feature-pluralize-empty-state-and-add-n-device-tests

> Mini-SDD lite. No `spec.md`, no `design.md`, no `verify-report.md`. Mirrors `archive/2026-07-02-fix-auth-session-restore-on-cold-start/` precedent. Renamed from `feature-multi-child-support` for honesty — the data layer is already 1:N; the real gap is one string + zero N-device tests.

## Why

Data layer is **already 1:N**: `devices.parent_id` non-unique (`001_initial_schema.sql:24-36`); RLS `devices_parent_select` returns N rows (`002_rls_policies.sql:37-41`); `ParentRepository.getDevices(): Result<List<ChildDevice>>` (`ParentRepository.kt:101`); `DashboardScreen` renders `LazyColumn { items(list) { ... } }` (`DashboardScreen.kt:354`); `app/src/main/assets/mock-supabase/devices.json` ships 3 devices (ACTIVE/DOWNTIME/LOCKED) — proves the gap.

**Empirical proof (2026-07-03, OPPO master APK)**: 3 cards in the LazyColumn (`/tmp/opencode/oppo_after.png`); fresh install shows singular "Empareja un dispositivo para comenzar" (`/tmp/opencode/oppo_fresh.png`). Real gap: (a) singular string at `DashboardScreen.kt:311`; (b) zero tests for N-device rendering.

## What changes

1. **1-line string change.** `DashboardScreen.kt:311`: `subtitle = "Empareja un dispositivo para comenzar"` → `subtitle = "Empareja uno o más dispositivos"`. Title, icon, Solicitudes empty state (`:380-384`), `+` FAB, bell icon untouched (Q3 scope).
2. **3 RED tests** in `DashboardScreenTest.kt` (or sibling). Seed `mockRepository.getDevices()` with 3 fixtures, render `DashboardScreen` in `Success`, assert: (a) `onAllNodesWithTag("device_card").assertCountEquals(3)`, (b) per-card name + model + badge label. May need `Modifier.testTag("device_card")` at `DeviceComponents.kt:36-39` (pattern at `:500`).
3. **2-commit TDD** per `openspec/config.yaml:strict_tdd: true`: test-only RED, then production+test GREEN.

## Why mini-SDD lite

Auth-fix precedent skipped spec/design for the same reason: **no spec-level behavioral change**. `openspec/specs/parent-device-list/spec.md:45-47` describes behavior (empty state + CTA), not literal Spanish. Spec at `:41-43` is silent on badge content; the 3 RED tests will pin it via the test suite, not spec delta. Single PR, well under 400-line budget.

## Scope

**In scope**: 1-line copy + 3 RED tests.
**Out of scope**: select-child picker, unpair, Solicitudes grouping/realtime, rename, `LazyColumn key = { it.id }` debt, Solicitudes empty-state copy, backend/schema/edge changes (none needed).

## Capabilities

- **New**: none.
- **Modified**: none. Spec contract unchanged.

## Affected areas

| Area | Impact | Description |
|---|---|---|
| `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreen.kt:311` | Modified | 1-line string. |
| `app/src/main/java/com/tudominio/parentalcontrol/ui/parent/components/DeviceComponents.kt:36-39` | Modified | Add `testTag("device_card")` for stable count assertion. |
| `app/src/test/java/com/tudominio/parentalcontrol/ui/parent/screens/DashboardScreenTest.kt` (or new sibling) | New / extended | +3 Robolectric tests. |
| `openspec/specs/parent-device-list/spec.md` | Unchanged | mini-SDD lite. |

## Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| `testTag` missing on `DeviceCard` today | Med | Add at `DeviceComponents.kt:36-39`; pattern at `:500`. |
| Badge copy regresses if `DeviceComponents.kt:179-208` AssistChip changes | Low | 3 RED tests pin labels. |
| Test fixture drift if `devices.json` edited | Low | Read JSON directly or import as constants. |

## Rollback

Two commits (test-only + production+test). `git revert` restores prior behavior.

## Success criteria

- [ ] RED: 3 new tests fail on `master` (count + name + model + badge).
- [ ] GREEN: 1-line string change flips all 3 to pass.
- [ ] Existing `DashboardScreenTest` (4 cases) stays green.
- [ ] `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` green.
- [ ] `./gradlew detekt` / `ktlintCheck` add no new violations on touched files.
- [ ] `parent-device-list/spec.md` UNCHANGED.

## Open questions

1. **Badge as formal spec?** Spec at `:41-43` is silent; tests will pin it. Formalizing as a spec delta is a separate future change.
2. **`testTag` on `DeviceCard`** — confirm `Modifier.testTag("device_card")` is acceptable; alternative is `onNodeWithText(...)`, less stable if localized.
3. **LazyColumn `key` stability** — pre-existing debt at `:354` (no `key = { it.id }`); out of scope.

## References

- Auth-fix precedent: `archive/2026-07-02-fix-auth-session-restore-on-cold-start/proposal.md` (mini-SDD lite, 2-commit RED/GREEN).
- Spec: `openspec/specs/parent-device-list/spec.md:41-47`.
- Bug surface: `DashboardScreen.kt:311` (subtitle), `:354` (LazyColumn).
- Fixture: `app/src/main/assets/mock-supabase/devices.json` (3 devices, 3 badge states).
- Test pattern: `DashboardScreenTest.kt:62-86` (Robolectric + mockk).
- Live evidence: `/tmp/opencode/oppo_after.png` + `/tmp/opencode/oppo_fresh.png`.
