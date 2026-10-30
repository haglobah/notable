# Ebook features

Goal (2026-10-30): reading features for PDFs and EPUBs on both the Boox (Android) and the PWA.
Read this first; update "State" when you finish a piece of work.

1. Table of contents from the document outline.
2. Search inside a book.
3. Reading position in the PWA (restore and write `openPageId`).
4. Reading state: progress, finished, last read.
5. PWA reader settings: theme (light, sepia, dark) and layout (scroll, paged). Font and size stay
   fixed: changing the EPUB layout would move every annotation (ADR-001).
6. Book metadata read at import (EPUB OPF, PDF info dictionary).
7. Library search over every attribute, with field filters.

## Schema (shared, `web/schema`, Kotlin twin `sync/automerge/AmModel.kt`)

New optional keys on a library notebook entry. Adding optional keys keeps the library at
`version: 1`; old readers ignore them. Writers set single fields, never rewrite the entry.

```
notebooks: { <id>: { …existing…,
  meta?:       { authors: [str], series: str|null, seriesIndex: number|null,
                 description: str|null, publisher: str|null, language: str|null,
                 isbn: str|null, published: str|null, subjects: [str] },
  readAt?:     timestamp,        // last time a reader opened it; absent = never opened
  finishedAt?: timestamp|null }  // set = finished; null or absent = not finished
```

- `meta` is written whole, once, at import, by the importing device. When present, every key in it
  is present (strict read). Lists hold scalar strings. `description` is plain text (tags stripped,
  whitespace collapsed, at most 4000 characters). `published` is the date string as the file
  gives it (`2019`, `2019-05-02`, …). `isbn` is digits and `X` only.
- The entry title comes from the metadata title when there is one, else the file name.
- `readAt` is set when a reader opens the notebook (Android editor, PWA reader), at most once per
  opening.
- `finishedAt` is set by "Mark as finished" and cleared to `null` by "Mark as unread".
- Progress is not stored in the library: it is the index of the notebook doc's `openPageId` in
  `pageOrder`, over the page count. Writing the library on every page turn would grow its history.

Fixtures: `library-books-v1.{json,automerge}` (TS, `npm run fixtures`) and
`library-books-from-kotlin.automerge` (Kotlin `GoldenFixtureTest`). Both read each other's.

## Metadata extraction (same result on both platforms)

Fixture: `web/schema/fixtures/book-meta/` holds sample files and `expected.json`, the metadata
and title each must give. TS (`web/app/src/lib/bookMeta.ts`) and Kotlin both test against it.

- **EPUB:** `META-INF/container.xml` → first `rootfile@full-path` → OPF `<metadata>`:
  - title: first `dc:title`.
  - authors: every `dc:creator` in document order, trimmed, empty ones dropped. (Roles are
    ignored: an EPUB 3 `refines` role or `opf:role` other than `aut` still counts.)
  - series: `<meta name="calibre:series" content>`, else EPUB 3
    `<meta property="belongs-to-collection">` text. seriesIndex: `calibre:series_index` content,
    else `<meta refines="#id" property="group-position">` for that collection; parsed as a
    number, null when not a finite number.
  - description: first `dc:description` (may hold escaped HTML: unescape entities, strip tags).
  - publisher, language: first `dc:publisher`, `dc:language`.
  - published: first `dc:date`, trimmed.
  - isbn: the first `dc:identifier` whose text, with `urn:isbn:` / `isbn:` prefix removed and
    `-` and spaces removed, is 10 or 13 characters of digits (last may be `X`), or whose
    `opf:scheme` is `ISBN` (then normalized the same way).
  - subjects: every `dc:subject`, trimmed, empty dropped, duplicates (case-insensitive) dropped,
    first spelling kept.
- **PDF:** the info dictionary through MuPDF: `info:Title`, `info:Author` (split on `;` and on
  ` and `/ `&`, trimmed), `info:Subject` → description, `info:Keywords` → subjects (split on `,`
  and `;`). Everything else null or empty.
- A field the file doesn't have is `null` (or `[]`). If every field would be null/empty, `meta` is
  not written at all.
- Strings are trimmed and inner whitespace runs collapsed to one space. Whitespace is the set JS
  `\s` matches (includes U+00A0 and U+FEFF, not U+0085); Android's regex rejects `(?U)`, so Kotlin
  spells the set out. An empty string after that is null (or dropped
  from a list).
