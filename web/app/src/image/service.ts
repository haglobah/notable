/*
 * The live code image in the PWA (ADR-002): the runtime fed by the library's code doc, the
 * commands, the open page, safe mode and the in-page REPL device. Android's twin is
 * ImageService.kt. The PWA has no REPL socket; the code panel talks to `repl` directly.
 */
import { createSignal, type Accessor } from "solid-js"
import type { DocHandle, Repo } from "@automerge/automerge-repo"
import { replDevice, type CommandInfo } from "@notable/repl"
import prelude from "@notable/schema/runtime/prelude.js?raw"
import type { LibraryState } from "../lib/library.ts"
import { JsRuntime } from "./engine.ts"
import { CodeDoc, type CodeState } from "./codeDoc.ts"
import { libraryView, pageView, type OpenPage } from "./host.ts"
import { LiveImage, type Mode } from "./liveImage.ts"
import type { DefStatus } from "@notable/repl"

const SAFE_KEY = "notable.image.safeMode"
const STARTS_KEY = "notable.image.failedStarts"
/** Failed starts in a row before safe mode, as on Android (BootGuard). */
const LIMIT = 2

const stored = (k: string) => { try { return localStorage.getItem(k) } catch { return null } }
const store = (k: string, v: string | null) => {
  try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v) } catch { /* private mode */ }
}

export type ImageService = {
  image: LiveImage
  mode: Accessor<Mode>
  defs: Accessor<DefStatus[]>
  commands: Accessor<CommandInfo[]>
  code: Accessor<CodeState>
  toast: Accessor<string | null>
  setOpenPage: (p: OpenPage | null) => void
  runCommand: (id: string) => void
  repl: ReturnType<typeof replDevice>
  safeModeSetting: Accessor<boolean>
  setSafeModeSetting: (on: boolean) => void
  stop: () => void
}

export function startImage(repo: Repo, library: DocHandle<unknown>, libraryState: Accessor<LibraryState>): ImageService {
  const [toast, setToast] = createSignal<string | null>(null)
  let toastTimer: ReturnType<typeof setTimeout> | undefined
  const snack = (text: string) => {
    clearTimeout(toastTimer)
    setToast(text)
    toastTimer = setTimeout(() => setToast(null), 4000)
  }
  let openPage: OpenPage | null = null
  const host = {
    log: (line: string) => console.info(`[image] ${line}`),
    snack,
    libraryJson: () => { const l = libraryState(); return libraryView(l.ok ? l.library : null) },
    pageJson: () => pageView(openPage),
  }
  const image = new LiveImage(new JsRuntime(), host, [
    { id: "command/page-info", title: "Page info", run: () => snack(openPage ? `Page ${openPage.index + 1} of ${openPage.pageCount}` : "No page is open") },
    { id: "command/image-status", title: "Live code status", run: img => {
      const d = img.defs()
      snack(`Live code: ${img.mode()}, ${d.filter(x => x.state === "loaded").length} loaded, ${d.filter(x => x.state === "failed").length} failed`)
    } },
  ])
  const [mode, setMode] = createSignal<Mode>(image.modeValue())
  const [defs, setDefs] = createSignal<DefStatus[]>([])
  const [commands, setCommands] = createSignal<CommandInfo[]>(image.commands())
  const [code, setCode] = createSignal<CodeState>({ s: "none" })
  image.subscribe(() => {
    setMode(image.modeValue())
    setDefs(image.defs())
    setCommands(image.commands())
  })

  // Safe mode: a start counts as failed until the image has loaded once.
  const failed = Number(stored(STARTS_KEY) ?? "0") || 0
  store(STARTS_KEY, String(failed + 1))
  const [safeModeSetting, setSafe] = createSignal(stored(SAFE_KEY) === "1")
  const safeReason = safeModeSetting() ? "switched on" : failed >= LIMIT ? `the last ${failed} starts didn't finish loading the image` : null

  const codeDoc = new CodeDoc(repo, library, s => {
    setCode(s)
    if (s.s === "ready") image.apply(s.image.defs)
    else if (s.s === "none") image.apply({})
    store(STARTS_KEY, "0")
  })
  void image.start(prelude, safeReason).then(() => {
    if (safeReason !== null) store(STARTS_KEY, "0")
    codeDoc.start()
  })

  return {
    image, mode, defs, commands, code, toast,
    setOpenPage: p => { openPage = p },
    runCommand: id => {
      const r = image.runCommand(id)
      if (!r.ok) snack(`Command failed: ${r.error}`)
    },
    repl: replDevice({ token: null, device: "pwa", image, docs: codeDoc }),
    safeModeSetting,
    setSafeModeSetting: on => { setSafe(on); store(SAFE_KEY, on ? "1" : null) },
    stop: () => codeDoc.stop(),
  }
}
