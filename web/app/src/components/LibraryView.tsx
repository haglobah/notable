import { For, Show, createMemo, createSignal, onCleanup } from "solid-js"
import { A, useParams, useSearchParams } from "@solidjs/router"
import { facetsOf, isFolderTrashed, normalize, parseQuery, queryLibrary, readNext, type Facet, type Facets, type Library, type NotebookEntry, type SortKey } from "@notable/schema"
import { childFolders, countIn, edited, entriesIn, folderPath, kindOf, openedAgo, recentEntries } from "../lib/library.ts"
import { itemKey, moveItems, setItemsFinished, setItemsQueued, trashItems, type Item } from "../lib/libraryActions.ts"
import { hideFinished, setHideFinished } from "../lib/libraryPrefs.ts"
import { bylineOf, quickFirst } from "../lib/books.ts"
import { directionLabel, parseSort, selected, setOnly, toggle, withoutFields, type SortChoice } from "../lib/filters.ts"
import { useSession } from "./session.tsx"
import { Glyph } from "./Marks.tsx"
import { Cover } from "./Cover.tsx"
import { DropZone, UploadList } from "./Uploads.tsx"

/** Fields the filter bar sets; "Clear filters" removes these and keeps typed words. */
const FILTER_FIELDS = ["is", "kind", "author", "series", "tag", "lang"]

