# Apply Progress: fix-supabase-client-provider-legacy-mock-gate

## Phase 1 — SupabaseClientProvider flag plumbing (commit 259fade)

`SupabaseClientProvider.getInstance()` had a legacy `httpClient = HttpClient(OkHttp)`
that ignored `BuildConfig.USE_MOCK_SUPABASE`. Fixed by branching on the flag
and delegating to the same `MockEngine` `MockSupabaseEngine` builds.

## Phase 2 — DeviceAuthManager flag plumbing (commit 9a7b610)

`DeviceAuthManager` had its own PRIVATE `httpClient = HttpClient(OkHttp)` field
that ignored the flag entirely. Replaced with the same `BuildConfig.USE_MOCK_SUPABASE`
branch: mock → `MockSupabaseEngine`, real → `OkHttp` engine.

Also added `POST /auth/v1/token` route in `MockSupabaseEngine` and a fixture
`assets/mock-supabase/auth-anonymous.json` so `createAnonymousSession` can
exercise the full mock path during emulator testing.

## Phase 3 — MockSupabaseEngine ContentNegotiation (commit 7075459)

`MockSupabaseEngine.httpClient` was a plain `HttpClient(MockEngine)`, so
`response.body<SupabaseAuthResponse>()` threw `JsonConvertException` (no
serializer registered). Installed `ContentNegotiation { json(Json { ... }) }`
matching the real `SupabaseClientProvider` setup.

## Phase 4 — Android Keystore AES/GCM padding bug (commit f70c5a3)

**Discovered during integration testing on the OnePlus emulator (Android 16).**

Once phases 1-3 were in, the auth call reached the mock and `handleAuthSuccess`
was invoked — but `persistSession` → `encryptWithKeystore` threw
`InvalidKeyException: KeyStoreException: Incompatible padding mode` from
Keystore2 (`Error::Km(INCOMPATIBLE_PADDING_MODE)`).

Root cause: `KeyGenParameterSpec.Builder` for the auth key was created
without `setEncryptionPaddings(...)`. The Keystore silently defaulted to a
non-NoPadding padding, which is incompatible with the `AES/GCM/NoPadding`
cipher used in `encryptWithKeystore`. `KeyStore.getKey()` still returns the
key as a valid `SecretKey` instance, so the bug only surfaces on first
cipher init.

Two complementary fixes:

1. `KeyGenParameterSpec.Builder` now sets
   `.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)` so newly
   created keys match the cipher.
2. New `getOrCreateAuthKey()` helper that detects pre-existing bad keys
   via a test `cipher.init`: if it throws `InvalidKeyException`, the alias
   is deleted and a fresh one is created. Runs once per encrypt/decrypt
   but the validation cost is a single cipher init.

## Phase 5 — Mock pairing fixture shape (commit 6a9ab12)

After phase 4 unblocked `persistSession`, the pairing call reached the mock
and `parsePairingResponse` ran. The screen showed "Respuesta del servidor
inválida" because `extractDeviceId` / `extractParentId` / `extractError`
used regexes (`"device_id":"..."`) that only matched compact JSON, but the
fixture `assets/mock-supabase/pairing.json` was pretty-printed.

Two complementary fixes:

1. Minified `pairing.json` to match the regex as written.
2. Updated the three extraction regexes to tolerate `\s*:\s*` so the parser
   handles both compact (real Supabase) and pretty (mock fixture) JSON.

## Phase 6 — Cleanup (commit a367df6)

Removed the temporary `Log.e` and `Log.w` diagnostic logs that were added
in `createAnonymousSession` to surface the Keystore exception. The
informative `Log.w(TAG, "Auth key has incompatible parameters, recreating", e)`
in `getOrCreateAuthKey` is kept because it alerts on key migration in
production.

## Verification

- 648 tests pass, 0 failures, 0 errors (`./gradlew :app:testDebugUnitTest`).
- End-to-end on OnePlus emulator (Android 16):
  - App launches.
  - Tap "Soy el hijo" → "Ingresar código manualmente" → "ABCDEFGH".
  - Auth succeeds (mock returns the new auth-anonymous.json fixture, deserialized via ContentNegotiation, session persisted via Keystore after migration).
  - Pairing call returns 200 with `{"device_id":"device-child-emulator-001","parent_id":"..."}`.
  - Child dashboard renders with "App bloqueada" and "Podrás usarlo hasta las 17:14".

## Follow-ups (deferred)

- Original session deferred items still pending:
  - Spec/code drift: Heartbeat vs Reconciliation naming in the chain.
  - `design.md` Decision 2 retroactive edit.
  - Pre-existing ktlint violations (479 in 24 untouched test files).
- New follow-up discovered this session:
  - `PairingManager` still uses hand-rolled regex extraction instead of
    `kotlinx.serialization` (same pattern as the working
    `SupabaseAuthResponse` decode). Worth refactoring when a real
    `PairingResponse` data class is introduced.
  - `MockSupabaseEngine` could minify all fixture JSON responses centrally
    so any future regex consumer doesn't hit the same bug.