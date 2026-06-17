// Tests for the approve-request edge function.
//
// Covers the three scenarios from the DENY-action fix:
//   1. action: "DENY"  -> time_request.status = DENIED, no grant, FCM REQUEST_DENIED.
//   2. action: "APPROVE" with minutes -> grant created, FCM POLICY_UPDATED.
//   3. no action field (backward compat) -> grant created (legacy behavior).
//
// Run with: cd supabase/functions/approve-request && deno test
//
// The Supabase JS client uses globalThis.fetch under the hood, so each test
// installs a fetch mock that responds to auth.getUser, the time_requests
// select/update, the grants insert, the device_push_tokens select, and the
// FCM POST.

import { assertEquals, assertStringIncludes } from "jsr:@std/assert@1";
import { handleRequest } from "./index.ts";

// ============ Fixtures ============

const PARENT_UUID = "11111111-1111-1111-1111-111111111111";

// The handler decodes the JWT manually with `atob(token.split(".")[1])`,
// so the middle segment must be a base64-encoded JSON object with `sub`.
const PARENT_JWT = `header.${btoa(JSON.stringify({ sub: PARENT_UUID }))}.signature`;
const REQUEST_ID = "req-001";
const DEVICE_ID = "device-aaaa";
const FCM_TOKEN = "fcm-token-xyz";
const GRANT_ID = "grant-001";

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function authHeader(jwt: string): Record<string, string> {
  return { Authorization: `Bearer ${jwt}` };
}

/**
 * In-memory tables the mock fetch reads from / writes to. The handler is
 * expected to mutate the rows it touches; the test asserts on the final
 * state.
 */
interface Tables {
  timeRequests: Map<string, Record<string, unknown>>;
  grants: Array<Record<string, unknown>>;
  pushTokens: Array<Record<string, unknown>>;
  devices: Map<string, Record<string, unknown>>;
}

function makeTables(initialTimeRequest: Record<string, unknown>): Tables {
  const timeRequests = new Map<string, Record<string, unknown>>();
  timeRequests.set(REQUEST_ID, initialTimeRequest);
  const devices = new Map<string, Record<string, unknown>>();
  devices.set(DEVICE_ID, { id: DEVICE_ID, policy_version: 7 });
  return {
    timeRequests,
    grants: [],
    pushTokens: [{ device_id: DEVICE_ID, token: FCM_TOKEN, is_active: true }],
    devices,
  };
}

/**
 * Build a fetch mock backed by the in-memory tables. Captures all calls
 * (URL, method, body) so tests can assert on what was sent — including the
 * FCM payload sent to the child device.
 */
function buildFetchMock(tables: Tables) {
  const calls: Array<{ url: string; method: string; body: unknown }> = [];

  const parseBody = async (init?: RequestInit): Promise<unknown> => {
    if (!init?.body) return undefined;
    if (typeof init.body === "string") {
      try {
        return JSON.parse(init.body);
      } catch {
        return init.body;
      }
    }
    return undefined;
  };

  const fetchMock = async (
    input: RequestInfo | URL,
    init?: RequestInit
  ): Promise<Response> => {
    const url = typeof input === "string" ? input : input.toString();
    const method = init?.method ?? "GET";
    const body = await parseBody(init);
    calls.push({ url, method, body });

    // Supabase auth.getUser -> /auth/v1/user
    if (url.includes("/auth/v1/user")) {
      return jsonResponse({ id: PARENT_UUID, email: "parent@local.test" });
    }

    // time_requests SELECT ... .eq("id", X) .single() -> /rest/v1/time_requests?...
    if (url.includes("/rest/v1/time_requests") && method === "GET") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? tables.timeRequests.get(id) : undefined;
      if (!row) return jsonResponse({ error: "not found" }, 404);
      return jsonResponse({ ...row, devices: { parent_id: PARENT_UUID } });
    }

    // time_requests UPDATE ... -> PATCH /rest/v1/time_requests?id=eq.X
    if (url.includes("/rest/v1/time_requests") && method === "PATCH") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? tables.timeRequests.get(id) : undefined;
      if (row && body && typeof body === "object") {
        Object.assign(row, body as Record<string, unknown>);
      }
      return jsonResponse([]);
    }

    // grants INSERT -> POST /rest/v1/grants
    if (url.includes("/rest/v1/grants") && method === "POST") {
      const inserted = {
        id: GRANT_ID,
        ...(body as Record<string, unknown>),
      };
      tables.grants.push(inserted);
      return jsonResponse(inserted);
    }

    // devices SELECT policy_version -> /rest/v1/devices?...
    if (url.includes("/rest/v1/devices") && method === "GET") {
      const id = url.match(/id=eq\.([^&]+)/)?.[1];
      const row = id ? tables.devices.get(id) : undefined;
      return jsonResponse(row ?? null);
    }

    // device_push_tokens SELECT -> /rest/v1/device_push_tokens?...
    if (url.includes("/rest/v1/device_push_tokens")) {
      return jsonResponse(tables.pushTokens[0] ?? null);
    }

    // FCM POST -> https://fcm.googleapis.com/fcm/send
    if (url.includes("fcm.googleapis.com")) {
      return jsonResponse({ success: 1 });
    }

    return jsonResponse({ error: `unhandled url: ${url}` }, 500);
  };

  return { fetchMock, calls };
}