- Description cleaning, in order: the element's text content (XML-decoded); every `<…>` tag
  replaced by a space; entities decoded (`&amp; &lt; &gt; &quot; &apos; &nbsp;`, `&#NNN;`,
  `&#xHH;`; others left as written); whitespace collapsed and trimmed; cut to 4000 UTF-16 units.
- Author splitting (PDF only): split on `;`, on `&`, and on the exact lowercase word ` and `.
  Subjects (PDF keywords): split on `,` and `;`. Subjects dedupe case-insensitively, first kept.
- ISBN: uppercase `X`.
- The fixture also gives each file's `pages` (EPUB at 446 × 595 pt, 12 pt), `outline` and a
  `search` query with the document pages that hit.

## Table of contents and in-book search (both platforms)

- Outline: MuPDF `loadOutline()`, depth-first, flattened with `depth` (0 = top level). An item's
  page is its `page`, else `resolveLink(uri)` (EPUB items have only a `uri`); items that resolve
  to no page (< 0) are dropped, their children kept one level up. Empty titles are dropped.
- Search: MuPDF search (TS: structured-text search; Android: `Page.search`, because fitz 1.28.0's
  `StructuredText.search(String, int)` aborts under CheckJNI) with ignore-case and ignore-diacritics (JS options
  `"ignore-case,ignore-diacritics"`, Java `SEARCH_IGNORE_CASE or SEARCH_IGNORE_DIACRITICS`), on every
  document page; both platforms use MuPDF 1.28. A hit
  gives the page, its quads (document points) and a snippet: the page's text (`asText()`, with
  whitespace collapsed) around the first occurrence, up to 40 characters each side, with `…` where
  cut. The occurrence is found with plain lowercase `indexOf`; when that misses (a hit only through
  ignore-diacritics), the snippet is the first 80 characters, with `…` if cut.

## Library search (same result on both platforms)

Fixture: `web/schema/fixtures/search-v1.json`: a library (`libraryToJson` form, with books
fields) plus `cases: [{ query, ids }]`, the ids in result order. TS `web/schema/src/search.ts`
and Kotlin both run every case.

- **Normalize** text and query: Unicode NFKD, drop combining marks, lowercase.
- **Tokens:** split on whitespace; a `"…"` span is one token (quotes removed). A token may be
  `-token` (negated) and/or `field:value` (`value` may itself be quoted: `author:"le guin"`).
  An unknown field name makes the token a plain term including the colon. A token whose value
  is empty (`author:`, `""`) is dropped. A lone `-` is a plain term. The query is normalized
  before it is split. Field names: `title`, `author`, `series`, `tag`, `subject`, `folder`,
  `publisher`, `isbn`, `lang`, `desc`, `kind`, `is`.
- **Fields of an entry:** title; authors; series; subjects (`tag`/`subject`); publisher;
  language (`lang`); isbn; description (`desc`); folder (every folder title on its path).
- **Plain term** matches when it is a substring of any field. **`field:value`** matches when the
  value is a substring of that field. `kind:` takes `pdf`, `epub`, `notebook` (no source),
  `quickpage`, matched exactly. `is:` takes `finished` (finishedAt set), `reading` (readAt set,
  not finished), `unread` (no readAt, not finished). An unknown `kind:`/`is:` value matches
  nothing. All tokens must match (AND); a negated token must not match.
- **Score** (plain terms only, summed): for each plain term, the best field weight among the
  fields it matches: title 8, authors 6, series 5, subjects 3, folder 2, publisher 2, isbn 2,
  language 1, description 1; +2 when some occurrence in that field starts a word (at the start
  of a value, or after a UTF-16 code unit that is not a letter or digit). "Best" is the first
  field in that list the term matches, not the highest total. Substrings are on UTF-16 strings.
- **Order:** score descending, then `readAt` descending (absent last), then `updatedAt`
  descending, then id ascending. An empty query returns nothing.

## Filters and sort (same result on both platforms)

`queryLibrary(lib, { query, sort?, descending?, folderId? })` (TS `search.ts`, Kotlin
`LibrarySearch.kt`). Fixture: `search-v1.json` `browse` (cases with `ids` in order) and `natural`
(`[a, b, sign]`). `searchLibrary(lib, q)` stays: `queryLibrary(lib, { query: q })`, but nothing
for a query without tokens.

- **Filters are query tokens.** UI controls (chips, menus) add and remove tokens in the query
  text, so a filter is always visible, editable and part of the URL.
