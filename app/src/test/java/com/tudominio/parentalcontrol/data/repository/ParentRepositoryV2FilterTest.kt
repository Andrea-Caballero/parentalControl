package com.tudominio.parentalcontrol.data.repository

import android.content.Context
import com.tudominio.parentalcontrol.auth.DeviceAuthManager
import com.tudominio.parentalcontrol.domain.model.Child
import com.tudominio.parentalcontrol.domain.model.ChildDevice
import com.tudominio.parentalcontrol.domain.model.DeviceState
import com.tudominio.parentalcontrol.network.SupabaseClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RED tests for the V2 server-side `Solicitudes` filter of
 * `sdd/fix-v2-server-side-solicitudes-filter`.
 *
 * Today's V1 implementation issues a single static Postgrest GET at
 * `ParentRepository.kt:297-298` with NO `device_id` filter — it returns
 * ALL pending requests belonging to the parent's devices (RLS scoped).
 * The client-side picker on `DashboardScreen` then discards rows whose
 * `deviceId` is not part of the currently-selected child.
 *
 * V2's contract: pushing `selectedChildId` to the server so only the
 * requested device ids (or all when `null`/Todos) come back. Specifically:
 *
 *  1. When `selectedChildId = "child-lucas-id"`, the request URL MUST
 *     carry `&device_id=in.(<devices of child lucas>)`. The repository is
 *     the one entity that knows which device ids belong to that child
 *     (it resolves them from the cached / just-fetched `getDevices()`
 *     list) — see [ParentRepository.getPendingRequests] future signature.
 *
 *  2. When `selectedChildId = null` (Todos), the request URL MUST NOT
 *     carry a `device_id` filter. RLS still scopes by `parent_id`. This
 *     keeps the no-arg overload `getPendingRequests()` callable from
 *     `SolicitudesPollingWorker.kt:70` (5-min auto-poll) without a
 *     behavioural change.
 *
 * Both assertions are RED today because (a) the no-arg method
 * `getPendingRequests()` does not take a `selectedChildId: String?`
 * parameter and (b) the URL builder hardcodes the static query without
 * any `device_id` clause — see the docstring at
 * `ParentRepository.kt:277-290`.
 *
 * These tests turn GREEN when `ParentRepository.getPendingRequests` is
 * overloaded to accept an optional `selectedChildId` parameter and the
 * URL builder appends `device_id=in.(...)` for non-null selections.
 */
class ParentRepositoryV2FilterTest {

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
                content = ByteReadChannel(PENDING_REQUESTS_RESPONSE_BODY),
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

