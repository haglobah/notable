import { Match, Show, Switch, createEffect, createSignal, onCleanup, onMount, type ParentProps } from "solid-js"
import { Route, Router } from "@solidjs/router"
import type { DocHandle, Repo } from "@automerge/automerge-repo"
import { logout, openRepo, whoami, type Me } from "./lib/session.ts"
import { useLibrary } from "./lib/library.ts"
import { takeSharedFiles } from "./lib/shareInbox.ts"
import { Login } from "./components/Login.tsx"
import { Wordmark } from "./components/Marks.tsx"
import { createUploads } from "./components/Uploads.tsx"
import { SessionContext, useSession } from "./components/session.tsx"
import { LibraryView } from "./components/LibraryView.tsx"
import { ReaderView } from "./components/ReaderView.tsx"
import { CodeView } from "./components/CodeView.tsx"
import { startImage } from "./image/service.ts"

type Phase =
  | { p: "checking" }
  | { p: "out"; notice?: string }
  | { p: "in"; me: Me; repo: Repo; library: DocHandle<unknown>; offline: boolean }
  | { p: "error"; message: string }

const OPEN_TIMEOUT_MS = 15_000

/** Finds the library doc locally or on the server; gives up with a reason instead of spinning. */
async function openLibrary(me: Me, repo: Repo, offline: boolean): Promise<DocHandle<unknown>> {
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), OPEN_TIMEOUT_MS)
  try {
    return await repo.find(me.libraryDocUrl, { signal: ac.signal })
  } catch {
    throw new Error(offline
      ? "Your library isn't stored on this device yet, and the server can't be reached. Connect once, then it works offline."
      : "Your library couldn't be loaded from the server. Reload to try again.")
  } finally {
    clearTimeout(timer)
  }
}

export function App() {
  const [phase, setPhase] = createSignal<Phase>({ p: "checking" })

  const start = async (notice?: string) => {
    setPhase({ p: "checking" })
    try {
      const who = await whoami()
      if (who.state === "out") return setPhase({ p: "out", notice })
      const repo = openRepo(who.me)
      const library = await openLibrary(who.me, repo, who.offline)
      setPhase({ p: "in", me: who.me, repo, library, offline: who.offline })
    } catch (e) {
      setPhase({ p: "error", message: (e as Error).message })
    }
  }

  const ended = async () => {
    const cur = phase()
    if (cur.p === "in") await cur.repo.shutdown().catch(() => {})
    setPhase({ p: "out", notice: "Your session has ended. Log in again to keep syncing." })
  }

  onMount(() => {
    const notice = new URLSearchParams(location.search).get("share") === "unavailable"
      ? "Open Notable once after installing it; sharing to it works from then on."
      : undefined
    void start(notice)
  })

  return (
    <Switch>
      <Match when={phase().p === "checking"}>
        <div class="center" aria-busy="true"><Wordmark /></div>
      </Match>
      <Match when={phase().p === "error" && (phase() as Extract<Phase, { p: "error" }>)}>
        {e => (
          <div class="problem" role="alert">
            <h1>Notable can't open</h1>
            <p>{e().message}</p>
            <button class="button" type="button" onClick={() => void start()}>Try again</button>
          </div>
        )}
      </Match>
      <Match when={phase().p === "out" && (phase() as Extract<Phase, { p: "out" }>)}>
        {o => <Login notice={o().notice} onLoggedIn={() => void start()} />}
      </Match>
      <Match when={phase().p === "in" && (phase() as Extract<Phase, { p: "in" }>)} keyed>
        {s => (
          <LoggedIn
            me={s.me} repo={s.repo} library={s.library} offline={s.offline}
            onEnded={ended}
            onLoggedOut={async () => { await logout(s.me, s.repo); setPhase({ p: "out" }) }}
          />
        )}
      </Match>
    </Switch>
  )
}

