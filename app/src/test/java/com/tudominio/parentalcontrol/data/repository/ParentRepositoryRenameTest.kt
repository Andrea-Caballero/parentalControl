package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
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
 * RED coverage for the deferred `renameChild` from
 * `openspec/changes/2026-07-07-fix-rename-child-dialog/proposal.md` §2.
 *
 * Per Q2=h + Q4=p (engram `sdd/fix-rename-child-dialog/decisions`):
 *  - ParentRepository gains a `renameChild(childId: String, newName: String): Result<Unit>`
 *    that PATCHes `${SUPABASE_URL}/rest/v1/children?id=eq.{childId}` with body
 *    `{"first_name":"..."}` (Supabase PostgREST shape).
 *  - Success on a 2xx; failure otherwise (typed as `DeviceListError.Transient`).
 *  - Mirrors the existing `approveRequest` / `denyRequest` HTTP pattern.
 *
 * RED today: `renameChild` does not exist on `ParentRepository`. The
 * assertions below fail to compile (`Unresolved reference: renameChild`).
 * The apply phase lands `renameChild` and the tests turn GREEN.
 */
class ParentRepositoryRenameTest {

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
                content = ByteReadChannel("""[{"id":"child-lucas","first_name":"Mateo"}]"""),
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

    private fun requestBodyText(req: HttpRequestData): String {
        return when (val body = req.body) {
            is TextContent -> body.text
            EmptyContent -> ""
            else -> body.toString()
        }
    }

    /**
     * Happy path: a PATCH 200 returns Result.success(Unit). The repo
     * uses the parent's bearer token + apikey + ContentType headers,
     * builds the URL with the `id=eq.{childId}` query filter, and
     * serializes `{"first_name":"<newName>"}` as the body.
     */
    @Test
    fun renameChild_patch_200_returns_success() = runTest {
        val result = repository.renameChild(
            childId = "child-lucas",
            newName = "Mateo"
        )

        assertTrue(
            "Expected Result.success(Unit), got $result",
            result.isSuccess
        )

        // Verify the wire request: PATCH /rest/v1/children?id=eq.child-lucas
        assertEquals(
            "renameChild must issue exactly one HTTP call, got ${captured.size}",
            1,
            captured.size
        )
        val req = captured.first()
        assertEquals(HttpMethod.Patch, req.method)
        val url = req.url.toString()
        assertTrue(
            "URL should target /rest/v1/children, got $url",
            url.contains("/rest/v1/children")
        )
        assertTrue(
            "URL should include id=eq.child-lucas filter, got $url",
            url.contains("id=eq.child-lucas")
        )
        assertEquals("Bearer test-jwt-token", req.headers[HttpHeaders.Authorization])
        assertEquals(SupabaseClientProvider.SUPABASE_ANON_KEY, req.headers["apikey"])

        val body = requestBodyText(req)
        assertTrue(
            "Body should contain first_name with the new value, got: $body",
            body.contains("\"first_name\":\"Mateo\"")
        )
    }

    /**
     * Failure path: a non-2xx response (the 409 UNIQUE conflict
     * scenario for an already-taken `(parent_id, first_name)` pair
     * per Q2 chain's `005_children_table.sql`) must surface as
     * `DeviceListError.Transient`, never as a silent success.
     */
    @Test
    fun renameChild_non_2xx_returns_Transient_failure() = runTest {
        val failingEngine = MockEngine { _ ->
            respondError(HttpStatusCode.Conflict)
        }
        val failingClient = HttpClient(failingEngine)
        every { clientProvider.httpClient } returns failingClient

        try {
            val result = repository.renameChild(
                childId = "child-lucas",
                newName = "Mateo"
            )

            assertTrue(
                "Expected Result.failure on 409, got $result",
                result.isFailure
            )
            val ex = result.exceptionOrNull()
            assertNotNull("Exception must not be null", ex)
            assertTrue(
                "renameChild must surface 409 as DeviceListError.Transient, got $ex",
                ex is DeviceListError.Transient
            )
            assertTrue(
                "Transient reason must mention HTTP 409, got ${(ex as DeviceListError.Transient).reason}",
                ex.reason.contains("HTTP 409")
            )
        } finally {
            failingClient.close()
        }
    }

    /**
     * Auth seam: with no token the repo must short-circuit to
     * `DeviceListError.AuthMissing` without issuing any HTTP. Mirrors
     * the contract every other `ParentRepository` method honours.
     */
    @Test
    fun renameChild_without_token_returns_AuthMissing() = runTest {
        every { authManager.getAccessToken() } returns null

        val result = repository.renameChild(
            childId = "child-lucas",
            newName = "Mateo"
        )

        assertTrue(
            "Expected Result.failure, got $result",
            result.isFailure
        )
        assertEquals(
            "Expected typed AuthMissing, got ${result.exceptionOrNull()}",
            DeviceListError.AuthMissing,
            result.exceptionOrNull()
        )
        assertTrue(
            "renameChild must NOT issue an HTTP call when unauthenticated",
            captured.isEmpty()
        )
    }
}