- **Any of:** in a field token, unquoted commas separate values; the token matches when any value
  does (`kind:pdf,epub`, `-kind:pdf,epub`). Commas inside quotes are literal
  (`author:"le guin, ursula"`). Empty values are dropped; a field token with none left is dropped.
  Plain terms never split. Tokens carry `values` (plain terms: one).
- **More fields:** `published:` (substring of `meta.published`, not scored); `has:` with
  `meta author series tag publisher lang isbn desc published` (exact; the meta field is non-empty);
  dates `added:` (`createdAt`), `edited:` (`updatedAt`), `read:` (`readAt`), `finished:`
  (`finishedAt`): the value is a prefix of the date as UTC ISO-8601 with milliseconds
  (`2026-10-25T00:00:00.000Z`), so `read:2026`, `read:2026-10`, `read:2026-10-2` work; no date, no
  match.
- **Scope:** `folderId` absent: the whole library; `null`: the root; an id: that folder. With
  `subfolders: true`, also every folder below it (so root + subfolders is everything).
- **UI scope rule (both platforms):** at the root, a query searches the whole library. In a
  folder, a query (typed or from filters) searches that folder and its subfolders, with a switch
  to "Everywhere". No query: the folder view as before, ordered by the chosen sort (that folder's
  own entries).
- **No tokens** match every entry in scope.
- **Sort keys:** `relevance` (as "Order" above; ignores `descending`), `title`, `author`
  (first author), `series` (series name, then `seriesIndex`, a missing index after any number),
  `published` (the string), `added`, `edited`, `read`, `finished`. Default direction: `title`,
  `author`, `series` ascending; the rest descending. Entries without the key come last in
  either direction, among themselves by title. Ties: title ascending, then id ascending.
- **Text keys** compare the normalized string (as above) in natural order: split into runs of
  ASCII digits and runs of anything else; two digit runs compare by value (leading zeros
  stripped: longer is larger, then code units), equal values: fewer leading zeros first; other
  runs by UTF-16 code units; when one runs out first, it comes first.
- **Facets** (`facetsOf(entries)`, UI only, not in the fixture): authors, series, tags,
  languages, kinds, states with counts; each value once per entry (normalized), the first
  spelling kept; most common first, then natural order.

## Length, progress and rating (round 3)

New optional entry keys (library stays `version: 1`), set as single fields:

```
pageCount?: int ≥ 0      // pages in the notebook doc's pageOrder
readPage?:  int ≥ 0      // index of the open page in pageOrder
rating?:    int 1..10 | null   // null: cleared; absent: never rated
```

- **Writers keep `pageCount` current:** at import or upload; whenever a device changes the
  page order (add, delete, duplicate, reorder); and as a backfill when the value differs or is
  absent: the PWA when the reader has the notebook open; Android when the editor opens it and when
  the library loads (not from the projector, which never writes the docs). **`readPage`** is written with
  `openPageId`, by the same debounced write. Both are written only when the value changes
  (`setReading`); writing an unchanged value still records an op in Automerge.
- **Progress** (`progressOf`, 0 to 1): finished → 1; no `pageCount` → unknown (null); no
  `readPage` → 0; else `readPage / (pageCount − 1)`, 1 when `pageCount ≤ 1`, capped at 1.
- **Number filters:** `rating:`, `pages:` (pageCount), `progress:` (percent, 0 to 100, unrounded).
  Values: `N`, `>N`, `>=N`, `<N`, `<=N`, `N-M` (inclusive); N is digits with an optional
  `.digits`; anything else matches nothing; an unknown number never matches. Commas give any-of
  as for other fields. `has:rating` (a number is set), `has:pages`.
- **Sort keys:** `length` (pageCount; default ascending, shortest first), `progress` (default
  descending), `rating` (default descending). Unknown values last, as for other keys.
- **Rating UI:** 1 to 10 plus Clear; in the reader (PWA) and the editor menu and card menu (Boox).

## PWA

- Worker requests `outline` and `search` (`pdf.worker.ts`).
- Source page → notebook page: the first page in `pageOrder` whose resolved background is that
  document page. TOC entries and hits without a notebook page are left out, and the count of
  those left out is shown.
- Reader settings live in `localStorage` (per device).

## Android

- Room `notebook` gets the new columns via a migration with a test.
- Editor menu: Contents, Search, Mark as finished/unread. Library: search field, progress and
  finished state on cards.