export function LibraryView() {
  const params = useParams<{ folderId?: string }>()
  const [search, setSearch] = useSearchParams<{ q?: string; sort?: string; dir?: string; in?: string }>()
  const s = useSession()
  const folderId = () => params.folderId ?? null
  const query = () => search.q ?? ""
  const choice = createMemo(() => parseSort(search.sort, search.dir))
  const setQuery = (q: string) => setSearch({ q: q.trim() ? q : undefined }, { replace: true })
  const setChoice = (c: SortChoice | null) => setSearch({ sort: c?.sort, dir: c ? (c.descending ? "desc" : "asc") : undefined }, { replace: true })

  // Hide finished (per device); an explicit `is:` filter asks for states by name and wins.
  const shown = (list: NotebookEntry[]) => (hideFinished() && !selected(query(), "is").length ? list.filter(e => !e.finishedAt) : list)
  // Selecting (docs/plans/library-features.md): null when not; else the chosen items by key.
  const [picked, setPicked] = createSignal<Map<string, Item> | null>(null)
  const toggleItem = (i: Item) => setPicked(m => {
    const next = new Map(m ?? [])
    if (next.has(itemKey(i))) next.delete(itemKey(i)); else next.set(itemKey(i), i)
    return next
  })
  const isPicked = (i: Item) => picked()?.has(itemKey(i)) ?? false

  return (
    <Show when={s.library().ok ? s.library() : null} keyed fallback={<LibraryProblem />}>
      {state => {
        const lib = () => (state as Extract<typeof state, { ok: true }>).library
        const path = createMemo(() => folderPath(lib(), folderId()))
        const missing = () => folderId() !== null && (!lib().folders[folderId()!] || isFolderTrashed(lib(), folderId()))
        const folders = createMemo(() => childFolders(lib(), folderId()))
        // No query: this folder's own entries, in the chosen order or the most recently edited first.
        const entries = createMemo(() => {
          const c = choice()
          const ordered = c ? queryLibrary(lib(), { query: "", sort: c.sort, descending: c.descending, folderId: folderId() }) : entriesIn(lib(), folderId())
          return quickFirst(lib(), shown(ordered))
        })
        const here = () => (path().length ? path()[path().length - 1].title : "Library")
        // In a folder a query covers it and its subfolders, unless "Everywhere" is on; at the root, everything.
        const everywhere = () => folderId() === null || search.in === "all"
        const scope = () => (everywhere() ? {} : { folderId: folderId(), subfolders: true })
        const results = createMemo(() => {
          if (!parseQuery(query()).length) return null
          const c = choice()
          return shown(queryLibrary(lib(), { query: query(), ...scope(), ...(c ? { sort: c.sort, descending: c.descending } : {}) }))
        })
        const facets = createMemo(() => facetsOf(queryLibrary(lib(), { query: "", ...scope() })))
        const recent = createMemo(() => recentEntries(lib()))
        const next = createMemo(() => readNext(lib()).filter(e => e.pageCount !== 0))
        // Quick pages (old single-page entries) aren't selectable: the Boox keeps them as page rows without a trash.
        const selectable = (): Item[] => [
          ...(results() ?? entries()).filter(e => e.kind !== "quickPage").map(e => ({ kind: "notebook" as const, id: e.id })),
          ...(results() ? [] : folders().map(f => ({ kind: "folder" as const, id: f.id }))),
        ]
        const [moving, setMoving] = createSignal(false)
        const [note, setNote] = createSignal("")
        // [say] gets how many changed (what [what] returns, else the items) and how many of them are books.
        const act = (what: (d: any, items: Item[], at: Date) => number | void, say: (n: number, books: number) => string) => {
          const items = [...(picked()?.values() ?? [])]
          const bookCount = items.filter(i => i.kind === "notebook").length
          let n = items.length
          s.libraryHandle.change((d: any) => { const r = what(d, items, new Date()); if (typeof r === "number") n = r })
          setPicked(null)
          setNote(say(n, bookCount))
        }
        const books = () => [...(picked()?.values() ?? [])].filter(i => i.kind === "notebook").map(i => lib().notebooks[i.id]).filter(Boolean)
        const plural = (n: number, one: string) => `${n} ${one}${n === 1 ? "" : "s"}`
        const where = () => (everywhere() ? "in your library" : `in ${here()} and its folders`)

        return (
          <Show when={!missing()} fallback={<FolderGone />}>
            <section aria-label={here()}>
              {/* No headings (round 5): inside a folder the breadcrumb says where you are. */}
              <div class="library-head">
                <Show when={path().length > 0} fallback={<span />}>
                  <nav aria-label="Folders">
                    <ol class="crumbs">
                      <li><A href="/">Library</A></li>
                      <For each={path().slice(0, -1)}>{f => <li><A href={`/f/${f.id}`}>{f.title}</A></li>}</For>
                      <li aria-current="page">{here()}</li>
                    </ol>
                  </nav>
                </Show>
                <div class="head-actions">
                  <Show when={picked() === null}>
                    <button type="button" class="button text" onClick={() => { setNote(""); setPicked(new Map()) }}>Select</button>
                    <A class="button text" href="/trash">Trash</A>
                  </Show>
                  <AddButton folderId={folderId()} />
                </div>
              </div>
              <DropZone label={`Drop to add to ${here()}`} onFiles={files => s.uploads.add(files, folderId())} />
              <UploadList uploads={s.uploads} />
              <Show when={picked()} fallback={
                <LibraryBar query={query()} onQuery={setQuery} facets={facets()} choice={choice()} onChoice={setChoice} searching={!!results()} recent={recent()} next={next()} />
              }>
                {m => {
                  const allRead = () => books().length > 0 && books().every(e => e.finishedAt)
                  const allQueued = () => books().length > 0 && books().every(e => e.queuedAt)
                  return (
                    <div class="select-bar" role="toolbar" aria-label="Selection">
                      <span class="select-count" aria-live="polite">{m().size} selected</span>
                      <Show when={m().size < selectable().length}>
                        <button type="button" class="button text" onClick={() => setPicked(new Map(selectable().map(i => [itemKey(i), i])))}>Select all</button>
                      </Show>
                      <span class="select-gap" />
                      <button type="button" class="button quiet" disabled={!m().size} onClick={() => setMoving(true)}>Move…</button>
                      <Show when={books().length > 0}>
                        <button type="button" class="button quiet" onClick={() => {
                          const read = !allRead()
                          act((d, items, at) => setItemsFinished(d, items, read ? at : null), (_, b) => `Marked ${plural(b, "book")} as ${read ? "read" : "unread"}`)
                        }}>{allRead() ? "Mark as unread" : "Mark as read"}</button>
                        <button type="button" class="button quiet" onClick={() => {
                          const queue = !allQueued()
                          act((d, items, at) => setItemsQueued(d, items, queue ? at : null), () => queue ? "Added to Read next" : "Removed from Read next")
                        }}>{allQueued() ? "Remove from Read next" : "Add to Read next"}</button>
                      </Show>
                      <button type="button" class="button quiet danger" disabled={!m().size}
                        onClick={() => act((d, items, at) => trashItems(d, items, at), n => `Moved ${plural(n, "item")} to the trash`)}>Move to trash</button>
                      <button type="button" class="button" onClick={() => setPicked(null)}>Done</button>
                    </div>
                  )
                }}
              </Show>
              <Show when={note()}>
                <p class="select-note" role="status">{note()}<Show when={note().includes("trash")}> · <A href="/trash">Open the trash</A></Show></p>
              </Show>
              <Show when={moving()}>
                <MovePicker lib={lib()} from={folderId()} exclude={[...(picked()?.values() ?? [])].filter(i => i.kind === "folder").map(i => i.id)}
                  onCancel={() => setMoving(false)}
                  onPick={target => {
                    setMoving(false)
                    act((d, items, at) => moveItems(d, items, target, at), n => `Moved ${plural(n, "item")}`)
                  }} />
              </Show>

              <Show when={results()}>
                {r => (
                  <section aria-label="Search results">
                    <p class="search-count" aria-live="polite">
                      {r().length === 0 ? `Nothing ${where()} matches.` : `${r().length} match${r().length === 1 ? "" : "es"} ${where()}`}
                      <Show when={folderId() !== null}>
                        {" · "}
                        <button type="button" class="link" onClick={() => setSearch({ in: everywhere() ? undefined : "all" }, { replace: true })}>
                          {everywhere() ? `Search only ${here()}` : "Search everywhere"}
                        </button>
                      </Show>
                    </p>
                    <Show when={r().length > 0}>
                      <ul class="shelf cards" aria-label="Matches">
                        <For each={r()}>{e => <li><EntryCard lib={lib()} entry={e} withFolder picking={picked() !== null} picked={isPicked({ kind: "notebook", id: e.id })} onPick={() => toggleItem({ kind: "notebook", id: e.id })} /></li>}</For>
                      </ul>
                    </Show>
                  </section>
                )}
              </Show>

              <Show when={!results()}>
              <Show when={folders().length + entries().length > 0} fallback={<Empty inFolder={folderId() !== null} />}>
                <Show when={folders().length > 0}>
                  <ul class="shelf" aria-label="Folders">
                    <For each={folders()}>
                      {f => {
                        const n = () => countIn(lib(), f.id)
                        const body = () => (
                          <>
                            <Glyph kind="Folder" />
                            <span>
                              <span class="item-title">{f.title}</span>
                              <span class="item-meta" style={{ display: "block" }}>{n() === 0 ? "Empty" : `${n()} item${n() === 1 ? "" : "s"}`}</span>
                            </span>
                          </>
                        )
                        return (
                          <li>
                            <Show when={picked() !== null} fallback={<A class="item" href={`/f/${f.id}`}>{body()}</A>}>
                              <button type="button" class="item pick" aria-pressed={isPicked({ kind: "folder", id: f.id })}
                                onClick={() => toggleItem({ kind: "folder", id: f.id })}>
                                <span class="pick-mark" aria-hidden="true" />{body()}
                              </button>
                            </Show>
                          </li>
                        )
                      }}
                    </For>
                  </ul>
                </Show>
                <Show when={entries().length > 0}>
                  <ul class="shelf cards" aria-label="Documents and notebooks">
                    <For each={entries()}>{e => <li><EntryCard lib={lib()} entry={e} picking={picked() !== null} picked={isPicked({ kind: "notebook", id: e.id })} onPick={() => toggleItem({ kind: "notebook", id: e.id })} /></li>}</For>
                  </ul>
                </Show>
              </Show>
              </Show>
            </section>
          </Show>
        )
      }}
    </Show>
  )
}

