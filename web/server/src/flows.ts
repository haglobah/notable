/*
 * The state of sign-ins through the humane.tools accounts provider (oidc.ts), kept in memory: a
 * restart in the middle of one only means signing in again.
 *
 * - A pending sign-in is what /api/oidc/start or /api/connect sent to the provider, keyed by its
 *   `state` and bound to the browser by a cookie holding that state: the callback takes it once.
 * - A connect code is what /api/oidc/callback hands to a connecting app (Flow) at its registered
 *   redirect URL; the app's server swaps it once, with the PKCE verifier only it has, for a
 *   session token (POST /api/connect/token).
 */
import type { User } from "./accounts.ts"

/** What to do once the provider says who it is: sign the browser in, or give an app a code. */
export type Purpose = { kind: "login" } | { kind: "connect"; client: string; redirectUri: string; state: string; challenge: string }

export type Pending = { purpose: Purpose; nonce: string; verifier: string; expires: number }
export type ConnectCode = { user: User; client: string; redirectUri: string; challenge: string; expires: number }

const PENDING_MS = 10 * 60_000
const CODE_MS = 60_000
const MAX_ENTRIES = 1000

/** A map whose entries expire, taken at most once, and bounded (the oldest go first). */
class OnceStore<T extends { expires: number }> {
  #entries = new Map<string, T>()
  put(key: string, value: T) {
    const now = Date.now()
    for (const [k, v] of this.#entries) if (v.expires <= now) this.#entries.delete(k)
    while (this.#entries.size >= MAX_ENTRIES) this.#entries.delete(this.#entries.keys().next().value!)
    this.#entries.set(key, value)
  }
  take(key: string): T | null {
    const v = this.#entries.get(key)
    this.#entries.delete(key)
    return v && v.expires > Date.now() ? v : null
  }
}

export class SignInFlows {
  readonly pending = new OnceStore<Pending>()
  readonly codes = new OnceStore<ConnectCode>()

  begin(state: string, purpose: Purpose, nonce: string, verifier: string) {
    this.pending.put(state, { purpose, nonce, verifier, expires: Date.now() + PENDING_MS })
  }

  issueCode(code: string, user: User, purpose: Extract<Purpose, { kind: "connect" }>) {
    this.codes.put(code, { user, client: purpose.client, redirectUri: purpose.redirectUri, challenge: purpose.challenge, expires: Date.now() + CODE_MS })
  }
}
