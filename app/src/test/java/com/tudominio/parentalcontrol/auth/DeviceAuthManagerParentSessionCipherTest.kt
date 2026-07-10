package com.tudominio.parentalcontrol.auth

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RED→GREEN tests for **WARNING-1 (W1) — Cipher the parent session at rest**
 * (`openspec/changes/feat-cross-device-pairing-and-approval/verify-report.md`).
 *
 * Before this slice, `DeviceAuthManager.persistParentSession` at
 * `DeviceAuthManager.kt:606-614` (Slice A master) wrote `access_token` and
 * `refresh_token` as cleartext SharedPreferences strings alongside
 * `parent_id` + `role`. The child anonymous-auth path uses
 * `persistSession(StoredSession)` + `encryptWithKeystore` to write a single
 * encrypted `encrypted_session` blob (see `DeviceAuthManager.kt:714-721`).
 * This divergence meant parent tokens — including the JWT that carries the
 * `parent_id` claim — sat on disk in cleartext, recoverable via `adb backup`
 * on rooted devices or `run-as` on a debuggable build.
 *
 * This file pins the encrypted-at-rest contract with three RED tests. The
 * fix mirrors the child path: serialize `ParentSession` to JSON, encrypt
 * with `encryptWithKeystore`, write as a single `encrypted_parent_session`
 * blob, and decrypt on `loadPersistedState`.
 *
 * # Robolectric Keystore caveat
 *
 * Robolectric 4.10.3 (this project's JCA provider is BouncyCastle, NOT
 * the Android Keystore) does not provide `KeyStore.getInstance(
 * "AndroidKeyStore")`, which `encryptWithKeystore` requires via
 * `getOrCreateAuthKey()`. See `DeviceAuthManagerColdStartTest` kdoc lines
 * 36–43 for the long explanation. The two round-trip tests below work
 * around this by spying the `internal` `encryptWithKeystore` /
 * `decryptWithKeystore` methods with a deterministic base64 passthrough —
 * that exercises the exact production write-and-read code paths while
 * sidestepping the cipher that the JVM unit-test runtime cannot supply.
 * The third test deliberately writes a tampered blob to verify the
 * decryption-failure branch.
 *
 * # Test cases
 *
 * - **PSC-1** `persistParentSession_writes_encrypted_blob_not_plaintext_access_token_in_prefs`:
 *   invoking `verifyMagicLinkOtp` happy path must produce
 *   `encrypted_parent_session` (encrypted blob) and must NOT have any
 *   `access_token` or `refresh_token` key in cleartext under
 *   `device_auth_prefs`.
 * - **PSC-2** `loadPersistedState_after_persistParentSession_restores_parent_session_via_decrypt`:
 *   write via `persistParentSession` (round-trip via spied encrypt/decrypt),
 *   simulate process death, then a cold-start manager's `getAccessToken()`
 *   must equal the original.
 * - **PSC-3** `loadPersistedState_with_corrupted_encrypted_blob_returns_null_no_throw`:
 *   pre-populate prefs with a `role=PARENT`, `parent_id=<UUID>`,
 *   `encrypted_parent_session=<tampered string>` triple. Cold-start manager
 *   must not throw, and `getParentId()` must return null (decryption
 *   failure path wipes parent-specific keys so the user lands on the
 *   sign-in screen).
 *
 * Mirrors the red→green→refactor approach used by
 * `DeviceAuthManagerColdStartTest` (three test cases pinning the
 * cold-start invariant via the same spy-based pattern).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeviceAuthManagerParentSessionCipherTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate (mirror DeviceAuthManagerMagicLinkTest.setUp).
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        resetManagerInstance()
    }

    private fun resetManagerInstance() {
        val field = DeviceAuthManager::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun prefs() =
        context.getSharedPreferences("device_auth_prefs", Context.MODE_PRIVATE)

    private fun newRealManagerForSpy(): DeviceAuthManager {
        val ctor = DeviceAuthManager::class.java
            .getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(context) as DeviceAuthManager
    }

    private fun injectHttpClient(manager: DeviceAuthManager, client: HttpClient) {
        val field = DeviceAuthManager::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        field.set(manager, client)
    }

    /**
     * PSC-1 (RED on master `@ b56cc9c`): the write side of the encrypted
     * parent blob contract.
     *
     * Before the fix, `persistParentSession` writes `access_token` +
     * `refresh_token` as cleartext. The test asserts:
     *  - `access_token` and `refresh_token` are NOT present in
     *    `device_auth_prefs` (cleartext invariant).
     *  - `encrypted_parent_session` IS present and contains the
     *    parentId / accessToken / refreshToken (encrypted blob invariant).
     *  - `role` is `PARENT` and `parent_id` equals the original UUID
     *    (per the existing magic-link verify contract pinned by
     *    `DeviceAuthManagerMagicLinkTest.verifyMagicLinkOtp_validToken`).
     *
     * The `encryptWithKeystore` method is spied to base64 the input —
     * that lets the test verify that the blob contains the parent's
     * session data without depending on the real Android Keystore
     * (which Robolectric can't instantiate; see class kdoc).
     */
    @Test
    fun persistParentSession_writes_encrypted_blob_not_plaintext_access_token_in_prefs() = runBlocking {
        val parentId = "550e8400-e29b-41d4-a716-446655440000"
        val accessToken = "jwt-access-token-cipher-test"
        val refreshToken = "jwt-refresh-token-cipher-test"
        val tokenHash = "token-hash-cipher"
        val email = "[email protected]"

        val real = newRealManagerForSpy()
        val spy = spyk(real, recordPrivateCalls = false)
        // Spy `encryptWithKeystore` (internal, mirrors `restoreSession` visibility)
        // with a deterministic base64 passthrough. Lets the test verify
        // the encrypted blob's contents without depending on the real
        // Android Keystore (Robolectric 4.10.3 limitation).
        every { spy.encryptWithKeystore(any()) } answers {
            val input = firstArg<String>()
            Base64.encodeToString(input.toByteArray(), Base64.NO_WRAP)
        }

        // Ktor MockEngine returns a valid verify response. The path mirrors
        // `DeviceAuthManagerMagicLinkTest.verifyMagicLinkOtp_validToken`.
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/verify")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token":"$accessToken",
                          "refresh_token":"$refreshToken",
                          "expires_in":3600,
                          "user":{
                            "id":"$parentId",
                            "email":"$email",
                            "app_metadata":{"parent_id":"$parentId"}
                          }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = ByteReadChannel("{}"), status = HttpStatusCode.InternalServerError)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        injectHttpClient(spy, client)

        val result = spy.verifyMagicLinkOtp(tokenHash, email)

        assertTrue(
            "verifyMagicLinkOtp must succeed so persistParentSession runs. Got: $result",
            result.isSuccess
        )

        // Assertion 1: NO cleartext access_token key.
        assertNull(
            "access_token must NOT be written as cleartext under device_auth_prefs " +
                "(W1 contract — WARNING-1 closure). Keys present: ${prefs().all.keys}",
            prefs().getString("access_token", null)
        )
        // Assertion 2: NO cleartext refresh_token key.
        assertNull(
            "refresh_token must NOT be written as cleartext under device_auth_prefs " +
                "(W1 contract). Keys present: ${prefs().all.keys}",
            prefs().getString("refresh_token", null)
        )
        // Assertion 3: encrypted_parent_session MUST be present.
        assertTrue(
            "encrypted_parent_session MUST be present after persistParentSession " +
                "(W1 contract). Keys present: ${prefs().all.keys}",
            prefs().contains("encrypted_parent_session")
        )
        // Assertion 4: the encrypted blob, when decrypted via the spied
        // cipher, must contain the parentId, accessToken, and refreshToken.
        val encryptedBlob = prefs().getString("encrypted_parent_session", null)
        assertNotNull("encrypted_parent_session value is non-null", encryptedBlob)
        val decryptedJson = String(Base64.decode(encryptedBlob!!, Base64.NO_WRAP))
        assertTrue(
            "Encrypted blob must contain accessToken. Decoded: $decryptedJson",
            decryptedJson.contains(accessToken)
        )
        assertTrue(
            "Encrypted blob must contain refreshToken. Decoded: $decryptedJson",
            decryptedJson.contains(refreshToken)
        )
        assertTrue(
            "Encrypted blob must contain parentId. Decoded: $decryptedJson",
            decryptedJson.contains(parentId)
        )
        // Assertion 5: parent_id + role cleartext keys (NOT secret, used by
        // getParentId() and the existing clean-cutover UUID check).
        assertEquals(
            "parent_id cleartext key is preserved at $parentId",
            parentId,
            prefs().getString("parent_id", null)
        )
        assertEquals(
            "role=PARENT cleartext key is preserved",
            "PARENT",
            prefs().getString("role", null)
        )
    }

    /**
     * PSC-2 (RED on master `@ b56cc9c`): the round-trip side of the
     * encrypted parent blob contract.
     *
     * After `persistParentSession` writes the encrypted blob, a cold-start
     * manager's `getAccessToken()` must equal the original access token
     * (and `getParentId()` must equal the parentId). This pins the
     * `loadPersistedState` decrypt + populate the way the existing
     * `DeviceAuthManagerColdStartTest.init_with_valid_encrypted_session_populates_accessToken`
     * pins the `encrypted_session` (child) restore path.
     *
     * `encryptWithKeystore` and `decryptWithKeystore` are both spied to
     * a base64 round-trip so the real write-and-read code paths run end
     * to end inside the JVM test (without the real Android Keystore).
     */
    @Test
    fun loadPersistedState_after_persistParentSession_restores_parent_session_via_decrypt() = runBlocking {
        val parentId = "550e8400-e29b-41d4-a716-446655440000"
        val accessToken = "jwt-round-trip-access-token-xyz"
        val refreshToken = "jwt-round-trip-refresh-token-xyz"
        val tokenHash = "token-hash-round-trip"
        val email = "[email protected]"

        val real = newRealManagerForSpy()
        val spy = spyk(real, recordPrivateCalls = false)
        every { spy.encryptWithKeystore(any()) } answers {
            Base64.encodeToString(firstArg<String>().toByteArray(), Base64.NO_WRAP)
        }
        every { spy.decryptWithKeystore(any()) } answers {
            String(Base64.decode(firstArg<String>(), Base64.NO_WRAP))
        }

        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/auth/v1/verify")) {
                respond(
                    content = ByteReadChannel(
                        """
                        {
                          "access_token":"$accessToken",
                          "refresh_token":"$refreshToken",
                          "expires_in":3600,
                          "user":{
                            "id":"$parentId",
                            "email":"$email",
                            "app_metadata":{"parent_id":"$parentId"}
                          }
                        }
                        """.trimIndent()
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            } else {
                respond(content = ByteReadChannel("{}"), status = HttpStatusCode.InternalServerError)
            }
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }
        injectHttpClient(spy, client)

        // Write the encrypted blob via the production write path.
        val result = spy.verifyMagicLinkOtp(tokenHash, email)
        assertTrue(
            "verifyMagicLinkOtp must succeed for the round-trip setup. Got: $result",
            result.isSuccess
        )

        // Sanity: encrypted_parent_session is present in prefs.
        assertNotNull(
            "Sanity: encrypted_parent_session must be persisted before cold start",
            prefs().getString("encrypted_parent_session", null)
        )

        // Simulate process death so the next getInstance() builds a fresh
        // manager and re-runs `init { loadPersistedState() }`.
        resetManagerInstance()

        // Cold start: a new manager must restore the parent session via decrypt.
        val coldStart = DeviceAuthManager.getInstance(context)
        assertEquals(
            "Cold start must restore the original accessToken via the encrypted blob",
            accessToken,
            coldStart.getAccessToken()
        )
        assertEquals(
            "Cold start must restore the parentId (mirrors getParentId for the " +
                "encrypted parent path)",
            parentId,
            coldStart.getParentId()
        )
    }

    /**
     * PSC-3 (RED on master `@ b56cc9c`): the decryption-failure branch.
     *
     * Pre-populate prefs with role=PARENT + parent_id=<UUID> +
     * encrypted_parent_session=<tampered garbage string>. A cold-start
     * manager must NOT throw and must wipe the parent-specific keys so
     * `getParentId()` returns null (the user re-authenticates on the next
     * interaction rather than seeing a stale PAIRED state with no token).
     *
     * Mirrors `DeviceAuthManagerColdStartTest.init_with_undecryptable_encrypted_session`
     * for the child path (`encrypted_session`).
     */
    @Test
    fun loadPersistedState_with_corrupted_encrypted_blob_returns_null_no_throw() = runBlocking {
        val realParentId = "550e8400-e29b-41d4-a716-446655440000"
        prefs().edit()
            .putString("role", "PARENT")
            .putString("parent_id", realParentId)
            .putString("encrypted_parent_session", "not_valid_base64!@#")
            .commit()

        // Cold start: must NOT throw, must wipe parent_id so the user
        // sees the sign-in screen on next interaction.
        val coldStart = DeviceAuthManager.getInstance(context)

        assertNull(
            "Corrupted encrypted_parent_session must NOT crash loadPersistedState. " +
                "After the decrypt-failed path the wipe must leave parent_id=null " +
                "so getParentId() returns null. " +
                "Keys after cold start: ${prefs().all.keys}",
            coldStart.getParentId()
        )
        // The corrupted blob key should also be cleared by the wipe so a
        // subsequent cold start doesn't repeatedly attempt + fail.
        assertTrue(
            "Corrupted blob must be cleared from prefs after the failure path. " +
                "Keys after cold start: ${prefs().all.keys}",
            !prefs().contains("encrypted_parent_session")
        )
    }
}