function EntryCard(p: { lib: Library; entry: NotebookEntry; withFolder?: boolean; picking?: boolean; picked?: boolean; onPick?: () => void }) {
  const folder = () => {
    if (!p.withFolder || !p.entry.folderId) return null
    return folderPath(p.lib, p.entry.folderId).map(f => f.title).join(" / ") || null
  }
  const body = () => (
    <>
      <Cover entry={p.entry} />
      <span class="card-title">{p.entry.title || "Untitled"}</span>
      <Show when={bylineOf(p.entry)}>{b => <span class="card-byline">{b()}</span>}</Show>
      <span class="item-meta">
        <Show when={typeof p.entry.rating === "number"}><span class="card-rating">★ {p.entry.rating}</span>{" "}</Show>
        {kindOf(p.entry)}, {p.entry.finishedAt ? "finished" : `edited ${edited(p.entry.updatedAt)}`}
        <Show when={folder()}>{f => <>, in {f()}</>}</Show>
        <Show when={p.entry.queuedAt && !p.entry.finishedAt}>, read next</Show>
      </span>
    </>
  )
  return (
    <Show when={p.picking && p.entry.kind !== "quickPage"} fallback={<A class="card" href={`/n/${p.entry.id}`}>{body()}</A>}>
      <button type="button" class="card pick" aria-pressed={!!p.picked} onClick={() => p.onPick?.()}>
        <span class="pick-mark" aria-hidden="true" />{body()}
      </button>
    </Show>
  )
}

