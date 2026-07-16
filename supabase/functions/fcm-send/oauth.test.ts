// B1a2 — OAuth token exchange. Run: deno test --allow-all --no-check oauth.test.ts
// deno-lint-ignore-file no-import-prefix
import { assertEquals } from "jsr:@std/assert@1";
import {
  clearOAuthCacheForTests,
  FcmConfigError,
  getCachedOAuthToken,
  MAX_EXPIRES_IN_SEC,
} from "./oauth-foundation.ts";
import { getFcmAccessToken } from "./oauth.ts";

const NOT_CONFIGURED = "fcm_service_account_not_configured";
const OAUTH_FAILED = "oauth_token_request_failed";
const SENTINEL = "sentinel-leak-marker-9f3b2c1a";

// Module-load RSA-2048 keypair (WebCrypto). Real PEM required so
// mintFcmAssertionJwt can sign (synthetic PEMs fail at importKey —
// Engram #371). This is NOT a real GCP credential.
const B1A2_KEY = await crypto.subtle.generateKey(
  {
    name: "RSASSA-PKCS1-v1_5",
    modulusLength: 2048,
    publicExponent: new Uint8Array([1, 0, 1]),
    hash: "SHA-256",
  },
  true,
  ["sign", "verify"],
);
const B1A2_PEM = `-----BEGIN PRIVATE KEY-----\n${
  btoa(String.fromCharCode(
    ...new Uint8Array(
      await crypto.subtle.exportKey("pkcs8", B1A2_KEY.privateKey),
    ),
  )).match(/.{1,64}/g)!.join("\n")
}\n-----END PRIVATE KEY-----\n`;

// Two real-PEM service accounts for the happy-path tests.
const B64_GOOD_SA = btoa(JSON.stringify({
  type: "service_account",
  project_id: "proj-good",
  private_key: B1A2_PEM,
  client_email: "fcm@proj-good.iam.gserviceaccount.com",
}));
const B64_BAD_PEM_SA = btoa(JSON.stringify({
  type: "service_account",
  project_id: "proj-bad",
  private_key: "-----BEGIN PRIVATE KEY-----\nNOPEM-----\n",
  client_email: "fcm@proj-bad.iam.gserviceaccount.com",
}));

const jsonResp = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });

// Captured call shape — `method` required so the TS compiler
// doesn't reject the push (TS2353 fix).
type CapturedCall = {
  url: string;
  method: string;
  body: unknown;
  headers: Headers;
};

function buildFetchStub(respond: () => Response) {
  const calls: CapturedCall[] = [];
  let n = 0;
  return {
    fn: (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
      const url = typeof input === "string" ? input : input.toString();
      let body: unknown = undefined;
      if (typeof init?.body === "string") {
        try {
          body = JSON.parse(init.body);
        } catch {
          body = init.body;
        }
      }
      calls.push({
        url,
        method: init?.method ?? "GET",
        body,
        headers: new Headers(init?.headers ?? {}),
      });
      return url.startsWith("https://oauth2.googleapis.com/token")
        ? (n += 1, Promise.resolve(respond()))
        : Promise.resolve(jsonResp({ error: "unexpected url" }, 500));
    },
    calls,
    oauthCalls: () => n,
  };
}

async function expectFcm(
  code: string,
  fn: () => unknown | Promise<unknown>,
): Promise<void> {
  let err: unknown;
  try {
    await fn();
  } catch (e) {
    err = e;
  }
  if (!err) throw new Error(`expected throw with code=${code}, got none`);
  if (!(err instanceof FcmConfigError)) {
    throw new Error(
      `expected FcmConfigError, got ${(err as Error)?.constructor?.name}`,
    );
  }
  assertEquals((err as FcmConfigError).code, code);
}

// AUDIT FIX (#374): cap `expires_in` at MAX_EXPIRES_IN_SEC; reuse the
// cached token on the 2nd call. Also pins the request contract (URL +
// HTTP POST + Content-Type) and the JWT-bearer grant_type.
Deno.test("B1a2.1 — cache miss→exchange→cache hit + JWT-bearer + cap=3600", async () => {
  clearOAuthCacheForTests();
  try {
    const stub = buildFetchStub(() =>
      jsonResp({ access_token: "tok-good", expires_in: 99_999 })
    );
    const fixedNow = 1_700_000_000_000;
    const tok = await getFcmAccessToken(B64_GOOD_SA, stub.fn, fixedNow);
    assertEquals(tok, "tok-good");
    assertEquals(stub.oauthCalls(), 1);
    assertEquals(stub.calls.length, 1);
    const call = stub.calls[0];
    // Request contract: exact Google OAuth token URL, HTTP POST, form-encoded body.
    assertEquals(call.url, "https://oauth2.googleapis.com/token");
    assertEquals(call.method, "POST");
    assertEquals(
      call.headers.get("Content-Type"),
      "application/x-www-form-urlencoded",
    );
    const form = new URLSearchParams(call.body as string);
    assertEquals(
      form.get("grant_type"),
      "urn:ietf:params:oauth:grant-type:jwt-bearer",
    );
    assertEquals((form.get("assertion") ?? "").length > 20, true);
    const cached = getCachedOAuthToken("proj-good", fixedNow + 100);
    if (!cached) throw new Error("cache must contain proj-good after exchange");
    assertEquals(cached.expiresAtMs, fixedNow + MAX_EXPIRES_IN_SEC * 1000);
    await getFcmAccessToken(B64_GOOD_SA, stub.fn, fixedNow + 2000);
    assertEquals(stub.oauthCalls(), 1);
  } finally {
    clearOAuthCacheForTests();
  }
});