function installFetchMock(fetchMock: typeof fetch) {
  // deno-lint-ignore no-explicit-any
  (globalThis as any).fetch = fetchMock;
}

function restoreFetch() {
  // deno-lint-ignore no-explicit-any
  delete (globalThis as any).fetch;
}

function setEnv() {
  Deno.env.set("SUPABASE_URL", "https://example.supabase.co");
  Deno.env.set("SUPABASE_ANON_KEY", "anon-test-key");
  Deno.env.set("SUPABASE_SERVICE_ROLE_KEY", "service-role-test-key");
  Deno.env.set("FCM_SERVER_KEY", "fcm-server-key-test");
}

function makeApproveRequest(body: object): Request {
  return new Request("https://example.test/functions/v1/approve-request", {
    method: "POST",
    headers: {
      ...authHeader(PARENT_JWT),
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

// ============ Tests ============

Deno.test({
  name: "deny_action_sets_status_to_denied_and_does_not_create_grant",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const initial = {
      id: REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
    };
    const tables = makeTables(initial);
    const { fetchMock, calls } = buildFetchMock(tables);
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(
        makeApproveRequest({ request_id: REQUEST_ID, action: "DENY" })
      );

      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body.success, true);
      assertEquals(body.decision, "DENIED");

      // time_request was updated to DENIED
      const updated = tables.timeRequests.get(REQUEST_ID);
      assertEquals(updated?.status, "DENIED");

      // NO grant was created
      assertEquals(tables.grants.length, 0);

      // FCM was sent to the child device with type: REQUEST_DENIED
      const fcmCall = calls.find((c) => c.url.includes("fcm.googleapis.com"));
      assertEquals(typeof fcmCall, "object");
      const fcmPayload = (fcmCall?.body as { data?: Record<string, unknown> })
        ?.data;
      assertEquals(fcmPayload?.type, "REQUEST_DENIED");
      assertEquals(fcmPayload?.request_id, REQUEST_ID);
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "approve_action_creates_grant_and_sends_policy_updated_fcm",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const initial = {
      id: REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
    };
    const tables = makeTables(initial);
    const { fetchMock, calls } = buildFetchMock(tables);
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(
        makeApproveRequest({
          request_id: REQUEST_ID,
          minutes: 15,
          action: "APPROVE",
        })
      );

      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body.success, true);
      assertEquals(typeof body.grant_id, "string");
      assertStringIncludes(body.grant_id, GRANT_ID);

      // time_request was updated to APPROVED
      const updated = tables.timeRequests.get(REQUEST_ID);
      assertEquals(updated?.status, "APPROVED");

      // A grant was created
      assertEquals(tables.grants.length, 1);
      const grant = tables.grants[0];
      assertEquals(grant.minutes, 15);
      assertEquals(grant.source, "EXTRA_TIME");
      assertEquals(grant.status, "APPROVED");
      assertEquals(grant.device_id, DEVICE_ID);

      // FCM was sent with type: POLICY_UPDATED and grant_id
      const fcmCall = calls.find((c) => c.url.includes("fcm.googleapis.com"));
      assertEquals(typeof fcmCall, "object");
      const fcmPayload = (fcmCall?.body as { data?: Record<string, unknown> })
        ?.data;
      assertEquals(fcmPayload?.type, "POLICY_UPDATED");
      assertEquals(fcmPayload?.grant_id, GRANT_ID);
      assertEquals(fcmPayload?.minutes, 15);
    } finally {
      restoreFetch();
    }
  },
});

Deno.test({
  name: "approve_action_default_when_no_action_field_is_backward_compatible",
  sanitizeOps: false,
  sanitizeResources: false,
  fn: async () => {
    setEnv();
    const initial = {
      id: REQUEST_ID,
      device_id: DEVICE_ID,
      package_name: "com.example.app",
      requested_minutes: 15,
      status: "PENDING",
    };
    const tables = makeTables(initial);
    const { fetchMock, calls } = buildFetchMock(tables);
    installFetchMock(fetchMock);

    try {
      const response = await handleRequest(
        makeApproveRequest({ request_id: REQUEST_ID, minutes: 30 })
      );

      assertEquals(response.status, 200);
      const body = await response.json();
      assertEquals(body.success, true);
      assertEquals(typeof body.grant_id, "string");

      // Grant was created with minutes from the legacy payload (no action field).
      assertEquals(tables.grants.length, 1);
      assertEquals(tables.grants[0].minutes, 30);

      // FCM was sent with type: POLICY_UPDATED (legacy behavior).
      const fcmCall = calls.find((c) => c.url.includes("fcm.googleapis.com"));
      const fcmPayload = (fcmCall?.body as { data?: Record<string, unknown> })
        ?.data;
      assertEquals(fcmPayload?.type, "POLICY_UPDATED");
    } finally {
      restoreFetch();
    }
  },
});