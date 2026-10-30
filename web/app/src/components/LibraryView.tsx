import { For, Show, createMemo, createSignal, onCleanup } from "solid-js"
import { A, useParams, useSearchParams } from "@solidjs/router"
import { facetsOf, normalize, parseQuery, queryLibrary, type Facet, type Facets, type Library, type NotebookEntry, type SortKey } from "@notable/schema"
import { childFolders, countIn, edited, entriesIn, folderPath, kindOf } from "../lib/library.ts"
import { bylineOf, continueReading } from "../lib/books.ts"
import { directionLabel, parseSort, selected, toggle, withoutFields, type SortChoice } from "../lib/filters.ts"
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

  return (
    <Show when={s.library().ok ? s.library() : null} keyed fallback={<LibraryProblem />}>
      {state => {
        const lib = () => (state as Extract<typeof state, { ok: true }>).library
        const path = createMemo(() => folderPath(lib(), folderId()))
        const missing = () => folderId() !== null && !lib().folders[folderId()!]
        const folders = createMemo(() => childFolders(lib(), folderId()))
        // No query: this folder's own entries, in the chosen order or the most recently edited first.
        const entries = createMemo(() => {
          const c = choice()
          return c ? queryLibrary(lib(), { query: "", sort: c.sort, descending: c.descending, folderId: folderId() }) : entriesIn(lib(), folderId())
        })
        const here = () => (path().length ? path()[path().length - 1].title : "Library")
        // In a folder a query covers it and its subfolders, unless "Everywhere" is on; at the root, everything.
        const everywhere = () => folderId() === null || search.in === "all"
        const scope = () => (everywhere() ? {} : { folderId: folderId(), subfolders: true })
        const results = createMemo(() => {
          if (!parseQuery(query()).length) return null
          const c = choice()
          return queryLibrary(lib(), { query: query(), ...scope(), ...(c ? { sort: c.sort, descending: c.descending } : {}) })
        })
        const facets = createMemo(() => facetsOf(queryLibrary(lib(), { query: "", ...scope() })))
        const reading = createMemo(() => (folderId() === null ? continueReading(lib()) : []))
        const where = () => (everywhere() ? "in your library" : `in ${here()} and its folders`)

        return (
          <Show when={!missing()} fallback={<FolderGone />}>
            <section aria-labelledby="place">
              <Show when={path().length > 0}>
                <nav aria-label="Folders">
                  <ol class="crumbs">
                    <li><A href="/">Library</A></li>
                    <For each={path().slice(0, -1)}>{f => <li><A href={`/f/${f.id}`}>{f.title}</A></li>}</For>
                  </ol>
                </nav>
              </Show>
              <div class="library-head">
                <h1 class="place" id="place">{here()}</h1>
                <AddButton folderId={folderId()} />
              </div>
              <DropZone label={`Drop to add to ${here()}`} onFiles={files => s.uploads.add(files, folderId())} />
              <UploadList uploads={s.uploads} />
              <SearchField query={query()} onQuery={setQuery} />
              <FilterBar
                query={query()} onQuery={setQuery} facets={facets()}
                choice={choice()} onChoice={setChoice} searching={!!results()}
                scope={folderId() === null ? null : { everywhere: search.in === "all", set: all => setSearch({ in: all ? "all" : undefined }, { replace: true }) }}
              />

              <Show when={results()}>
                {r => (
                  <section aria-label="Search results">
                    <p class="search-count" aria-live="polite">
                      {r().length === 0 ? `Nothing ${where()} matches.` : `${r().length} match${r().length === 1 ? "" : "es"} ${where()}`}
                    </p>
                    <Show when={r().length > 0}>
                      <ul class="shelf cards" aria-label="Matches">
                        <For each={r()}>{e => <li><EntryCard lib={lib()} entry={e} withFolder /></li>}</For>
                      </ul>
                    </Show>
                  </section>
                )}
              </Show>

              <Show when={!results() && reading().length > 0}>
                <h2 class="shelf-head">Continue reading</h2>
                <ul class="shelf cards" aria-label="Continue reading">
                  <For each={reading()}>{e => <li><EntryCard lib={lib()} entry={e} withFolder /></li>}</For>
                </ul>
                <h2 class="shelf-head">Everything</h2>
              </Show>

              <Show when={!results()}>
              <Show when={folders().length + entries().length > 0} fallback={<Empty inFolder={folderId() !== null} />}>
                <Show when={folders().length > 0}>
                  <ul class="shelf" aria-label="Folders">
                    <For each={folders()}>
                      {f => {
                        const n = () => countIn(lib(), f.id)
                        return (
                          <li>
                            <A class="item" href={`/f/${f.id}`}>
                              <Glyph kind="Folder" />
                              <span>
                                <span class="item-title">{f.title}</span>
                                <span class="item-meta" style={{ display: "block" }}>{n() === 0 ? "Empty" : `${n()} item${n() === 1 ? "" : "s"}`}</span>
                              </span>
                            </A>
                          </li>
                        )
                      }}
                    </For>
                  </ul>
                </Show>
                <Show when={entries().length > 0}>
                  <ul class="shelf cards" aria-label="Documents and notebooks">
                    <For each={entries()}>{e => <li><EntryCard lib={lib()} entry={e} /></li>}</For>
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

function EntryCard(p: { lib: Library; entry: NotebookEntry; withFolder?: boolean }) {
  const folder = () => {
    if (!p.withFolder || !p.entry.folderId) return null
    return folderPath(p.lib, p.entry.folderId).map(f => f.title).join(" / ") || null
  }
  return (
    <A class="card" href={`/n/${p.entry.id}`}>
      <Cover entry={p.entry} />
      <span class="card-title">{p.entry.title || "Untitled"}</span>
      <Show when={bylineOf(p.entry)}>{b => <span class="card-byline">{b()}</span>}</Show>
      <span class="item-meta">
        <Show when={typeof p.entry.rating === "number"}><span class="card-rating">★ {p.entry.rating}</span>{" "}</Show>
        {kindOf(p.entry)}, {p.entry.finishedAt ? "finished" : `edited ${edited(p.entry.updatedAt)}`}
        <Show when={folder()}>{f => <>, in {f()}</>}</Show>
      </span>
    </A>
  )
}

const STATES: [string, string][] = [["unread", "Unread"], ["reading", "Reading"], ["finished", "Finished"]]
const KINDS: [string, string][] = [["pdf", "PDF"], ["epub", "EPUB"], ["notebook", "Notebook"], ["quickpage", "Quick page"]]
const SORTS: [SortKey, string][] = [
  ["relevance", "Best match"], ["title", "Title"], ["author", "Author"], ["series", "Series"],
  ["length", "Length"], ["progress", "Progress"], ["rating", "Rating"],
  ["published", "Published"], ["added", "Added"], ["edited", "Edited"], ["read", "Last read"], ["finished", "Finished"],
]

/** Chips and menus that write filter tokens into the query, the sort, and where a query looks. */
function FilterBar(p: {
  query: string; onQuery: (q: string) => void; facets: Facets
  choice: SortChoice | null; onChoice: (c: SortChoice | null) => void; searching: boolean
  scope: { everywhere: boolean; set: (all: boolean) => void } | null
}) {
  const count = (fs: Facet[], v: string) => fs.find(f => normalize(f.value) === v)?.count ?? 0
  const chips = (field: string, options: [string, string][], counts: Facet[]) => (
    <For each={options}>
      {([value, label]) => {
        const on = () => selected(p.query, field).some(v => normalize(v) === value)
        return (
          <Show when={on() || count(counts, value) > 0}>
          <button type="button" class="chip" aria-pressed={on()} onClick={() => p.onQuery(toggle(p.query, field, value))}>
            {label}<span class="chip-count">{count(counts, value)}</span>
          </button>
          </Show>
        )
      }}
    </For>
  )
  const anyFilter = () => FILTER_FIELDS.some(f => selected(p.query, f).length > 0)
  const sortValue = () => p.choice?.sort ?? ""
  return (
    <div class="filters">
      <div class="chips" role="group" aria-label="Reading">{chips("is", STATES, p.facets.states)}</div>
      <div class="chips" role="group" aria-label="Kind">{chips("kind", KINDS, p.facets.kinds)}</div>
      <div class="filter-row">
        <FacetMenu label="Author" field="author" options={p.facets.authors} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Series" field="series" options={p.facets.series} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Tag" field="tag" options={p.facets.tags} query={p.query} onQuery={p.onQuery} />
        <FacetMenu label="Language" field="lang" options={p.facets.languages} query={p.query} onQuery={p.onQuery} />
        <label class="sort">
          <span>Sort</span>
          <select value={sortValue()} onChange={e => {
            const v = e.currentTarget.value
            p.onChoice(v ? parseSort(v, undefined) : null)
          }}>
            <option value="">{p.searching ? "Best match" : "Recently edited"}</option>
            <For each={SORTS.filter(([k]) => k !== "relevance" || p.searching)}>{([k, label]) => <option value={k}>{label}</option>}</For>
          </select>
        </label>
        <Show when={p.choice && p.choice.sort !== "relevance" ? p.choice : null}>
          {c => (
            <button type="button" class="button quiet small" onClick={() => p.onChoice({ ...c(), descending: !c().descending })}>
              {directionLabel(c())}
            </button>
          )}
        </Show>
        <Show when={anyFilter()}>
          <button type="button" class="button text" onClick={() => p.onQuery(withoutFields(p.query, FILTER_FIELDS))}>Clear filters</button>
        </Show>
      </div>
      <Show when={p.scope && p.searching ? p.scope : null}>
        {sc => (
          <div class="chips" role="group" aria-label="Look in">
            <button type="button" class="chip" aria-pressed={!sc().everywhere} onClick={() => sc().set(false)}>This folder</button>
            <button type="button" class="chip" aria-pressed={sc().everywhere} onClick={() => sc().set(true)}>Everywhere</button>
          </div>
        )}
      </Show>
    </div>
  )
}

/** A menu of a facet's values with counts; checked values are in the query. */
function FacetMenu(p: { label: string; field: string; options: Facet[]; query: string; onQuery: (q: string) => void }) {
  const [filter, setFilter] = createSignal("")
  const chosen = () => selected(p.query, p.field).map(normalize)
  const shown = () => {
    const f = normalize(filter().trim())
    return f ? p.options.filter(o => normalize(o.value).includes(f)) : p.options
  }
  let menu: HTMLDetailsElement | undefined
  const closeOutside = (e: PointerEvent) => { if (menu?.open && !menu.contains(e.target as Node)) menu.open = false }
  document.addEventListener("pointerdown", closeOutside)
  onCleanup(() => document.removeEventListener("pointerdown", closeOutside))
  return (
    <Show when={p.options.length > 0}>
      <details class="facet" ref={menu}>
        <summary class="button quiet small">
          {p.label}<Show when={chosen().length}>{n => <span class="chip-count">{n()}</span>}</Show>
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
                    <span class="facet-value">{o.value}</span>
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

/** Searches the whole library (or this folder and below); the query lives in the URL (?q=). */
function SearchField(p: { query: string; onQuery: (q: string) => void }) {
  return (
    <form class="library-search" role="search" onSubmit={e => e.preventDefault()}>
      <input
        type="search"
        aria-label="Search the library"
        aria-describedby="search-hint"
        placeholder="Search titles, authors, series, tags…"
        value={p.query}
        onInput={e => p.onQuery(e.currentTarget.value)}
      />
      <p class="search-hint" id="search-hint">
        Or type filters: <code>author:</code>, <code>series:</code>, <code>tag:</code>, <code>folder:</code>, <code>published:</code>,
        {" "}<code>kind:pdf,epub</code>, <code>is:reading</code>, <code>rating:&gt;=8</code>, <code>pages:&lt;200</code>,
        {" "}<code>progress:&gt;50</code>, <code>has:series</code>, <code>read:2026-10</code>;
        {" "}<code>-</code> leaves something out.
      </p>
    </form>
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
