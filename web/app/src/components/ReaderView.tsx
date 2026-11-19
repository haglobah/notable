import { For, Show, createEffect, createMemo, createResource, createSignal, on, onCleanup, onMount, untrack, type Accessor } from "solid-js"
import { A, useParams } from "@solidjs/router"
import * as Am from "@automerge/automerge"
import type { AutomergeUrl, DocHandle } from "@automerge/automerge-repo"
import { markRead, readNotebook, setFinished, setOpenPage, setRating, setReading, type Notebook, type NotebookEntry, type Page } from "@notable/schema"
import { edited, kindOf } from "../lib/library.ts"
import {
  canvasWidthOf, docKeyId, docKeyOf, downloadPdf, inkSignature, pageHeight, pagePixels, paintBackground, paintInk, resolveBackground,
  type DocKey, type PageBackground,
} from "@notable/reader"
import { reader } from "../lib/reader.ts"
import { mapToPages, pagesOfDocument, progressOf, progressText, sourceKey, splitSnippet } from "../lib/reading.ts"
import { readerSettings, setReaderSettings, type ReaderLayout, type ReaderTheme } from "../lib/readerSettings.ts"
import { bylineOf } from "../lib/books.ts"
import { MIN_ZOOM, attachPinchZoom, type PinchZoom } from "../lib/zoom.ts"
import { useSession } from "./session.tsx"

/** A page write a second after the reader settles there: scrolling past pages writes nothing. */
const POSITION_DELAY_MS = 1000

type Panel = "none" | "contents" | "search" | "settings"
/** A request to show a page; `n` makes the same page twice a new request. */
type Jump = { pageId: string; n: number }
/** Search hits to draw: quads in document points, per notebook page. */
type Highlights = Map<string, number[][]>

