# Archive Report

**Change**: `fix-supabase-client-provider-legacy-mock-gate`
**Archived on**: 2026-06-24
**Spec location**: `openspec/specs/mock-supabase-legacy-gate/spec.md`
**Apply-progress**: `apply-progress.md` (preserved in archive)

## Summary

Eight commits across three sessions this week fixed the legacy `SupabaseClientProvider.getInstance()` to honor `BuildConfig.USE_MOCK_SUPABASE`, plus five follow-on bugs discovered during end-to-end emulator testing. The change is now feature-complete and verified; the spec merges into the main specs directory as a new capability.

## Commits archived

| SHA | Subject |
|-----|---------|
| `259fade` | fix(network): make legacy getInstance() honor BuildConfig.USE_MOCK_SUPABASE |
| `9a7b610` | fix(auth): route DeviceAuthManager through MockSupabaseEngine when USE_MOCK_SUPABASE=true |
| `7075459` | fix(mock): install ContentNegotiation in MockSupabaseEngine.httpClient |
| `f70c5a3` | fix(auth): set ENCRYPTION_PADDING_NONE on GCM key + migrate pre-fix keys |
| `6a9ab12` | fix(pairing): minify fixture + tolerate whitespace in JSON extraction |
| `a367df6` | chore(auth): remove temporary diagnostic logs from createAnonymousSession |
| `f2ac0fc` | docs(sdd): record apply-progress for fix-supabase-client-provider-legacy-mock-gate |
| `d8d6f37` | feat(enforcement): wire BlockOverlay "Pedir permiso" → ExtraTimeScreen (T28) |

## Spec merge

Delta: `openspec/changes/fix-supabase-client-provider-legacy-mock-gate/specs/mock-supabase-legacy-gate/spec.md`
Target: `openspec/specs/mock-supabase-legacy-gate/spec.md` (new file — no existing spec to merge into)

No conflicts; the new spec stands alone because no other spec covers the legacy `getInstance()` transport selection.

## Follow-ups deferred

See `verify-report.md` "Issues / Warnings" section. Top three:

1. Centralize JSON minification in `MockSupabaseEngine` (defensive for future regex consumers).
2. Refactor `PairingManager` extraction to `kotlinx.serialization` (consistency with `DeviceAuthManager`).
3. Pre-existing spec/code drift in `boot-worker-lifecycle` (Heartbeat vs Reconciliation). Not introduced by this change.

## Lessons learned (carried into Engram)

- A Service cannot mutate the Compose graph directly; deeplinks are the canonical Service→Activity navigation pattern. Topic: `architecture/service-to-activity-navigation`.
- Android Keystore silently defaults to PKCS7 padding for `KeyGenParameterSpec`; `setEncryptionPaddings(ENCRYPTION_PADDING_NONE)` is REQUIRED for `AES/GCM/NoPadding` cipher. Topic: `architecture/keystore-gcm-padding`.
- Hand-rolled JSON regex extraction is fragile when consumers and producers don't agree on whitespace. Topic: `pattern/json-extraction-fragility`.