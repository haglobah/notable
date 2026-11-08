import { createResource, createSignal, Show } from "solid-js"
import { login, LoginError } from "../lib/session.ts"
import { Wordmark } from "./Marks.tsx"

/** What the server offers (GET /api/login-methods); offline or an older server: the password only. */
const loginMethods = async (): Promise<{ humaneTools: boolean }> => {
  try {
    const r = await fetch("/api/login-methods")
    return r.ok ? ((await r.json()) as { humaneTools: boolean }) : { humaneTools: false }
  } catch {
    return { humaneTools: false }
  }
}

/**
 * What the server's codes for a failed sign-in through humane tools mean (SIGN_IN_ERRORS in
 * server/src/server.ts). Only these texts are shown: anyone can link to /?login_error=….
 */
const LOGIN_ERRORS: Record<string, string> = {
  expired: "The sign-in expired or was started in another window. Try again.",
  login_required: "Sign in to humane tools first, then try again.",
  refused: "humane tools did not accept the sign-in.",
  unavailable: "humane tools can't be reached right now. Try again later.",
  invalid_answer: "humane tools answered with something Notable could not check.",
  email_unverified: "Verify your humane tools email address first.",
  no_account: "This humane tools account has no Notable account here. Ask the admin to link it.",
}

/** A failed sign-in through humane tools comes back as /?login_error=<code>; shown once. */
const takeLoginError = (): string | null => {
  const url = new URL(location.href)
  const code = url.searchParams.get("login_error")
  if (code === null) return null
  url.searchParams.delete("login_error")
  history.replaceState(history.state, "", url)
  const reason = Object.hasOwn(LOGIN_ERRORS, code) ? LOGIN_ERRORS[code] : "Something went wrong."
  return `Signing in with humane tools failed. ${reason}`
}

export function Login(props: { notice?: string; onLoggedIn: () => void }) {
  const [error, setError] = createSignal<string | null>(takeLoginError())
  const [methods] = createResource(loginMethods)
  const [busy, setBusy] = createSignal(false)

  const submit = async (e: SubmitEvent) => {
    e.preventDefault()
    const form = new FormData(e.currentTarget as HTMLFormElement)
    setBusy(true)
    setError(null)
    try {
      await login(String(form.get("username") ?? ""), String(form.get("password") ?? ""))
      props.onLoggedIn()
    } catch (err) {
      setError(err instanceof LoginError ? err.message : `Logging in failed: ${(err as Error).message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main class="login">
      <div class="login-inner">
        <div>
          <Wordmark large drawn />
          <p class="login-lede">Your documents and notebooks from the Boox, on any screen.</p>
        </div>
        <Show when={props.notice}>
          <p class="note" role="status">{props.notice}</p>
        </Show>
        <Show when={methods()?.humaneTools}>
          <div class="login-ht">
            <a class="button" href="/api/oidc/start">Sign in with humane tools</a>
            <p class="login-or">or with a Notable username and password:</p>
          </div>
        </Show>
        <form class="form" onSubmit={submit}>
          <div class="field">
            <label for="username">Username</label>
            <input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck={false} required />
          </div>
          <div class="field">
            <label for="password">Password</label>
            <input id="password" name="password" type="password" autocomplete="current-password" required />
          </div>
          <Show when={error()}>
            <p class="alert" role="alert">{error()}</p>
          </Show>
          <button class="button" type="submit" disabled={busy()}>{busy() ? "Logging in…" : "Log in"}</button>
        </form>
      </div>
    </main>
  )
}