/** The pages with the Boox's ink on top, updating live; the reading position syncs both ways. */
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

  // Opened: once per visit, so "Continue reading" and is:reading know about it.
  createEffect(on(() => entry()?.id, id => {
    if (!id) return
    try {
      s.libraryHandle.change((d: any) => markRead(d, id, new Date()))
    } catch (e) {
      console.warn(`can't record reading ${id}:`, e)
    }
  }))

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

  const [panel, setPanel] = createSignal<Panel>("none")
  const toggle = (p: Panel) => setPanel(panel() === p ? "none" : p)
  const [jump, setJump] = createSignal<Jump | null>(null)
  let jumps = 0
  const goTo = (pageId: string) => setJump({ pageId, n: ++jumps })
  const [highlights, setHighlights] = createSignal<Highlights>(new Map())
  const [current, setCurrent] = createSignal(-1)

  // The page across the middle of the screen becomes the doc's open page, once the reader rests.
  let pending: { pageId: string; timer: ReturnType<typeof setTimeout> } | undefined
  const writePosition = (pageId: string) => {
    const h = handle(), n = nb(), e = entry()
    if (!h || !n || !e || !(pageId in n.pages)) return
    try {
      if (n.openPageId !== pageId) h.change((d: any) => setOpenPage(d, pageId))
      // The library's copy, for sorting by progress without opening every notebook.
      const readPage = n.pageOrder.indexOf(pageId)
      if (e.readPage !== readPage) s.libraryHandle.change((d: any) => setReading(d, e.id, { readPage }))
    } catch (err) {
      console.warn(`can't save the reading position ${pageId}:`, err)
    }
  }
  // The library's page count follows the notebook (pages added on the Boox, docs from before it).
  createEffect(() => {
    const n = nb(), e = entry()
    if (!n || !e || e.pageCount === n.pageOrder.length) return
    try {
      s.libraryHandle.change((d: any) => setReading(d, e.id, { pageCount: n.pageOrder.length }))
    } catch (err) {
      console.warn(`can't save the page count of ${e.id}:`, err)
    }
  })
  const flushPosition = () => {
    if (!pending) return
    clearTimeout(pending.timer)
    const id = pending.pageId
    pending = undefined
    writePosition(id)
  }
  onCleanup(flushPosition)
  const onCurrent = (i: number) => {
    const n = nb(), e = entry()
    if (!n || !e) return
    setCurrent(i)
    s.image.setOpenPage({ id: n.pageOrder[i], notebookId: e.kind === "quickPage" ? null : e.id, index: i, pageCount: n.pageOrder.length })
    if (pending) clearTimeout(pending.timer)
    const pageId = n.pageOrder[i]
    pending = { pageId, timer: setTimeout(flushPosition, POSITION_DELAY_MS) }
  }

  const closeOnEscape = (ev: KeyboardEvent) => { if (ev.key === "Escape" && panel() !== "none") setPanel("none") }
  document.addEventListener("keydown", closeOnEscape)
  onCleanup(() => document.removeEventListener("keydown", closeOnEscape))

  return (
    <Show when={entry()} fallback={
      <div class="empty">
        <h2>This document isn't in your library</h2>
        <p>It may have been deleted on another device. <A href="/">Go to the library</A>.</p>
      </div>
    }>
      {e => {
        const key = createMemo(() => sourceKey(e()))
        const progress = createMemo(() => {
          const n = nb()
          if (!n) return null
          const i = current()
          return i >= 0 ? { index: i, count: n.pageOrder.length, fraction: n.pageOrder.length === 1 ? 1 : i / (n.pageOrder.length - 1) } : progressOf(n)
        })
        return (
          <article class="reader">
            <A class="back" href={back()}>Back to {backLabel()}</A>
            <h1 class="place">{e().title || "Untitled"}</h1>
            <Show when={bylineOf(e())}>{b => <p class="byline">{b()}</p>}</Show>
            <p class="reader-meta">
              {kindOf(e())}
              <Show when={nb()}>{n => `, ${n().pageOrder.length} page${n().pageOrder.length === 1 ? "" : "s"}`}</Show>
              , edited {edited(e().updatedAt)}
              <Show when={e().finishedAt}>, finished</Show>
            </p>
            <Show when={openError()}>
              {m => <p class="alert" role="alert">This notebook can't be opened: {m()}</p>}
            </Show>
            <Show when={handle() && nb()}>
              <div class="reader-actions">
                <Show when={key()}>
                  <button class="button quiet" type="button" aria-pressed={panel() === "contents"} onClick={() => toggle("contents")}>Contents</button>
                  <button class="button quiet" type="button" aria-pressed={panel() === "search"} onClick={() => toggle("search")}>Search</button>
                </Show>
                <button class="button quiet" type="button" aria-pressed={panel() === "settings"} onClick={() => toggle("settings")}>View</button>
                <FinishedButton entry={e()} />
                <Download handle={handle()!} entry={e()} />
              </div>
              <Commands />
              <Show when={progress()}>{p => <p class="reader-position" aria-live="polite">{progressText(p())}</p>}</Show>
              <Rating entry={e()} />
              <Show when={panel() !== "none"}>
                <aside class="reader-panel" aria-label={panel() === "contents" ? "Contents" : panel() === "search" ? "Search in this book" : "View"}>
                  <div class="reader-panel-head">
                    <h2>{panel() === "contents" ? "Contents" : panel() === "search" ? "Search" : "View"}</h2>
                    <button class="button text" type="button" onClick={() => setPanel("none")}>Close</button>
                  </div>
                  <Show when={panel() === "contents" && key()}>
                    {k => <Contents k={k()} nb={nb()!} entry={e()} current={current()} onPick={id => { goTo(id); setPanel("none") }} />}
                  </Show>
                  <Show when={panel() === "search" && key()}>
                    {k => <Search k={k()} nb={nb()!} entry={e()} onPick={goTo} onHits={setHighlights} />}
                  </Show>
                  <Show when={panel() === "settings"}><ViewSettings /></Show>
                </aside>
              </Show>
              <Pages nb={nb()!} entry={e()} jump={jump} highlights={highlights} onCurrent={onCurrent} />
            </Show>
          </article>
        )
      }}
    </Show>
  )
}

