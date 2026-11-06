import { For, Show, createEffect, createMemo, createResource, createSignal, onCleanup, onMount } from "solid-js"
import { A, useParams } from "@solidjs/router"
import * as Am from "@automerge/automerge"
import type { AutomergeUrl, DocHandle } from "@automerge/automerge-repo"
import { readNotebook, type Notebook, type NotebookEntry, type Page } from "@notable/schema"
import { edited, kindOf } from "../lib/library.ts"
import {
  canvasWidthOf, docKeyId, docKeyOf, inkSignature, pageHeight, pagePixels, paintBackground, paintInk, resolveBackground,
  type DocKey, type PageBackground,
} from "@notable/reader"
import { pdfFileName } from "@notable/reader/project"
import { reader } from "../lib/reader.ts"
import { MIN_ZOOM, attachPinchZoom, type PinchZoom } from "../lib/zoom.ts"
import { useSession } from "./session.tsx"

/** Read-only pages with the Boox's ink on top, updating live, and the one download: a PDF. */
export function ReaderView() {
  const params = useParams<{ notebookId: string }>()
  const s = useSession()
  const entry = createMemo(() => {
    const l = s.library()
    return l.ok ? l.library.notebooks[params.notebookId] ?? null : null
  })
  const [handle] = createResource(() => entry()?.docUrl, url => s.repo.find(url as AutomergeUrl))
  const [notebook, setNotebook] = createSignal<{ ok: true; nb: Notebook } | { ok: false; error: string }>()
  createEffect(() => {
    const h = handle()
    if (!h) return
    const read = () => {
      try {
        setNotebook({ ok: true, nb: readNotebook(h.doc()) })
      } catch (e) {
        setNotebook({ ok: false, error: (e as Error).message })
      }
    }
    read()
    h.on("change", read)
    onCleanup(() => h.off("change", read))
  })
  const back = () => (entry()?.folderId ? `/f/${entry()!.folderId}` : "/")
  const backLabel = createMemo(() => {
    const l = s.library()
    const folderId = entry()?.folderId
    if (!folderId) return "Library"
    return (l.ok && l.library.folders[folderId]?.title) || "folder"
  })
  const nb = () => { const n = notebook(); return n?.ok ? n.nb : null }
  const openError = () => {
    if (handle.error) return (handle.error as Error).message
    const n = notebook()
    return n && !n.ok ? n.error : null
  }

  return (
    <Show when={entry()} fallback={
      <div class="empty">
        <h2>This document isn't in your library</h2>
        <p>It may have been deleted on another device. <A href="/">Go to the library</A>.</p>
      </div>
    }>
      {e => (
        <article class="reader">
          <A class="back" href={back()}>Back to {backLabel()}</A>
          <h1 class="place">{e().title || "Untitled"}</h1>
          <p class="reader-meta">
            {kindOf(e())}
            <Show when={nb()}>{n => `, ${n().pageOrder.length} page${n().pageOrder.length === 1 ? "" : "s"}`}</Show>
            , edited {edited(e().updatedAt)}
          </p>
          <Show when={openError()}>
            {m => <p class="alert" role="alert">This notebook can't be opened: {m()}</p>}
          </Show>
          <Show when={handle() && nb()}>
            <Download handle={handle()!} entry={e()} />
            <Commands />
            <Pages nb={nb()!} entry={e()} onCurrent={i => {
              const n = nb()
              s.image.setOpenPage(n ? { id: n.pageOrder[i], notebookId: e().kind === "quickPage" ? null : e().id, index: i, pageCount: n.pageOrder.length } : null)
            }} />
          </Show>
        </article>
      )}
    </Show>
  )
}

/** The live code image's commands (ADR-002); errors show as a toast. */
function Commands() {
  const s = useSession()
  onCleanup(() => s.image.setOpenPage(null))
  return (
    <Show when={s.image.commands().length > 0}>
      <div class="reader-actions" aria-label="Commands">
        <For each={s.image.commands()}>
          {c => <button class="button quiet" type="button" onClick={() => s.image.runCommand(c.id)}>{c.title}</button>}
        </For>
      </div>
    </Show>
  )
}

type DownloadState = { s: "idle" } | { s: "working"; done: number; total: number } | { s: "failed"; message: string }

function Download(p: { handle: DocHandle<unknown>; entry: NotebookEntry }) {
  const [state, setState] = createSignal<DownloadState>({ s: "idle" })
  const run = async () => {
    setState({ s: "working", done: 0, total: 0 })
    try {
      const pdf = await reader.projectPdf(Am.save(p.handle.doc()), p.entry, (done, total) => setState({ s: "working", done, total }))
      const url = URL.createObjectURL(new Blob([pdf as Uint8Array<ArrayBuffer>], { type: "application/pdf" }))
      const a = document.createElement("a")
      a.href = url
      a.download = pdfFileName(p.entry.title)
      document.body.append(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(url), 60_000)
      setState({ s: "idle" })
    } catch (e) {
      setState({ s: "failed", message: (e as Error).message })
    }
  }
  const working = () => { const st = state(); return st.s === "working" ? st : null }
  const failed = () => { const st = state(); return st.s === "failed" ? st : null }
  return (
    <div class="reader-actions">
      <button class="button" type="button" onClick={run} disabled={!!working()}>
        {working() ? "Preparing PDF…" : "Download PDF"}
      </button>
      <Show when={working()?.total}>
        <span class="reader-progress" aria-live="polite">page {working()!.done} of {working()!.total}</span>
      </Show>
      <Show when={failed()}>
        {st => <p class="alert" role="alert">The PDF couldn't be made: {st().message}</p>}
      </Show>
    </div>
  )
}

type Sized = { id: string; page: Page; bg: PageBackground; key: DocKey | null; height: number }

function Pages(p: { nb: Notebook; entry: NotebookEntry; onCurrent: (index: number) => void }) {
  const cw = () => canvasWidthOf(p.nb)
  // Each source document's page sizes, fetched once; a document page fixes the page's height.
  const [sizes, setSizes] = createSignal<Record<string, [number, number][] | Error>>({})
  createEffect(() => {
    for (const id of p.nb.pageOrder) {
      const key = docKeyOf(resolveBackground(p.nb, p.entry, id))
      if (!key) continue
      const k = docKeyId(key)
      if (k in sizes()) continue
      setSizes(m => ({ ...m, [k]: [] }))
      reader.pageSizes(key).then(v => setSizes(m => ({ ...m, [k]: v })), e => setSizes(m => ({ ...m, [k]: e as Error })))
    }
  })
  const pages = createMemo(() => new Map(p.nb.pageOrder.map((id): [string, Sized] => {
    const page = p.nb.pages[id]
    const bg = resolveBackground(p.nb, p.entry, id)
    const key = docKeyOf(bg)
    const sz = key && sizes()[docKeyId(key)]
    const height = pageHeight(page, bg, cw(), Array.isArray(sz) && bg.kind === "document" ? sz[bg.index] : undefined)
    return [id, { id, page, bg, key, height }]
  })))
  const docError = (key: DocKey | null) => {
    if (!key) return null
    const v = sizes()[docKeyId(key)]
    return v instanceof Error ? v : null
  }

  let scroller!: HTMLDivElement
  let list!: HTMLOListElement
  let pinch: PinchZoom | undefined
  const [zoom, setZoom] = createSignal(MIN_ZOOM)
  onMount(() => {
    pinch = attachPinchZoom(scroller, list, zoom, setZoom)
    onCleanup(() => pinch!.detach())
  })
  const fit = () => pinch?.zoomTo(MIN_ZOOM, { x: window.innerWidth / 2, y: window.innerHeight / 2 })

  return (
    <>
      <div class="pages-scroller" ref={scroller}>
        <ol class="pages" ref={list} style={{ width: `${zoom() * 100}%`, "--zoom": zoom() }}>
          <For each={p.nb.pageOrder}>
            {(id, i) => (
              <Show when={pages().get(id)}>
                {x => <PageView sized={x()} cw={cw()} n={i() + 1} docError={docError(x().key)} onCurrent={() => p.onCurrent(i())} />}
              </Show>
            )}
          </For>
        </ol>
      </div>
      <Show when={zoom() > MIN_ZOOM}>
        <button class="button zoom-fit" type="button" onClick={fit}>Fit width ({Math.round(zoom() * 100)}%)</button>
      </Show>
    </>
  )
}

function PageView(p: { sized: Sized; cw: number; n: number; docError: Error | null; onCurrent: () => void }) {
  let box!: HTMLLIElement
  let bgCanvas!: HTMLCanvasElement
  let inkCanvas!: HTMLCanvasElement
  const [near, setNear] = createSignal(false)
  const [width, setWidth] = createSignal(0)
  const [problem, setProblem] = createSignal<string | null>(null)
  const [loading, setLoading] = createSignal(false)

  onMount(() => {
    // Draw pages within about a screen of the viewport; free the canvases of the rest.
    const io = new IntersectionObserver(es => setNear(es[0].isIntersecting), { rootMargin: "120% 0px" })
    io.observe(box)
    // The page across the middle of the viewport is the open page (notable.page()).
    const middle = new IntersectionObserver(es => { if (es[0].isIntersecting) p.onCurrent() }, { rootMargin: "-50% 0px -50% 0px" })
    middle.observe(box)
    const ro = new ResizeObserver(() => setWidth(box.clientWidth))
    ro.observe(box)
    onCleanup(() => { io.disconnect(); middle.disconnect(); ro.disconnect() })
  })

  const pixels = createMemo(() => pagePixels(width(), p.sized.height, p.cw, window.devicePixelRatio),
    undefined, { equals: (a, b) => a?.w === b?.w && a?.h === b?.h })

  // Background: document page bitmap, template or image.
  const bgSig = createMemo(() => JSON.stringify(p.sized.bg))
  createEffect(() => {
    const px = pixels(), on = near()
    bgSig()
    if (!px || !on) {
      bgCanvas.width = 0; bgCanvas.height = 0
      return
    }
    let alive = true
    onCleanup(() => { alive = false })
    bgCanvas.width = px.w; bgCanvas.height = px.h
    setProblem(null)
    const isDocument = p.sized.bg.kind === "document"
    if (isDocument) {
      setLoading(true)
      onCleanup(() => setLoading(false))
    }
    paintBackground(reader, bgCanvas.getContext("2d")!, p.sized.bg, px, p.cw, p.sized.height, () => alive)
      .catch(e => alive && setProblem((e as Error).message))
      .finally(() => isDocument && setLoading(false))
  })

  // Ink: images and strokes.
  const inkSig = createMemo(() => inkSignature(p.sized.page))
  createEffect(() => {
    const px = pixels(), on = near()
    inkSig()
    if (!px || !on) {
      inkCanvas.width = 0; inkCanvas.height = 0
      return
    }
    let alive = true
    onCleanup(() => { alive = false })
    // Cleared when the new ink is ready, so a redraw doesn't blink.
    const ink = document.createElement("canvas")
    ink.width = px.w; ink.height = px.h
    void paintInk(reader, ink.getContext("2d")!, p.sized.page, px, () => alive).then(() => {
      if (!alive) return
      inkCanvas.width = px.w; inkCanvas.height = px.h
      inkCanvas.getContext("2d")!.drawImage(ink, 0, 0)
    })
  })

  const message = () => problem() ?? (p.docError ? `This document can't be opened: ${p.docError.message}` : null)
  return (
    <li class="page" classList={{ loading: loading() }} aria-busy={loading()} ref={box} style={{ "aspect-ratio": `${p.cw} / ${p.sized.height}` }} aria-label={`Page ${p.n}`}>
      <canvas ref={bgCanvas} class="page-layer" aria-hidden="true" />
      <canvas ref={inkCanvas} class="page-layer" aria-hidden="true" />
      <Show when={message()}>{m => <p class="page-problem">{m()}</p>}</Show>
      <span class="page-number" aria-hidden="true">{p.n}</span>
    </li>
  )
}