## State

- 2026-10-30: plan written.
- 2026-10-30, PWA and shared schema: done, not committed.
  - Schema: `meta`/`readAt`/`finishedAt` read, write, JSON; `markRead`, `setFinished`,
    `setOpenPage`; `search.ts`. Fixtures `library-books-v1.*`, `search-v1.json` (cases by hand),
    `book-meta/` (made by `web/app/scripts/write-book-meta-fixtures.ts`, `expected.json` by hand).
  - App: `lib/bookMeta.ts` (zip + XML readers, OPF and PDF info), `lib/bookText.ts` (outline,
    search, snippets), `lib/reading.ts`, `lib/books.ts`, `lib/readerSettings.ts`; upload writes
    title and meta (a metadata failure adds the file under its name and says so in the upload
    list); reader: position restored and written back (1 s after it rests), `readAt` on open,
    Mark as finished/unread, Contents, Search with highlights, View (Paper/Sepia/Night, scroll or
    one page at a time with keys, edge taps and swipes); library: search over everything (`?q=`),
    Continue reading, bylines, progress bar and Finished badge on covers.
  - Tests: `npm test` in `web/`: schema 90, server 60, app 83 (2 qpdf/poppler skips), repl 6; all
    pass. `npm run typecheck` in `web/app` clean.
  - Checked in Chrome against a local server: metadata on upload, Contents jump, search hits and
    highlight, night + paged, position after reload in both layouts, finished, library search
    queries, Continue reading. Not checked: phone width (the window didn't resize), iOS.
- 2026-10-30, Android (not committed): schema twin (`AmBookMeta` = `data/model/BookMeta`,
  `AutomergeSchema.putBookMeta/setReadAt/setFinishedAt`, strict read with paths; `hasFinishedAt`
  keeps null apart from absent), `LibrarySearch.kt` (all `search-v1.json` cases, 31 since
  `constructor:`/`tostring:` were added),
  `library-books-from-kotlin.automerge` written (TS `books.test.ts`: 40 pass, 0 skipped). Room 39:
  `notebook.bookMeta` (JSON), `readAt`, `finishedAt` (AutoMigration 38→39, test). Projector copies
  the book fields into Room; the Room → doc migration writes `meta` with a new entry (that is how
  an import's meta reaches the doc). New `LocalChange.NotebookRead/NotebookFinished` (single
  fields, no `updatedAt` bump, an older `readAt` never replaces a newer one), outbox debt
  `reading/<id>` replayed from Room. Import (`io/BookMeta.kt`, `io/BookText.kt`): metadata title
  unless the caller chose one; a failure is logged and shown, the import goes on. Editor menu:
  Contents, Search in book, Mark as finished/unread; `readAt` once per editor opening (every
  notebook, not only books). Library: search field over all folders, cards with authors,
  "Page n of m" for books, finished mark, folder path in results. Checked on the Note Air 5C.
  - Whitespace: Android's ICU regex rejects `(?U)`; Kotlin spells out JS `\s` (what the TS code
    matches) instead. That set differs from Unicode White_Space in U+0085 (not in) and U+FEFF (in).
  - Search: fitz 1.28.0 `StructuredText.search(String, int)` has a JNI bug (CheckJNI aborts the
    debug app). Android uses `Page.search(q, flags)` (same flags, default stext options) and
    `asText()` of a preserve-whitespace stext for the snippet. Fixture hit pages match.
  - Not done: hit highlights on the page (would need drawing in the page renderer).
- Known gaps:
  - Android search hits aren't highlighted on the page (the PWA's are).
  - Android derives `kind:` from Room's default background, not the entry's `source`: a native
    notebook with PDF pages added counts as `notebook` there.
  - TS looked field names up on a plain object, so `constructor:x` hit `Object.prototype`; fixed
    (a `Map`) and pinned by two new `search-v1.json` cases.