function FinishedButton(p: { entry: NotebookEntry }) {
  const s = useSession()
  const [error, setError] = createSignal<string | null>(null)
  const set = (at: Date | null) => {
    setError(null)
    try {
      s.libraryHandle.change((d: any) => setFinished(d, p.entry.id, at))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  return (
    <>
      <Show when={p.entry.finishedAt} fallback={<button class="button quiet" type="button" onClick={() => set(new Date())}>Mark as finished</button>}>
        <button class="button quiet" type="button" onClick={() => set(null)}>Mark as unread</button>
      </Show>
      <Show when={error()}>{m => <p class="alert" role="alert">This couldn't be saved: {m()}</p>}</Show>
    </>
  )
}

/** 1 to 10, tap the current one again (or Clear) to take it back. */
function Rating(p: { entry: NotebookEntry }) {
  const s = useSession()
  const [error, setError] = createSignal<string | null>(null)
  const set = (r: number | null) => {
    setError(null)
    try {
      s.libraryHandle.change((d: any) => setRating(d, p.entry.id, r))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  const current = () => (typeof p.entry.rating === "number" ? p.entry.rating : null)
  return (
    <div class="rating" role="group" aria-label="Your rating">
      <span class="rating-label">Rating</span>
      <For each={[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]}>
        {n => (
          <button type="button" class="rating-step" aria-pressed={current() === n} aria-label={`${n} of 10`}
            classList={{ below: current() !== null && n < current()! }} onClick={() => set(current() === n ? null : n)}>
            {n}
          </button>
        )}
      </For>
      <Show when={current() !== null}>
        <button type="button" class="button text" onClick={() => set(null)}>Clear</button>
      </Show>
      <Show when={error()}>{m => <p class="alert" role="alert">The rating couldn't be saved: {m()}</p>}</Show>
    </div>
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
      await downloadPdf(reader, Am.save(p.handle.doc()), p.entry, (done, total) => setState({ s: "working", done, total }))
      setState({ s: "idle" })
    } catch (e) {
      setState({ s: "failed", message: (e as Error).message })
    }
  }
  const working = () => { const st = state(); return st.s === "working" ? st : null }
  const failed = () => { const st = state(); return st.s === "failed" ? st : null }
  return (
    <>
      <button class="button" type="button" onClick={run} disabled={!!working()}>
        {working() ? "Preparing PDF…" : "Download PDF"}
      </button>
      <Show when={working()?.total}>
        <span class="reader-progress" aria-live="polite">page {working()!.done} of {working()!.total}</span>
      </Show>
      <Show when={failed()}>
        {st => <p class="alert" role="alert">The PDF couldn't be made: {st().message}</p>}
      </Show>
    </>
  )
}

const missingNote = (n: number, what: string) =>
  n === 0 ? null : `${n} ${what}${n === 1 ? " points at a page" : " point at pages"} this notebook no longer has.`

function Contents(p: { k: DocKey; nb: Notebook; entry: NotebookEntry; current: number; onPick: (pageId: string) => void }) {
  const [outline] = createResource(() => docKeyId(p.k), () => reader.outline(p.k))
  const mapped = createMemo(() => {
    const o = outline()
    return o ? mapToPages(o, pagesOfDocument(p.nb, p.entry, p.k)) : null
  })
  const indexOf = createMemo(() => new Map(p.nb.pageOrder.map((id, i) => [id, i])))
  // The entry you are in: the last one starting at or before the current page.
  const here = createMemo(() => {
    const m = mapped()
    if (!m) return -1
    let at = -1
    m.mapped.forEach((x, i) => { if ((indexOf().get(x.pageId) ?? Infinity) <= p.current) at = i })
    return at
  })
  return (
    <Show when={!outline.error} fallback={<p class="alert" role="alert">The contents can't be read: {(outline.error as Error)?.message}</p>}>
      <Show when={mapped()} fallback={<p class="reader-panel-note" aria-busy="true">Reading the contents…</p>}>
        {m => (
          <>
            <Show when={m().mapped.length > 0} fallback={<p class="reader-panel-note">This document has no table of contents.</p>}>
              <ol class="toc">
                <For each={m().mapped}>
                  {(x, i) => (
                    <li style={{ "--depth": x.depth }}>
                      <button type="button" class="toc-item" aria-current={i() === here() ? "true" : undefined} onClick={() => p.onPick(x.pageId)}>
                        <span>{x.title}</span>
                        <span class="toc-page">{(indexOf().get(x.pageId) ?? 0) + 1}</span>
                      </button>
                    </li>
                  )}
                </For>
              </ol>
            </Show>
            <Show when={missingNote(m().missing, m().missing === 1 ? "entry" : "entries")}>{t => <p class="reader-panel-note">{t()}</p>}</Show>
          </>
        )}
      </Show>
    </Show>
  )
}

type SearchState =
  | { s: "idle" }
  | { s: "searching"; query: string; done: number; total: number }
  | { s: "done"; query: string; hits: { pageId: string; page: number; snippet: string }[]; missing: number }
  | { s: "failed"; message: string }

function Search(p: { k: DocKey; nb: Notebook; entry: NotebookEntry; onPick: (pageId: string) => void; onHits: (h: Highlights) => void }) {
  const [state, setState] = createSignal<SearchState>({ s: "idle" })
  const [query, setQuery] = createSignal("")
  const indexOf = createMemo(() => new Map(p.nb.pageOrder.map((id, i) => [id, i])))
  let run = 0
  const submit = async (ev: Event) => {
    ev.preventDefault()
    const q = query().trim()
    p.onHits(new Map())
    if (!q) return setState({ s: "idle" })
    const mine = ++run
    setState({ s: "searching", query: q, done: 0, total: 0 })
    try {
      const hits = await reader.search(p.k, q, (done, total) => { if (mine === run) setState({ s: "searching", query: q, done, total }) })
      if (mine !== run) return
      const { mapped, missing } = mapToPages(hits, pagesOfDocument(p.nb, p.entry, p.k))
      p.onHits(new Map(mapped.map(h => [h.pageId, h.quads])))
      setState({ s: "done", query: q, hits: mapped.map(h => ({ pageId: h.pageId, page: h.page, snippet: h.snippet })), missing })
    } catch (e) {
      if (mine === run) setState({ s: "failed", message: (e as Error).message })
    }
  }
  const st = state
  return (
    <>
      <form class="book-search" role="search" onSubmit={submit}>
        <input type="search" aria-label="Search in this book" placeholder="Word or phrase" value={query()} onInput={ev => setQuery(ev.currentTarget.value)} autofocus />
        <button class="button" type="submit" disabled={st().s === "searching"}>Search</button>
      </form>
      <Show when={st().s === "searching" && (st() as Extract<SearchState, { s: "searching" }>)}>
        {x => <p class="reader-panel-note" aria-live="polite">{x().total ? `Searching page ${x().done} of ${x().total}…` : "Searching…"}</p>}
      </Show>
      <Show when={st().s === "failed" && (st() as Extract<SearchState, { s: "failed" }>)}>
        {x => <p class="alert" role="alert">The search failed: {x().message}</p>}
      </Show>
      <Show when={st().s === "done" && (st() as Extract<SearchState, { s: "done" }>)}>
        {x => (
          <>
            <p class="reader-panel-note" aria-live="polite">
              {x().hits.length === 0 ? `No pages contain “${x().query}”.` : `${x().hits.length} page${x().hits.length === 1 ? "" : "s"} contain “${x().query}”.`}
            </p>
            <ol class="hits">
              <For each={x().hits}>
                {h => {
                  const [before, match, after] = splitSnippet(h.snippet, x().query)
                  return (
                    <li>
                      <button type="button" class="hit" onClick={() => p.onPick(h.pageId)}>
                        <span class="hit-page">Page {(indexOf().get(h.pageId) ?? 0) + 1}</span>
                        <span class="hit-snippet">{before}<mark>{match}</mark>{after}</span>
                      </button>
                    </li>
                  )
                }}
              </For>
            </ol>
            <Show when={missingNote(x().missing, x().missing === 1 ? "hit" : "hits")}>{t => <p class="reader-panel-note">{t()}</p>}</Show>
          </>
        )}
      </Show>
    </>
  )
}

const THEME_LABELS: Record<ReaderTheme, string> = { paper: "Paper", sepia: "Sepia", night: "Night" }
const LAYOUT_LABELS: Record<ReaderLayout, string> = { scroll: "Scroll", paged: "One page at a time" }

function ViewSettings() {
  return (
    <div class="view-settings">
      <fieldset>
        <legend>Pages</legend>
        <For each={Object.keys(THEME_LABELS) as ReaderTheme[]}>
          {t => (
            <label class="choice">
              <input type="radio" name="reader-theme" checked={readerSettings().theme === t} onChange={() => setReaderSettings({ theme: t })} />
              {THEME_LABELS[t]}
            </label>
          )}
        </For>
      </fieldset>
      <fieldset>
        <legend>Layout</legend>
        <For each={Object.keys(LAYOUT_LABELS) as ReaderLayout[]}>
          {l => (
            <label class="choice">
              <input type="radio" name="reader-layout" checked={readerSettings().layout === l} onChange={() => setReaderSettings({ layout: l })} />
              {LAYOUT_LABELS[l]}
            </label>
          )}
        </For>
      </fieldset>
      <p class="reader-panel-note">Text size is fixed so handwriting stays where it was written. Pinch to zoom.</p>
    </div>
  )
}

type Sized = { id: string; page: Page; bg: PageBackground; key: DocKey | null; height: number; docSize: [number, number] | null }

function Pages(p: { nb: Notebook; entry: NotebookEntry; jump: Accessor<Jump | null>; highlights: Accessor<Highlights>; onCurrent: (index: number) => void }) {
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
    const docSize = (Array.isArray(sz) && bg.kind === "document" ? sz[bg.index] : undefined) ?? null
    const height = pageHeight(page, bg, cw(), docSize ?? undefined)
    return [id, { id, page, bg, key, height, docSize }]
  })))
  const docError = (key: DocKey | null) => {
    if (!key) return null
    const v = sizes()[docKeyId(key)]
    return v instanceof Error ? v : null
  }
  // Sizes known (or failed) for every document the pages use: heights no longer change under a jump.
  const settled = createMemo(() => [...pages().values()].every(x => {
    if (!x.key) return true
    const v = sizes()[docKeyId(x.key)]
    return v instanceof Error || (Array.isArray(v) && v.length > 0)
  }))

  let scroller!: HTMLDivElement
  let list!: HTMLOListElement
  let pinch: PinchZoom | undefined
  const [zoom, setZoom] = createSignal(MIN_ZOOM)
  onMount(() => {
    pinch = attachPinchZoom(scroller, list, zoom, setZoom)
    onCleanup(() => pinch!.detach())
  })
  const fit = () => pinch?.zoomTo(MIN_ZOOM, { x: window.innerWidth / 2, y: window.innerHeight / 2 })

  const layout = () => readerSettings().layout
  // Where the last reader left off; the doc's openPageId at the time the reader opened.
  const initial = Math.max(0, untrack(() => (p.nb.openPageId ? p.nb.pageOrder.indexOf(p.nb.openPageId) : 0)))
  const [current, setCurrent] = createSignal(initial)
  // Until the open page is shown, the page that happens to cross the middle isn't a position.
  const [restored, setRestored] = createSignal(false)
  const reportScrolled = (i: number) => {
    if (!restored()) return
    setCurrent(i)
    p.onCurrent(i)
  }

  const show = (index: number) => {
    const i = Math.min(Math.max(0, index), p.nb.pageOrder.length - 1)
    setCurrent(i)
    if (layout() === "paged") window.scrollTo({ top: Math.max(0, scroller.getBoundingClientRect().top + window.scrollY - 8) })
    else list.querySelector<HTMLElement>(`[data-page="${i}"]`)?.scrollIntoView({ block: "start" })
    if (restored()) p.onCurrent(i)
  }

  // Open there once the page heights are real; observers fired by the scroll are ignored until then.
  createEffect(on(settled, ok => {
    if (!ok || restored()) return
    if (initial > 0 || layout() === "paged") show(initial)
    requestAnimationFrame(() => requestAnimationFrame(() => { setRestored(true); p.onCurrent(current()) }))
  }))
  createEffect(on(layout, () => { if (restored()) requestAnimationFrame(() => show(current())) }, { defer: true }))
  createEffect(on(p.jump, j => {
    if (!j) return
    const i = p.nb.pageOrder.indexOf(j.pageId)
    if (i >= 0) show(i)
  }, { defer: true }))

  // One page at a time: keys, taps at the edges and swipes turn pages.
  const turn = (by: number) => show(current() + by)
  const onKey = (ev: KeyboardEvent) => {
    if (layout() !== "paged" || ev.defaultPrevented || ev.altKey || ev.ctrlKey || ev.metaKey) return
    const t = ev.target as HTMLElement | null
    if (t && (t.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON)$/.test(t.tagName))) return
    if (ev.key === "ArrowRight" || ev.key === "PageDown" || (ev.key === " " && !ev.shiftKey)) { ev.preventDefault(); turn(1) }
    else if (ev.key === "ArrowLeft" || ev.key === "PageUp" || (ev.key === " " && ev.shiftKey)) { ev.preventDefault(); turn(-1) }
  }
  document.addEventListener("keydown", onKey)
  onCleanup(() => document.removeEventListener("keydown", onKey))
  let swipe: { x: number; y: number; id: number } | null = null
  const down = (ev: PointerEvent) => {
    // A second finger is a pinch, not a swipe or a tap.
    swipe = layout() === "paged" && ev.isPrimary && zoom() === MIN_ZOOM ? { x: ev.clientX, y: ev.clientY, id: ev.pointerId } : null
  }
  const up = (ev: PointerEvent) => {
    const s = swipe
    swipe = null
    if (!s || s.id !== ev.pointerId || layout() !== "paged") return
    const dx = ev.clientX - s.x, dy = ev.clientY - s.y
    if (Math.abs(dx) > 50 && Math.abs(dx) > 2 * Math.abs(dy)) return turn(dx < 0 ? 1 : -1)
    if (Math.abs(dx) < 10 && Math.abs(dy) < 10) {
      const r = list.getBoundingClientRect()
      const at = (ev.clientX - r.left) / r.width
      if (at < 0.3) turn(-1)
      else if (at > 0.7) turn(1)
    }
  }

  return (
    <>
      <div class="pages-scroller" ref={scroller} onPointerDown={down} onPointerUp={up} onPointerCancel={() => { swipe = null }}>
        <ol class={`pages theme-${readerSettings().theme}`} classList={{ paged: layout() === "paged" }} ref={list} style={{ width: `${zoom() * 100}%`, "--zoom": zoom() }}>
          <For each={p.nb.pageOrder}>
            {(id, i) => (
              <Show when={pages().get(id)}>
                {x => (
                  <PageView sized={x()} cw={cw()} n={i() + 1} index={i()} current={layout() === "paged" && i() === current()}
                    docError={docError(x().key)} hits={p.highlights().get(id)} onCurrent={() => { if (layout() === "scroll") reportScrolled(i()) }} />
                )}
              </Show>
            )}
          </For>
        </ol>
      </div>
      <Show when={layout() === "paged"}>
        <nav class="page-turn" aria-label="Pages">
          <button class="button quiet" type="button" onClick={() => turn(-1)} disabled={current() <= 0}>Previous</button>
          <span class="page-turn-at">{current() + 1} / {p.nb.pageOrder.length}</span>
          <button class="button quiet" type="button" onClick={() => turn(1)} disabled={current() >= p.nb.pageOrder.length - 1}>Next</button>
        </nav>
      </Show>
      <Show when={zoom() > MIN_ZOOM}>
        <button class="button zoom-fit" type="button" onClick={fit}>Fit width ({Math.round(zoom() * 100)}%)</button>
      </Show>
    </>
  )
}

function PageView(p: { sized: Sized; cw: number; n: number; index: number; current: boolean; docError: Error | null; hits: number[][] | undefined; onCurrent: () => void }) {
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
  // Quads are ul, ur, ll, lr in document points; the page is the document page scaled to its width.
  const hitPolygons = () => (p.hits ?? []).map(q => `${q[0]},${q[1]} ${q[2]},${q[3]} ${q[6]},${q[7]} ${q[4]},${q[5]}`)
  return (
    <li class="page" classList={{ loading: loading(), current: p.current }} aria-busy={loading()} ref={box} data-page={p.index}
      style={{ "aspect-ratio": `${p.cw} / ${p.sized.height}`, "--aspect": p.cw / p.sized.height }} aria-label={`Page ${p.n}`}>
      <canvas ref={bgCanvas} class="page-layer" aria-hidden="true" />
      <canvas ref={inkCanvas} class="page-layer" aria-hidden="true" />
      <Show when={p.hits && p.sized.docSize}>
        {size => (
          <svg class="page-layer page-hits" viewBox={`0 0 ${size()[0]} ${size()[1]}`} preserveAspectRatio="none" aria-hidden="true">
            <For each={hitPolygons()}>{pts => <polygon points={pts} />}</For>
          </svg>
        )}
      </Show>
      <Show when={message()}>{m => <p class="page-problem">{m()}</p>}</Show>
      <span class="page-number" aria-hidden="true">{p.n}</span>
    </li>
  )
}