/** Where to move the selection: every live folder as a tree, the root first. */
function MovePicker(p: { lib: Library; from: string | null; exclude: string[]; onCancel: () => void; onPick: (folderId: string | null) => void }) {
  let dialog: HTMLDialogElement | undefined
  const rows = () => {
    const out: { id: string; title: string; depth: number }[] = []
    const walk = (parent: string | null, depth: number) => {
      for (const f of childFolders(p.lib, parent)) {
        if (p.exclude.includes(f.id)) continue
        out.push({ id: f.id, title: f.title, depth })
        walk(f.id, depth + 1)
      }
    }
    walk(null, 1)
    return out
  }
  return (
    <dialog ref={el => { dialog = el; queueMicrotask(() => el.showModal()) }} class="move-picker" aria-label="Move to"
      onCancel={e => { e.preventDefault(); p.onCancel() }}
      onClick={e => { if (e.target === dialog) p.onCancel() }}>
      <h2>Move to</h2>
      <ul>
        <li><button type="button" class="menu-row" aria-current={p.from === null ? "true" : undefined} onClick={() => p.onPick(null)}>Library</button></li>
        <For each={rows()}>
          {r => (
            <li><button type="button" class="menu-row" style={{ "padding-left": `${r.depth * 1.1 + 0.75}rem` }}
              aria-current={p.from === r.id ? "true" : undefined} onClick={() => p.onPick(r.id)}>{r.title}</button></li>
          )}
        </For>
      </ul>
      <div class="move-actions"><button type="button" class="button quiet" onClick={() => p.onCancel()}>Cancel</button></div>
    </dialog>
  )
}

const STATES: [string | null, string][] = [[null, "All"], ["reading", "Reading"], ["unread", "Unread"], ["finished", "Finished"], ["next", "Read next"]]
const KIND_LABELS: Record<string, string> = { pdf: "PDF", epub: "EPUB", notebook: "Notebook", quickpage: "Quick page" }
const SORTS: [SortKey, string][] = [
  ["relevance", "Best match"], ["title", "Title"], ["author", "Author"], ["series", "Series"],
  ["length", "Length"], ["progress", "Progress"], ["rating", "Rating"],
  ["published", "Published"], ["added", "Added"], ["edited", "Edited"], ["read", "Last read"], ["finished", "Finished"],
]

