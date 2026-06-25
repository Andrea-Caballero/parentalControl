package com.tudominio.parentalcontrol.data.remote

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ktor `HttpClient` backed by a [MockEngine] that serves fixture JSON from
 * `app/src/main/assets/mock-supabase/`.
 *
 * Per design §D6 of `openspec/changes/hotfix-parent-auth-session/design.md`:
 * the fixtures live in `assets/` (not in `test/resources/`) so the flag
 * works in production-debug builds, not only under unit tests. The wire
 * shapes match the [com.tudominio.parentalcontrol.data.repository.ParentRepository]
 * `DeviceDto` / `TimeRequestDto` parsers, so the same `getDevices()` /
 * `getPendingRequests()` code paths work against both the real Supabase
 * endpoints and the mock engine without any branching.
 *
 * The [TemplateFixture] shape is NEW (the existing `getTemplates()` returns
 * a hardcoded list). It exists for future code that wants to call
 * `/functions/v1/get-templates` against the mock engine.
 */
class MockSupabaseEngine(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Returns the raw JSON body of the `/devices` fixture, parsed as the
     * same `DeviceDto` shape that `ParentRepository.DeviceDto.toChildDevice`
     * already handles. Kept as a public method so the test can assert the
     * wire-shape contract without going through the Ktor engine.
     */
    fun devices(): List<DeviceFixture> {
        val raw = readAsset("mock-supabase/devices.json")
        return json.decodeFromString<List<DeviceFixture>>(raw)
    }

    fun pendingRequests(): List<PendingFixture> {
        val raw = readAsset("mock-supabase/pending-requests.json")
        return json.decodeFromString<List<PendingFixture>>(raw)
    }

    fun templates(): List<TemplateFixture> {
        val raw = readAsset("mock-supabase/templates.json")
        return json.decodeFromString<List<TemplateFixture>>(raw)
    }

    /**
     * Ktor [HttpClient] backed by a [MockEngine] that dispatches by URL
     * path. The engine responds with the corresponding fixture JSON for
     * the three documented endpoints; everything else gets a 404 so a
     * wrongly-routed call surfaces immediately instead of silently
     * returning empty data.
     */
    val httpClient: HttpClient = HttpClient(
        engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body: String = when {
                path.endsWith("/auth/v1/token") ->
                    // Per `fix-supabase-client-provider-legacy-mock-gate`
                    // family: the legacy `DeviceAuthManager.httpClient` now
                    // routes through this mock too, so `createAnonymousSession`
                    // gets a parseable `SupabaseAuthResponse` instead of a
                    // placeholder-URL connection error.
                    readAsset("mock-supabase/auth-anonymous.json")
                path.endsWith("/functions/v1/create-pairing-code") ->
                    readAsset("mock-supabase/create-pairing-code.json")
                path.endsWith("/functions/v1/get-devices-for-parent") ->
                    readAsset("mock-supabase/devices.json")
                path.endsWith("/functions/v1/get-templates") ||
                    path.endsWith("/rest/v1/templates") ->
                    readAsset("mock-supabase/templates.json")
                path.endsWith("/functions/v1/pairing") ->
                    readAsset("mock-supabase/pairing.json")
                path.startsWith("/rest/v1/time_requests") ->
                    readAsset("mock-supabase/pending-requests.json")
                else ->
                    """{"error":"unknown route $path"}"""
            }
            val status = if (body.startsWith("""{"error":""")) {
                HttpStatusCode.NotFound
            } else {
                HttpStatusCode.OK
            }
            respond(
                content = ByteReadChannel(body),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
    ) {
        // Without `ContentNegotiation` installed, `response.body<T>()` throws
        // `JsonConvertException` because the engine can't decode the raw body
        // into a typed object. The legacy `DeviceAuthManager.httpClient` is
        // now wired to this mock (per the `fix-supabase-client-provider-legacy-
        // mock-gate` family), and `createAnonymousSession` does
        // `response.body<SupabaseAuthResponse>()` — without this install, the
        // auth call throws, falls back to `AuthResult.Error`, and the
        // `PairingManager` shows `NETWORK_ERROR` to the user even though the
        // mock fixture is reachable. Adding `ContentNegotiation` here makes
        // the mock client a drop-in replacement for the real engine in any
        // caller that does typed deserialization. `bodyAsText()`-style callers
        // (the existing `MockSupabaseEngineTest`) are unaffected.
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }
            )
        }
    }

    private fun readAsset(path: String): String {
        val raw = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return minifyJsonIfNeeded(raw)
    }

    /**
     * Defensive JSON minifier for fixture responses.
     *
     * Fixtures live in `app/src/main/assets/mock-supabase/` and are often
     * pretty-printed (with newlines and spaces after colons) for review-ability
     * — that's the format a human wants to diff. But the wire format Supabase
     * actually returns is compact, and a hand-rolled JSON regex consumer (see
     * `PairingManager.extractDeviceId` / `extractParentId` / `extractError`)
     * only matches the compact form unless its regex explicitly tolerates
     * whitespace.
     *
     * Centralizing the minification here means a future fixture can stay
     * pretty-printed (good for review) AND a future regex consumer can stay
     * strict (good for catching real bugs), because the mock engine will
     * always serve compact JSON to consumers — exactly the format the real
     * Supabase returns.
     *
     * Implementation note: uses [kotlinx.serialization.json.Json.parseToJsonElement]
     * + [kotlinx.serialization.json.JsonElement.toString] rather than
     * `org.json.JSONObject.toString()` because the latter escapes forward
     * slashes inside strings (`/` → `\/`). Both forms are valid JSON, but
     * the escaped form breaks byte-for-byte string assertions in callers
     * (e.g. `deeplink.startsWith("parentalcontrol://pair?code=")`) and
     * shifts the actual `/` characters downstream of any `bodyAsText()`
     * call. kotlinx.serialization's encoder preserves `:` and `/` literally.
     *
     * Falls back to the raw text if parsing fails so a malformed fixture
     * surfaces as an immediate JSON parse error in the caller (typed
     * decoding) or as a non-compact response body (regex consumers),
     * instead of being silently swallowed here.
     */
    internal fun minifyJsonIfNeeded(json: String): String {
        val trimmed = json.trim()
        return try {
            when {
                trimmed.startsWith("[") -> Json.parseToJsonElement(trimmed).toString()
                trimmed.startsWith("{") -> Json.parseToJsonElement(trimmed).toString()
                else -> json
            }
        } catch (e: Exception) {
            json
        }
    }
}

