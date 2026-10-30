/*
 * Sign-in with a humane tools (HT) account: Todos' auth server (Better Auth) checks the email and
 * password, and Notable then issues its own session as for a password login. HT is asked only at
 * login, so an HT outage never ends a Notable session, and Android needs no cookies.
 *
 * Node's fetch sends `Sec-Fetch-Mode: cors`, so Better Auth checks the Origin of every call. They
 * carry HT's own origin, which HT always trusts; HT needn't list this server in its
 * trustedOrigins. The HT session that sign-in creates is signed out again at once; Notable never
 * keeps it.
 */
export type HtAccount = { id: string; email: string }

export type HtResult =
  | { ok: true; account: HtAccount }
  | { ok: false; status: 401 | 403 | 429 | 503; error: string; retryAfter?: string }

const unavailable: HtResult = { ok: false, status: 503, error: "humane tools sign-in is unavailable" }

export function openHt(service: string, { timeoutMs = 5000, fetcher = fetch } = {}) {
  const base = `${service.replace(/\/$/, "")}/api/auth`
  const origin = new URL(service).origin
  const call = (path: string, init: RequestInit & { headers: Record<string, string> }) =>
    fetcher(`${base}/${path}`, { ...init, headers: { ...init.headers, origin }, redirect: "error", signal: AbortSignal.timeout(timeoutMs) })

  /** Ends the session a sign-in created. Best effort: HT expires it on its own anyway. */
  const signOut = (setCookies: string[]) => {
    const cookie = setCookies.map(c => c.split(";")[0]).join("; ")
    if (!cookie) return
    call("sign-out", { method: "POST", headers: { cookie } }).then(r => r.body?.cancel(), () => {})
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
    if (r.status === 403) {
      const { code } = ((await r.json().catch(() => null)) ?? {}) as { code?: unknown }
      return code === "EMAIL_NOT_VERIFIED" ? { ok: false, status: 403, error: "verify your humane tools email address first" } : unavailable
    }
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