// ===== B1a2.2 — every malformed OAuth response → oauth_token_request_failed =====
// WARNING B1A-OAUTH-003: table-driven loop covering distinct
// JSON-representable malformed values. The thrown message MUST NOT
// echo the response body. NaN/Infinity are NOT JSON-representable
// (they serialize as null), so the original NaN/Infinity cases were
// replaced with boolean/array values that JSON preserves and that fail
// the strict `typeof exp !== "number"` schema check.
Deno.test("B1a2.2 — every malformed OAuth response → oauth_token_request_failed", async () => {
  clearOAuthCacheForTests();
  try {
    const rawText = new Response("not-json-{", {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
    // Tuple form: [label, Response builder]. Tuples preserve readability
    // while the captured-call TS2353 fix lives in `buildFetchStub`.
    const cases: Array<[string, () => Response]> = [
      ["missing access_token", () => jsonResp({ expires_in: 3600 })],
      [
        "empty access_token",
        () => jsonResp({ access_token: "", expires_in: 3600 }),
      ],
      [
        "numeric access_token",
        () => jsonResp({ access_token: 1234, expires_in: 3600 }),
      ],
      [
        "object access_token",
        () => jsonResp({ access_token: {}, expires_in: 3600 }),
      ],
      ["missing expires_in", () => jsonResp({ access_token: "tok" })],
      [
        "expires_in zero",
        () => jsonResp({ access_token: "tok", expires_in: 0 }),
      ],
      [
        "expires_in negative",
        () => jsonResp({ access_token: "tok", expires_in: -1 }),
      ],
      [
        "expires_in string",
        () => jsonResp({ access_token: "tok", expires_in: "3600" }),
      ],
      [
        "expires_in null",
        () => jsonResp({ access_token: "tok", expires_in: null }),
      ],
      // NaN/Infinity are NOT JSON-representable (JSON.stringify → null) —
      // they were replaced with boolean and array (both JSON-preservable,
      // both fail the strict `typeof exp !== "number"` schema check).
      [
        "expires_in boolean",
        () => jsonResp({ access_token: "tok", expires_in: true }),
      ],
      [
        "expires_in array",
        () => jsonResp({ access_token: "tok", expires_in: [3600] }),
      ],
      ["unrelated shape", () => jsonResp({ foo: "bar" })],
      ["invalid JSON", () => rawText],
      ["non-200", () => new Response("", { status: 502 })],
    ];
    for (const [, buildResp] of cases) {
      const stub = buildFetchStub(buildResp);
      await expectFcm(
        OAUTH_FAILED,
        () => getFcmAccessToken(B64_GOOD_SA, stub.fn),
      );
    }
  } finally {
    clearOAuthCacheForTests();
  }
});

Deno.test("B1a2.3 — missing / malformed secret → FcmConfigError, no fetch", async () => {
  // empty secret → decodeServiceAccount fails before any fetch.
  const stub1 = buildFetchStub(() => jsonResp({}));
  await expectFcm(NOT_CONFIGURED, () => getFcmAccessToken("", stub1.fn));
  assertEquals(stub1.oauthCalls(), 0, "empty secret → no OAuth fetch");
  // fake-PEM → mintFcmAssertionJwt normalizes importKey failure (B1a1) and B1a2 must propagate it cleanly.
  const stub2 = buildFetchStub(() => jsonResp({}));
  await expectFcm(
    NOT_CONFIGURED,
    () => getFcmAccessToken(B64_BAD_PEM_SA, stub2.fn),
  );
  assertEquals(stub2.oauthCalls(), 0, "importKey failure → no OAuth fetch");
});

// ===== B1a2.4 — error.message does NOT echo response body or token =====
// Sentinel-leak guard. The response body embeds SENTINEL; the thrown
// FcmConfigError.message must not contain it. Production's generic
// messages ("OAuth endpoint returned 502", "invalid JSON", "missing
// or invalid access_token", "missing or invalid expires_in") never
// reference any token-shaped substring. A regression that accidentally
// echoes response data would fail this test.
Deno.test("B1a2.4 — error.message does NOT echo response body or token", async () => {
  clearOAuthCacheForTests();
  try {
    const sentinelBody = { access_token: SENTINEL, expires_in: SENTINEL };
    const cases: Array<[string, () => Response]> = [
      [
        "non-200 SENTINEL body",
        () =>
          new Response(JSON.stringify(sentinelBody), {
            status: 502,
            headers: { "Content-Type": "application/json" },
          }),
      ],
      [
        "malformed JSON with SENTINEL",
        () =>
          new Response(`{ "access_token": "${SENTINEL}" `, {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
      ],
      // Valid access_token but expires_in is the SENTINEL string — the
      // "missing or invalid expires_in" message must not echo it.
      [
        "expires_in is SENTINEL",
        () => jsonResp({ access_token: "real-tok", expires_in: SENTINEL }),
      ],
    ];
    for (const [label, buildResp] of cases) {
      const stub = buildFetchStub(buildResp);
      let err: unknown;
      try {
        await getFcmAccessToken(B64_GOOD_SA, stub.fn);
      } catch (e) {
        err = e;
      }
      if (!(err instanceof FcmConfigError)) {
        throw new Error(
          `${label}: expected FcmConfigError, got ${
            (err as Error)?.constructor?.name
          }`,
        );
      }
      if (err.message.includes(SENTINEL)) {
        throw new Error(
          `${label}: FcmConfigError.message LEAKS SENTINEL: ${err.message}`,
        );
      }
    }
  } finally {
    clearOAuthCacheForTests();
  }
});
