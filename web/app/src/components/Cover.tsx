import { Show, createSignal, onCleanup, onMount } from "solid-js"
import type { AutomergeUrl, DocHandle } from "@automerge/automerge-repo"
import { readNotebook, type NotebookEntry } from "@notable/schema"
import { coverSignature } from "../lib/cover.ts"
import { kindOf } from "../lib/library.ts"
import { paintCover } from "../lib/paint.ts"
import { Glyph } from "./Marks.tsx"
import { useSession } from "./session.tsx"

/** Painted covers by entry id; a revisit redraws only when the cover page or card width changed. */
const painted = new Map<string, { sig: string; width: number; canvas: HTMLCanvasElement }>()
const MAX_PAINTED = 60

/** Covers load whole notebook docs; a few at a time keeps a full screen of cards from stalling. */
let running = 0
const waiting: (() => void)[] = []
async function oneAtATime<T>(work: () => Promise<T>): Promise<T> {
  if (running >= 2) await new Promise<void>(r => waiting.push(r))
  running++
  try {
    return await work()
  } finally {
    running--
    waiting.shift()?.()
  }
}

/** The first page with its ink, drawn once the card nears the viewport; the kind's glyph until then. */
export function Cover(p: { entry: NotebookEntry }) {
  const s = useSession()
  let box!: HTMLDivElement
  let canvas!: HTMLCanvasElement
  const [shown, setShown] = createSignal(false)

  const show = (src: HTMLCanvasElement) => {
    canvas.width = src.width
    canvas.height = src.height
    canvas.getContext("2d")!.drawImage(src, 0, 0)
    setShown(true)
  }

  onMount(() => {
    let alive = true
    let handle: DocHandle<unknown> | undefined
    let busy = false, again = false

    const refresh = async () => {
      if (busy) { again = true; return }
      busy = true
      try {
        do {
          again = false
          const h = handle!
          const entry = p.entry
          const nb = readNotebook(h.doc())
          const sig = coverSignature(nb, entry)
          const width = box.clientWidth
          const have = painted.get(entry.id)
          if (have && have.sig === sig && have.width === width) { show(have.canvas); continue }
          const c = await oneAtATime(() => paintCover(nb, entry, width, window.devicePixelRatio))
          if (!alive) return
          painted.delete(entry.id)
          painted.set(entry.id, { sig, width, canvas: c })
          if (painted.size > MAX_PAINTED) painted.delete(painted.keys().next().value!)
          show(c)
        } while (again && alive)
      } catch (e) {
        // The glyph stays; the card still opens the notebook.
        console.warn(`cover of "${p.entry.title}" (${p.entry.id}) can't be drawn:`, e)
      } finally {
        busy = false
      }
    }

    const cached = painted.get(p.entry.id)
    if (cached) show(cached.canvas)

    const start = async () => {
      try {
        handle = await oneAtATime(() => s.repo.find(p.entry.docUrl as AutomergeUrl))
      } catch (e) {
        console.warn(`cover of "${p.entry.title}" (${p.entry.id}): notebook can't be opened:`, e)
        return
      }
      if (!alive) return
      handle.on("change", refresh)
      refresh()
    }
    const io = new IntersectionObserver(es => {
      if (!es.some(e => e.isIntersecting)) return
      io.disconnect()
      start()
    }, { rootMargin: "50% 0px" })
    io.observe(box)
    onCleanup(() => {
      alive = false
      io.disconnect()
      handle?.off("change", refresh)
    })
  })

  return (
    <div class="cover" ref={box} aria-hidden="true">
      <Show when={!shown()}><Glyph kind={kindOf(p.entry)} /></Show>
      <canvas ref={canvas} class="cover-page" classList={{ shown: shown() }} />
    </div>
  )
}
