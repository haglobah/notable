/*
 * Sign-in with a humane tools (HT) account: Todos' auth server (Better Auth) checks the email and
 * password, and Notable then issues its own session as for a password login. HT is asked only at
 * login, so an HT outage never ends a Notable session, and Android needs no cookies.
 *
 * The server calls HT itself, without cookies or Fetch Metadata headers, so Better Auth's origin
 * check doesn't apply and HT needn't list this origin in its trustedOrigins. The HT session that
 * sign-in creates is signed out again at once; Notable never keeps it.
 */
export type HtAccount = { id: string; email: string }

export type HtResult =
  | { ok: true; account: HtAccount }
  | { ok: false; status: 401 | 403 | 429 | 503; error: string; retryAfter?: string }

const unavailable: HtResult = { ok: false, status: 503, error: "humane tools sign-in is unavailable" }

export function openHt(service: string, { timeoutMs = 5000, fetcher = fetch } = {}) {
  const base = `${service.replace(/\/$/, "")}/api/auth`
  const call = (path: string, init: RequestInit) =>
    fetcher(`${base}/${path}`, { ...init, redirect: "error", signal: AbortSignal.timeout(timeoutMs) })

  /** Ends the session a sign-in created. Best effort: HT expires it on its own anyway. */
  const signOut = (setCookies: string[]) => {
    const cookie = setCookies.map(c => c.split(";")[0]).join("; ")
    if (!cookie) return
    // A request with cookies must carry a trusted origin; HT always trusts its own.
    call("sign-out", { method: "POST", headers: { cookie, origin: new URL(service).origin } }).then(r => r.body?.cancel(), () => {})
  }

  const signIn = async (email: string, password: string): Promise<HtResult> => {
    let r: Response
    try {
      r = await call("sign-in/email", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ email, password, rememberMe: false }),
      })
    } catch {
      return unavailable
    }
    if (r.status === 401) return { ok: false, status: 401, error: "invalid username or password" }
    if (r.status === 403) return { ok: false, status: 403, error: "verify your humane tools email address first" }
    if (r.status === 429) return { ok: false, status: 429, error: "too many failed logins, try again later", retryAfter: r.headers.get("retry-after") ?? "60" }
    if (!r.ok) return unavailable
    const data = (await r.json().catch(() => null)) as { user?: { id?: unknown; email?: unknown } } | null
    signOut(r.headers.getSetCookie())
    const { id, email: confirmed } = data?.user ?? {}
    if (typeof id !== "string" || !id || typeof confirmed !== "string") return unavailable
    return { ok: true, account: { id, email: confirmed.toLowerCase() } }
  }

  return { signIn }
}

export type Ht = ReturnType<typeof openHt>