        // Prime the V2 device cache so the overload can translate the
        // child id to device ids without a real `get-devices-for-parent`
        // round-trip (the MockEngine is wired to the time-requests
        // response shape, not the device-list shape). Mirrors the
        // lazy-hydration call site proposed in
        // `openspec/changes/2026-07-07-fix-v2-server-side-solicitudes-filter/proposal.md`
        // §What changes #1 — production hydration is a future follow-up.
        repository.primeDevicesCache(LUCAS_DEVICE_FIXTURE)
    }

    @After
    fun tearDown() {
        mockClient.close()
    }

    /**
     * Captures the request issued by a no-arg `getPendingRequests()` and
     * resolves its full URL (path + raw query). The mock captures the
     * raw query via [HttpRequestData.url].
     */
    private fun lastRequestUrl(): String {
        val request = captured.lastOrNull()
            ?: error("No HTTP request was captured — the repository never issued a fetch")
        return request.url.toString()
    }

    /**
     * V2 contract, positive: when the parent UI has selected a child, the
     * repository MUST append `device_id=in.(<ids>)` to the Postgrest
     * query. Today (V1) the request URL is hardcoded at
     * `ParentRepository.kt:297-298` and has no `device_id` clause — this
     * test is RED.
     */
    @Test
    fun `getPendingRequests with selectedChildId sends device_id in filter`() = runTest {
        val result = repository.getPendingRequests(selectedChildId = CHILD_LUCAS_ID)

        // 1. The fetch must succeed with a non-failure Result — this guards
        //    against a future regression where the V2 path accidentally
        //    short-circuits before the HTTP layer is reached.
        assertTrue(
            "Expected getPendingRequests(selectedChildId=…) to succeed, got $result",
            result.isSuccess
        )

        // 2. The captured URL MUST carry a `device_id=in.(...)` clause.
        //    In V2 the repository translates the child id to its
        //    associated device id(s) and emits a Postgrest `in` filter.
        //    In V1 the URL is the static string at ParentRepository.kt:298,
        //    which has no `device_id` segment — this assertion is RED.
        val url = lastRequestUrl()
        assertTrue(
            "Expected request URL to contain 'device_id=in.(...)' when a child is " +
                "selected, but got: $url",
            url.contains("device_id=in.")
        )

        // 3. The Postgrest `in.(...)` list MUST include Lucas's device
        //    (dev-001). UUIDs are URL-safe so no encoding is required;
        //    a single-id filter is the minimum viable shape when only
        //    one device is linked to the child record.
        assertTrue(
            "Expected device_id=in filter to include 'dev-001' (Lucás's device), " +
                "but got: $url",
            url.contains("dev-001")
        )

        // 4. The static ordering and status clauses MUST remain intact —
        //    the V2 change is purely additive.
        assertTrue(
            "Expected the existing status filter to be preserved, but got: $url",
            url.contains("status=eq.PENDING")
        )
    }

    /**
     * V2 contract, neutral: when `selectedChildId = null` (Todos) the URL
     * MUST NOT carry a `device_id=` clause. RLS
     * (`time_requests_parent_select`, see
     * `supabase/migrations/002_rls_policies.sql:153-161`) already
     * scopes rows by `parent_id = auth.uid()`. Adding a `device_id=in.()`
     * filter on the Todos path would force the repository to resolve the
     * parent's full device list on every fetch — wasted bytes.
     *
     * Today (V1) the no-arg call passes the static URL with no
     * `device_id` clause — this assertion happens to be GREEN. We keep
     * it as a guardrail so the GREEN phase can't accidentally regress
     * the Todos path.
     */
    @Test
    fun `getPendingRequests with null selectedChildId omits device_id filter`() = runTest {
        val result = repository.getPendingRequests(selectedChildId = null)

        assertTrue(
            "Expected getPendingRequests(selectedChildId=null) to succeed, got $result",
            result.isSuccess
        )

        val url = lastRequestUrl()
        assertFalse(
            "Expected no 'device_id=' clause when selectedChildId is null " +
                "(Todos returns all parent's pending requests via RLS), " +
                "but got: $url",
            url.contains("device_id=")
        )
        // Sanity: the static shape must still be there.
        assertTrue(
            "Expected status=eq.PENDING to be preserved, but got: $url",
            url.contains("status=eq.PENDING")
        )
        assertTrue(
            "Expected order=created_at.desc to be preserved, but got: $url",
            url.contains("order=created_at.desc")
        )
    }

    companion object {
        private const val CHILD_LUCAS_ID = "child-lucas-id"

        // Minimal Lucas device used to prime `_devicesCache` so the
        // V2 overload can resolve `CHILD_LUCAS_ID` → "dev-001". The
        // fixture keeps the irrelevant `Child` fields empty and the
        // state at `ACTIVE` — the V2 path only reads `id` and the
        // `child.id` association.
        private val LUCAS_DEVICE_FIXTURE = listOf(
            ChildDevice(
                id = "dev-001",
                name = "Lucas Tablet",
                model = null,
                appVersion = "1.0.0",
                policyVersion = 1,
                state = DeviceState.ACTIVE,
                lastSeenAt = "2026-07-07T12:00:00Z",
                isOnline = true,
                child = Child(
                    id = CHILD_LUCAS_ID,
                    parentId = "parent-test",
                    firstName = "Lucas",
                    createdAt = "",
                    updatedAt = ""
                )
            )
        )

        // Two pending requests across two devices — one owned by Lucas
        // (dev-001) and one owned by another future child (dev-002). The
        // server is mocked to return both regardless of the filter; the
        // V2 contract asserts on the REQUEST URL shape, not the
        // response. Children table is in `supabase/migrations/005_children_table.sql`.
        private const val PENDING_REQUESTS_RESPONSE_BODY = """
            [
              {
                "id":"req-001",
                "device_id":"dev-001",
                "device_name":"Lucas Tablet",
                "package_name":"com.instagram.android",
                "app_name":"Instagram",
                "minutes_requested":15,
                "reason":null,
                "status":"PENDING",
                "created_at":"2026-07-07T12:00:00Z",
                "responded_at":null,
                "parent_response":null
              },
              {
                "id":"req-002",
                "device_id":"dev-002",
                "device_name":"Other Tablet",
                "package_name":"com.whatsapp",
                "app_name":"WhatsApp",
                "minutes_requested":10,
                "reason":null,
                "status":"PENDING",
                "created_at":"2026-07-07T12:01:00Z",
                "responded_at":null,
                "parent_response":null
              }
            ]
        """
    }
}