/** Closes a popover on a tap outside it; menus open anchored to their own button. */
function usePopover() {
  let el: HTMLDetailsElement | undefined
  const close = () => { if (el) el.open = false }
  const outside = (e: PointerEvent) => { if (el?.open && !el.contains(e.target as Node)) close() }
  // Fixed, placed from the button: the facet line scrolls sideways and would clip it otherwise.
  const place = () => {
    if (!el?.open) return
    const pop = el.querySelector<HTMLElement>(".facet-pop"), button = el.querySelector("summary")
    if (!pop || !button) return
    const r = button.getBoundingClientRect(), gutter = 16, w = pop.offsetWidth
    const left = pop.classList.contains("facet-pop-end") ? r.right - w : r.left
    pop.style.left = `${Math.max(gutter, Math.min(left, window.innerWidth - w - gutter))}px`
    pop.style.top = `${r.bottom + 2}px`
    pop.style.maxHeight = `${Math.max(160, window.innerHeight - r.bottom - gutter)}px`
  }
  document.addEventListener("pointerdown", outside)
  window.addEventListener("scroll", close, { passive: true })
  window.addEventListener("resize", close)
  onCleanup(() => {
    document.removeEventListener("pointerdown", outside)
    window.removeEventListener("scroll", close)
    window.removeEventListener("resize", close)
  })
  return {
    ref: (x: HTMLDetailsElement) => { el = x; x.addEventListener("toggle", place) },
    close,
  }
}

/**
 * The two lines above the notebooks (docs/plans/ebook-features.md, "Library bar design"):
 * search (✕ empties it, ? shows the syntax), Recent and sort; then reading state and the facet
 * menus. Every filter control writes the query text.
 */
function LibraryBar(p: {
  query: string; onQuery: (q: string) => void; facets: Facets
  choice: SortChoice | null; onChoice: (c: SortChoice | null) => void; searching: boolean
  recent: NotebookEntry[]; next: NotebookEntry[]
}) {
  let input: HTMLInputElement | undefined
  const states = () => selected(p.query, "is").map(normalize)
  const anyFilter = () => FILTER_FIELDS.some(f => selected(p.query, f).length > 0)
  const kinds = () => p.facets.kinds.map(k => ({ ...k, label: KIND_LABELS[k.value] ?? k.value }))
  return (
    <div class="lib-bar">
      <div class="lib-line">
        <form class="lib-search" role="search" onSubmit={e => e.preventDefault()}>
          <svg class="lib-search-glyph" viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" /><path d="M10.4 10.4 14 14" /></svg>
          <input ref={input} type="search" aria-label="Search the library" placeholder="Search titles, authors, tags…"
            value={p.query} onInput={e => p.onQuery(e.currentTarget.value)} />
          <Show when={p.query}>
            <button type="button" class="lib-text lib-clear" aria-label="Clear search" title="Clear search"
              onClick={() => { p.onQuery(""); input?.focus() }}>
              <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2 2l8 8M10 2l-8 8" /></svg>
            </button>
          </Show>
          <QueryHelp />
        </form>
        <Show when={p.recent.length > 0}><RecentMenu recent={p.recent} /></Show>
        <Show when={p.next.length > 0}><ReadNextMenu next={p.next} /></Show>
        <SortControl choice={p.choice} onChoice={p.onChoice} searching={p.searching} />
      </div>
      <div class="lib-line lib-filters">
        <div class="segments" role="radiogroup" aria-label="Reading">
          <For each={STATES}>
            {([value, label]) => {
              const on = () => (value === null ? states().length === 0 : states().includes(value))
              return (
                <button type="button" role="radio" class="segment" aria-checked={on()}
                  onClick={() => p.onQuery(setOnly(p.query, "is", value))}>{label}</button>
              )
            }}
          </For>
        </div>
        <FacetMenu label="Author" field="author" options={p.facets.authors} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Series" field="series" options={p.facets.series} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Tag" field="tag" options={p.facets.tags} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Language" field="lang" options={p.facets.languages} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Type" field="kind" options={kinds()} query={p.query} onQuery={p.onQuery} />
        <button type="button" class="lib-text lib-toggle" aria-pressed={hideFinished()} onClick={() => setHideFinished(!hideFinished())}>Hide finished</button>
        <Show when={anyFilter()}>
          <button type="button" class="lib-text lib-clear" aria-label="Clear filters" title="Clear filters" onClick={() => p.onQuery(withoutFields(p.query, FILTER_FIELDS))}>
            <svg viewBox="0 0 12 12" aria-hidden="true"><path d="M2 2l8 8M10 2l-8 8" /></svg>
          </button>
        </Show>
      </div>
    </div>
  )
}

