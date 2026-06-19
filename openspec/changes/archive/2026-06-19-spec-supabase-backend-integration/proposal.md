# Proposal: spec-supabase-backend-integration

## Intent

`ParentRepository.kt` has 5 stub methods marked `TODO: llamar a supabase|Edge Function|cambiar estado`. Each returns hard-coded data or `true`. TODOs are not actionable — a future implementer must reverse-engineer intent.

This change is **documentation-only**. It captures WHEN/THEN scenarios for each stub **before** any Supabase code lands. TODOs stay untouched here; a future implementation change uses these specs as acceptance criteria and removes the TODOs.

Outcome: each TODO has a matching Requirement; one spec tells future implementers what every method must do. Strict TDD applies to that **future** change, not this one.

## Scope

### In Scope
- ONE new capability `supabase-backend-integration` → `openspec/specs/supabase-backend-integration/spec.md`.
- Requirements + WHEN/THEN scenarios for `getTemplates`, `grantReward`, `applyTemplate`, `lockDevice`, `unlockDevice`.
- Scenarios cover **observable behavior** only — no SDK or wire-protocol choices.

### Out of Scope
- Code change. TODOs stay.
- New deps, secrets, env config, network layer.
- 5 gates — no code touched.
- Removing TODOs (follow-up cleanup change).

## Capabilities

### New Capabilities
- `supabase-backend-integration`: contract for the 5 stubbed parent-side repository methods that will eventually call the remote backend.

### Modified Capabilities
- None.

## Approach

1. Map each TODO to a Requirement.
2. Each Requirement gets 1–3 WHEN/THEN scenarios: happy path, auth failure, server-side rejection where relevant.
3. Consolidate `lockDevice`/`unlockDevice` into one **device-state mutation** Requirement to fit the 650-word budget.
4. Scenarios stay tech-agnostic: no SDK, protocol, RLS, or JSON-key names.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `openspec/changes/spec-supabase-backend-integration/` | New | Change folder, contains `proposal.md`. |
| `openspec/specs/supabase-backend-integration/spec.md` | New at archive | Created by `sdd-archive`, not now. |
| `app/src/main/java/com/tudominio/parentalcontrol/data/repository/ParentRepository.kt` | **Untouched** | TODOs stay; cleanup is follow-up. |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Over-specify an SDK, lock future impl. | Med | Observable contract only; no SDK names, no wire format. |
| Drift if remote API changes. | Low | Scenarios cover what the app sees, not wire details. |
| 650-word budget tight for 5 methods. | Med | Consolidate lock/unlock; split only if first spec exceeds budget. |

## Rollback Plan

Doc-only. `git revert <archive-commit-sha>` removes the synced main spec, restores the change folder; `ParentRepository.kt` was never touched. Zero code risk.

## Success Criteria

- [ ] `proposal.md` exists at the expected path.
- [ ] New spec covers each of the 5 TODOs with ≥ 1 WHEN/THEN scenario each.
- [ ] No scenario names a specific SDK, protocol, or JSON key.
- [ ] No code files modified (`git diff --stat` from `61a6098` shows only `openspec/`).
- [ ] 5 gates still green (sanity — trivial).