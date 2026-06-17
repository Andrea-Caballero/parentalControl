package com.example.parentalcontrol.pairing

import android.content.Context
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [PairingManager.pairWithCode] (PR 1 task #6 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the real HTTP call goes to
 * `${SUPABASE_URL}/functions/v1/pairing` with the right headers and that the
 * response is parsed into [PairingResult.Success] when the edge function
 * returns 200 OK with `{ device_id, parent_id }`.
 */
class PairingManagerTest {

    private val context: Context = mockk(relaxed = true)
    private lateinit var mockAuthManager: DeviceAuthManager
    private lateinit var mockClientProvider: SupabaseClientProvider
    private lateinit var mockClient: HttpClient

    @Before
    fun setUp() {
        // Reset the PairingManager singleton so each test gets a fresh instance
        // bound to this test's mocks
        val instanceField = PairingManager::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        mockClient = HttpClient(
            MockEngine { request ->
                respond(
                    content = ByteReadChannel(
                        """{"device_id":"<uuid-device>","parent_id":"<uuid-parent>"}"""
                    ),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        mockAuthManager = mockk()
        every { mockAuthManager.getAccessToken() } returns "test-jwt-token"
        coEvery { mockAuthManager.savePairedSession(any(), any()) } returns Unit

        mockClientProvider = mockk()
        every { mockClientProvider.httpClient } returns mockClient

        // PairingManager.getInstance() builds private `authManager` and
        // `clientProvider` via the companion's getInstance() — substitute them.
        mockkObject(DeviceAuthManager.Companion)
        every { DeviceAuthManager.getInstance(any()) } returns mockAuthManager
        mockkObject(SupabaseClientProvider.Companion)
        every { SupabaseClientProvider.getInstance(any()) } returns mockClientProvider
    }

    @After
    fun tearDown() {
        mockClient.close()
        unmockkObject(DeviceAuthManager.Companion)
        unmockkObject(SupabaseClientProvider.Companion)
    }

    @Test
    fun pairWithCode_real_supabase_returns_success() = runTest {
        val manager = PairingManager.getInstance(context)
        // Avoid JVM `Build.MODEL` null in unit tests
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected Success, got $result", result is PairingResult.Success)
        val success = result as PairingResult.Success
        assertEquals("<uuid-device>", success.deviceId)
        assertEquals("<uuid-parent>", success.parentId)
        // Persistence side-effect: session is stored
        coVerify { mockAuthManager.savePairedSession("<uuid-device>", "<uuid-parent>") }
    }

    @Test
    fun pairWithCode_returns_session_error_when_no_token() = runTest {
        every { mockAuthManager.getAccessToken() } returns null
        val manager = PairingManager.getInstance(context)

        val result = manager.pairWithCode("ABCDEFGH")

        assertTrue("Expected SESSION_ERROR, got $result", result is PairingResult.Error)
        assertEquals(PairingErrorType.SESSION_ERROR, (result as PairingResult.Error).type)
    }

    @Test
    fun pairWithCode_returns_invalid_code_on_404() = runTest {
        val failingEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{"error":"not found"}"""),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val failingClient = HttpClient(failingEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { mockClientProvider.httpClient } returns failingClient

        val manager = PairingManager.getInstance(context)
        manager.deviceInfoProvider = {
            DeviceInfo(
                deviceName = "TestManufacturer TestModel",
                deviceModel = "TestModel",
                osVersion = "33",
                appVersion = "1.0.0",
                ageBand = null
            )
        }

        val result = manager.pairWithCode("DEADBEEF")

        assertTrue("Expected INVALID_CODE, got $result", result is PairingResult.Error)
        assertEquals(PairingErrorType.INVALID_CODE, (result as PairingResult.Error).type)
        failingClient.close()
    }
}