/** "Recent": the documents opened last, anywhere in the library, each with when; a tap opens it. */
function RecentMenu(p: { recent: NotebookEntry[] }) {
  const pop = usePopover()
  return (
    <details class="facet lib-recent" ref={pop.ref}>
      <summary class="lib-text">Recent</summary>
      <div class="facet-pop facet-pop-end" role="menu" aria-label="Recently opened">
        <ul>
          <For each={p.recent}>
            {e => (
              <li>
                <A href={`/n/${e.id}`} role="menuitem" class="menu-row recent-row" onClick={() => pop.close()}>
                  <span class="recent-title">{e.title || "Untitled"}</span>
                  <span class="menu-hint">{openedAgo(e.readAt!)}</span>
                </A>
              </li>
            )}
          </For>
        </ul>
      </div>
    </details>
  )
}

/** "Read next": the books put on the list, oldest first; a tap opens one. */
function ReadNextMenu(p: { next: NotebookEntry[] }) {
  const pop = usePopover()
  return (
    <details class="facet lib-recent" ref={pop.ref}>
      <summary class="lib-text">Read next <span class="lib-count">{p.next.length}</span></summary>
      <div class="facet-pop facet-pop-end" role="menu" aria-label="Read next">
        <ul>
          <For each={p.next}>
            {e => (
              <li>
                <A href={`/n/${e.id}`} role="menuitem" class="menu-row recent-row" onClick={() => pop.close()}>
                  <span class="recent-title">{e.title || "Untitled"}</span>
                  <span class="menu-hint">{e.meta?.authors[0] ?? ""}</span>
                </A>
              </li>
            )}
          </For>
        </ul>
      </div>
    </details>
  )
}

/** Two hit zones: the key opens the menu, the arrow flips the direction. */
function SortControl(p: { choice: SortChoice | null; onChoice: (c: SortChoice | null) => void; searching: boolean }) {
  const pop = usePopover()
  const label = () => (p.choice ? SORTS.find(([k]) => k === p.choice!.sort)?.[1] ?? "Sort" : "Sort")
  const keys = () => SORTS.filter(([k]) => k !== "relevance" || p.searching)
  const pick = (c: SortChoice | null) => { p.onChoice(c); pop.close() }
  return (
    <div class="lib-sort">
      <details class="facet" ref={pop.ref}>
        <summary class="lib-text" aria-label={`Sort: ${label()}`}>{label()}</summary>
        <div class="facet-pop facet-pop-end" role="menu">
          <ul>
            <li>
              <button type="button" role="menuitemradio" class="menu-row" aria-checked={!p.choice} onClick={() => pick(null)}>
                {p.searching ? "Best match" : "Recently edited"}
              </button>
            </li>
            <For each={keys().filter(([k]) => k !== "relevance")}>
              {([k, l]) => (
                <li>
                  <button type="button" role="menuitemradio" class="menu-row" aria-checked={p.choice?.sort === k}
                    onClick={() => pick(parseSort(k, undefined))}>
                    {l}<span class="menu-hint">{directionLabel(parseSort(k, undefined)!)}</span>
                  </button>
                </li>
              )}
            </For>
          </ul>
        </div>
      </details>
      <Show when={p.choice && p.choice.sort !== "relevance" ? p.choice : null}>
        {c => (
          <button type="button" class="lib-arrow" aria-label={`${directionLabel(c())}; tap to reverse`} title={directionLabel(c())}
            onClick={() => p.onChoice({ ...c(), descending: !c().descending })}>
            <svg viewBox="0 0 16 16" aria-hidden="true" classList={{ up: !c().descending }}><path d="M8 2.5v11M3.5 9 8 13.5 12.5 9" /></svg>
          </button>
        )}
      </Show>
    </div>
  )
}

