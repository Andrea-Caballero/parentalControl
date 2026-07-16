// B1a2 — OAuth token exchange + strict response validation. Additive
// on B1a1 (master after B1a1 lands): imports
// `./oauth-foundation.ts` for credential/JWT/cache primitives and adds
// the FCM token-endpoint exchange + OAuth schema validator.
//
// Reviewer fixes (#378, #379):
//   - Injected `fetchImpl: typeof fetch` (no global fetch mutation)
//   - `access_token` must be non-empty string; missing/empty/non-string
//     throws `FcmConfigError(OAUTH_FAIL)` with a generic message that
//     does NOT echo the response body
//   - `expires_in` must be a finite positive number; caps at
//     `MAX_EXPIRES_IN_SEC`; missing/wrong-type throws
//   - Invalid JSON / non-2xx / unreadable body → `OAUTH_FAIL`
//
// `index.ts` is unchanged in this slice; legacy `key=` send stays until
// Slice B1c wires the cutover.

import {
  decodeServiceAccount,
  getCachedOAuthToken,
  GOOGLE_OAUTH_TOKEN_URL,
  MAX_EXPIRES_IN_SEC,
  mintFcmAssertionJwt,
  setCachedOAuthToken,
} from "./oauth-foundation.ts";

const OAUTH_FAILED = "oauth_token_request_failed";

export { MAX_EXPIRES_IN_SEC };

/**
 * Acquire an OAuth bearer token for the FCM service account. Behavior:
 *   - `decodeServiceAccount` throws `FcmConfigError("fcm_service_account_not_configured")`
 *     for malformed config (B1a1 owns that contract).
 *   - On cache hit → returns the cached token.
 *   - On cache miss: signs a fresh RS256 assertion, POSTs
 *     `application/x-www-form-urlencoded grant_type=…&assertion=…`,
 *     validates the response, caps `expires_in`, populates the cache,
 *     returns the access token.
 *   - Token-endpoint problems (non-2xx, JSON parse error, schema
 *     mismatch, unreadable body) throw
 *     `FcmConfigError("oauth_token_request_failed")` with a generic
 *     message that does NOT echo the response body.
 */
export async function getFcmAccessToken(
  rawSecret: string,
  fetchImpl: typeof fetch,
  nowMs: number = Date.now(),
): Promise<string> {
  const sa = decodeServiceAccount(rawSecret);
  const cached = getCachedOAuthToken(sa.project_id, nowMs);
  if (cached) return cached.token;
  const assertion = await mintFcmAssertionJwt(sa, nowMs);
  const response = await fetchImpl(GOOGLE_OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }).toString(),
  });
  if (!response.ok) {
    throw new FcmConfigError(
      OAUTH_FAILED,
      `OAuth endpoint returned ${response.status}`,
    );
  }
  let rawText: string;
  try {
    rawText = await response.text();
  } catch {
    throw new FcmConfigError(OAUTH_FAILED, "response body unreadable");
  }
  // Strict OAuth-response schema validator. All branches throw
  // FcmConfigError(OAUTH_FAILED) — generic message, never echoes the
  // response body or any token-like fragment.
  let json: unknown;
  try {
    json = JSON.parse(rawText);
  } catch {
    throw new FcmConfigError(OAUTH_FAILED, "invalid JSON");
  }
  if (!json || typeof json !== "object") {
    throw new FcmConfigError(OAUTH_FAILED, "not a JSON object");
  }
  const obj = json as Record<string, unknown>;
  const access = obj.access_token;
  if (typeof access !== "string" || access.length === 0) {
    throw new FcmConfigError(OAUTH_FAILED, "missing or invalid access_token");
  }
  const exp = obj.expires_in;
  if (typeof exp !== "number" || !Number.isFinite(exp) || exp <= 0) {
    throw new FcmConfigError(OAUTH_FAILED, "missing or invalid expires_in");
  }
  const accessToken: string = access;
  const expiresInSec = Math.min(exp, MAX_EXPIRES_IN_SEC);
  setCachedOAuthToken(sa.project_id, accessToken, expiresInSec, nowMs);
  return accessToken;
}

import { FcmConfigError } from "./oauth-foundation.ts";
