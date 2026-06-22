package com.tudominio.parentalcontrol.data.remote

import androidx.test.core.app.ApplicationProvider
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the [MockSupabaseEngine] and the fixture JSON it serves (T5 of
 * `hotfix-parent-auth-session`).
 *
 * Per the abort condition in tasks.md §"Sequencing Concerns": if the
 * fixture JSON shape doesn't match what `ParentRepository` parses, STOP —
 * the fixture contract must match the parser, not vice versa. These tests
 * pin the wire-shape contract: the JSON in `app/src/main/assets/mock-supabase/`
 * is parseable by the same `DeviceDto` / `TimeRequestDto` shapes that
 * `ParentRepository` already uses for the real Supabase endpoints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MockSupabaseEngineTest {

    private lateinit var engine: MockSupabaseEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        engine = MockSupabaseEngine(context)
    }

    @Test
    fun `devices fixture parses as at least 2 child device rows`() = runTest {
        val devices = engine.devices()

        assertNotNull("devices payload must not be null", devices)
        assertTrue(
            "fixture must contain >= 2 devices per spec scenario, got ${devices.size}",
            devices.size >= 2
        )
        val first = devices.first()
        assertNotNull("id must not be null", first.id)
        assertNotNull("device_name must not be null", first.device_name)
        assertNotNull("last_seen_at must not be null", first.last_seen_at)
        // device_state defaults to ACTIVE in the parser; the fixture must use
        // a value the parser can map (ACTIVE, LOCKED, DOWNTIME).
        assertTrue(
            "device_state must be ACTIVE/LOCKED/DOWNTIME, got ${first.device_state}",
            first.device_state.uppercase() in setOf("ACTIVE", "LOCKED", "DOWNTIME")
        )
    }

    @Test
    fun `pending requests fixture contains at least one PENDING request`() = runTest {
        val requests = engine.pendingRequests()

        assertNotNull("pending requests must not be null", requests)
        assertTrue(
            "fixture must contain >= 1 pending request, got ${requests.size}",
            requests.size >= 1
        )
        val first = requests.first()
        assertEquals("PENDING", first.status.uppercase())
        assertNotNull("id must not be null", first.id)
        assertNotNull("device_id must not be null", first.device_id)
        assertTrue(
            "minutes_requested must be > 0, got ${first.minutes_requested}",
            first.minutes_requested > 0
        )
    }

    @Test
    fun `templates fixture contains at least one policy template`() = runTest {
        val templates = engine.templates()

        assertNotNull("templates must not be null", templates)
        assertTrue(
            "fixture must contain >= 1 template per spec scenario, got ${templates.size}",
            templates.size >= 1
        )
        val first = templates.first()
        assertNotNull("templateId must not be null", first.templateId)
        assertNotNull("name must not be null", first.name)
        assertNotNull("policyJson must not be null", first.policyJson)
    }

    @Test
    fun `MockSupabaseEngine exposes httpClient that responds to fixture paths`() = runTest {
        val client = engine.httpClient

        // The engine must respond to the paths the real ParentRepository
        // hits. We exercise the three documented paths and assert the
        // response is a 2xx with a non-empty body.
        val devicesResponse = client.get("/functions/v1/get-devices-for-parent")
        assertTrue(
            "devices path must return 2xx, got ${devicesResponse.status}",
            devicesResponse.status.value in 200..299
        )
        assertTrue(
            "devices body must be non-empty",
            devicesResponse.bodyAsText().isNotBlank()
        )

        val pendingResponse = client.get(
            "/rest/v1/time_requests?select=*&status=eq.PENDING&order=created_at.desc"
        )
        assertTrue(
            "pending path must return 2xx, got ${pendingResponse.status}",
            pendingResponse.status.value in 200..299
        )
        assertTrue(
            "pending body must be non-empty",
            pendingResponse.bodyAsText().isNotBlank()
        )

        val templatesResponse = client.get("/functions/v1/get-templates")
        assertTrue(
            "templates path must return 2xx, got ${templatesResponse.status}",
            templatesResponse.status.value in 200..299
        )
        assertTrue(
            "templates body must be non-empty",
            templatesResponse.bodyAsText().isNotBlank()
        )
    }

    /**
     * Regression: `MockSupabaseEngine.when` block must route
     * `POST /functions/v1/create-pairing-code` to the pairing-code fixture.
     * Without this branch the parent's "Generar código" call returns HTTP 404
     * in debug builds (`USE_MOCK_SUPABASE=true`), the ViewModel never sets
     * `_pairingCode`, and `PairingBottomSheet` step 2 renders empty. See
     * `openspec/changes/wire-pairing-and-approval-end-to-end` PR 2 (the mock
     * engine was added in commit `2d17041` AFTER PR 1 wired the repository
     * call — the fixture case was simply forgotten in that PR).
     */
    @Test
    fun `create-pairing-code POST returns pairing code response shape`() = runTest {
        val client = engine.httpClient

        val response = client.post("/functions/v1/create-pairing-code") {
            contentType(ContentType.Application.Json)
            setBody(
                "{\"device_name\":\"Test\",\"age_band\":\"6-8\",\"ttl_minutes\":15}"
            )
        }

        assertEquals(
            "create-pairing-code must return 200, got ${response.status}",
            200,
            response.status.value
        )

        val body = response.bodyAsText()
        assertTrue(
            "body must contain \"code\" field, got: $body",
            body.contains("\"code\"")
        )
        assertTrue(
            "body must contain \"expires_at\" field, got: $body",
            body.contains("\"expires_at\"")
        )
        assertTrue(
            "body must contain \"deeplink\" field, got: $body",
            body.contains("\"deeplink\"")
        )

        // Extract the deeplink value (lenient: find first "deeplink":"..." run).
        val deeplinkMatch = Regex("\"deeplink\"\\s*:\\s*\"([^\"]+)\"").find(body)
        assertNotNull("deeplink value must be present, got: $body", deeplinkMatch)
        val deeplink = deeplinkMatch!!.groupValues[1]
        assertTrue(
            "deeplink must start with parentalcontrol://pair?code=, got: $deeplink",
            deeplink.startsWith("parentalcontrol://pair?code=")
        )
    }
}