/** The query syntax, behind a "?" instead of three lines of hints. */
function QueryHelp() {
  const pop = usePopover()
  const rows: [string, string][] = [
    ["author:le guin", "by field: author, series, tag, folder, lang, published"],
    ["kind:pdf,epub", "any of several"],
    ["is:reading", "unread, reading, finished, next"],
    ["rating:>=8", "also pages:<200, progress:>50, 7-9"],
    ["has:series", "also author, tag, rating, pages…"],
    ["read:2026-10", "also added, edited, finished"],
    ["-word", "leave out"],
  ]
  return (
    <details class="facet lib-help" ref={pop.ref}>
      <summary class="lib-text" aria-label="Search syntax">?</summary>
      <div class="facet-pop facet-pop-end">
        <dl class="syntax">
          <For each={rows}>{([code, what]) => <><dt><code>{code}</code></dt><dd>{what}</dd></>}</For>
        </dl>
      </div>
    </details>
  )
}

/** A facet's values with counts; checked values are in the query. With selections, the button inverts. */
function FacetMenu(p: { label: string; field: string; options: (Facet & { label?: string })[]; query: string; onQuery: (q: string) => void }) {
  const [filter, setFilter] = createSignal("")
  const chosen = () => selected(p.query, p.field).map(normalize)
  const shown = () => {
    const f = normalize(filter().trim())
    return f ? p.options.filter(o => normalize(o.label ?? o.value).includes(f)) : p.options
  }
  const pop = usePopover()
  return (
    <Show when={p.options.length > 0}>
      <details class="facet" ref={pop.ref}>
        <summary class="lib-text" classList={{ active: chosen().length > 0 }}>
          {p.label}<Show when={chosen().length}>{n => <span class="lib-count">{n()}</span>}</Show>
        </summary>
        <div class="facet-pop">
          <Show when={p.options.length > 8}>
            <input type="search" class="facet-filter" placeholder={`Find ${p.label.toLowerCase()}`} aria-label={`Find ${p.label.toLowerCase()}`}
              value={filter()} onInput={e => setFilter(e.currentTarget.value)} />
          </Show>
          <ul>
            <For each={shown()}>
              {o => (
                <li>
                  <label class="choice">
                    <input type="checkbox" checked={chosen().includes(normalize(o.value))} onChange={() => p.onQuery(toggle(p.query, p.field, o.value))} />
                    <span class="facet-value">{o.label ?? o.value}</span>
                    <span class="chip-count">{o.count}</span>
                  </label>
                </li>
              )}
            </For>
          </ul>
        </div>
      </details>
    </Show>
  )
}

function Empty(props: { inFolder: boolean }) {
  return (
    <div class="empty">
      <h2>{props.inFolder ? "This folder is empty" : "Nothing here yet"}</h2>
      <p>
        Add a PDF or EPUB to read it on the Boox. Notebooks you write in Notable show up here once the Boox
        syncs.
      </p>
    </div>
  )
}

function FolderGone() {
  return (
    <div class="empty">
      <h2>This folder doesn't exist</h2>
      <p>It may have been deleted on another device. <A href="/">Go to the library</A>.</p>
    </div>
  )
}

function LibraryProblem() {
  const s = useSession()
  const error = () => { const l = s.library(); return l.ok ? "" : l.error }
  return (
    <div class="problem" role="alert">
      <h1>Your library can't be read</h1>
      <p>This app doesn't understand the library it received: {error()}</p>
      <p>If another device runs a newer Notable, update this one too. Reload to try again.</p>
    </div>
  )
}

/** On phones this sits fixed at the bottom, in thumb reach; on wider screens it joins the heading. */
function AddButton(props: { folderId: string | null }) {
  const s = useSession()
  let input: HTMLInputElement | undefined
  return (
    <div class="add-bar">
      <button class="button" type="button" onClick={() => input?.click()}>Add PDF or EPUB</button>
      <input
        ref={input}
        type="file"
        class="visually-hidden"
        tabindex="-1"
        aria-hidden="true"
        multiple
        accept=".pdf,.epub,application/pdf,application/epub+zip"
        onChange={e => {
          const files = [...(e.currentTarget.files ?? [])]
          e.currentTarget.value = ""
          if (files.length) s.uploads.add(files, props.folderId)
        }}
      />
    </div>
  )
}