function LoggedIn(props: { me: Me; repo: Repo; library: DocHandle<unknown>; offline: boolean; onEnded: () => void; onLoggedOut: () => Promise<void> }) {
  const library = useLibrary(props.library)
  const uploads = createUploads({ repo: props.repo, library: props.library, maxBlobBytes: props.me.maxBlobBytes, onUnauthorized: props.onEnded })
  const image = startImage(props.repo, props.library, library)
  onCleanup(() => image.stop())
  const [connected, setConnected] = createSignal(props.repo.peers.length > 0)
  const net = props.repo.networkSubsystem
  const onPeer = () => setConnected(true)
  const onGone = () => setConnected(props.repo.peers.length > 0)
  net.on("peer", onPeer)
  net.on("peer-disconnected", onGone)

  // A revoked or expired session can't be seen on the WebSocket; ask the server when we come back.
  const recheck = async () => {
    if (document.visibilityState !== "visible") return
    const who = await whoami().catch(() => null)
    if (who?.state === "out") props.onEnded()
  }
  window.addEventListener("online", recheck)
  document.addEventListener("visibilitychange", recheck)
  onCleanup(() => {
    net.off("peer", onPeer)
    net.off("peer-disconnected", onGone)
    window.removeEventListener("online", recheck)
    document.removeEventListener("visibilitychange", recheck)
  })

  onMount(async () => {
    const params = new URLSearchParams(location.search)
    if (!params.has("share")) return
    history.replaceState(null, "", location.pathname)
    const shared = await takeSharedFiles()
    // Each shared file leaves the inbox once tried; a failure stays visible in the upload list.
    for (const item of shared) uploads.add([item.file], null, () => item.remove())
  })

  const session = { me: props.me, repo: props.repo, library, uploads, connected, image, logOut: props.onLoggedOut }
  return (
    <SessionContext.Provider value={session}>
      <Router root={Shell}>
        <Route path="/" component={LibraryView} />
        <Route path="/f/:folderId" component={LibraryView} />
        <Route path="/n/:notebookId" component={ReaderView} />
        <Route path="/code" component={CodeView} />
        <Route path="*" component={NotFound} />
      </Router>
    </SessionContext.Provider>
  )
}

function Shell(props: ParentProps) {
  const s = useSession()
  const [menu, setMenu] = createSignal(false)
  // The socket takes a moment to connect; only say "offline" once it has had time to.
  const [offlineFor, setOfflineFor] = createSignal(false)
  let grace: ReturnType<typeof setTimeout> | undefined
  createEffect(() => {
    clearTimeout(grace)
    if (s.connected()) setOfflineFor(false)
    else grace = setTimeout(() => setOfflineFor(true), 4000)
  })
  onCleanup(() => clearTimeout(grace))
  let menuRoot: HTMLDivElement | undefined
  const closeOnOutside = (e: PointerEvent) => { if (menu() && !menuRoot?.contains(e.target as Node)) setMenu(false) }
  const closeOnEscape = (e: KeyboardEvent) => { if (e.key === "Escape") setMenu(false) }
  document.addEventListener("pointerdown", closeOnOutside)
  document.addEventListener("keydown", closeOnEscape)
  onCleanup(() => {
    document.removeEventListener("pointerdown", closeOnOutside)
    document.removeEventListener("keydown", closeOnEscape)
  })

  return (
    <div class="shell">
      <header class="topbar">
        <Wordmark href="/" />
        <div class="topbar-end">
          <a class="button text" href="/code">Code</a>
          <span class={`status${s.connected() ? " connected" : ""}`} role="status">
            {s.connected() ? "Connected" : "Offline"}
          </span>
          <div class="account" ref={menuRoot}>
            <button class="button text" type="button" aria-haspopup="true" aria-expanded={menu()} onClick={() => setMenu(!menu())}>
              {s.me.username}
            </button>
            <Show when={menu()}>
              <div class="account-menu">
                <p>Logging out removes this device's copy of your library. Your Boox and the server keep theirs.</p>
                <button class="button quiet" type="button" onClick={() => void s.logOut()}>Log out</button>
              </div>
            </Show>
          </div>
        </div>
      </header>
      <Show when={offlineFor()}>
        <p class="note" style={{ "margin-bottom": "1.5rem" }}>
          Not connected to the server. You can browse what's on this device. Adding documents needs a connection.
        </p>
      </Show>
      <main>{props.children}</main>
      <Show when={s.image.toast()}>
        {t => <p class="toast" role="status">{t()}</p>}
      </Show>
    </div>
  )
}

function NotFound() {
  return (
    <div class="empty">
      <h2>There's nothing at this address</h2>
      <p><a href="/">Go to the library</a>.</p>
    </div>
  )
}
