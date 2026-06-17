package com.example.parentalcontrol.data.repository

import android.content.Context
import com.example.parentalcontrol.auth.DeviceAuthManager
import com.example.parentalcontrol.domain.model.DeviceState
import com.example.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.utils.EmptyContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ParentRepository.createPairingCode] (PR 1 task #5 of
 * `openspec/changes/wire-pairing-and-approval-end-to-end`).
 *
 * Verifies that the real HTTP call goes to
 * `${SUPABASE_URL}/functions/v1/create-pairing-code` with the right headers
 * and JSON body, and that the response is parsed into a
 * [com.example.parentalcontrol.viewmodel.PairingCodeResult].
 *
 * The Context is mocked because the [ParentRepository.createPairingCode]
 * method does not use it; Hilt injects a real ApplicationContext in
 * production.
 */
class ParentRepositoryTest {

    private lateinit var captured: MutableList<HttpRequestData>
    private lateinit var mockClient: HttpClient
    private lateinit var authManager: DeviceAuthManager
    private lateinit var clientProvider: SupabaseClientProvider
    private lateinit var context: Context
    private lateinit var repository: ParentRepository

    @Before
    fun setUp() {
        captured = mutableListOf()

        val mockEngine = MockEngine { request ->
            captured.add(request)
            respond(
                content = ByteReadChannel(PAIRING_RESPONSE_BODY),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        mockClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        authManager = mockk()
        every { authManager.getAccessToken() } returns "test-jwt-token"

        clientProvider = mockk()
        every { clientProvider.httpClient } returns mockClient

        context = mockk(relaxed = true)

        repository = ParentRepository(
            context = context,
            authManager = authManager,
            clientProvider = clientProvider
        )
    }

    @After
    fun tearDown() {
        mockClient.close()
    }

    companion object {
        private const val PAIRING_RESPONSE_BODY =
            """{"code":"ABCDEFGH","expires_at":"2026-06-04T12:10:00Z","deeplink":""" +
                """"parentalcontrol://pair?code=ABCDEFGH"}"""

        private const val DEVICES_RESPONSE_BODY = """[
            {
                "id":"dev-1",
                "device_name":"Galaxy S21",
                "device_model":"SM-G991B",
                "os_version":"34",
                "app_version":"1.0.0",
                "device_state":"ACTIVE",
                "policy_version":5,
                "last_seen_at":"2026-06-04T12:00:00Z"
            }
        ]"""
    }

    private fun requestBodyText(req: HttpRequestData): String {
        return when (val body = req.body) {
            is TextContent -> body.text
            EmptyContent -> ""
            else -> body.toString()
        }
    }

    @Test
    fun createPairingCode_posts_to_create_pairing_code_edge_function() = runTest {
        val result = repository.createPairingCode(
            deviceName = "S21",
            ageBand = "7-12",
            ttlMinutes = 10
        )

        assertTrue("Expected success, got $result", result.isSuccess)
        val code = result.getOrNull()
        assertNotNull("PairingCodeResult should not be null", code)
        assertEquals("ABCDEFGH", code!!.code)
        assertEquals("2026-06-04T12:10:00Z", code.expiresAt)
        assertEquals("parentalcontrol://pair?code=ABCDEFGH", code.deeplink)

        assertEquals(1, captured.size)
        val req = captured.first()
        assertEquals(HttpMethod.Post, req.method)
        val url = req.url.toString()
        assertTrue(
            "URL should contain /functions/v1/create-pairing-code, got $url",
            url.contains("/functions/v1/create-pairing-code")
        )
        assertEquals("Bearer test-jwt-token", req.headers[HttpHeaders.Authorization])
        assertEquals(SupabaseClientProvider.SUPABASE_ANON_KEY, req.headers["apikey"])
    }

    @Test
    fun createPairingCode_returns_failure_when_not_authenticated() = runTest {
        every { authManager.getAccessToken() } returns null

        val result = repository.createPairingCode("S21", "7-12", 10)

        assertTrue("Expected failure, got $result", result.isFailure)
        assertTrue(
            "Expected IllegalStateException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalStateException
        )
        assertTrue("Should not have made an HTTP call", captured.isEmpty())
    }

    @Test
    fun createPairingCode_returns_failure_on_non_2xx() = runTest {
        val failingEngine = MockEngine { _ ->
            respondError(HttpStatusCode.InternalServerError)
        }
        val failingClient = HttpClient(failingEngine)
        every { clientProvider.httpClient } returns failingClient

        val result = repository.createPairingCode("S21", "7-12", 10)

        assertTrue("Expected failure, got $result", result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull("Exception should not be null", ex)
        assertTrue(
            "Expected RuntimeException mentioning HTTP 500, got ${ex!!.message}",
            ex.message?.contains("HTTP 500") == true
        )

        failingClient.close()
    }

    @Test
    fun createPairingCode_sends_device_name_age_band_and_ttl_in_body() = runTest {
        repository.createPairingCode("GalaxyTab", "13-17", 15)

        assertEquals(1, captured.size)
        val body = requestBodyText(captured.first())
        assertTrue("Body should contain device_name, got: $body", body.contains("\"device_name\":\"GalaxyTab\""))
        assertTrue("Body should contain age_band, got: $body", body.contains("\"age_band\":\"13-17\""))
        assertTrue("Body should contain ttl_minutes, got: $body", body.contains("\"ttl_minutes\":15"))
    }

    /**
     * PR 2 task #12 of `wire-pairing-and-approval-end-to-end`.
     * Replaces the SharedPreferences/hardcoded mock in [ParentRepository.getDevices]
     * with a real HTTP call to the `get-devices-for-parent` edge function.
     */
    @Test
    fun getDevices_calls_get_devices_for_parent_with_jwt() = runTest {
        val devicesCaptured = mutableListOf<HttpRequestData>()
        val devicesEngine = MockEngine { request ->
            devicesCaptured.add(request)
            respond(
                content = ByteReadChannel(DEVICES_RESPONSE_BODY),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val devicesClient = HttpClient(devicesEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        every { clientProvider.httpClient } returns devicesClient

        try {
            val result = repository.getDevices()

            assertTrue(
                "Expected Result.success, got $result",
                result.isSuccess
            )
            val devices = result.getOrNull()
            assertNotNull("devices should not be null", devices)
            assertEquals(1, devices!!.size)

            val device = devices.first()
            assertEquals("dev-1", device.id)
            assertEquals("Galaxy S21", device.name)
            assertEquals("SM-G991B", device.model)
            assertEquals("1.0.0", device.appVersion)
            assertEquals(5, device.policyVersion)
            assertEquals(DeviceState.ACTIVE, device.state)
            assertEquals("2026-06-04T12:00:00Z", device.lastSeenAt)

            // Verify the wire request: POST /functions/v1/get-devices-for-parent with Bearer token
            assertEquals(1, devicesCaptured.size)
            val req = devicesCaptured.first()
            val url = req.url.toString()
            assertTrue(
                "URL should contain /functions/v1/get-devices-for-parent, got $url",
                url.contains("/functions/v1/get-devices-for-parent")
            )
            assertEquals("Bearer test-jwt-token", req.headers[HttpHeaders.Authorization])
            assertEquals(
                SupabaseClientProvider.SUPABASE_ANON_KEY,
                req.headers["apikey"]
            )
        } finally {
            devicesClient.close()
        }
    }
}
