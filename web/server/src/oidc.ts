/*
 * A confidential OIDC client for the humane.tools accounts provider (ht's ADR-005): authorization
 * code + PKCE, HS256 ID tokens signed with the client secret. Ported from ht's
 * apps/todos/backend/oidc-client.ts (as Mail ports it), with userinfo reading the email too: the
 * provider's `sub` is the humane tools user id that NOTABLE_HT_LINKS accounts are matched by (ht.ts).
 *
 * Notable asks the provider only at sign-in and then issues its own session, as for an
 * email/password login; it keeps no provider tokens.
 */
import { createHash, randomBytes } from "node:crypto"

export type OidcClient = {
  accountsUrl: string
  clientId: string
  clientSecret: string
  redirectUri: string
  fetch?: typeof fetch
}

/** The provider said no, or could not be asked. */
export type Outcome<T> = { type: "ok"; value: T } | { type: "rejected"; error: string } | { type: "unavailable"; error: string }

export const randomToken = (bytes = 32) => randomBytes(bytes).toString("base64url")

export const pkceChallenge = (verifier: string) => createHash("sha256").update(verifier).digest("base64url")

export const authorizeUrl = (client: OidcClient, p: { state: string; nonce: string; challenge: string; prompt?: string }) =>
  `${client.accountsUrl}/api/auth/oauth2/authorize?${new URLSearchParams({
    response_type: "code",
    client_id: client.clientId,
    redirect_uri: client.redirectUri,
    scope: "openid profile email",
    state: p.state,
    nonce: p.nonce,
    code_challenge: p.challenge,
    code_challenge_method: "S256",
    ...(p.prompt ? { prompt: p.prompt } : {}),
  })}`

const call = async (client: OidcClient, url: string, init: RequestInit) => {
  try {
    return await (client.fetch ?? fetch)(url, { ...init, redirect: "error", signal: AbortSignal.timeout(10_000) })
  } catch (e) {
    return e instanceof Error ? e : new Error(String(e))
  }
}

/** The code's tokens; only the ID token and the access token (for userinfo) are used. */
export async function exchangeCode(client: OidcClient, code: string, verifier: string): Promise<Outcome<{ accessToken: string; idToken: string }>> {
  const r = await call(client, `${client.accountsUrl}/api/auth/oauth2/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: client.redirectUri,
      code_verifier: verifier,
      client_id: client.clientId,
      client_secret: client.clientSecret,
    }),
  })
  if (r instanceof Error) return { type: "unavailable", error: r.message }
  const body = (await r.json().catch(() => null)) as Record<string, unknown> | null
  if (r.status >= 500 || r.status === 429) return { type: "unavailable", error: `token endpoint ${r.status}` }
  if (!r.ok) return { type: "rejected", error: String(body?.error ?? r.status) }
  if (typeof body?.access_token !== "string" || typeof body.id_token !== "string") return { type: "unavailable", error: "token response without access and ID token" }
  return { type: "ok", value: { accessToken: body.access_token, idToken: body.id_token } }
}

export async function userinfo(client: OidcClient, accessToken: string): Promise<Outcome<{ sub: string; email: string; emailVerified: boolean | null }>> {
  const r = await call(client, `${client.accountsUrl}/api/auth/oauth2/userinfo`, { headers: { authorization: `Bearer ${accessToken}` } })
  if (r instanceof Error) return { type: "unavailable", error: r.message }
  if (r.status === 401) return { type: "rejected", error: "invalid_token" }
  if (!r.ok) return { type: "unavailable", error: `userinfo ${r.status}` }
  const body = (await r.json().catch(() => null)) as { sub?: unknown; email?: unknown; email_verified?: unknown } | null
  if (typeof body?.sub !== "string" || !body.sub) return { type: "unavailable", error: "userinfo without sub" }
  if (typeof body.email !== "string" || !body.email) return { type: "unavailable", error: "userinfo without email" }
  return { type: "ok", value: { sub: body.sub, email: body.email.toLowerCase(), emailVerified: typeof body.email_verified === "boolean" ? body.email_verified : null } }
}

const decodeJson = (part: string): Record<string, unknown> | undefined => {
  try {
    const v = JSON.parse(Buffer.from(part, "base64url").toString())
    return v && typeof v === "object" ? v : undefined
  } catch {
    return undefined
  }
}

/**
 * Verifies an HS256 ID token from the provider. Better Auth 1.4.18 sets no `iss`, so this checks
 * signature, alg, aud, exp, nonce and sub only (as ht's clients do).
 */
export async function verifyIdToken(idToken: string, expected: { clientId: string; clientSecret: string; nonce: string; now?: number }): Promise<{ type: "ok"; sub: string } | { type: "invalid"; reason: string }> {
  const parts = idToken.split(".")
  if (parts.length !== 3) return { type: "invalid", reason: "malformed" }
  const [header, payload, signature] = parts as [string, string, string]
  if (decodeJson(header)?.alg !== "HS256") return { type: "invalid", reason: "alg" }
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(expected.clientSecret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"])
  const valid = await crypto.subtle.verify("HMAC", key, Buffer.from(signature, "base64url"), new TextEncoder().encode(`${header}.${payload}`))
  if (!valid) return { type: "invalid", reason: "signature" }
  const claims = decodeJson(payload)
  if (!claims) return { type: "invalid", reason: "malformed" }
  const audiences = Array.isArray(claims.aud) ? claims.aud : [claims.aud]
  if (!audiences.includes(expected.clientId)) return { type: "invalid", reason: "aud" }
  if (typeof claims.exp !== "number" || claims.exp * 1000 <= (expected.now ?? Date.now())) return { type: "invalid", reason: "exp" }
  if (claims.nonce !== expected.nonce) return { type: "invalid", reason: "nonce" }
  if (typeof claims.sub !== "string" || !claims.sub) return { type: "invalid", reason: "sub" }
  return { type: "ok", sub: claims.sub }
}