- 2026-10-30, filters and sort, shared engine and PWA: done, not committed.
  - `search.ts`: `queryLibrary` (scope with `subfolders`, sort keys, natural order), any-of
    values, `published:`, `has:`, date prefix fields, `facetsOf`. `search-v1.json` gained
    `browse` (27 cases, by hand) and `natural`.
  - PWA: `lib/filters.ts` edits the query text (toggle a value into its field's token, clear
    filters, sort from `?sort=&dir=`); `LibraryView` filter bar: reading and kind chips with
    counts, Author/Series/Tag/Language menus (with a find box past 8 values), Sort + direction,
    Clear filters, This folder / Everywhere in a folder. The sort also orders the folder view.
  - Tests: schema 121, app 91 (2 skips); typecheck clean. Checked in Chrome at 500 px: author
    menu, sort and direction, finished chip, clear, URL restore. Not checked in a folder (the PWA
    can't make folders; the engine cases cover the scope).
- 2026-10-30, Android, filters and sort (not committed): `LibrarySearch.kt` ported again from
  `search.ts`: any-of `values`, `published:`, `has:`, date prefix fields (UTC ISO with ms via
  `java.time`), `query(lib, Query(query, sort, descending, scope))` with `Scope.All` /
  `Scope.In(id, subfolders)`, sort keys with per-key default direction, `naturalCompare`,
  `facetsOf`. `LibrarySearchTest` runs all 31 `cases`, 27 `browse`, the `natural` pairs and the
  `facetsOf` assertions; all pass. Library UI (`ui/components/LibraryFilterBar.kt`): state and
  kind chips, Author/Series/Tag/Language menus (dialogs with counts, multi-select) from
  `facetsOf` over the search scope, Sort (key dialog + direction toggle; "Usual order" keeps the
  old folder order), "Everywhere" in a folder while a query is set. Filters edit the query text
  (`ui/viewmodels/QueryFilters.kt`): one any-of token per field, values normalized (lowercase),
  quoted when they hold a space, comma or colon; a `"` in a value is dropped (the grammar can't
  write it). Scope as in "UI scope rule". Sort is remembered per device in `AppSettings`
  (`librarySort`, `librarySortDescending`). Checked on the Note Air 5C.
  - Known gap, unchanged: Room-side `kind` comes from the notebook's default background, not the
    doc's `source`.
- 2026-10-30, round 3 (length, progress, rating), schema and PWA: done, not committed.
  - Schema: `pageCount`, `readPage`, `rating` (strict read), `setRating`, `setReading` (writes only
    changes); `search.ts`: number filters, `has:rating`/`has:pages`, sort keys `length`,
    `progress`, `rating`, `progressOf` (exported as `entryProgress`). Fixture library has the new
    fields; 20 new `browse` cases (47), by hand.
  - PWA: upload writes `pageCount`; the reader writes `readPage` with the position and backfills
    `pageCount` when it differs; a 1–10 rating row (tap again or Clear to take it back); sort menu
    gains Length, Progress, Rating; cards show "★ n".
  - Tests: schema 141 pass, 1 fails until Kotlin regenerates `library-books-from-kotlin.automerge`;
    app 91 (2 skips). Checked in Chrome: backfill on open, rating, the three sorts, `rating:>=8`,
    `pages:<5`.
- 2026-10-30, Android, round 3 (not committed): the filter bar's Author/Series/Tag/Language and
  Sort menus open as popups anchored under their own button (`ScaledAnchoredPopup`: below, or
  above when there is no room, clamped to the window, no animation, rows of at least 56 dp, at most
  60 % of the screen high); a value tap toggles and keeps the menu open. Schema: `pageCount`,
  `readPage`, `rating` (`hasRating` keeps null apart from absent; strict read with the TS
  messages), `setRating`, `setReading` (writes only what changed). `LibrarySearch`: `rating:`,
  `pages:`, `progress:` (ASCII digits, as JS `\d`), `has:rating`, `has:pages`, sort keys `length`,
  `progress`, `rating`, `progressOf`; all 47 `browse` cases pass. `library-books-from-kotlin`
  regenerated (TS `books.test.ts` 92 pass). Writers: an upload or import writes both with the
  entry; every page-order change (add, duplicate, move, delete, next page past the end) and every
  open-page change syncs them from the notebook doc (`LocalDocWriter.syncReading`, no commit when
  unchanged); `LocalChange.NotebookRated` / `ReadingSynced`, replayed with the `reading/<id>`
  debt. Backfill: on opening a notebook in the editor, and on loading the library for every
  notebook whose entry's `pageCount` (Room 40 keeps the doc's value) differs from its pages. Not
  from the projector: it never writes docs. Room 40: `rating`, `pageCount` (AutoMigration, test).
  Rating 1–10 plus Clear in the editor menu (opens in place) and the notebook details dialog;
  cards show "★ n". Checked on the Note Air 5C.