/**
 * Wire shape that mirrors `ParentRepository.DeviceDto` so the parser
 * contract is shared. Kept here (not in the repository) because the
 * fixture is a test/demo artifact, not production wire data.
 */
@Serializable
data class DeviceFixture(
    val id: String,
    val device_name: String,
    val device_model: String? = null,
    val os_version: String? = null,
    val app_version: String = "1.0.0",
    val device_state: String = "ACTIVE",
    val policy_version: Int = 1,
    val last_seen_at: String
)

/**
 * Wire shape that mirrors `ParentRepository.TimeRequestDto` so the parser
 * contract is shared.
 */
@Serializable
data class PendingFixture(
    val id: String,
    val device_id: String,
    val device_name: String? = null,
    val package_name: String? = null,
    val app_name: String? = null,
    val minutes_requested: Int,
    val reason: String? = null,
    val status: String,
    val created_at: String,
    val responded_at: String? = null,
    val parent_response: String? = null
)

/**
 * New fixture shape for policy templates. The existing
 * `ParentRepository.getTemplates()` returns a hardcoded list and does not
 * hit Supabase yet; this fixture is the seam for the future
 * `getTemplates()` HTTP call.
 */
@Serializable
data class TemplateFixture(
    val templateId: String,
    val name: String,
    val policyJson: String
)
