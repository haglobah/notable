/*
 * Copies the ebooks (EPUB and PDF) of an Audiobookshelf server into a Notable account, one folder
 * per Audiobookshelf library, through the PWA's addDocument. Audiobooks and podcasts have no
 * pages and are skipped.
 *
 *   ABS_SERVER=https://abs.example ABS_TOKEN=... \
 *   NOTABLE_SERVER=https://notable.hagenlocher.me NOTABLE_USER=... NOTABLE_PASSWORD=... \
 *     node scripts/abs-import.ts list      # what would be imported, writes nothing
 *     node scripts/abs-import.ts import
 *
 * Runs again safely: a book whose title is already in its folder is skipped before download, and
 * one whose file is already in the library (same sha256) after it. At the end a second connection
 * reads every new doc back from the server before this one closes.
 */
import { Repo, type AutomergeUrl, type DocHandle } from "@automerge/automerge-repo"
import { folderValue, readLibrary, type Library } from "@notable/schema"
import { HeaderClientAdapter } from "@notable/server/test/helpers.ts"
import { addDocument, fetchTransport, formatSize, sha256File, UploadFailure } from "../src/lib/upload.ts"

const env = (k: string) => process.env[k] ?? (() => { throw new Error(`set ${k}`) })()
const abs = env("ABS_SERVER").replace(/\/$/, "")
const server = env("NOTABLE_SERVER").replace(/\/$/, "")

type Format = "epub" | "pdf"
const MIME: Record<Format, string> = { pdf: "application/pdf", epub: "application/epub+zip" }
type Book = { itemId: string; title: string; format: Format }
type Shelf = { name: string; books: Book[]; other: string[] }

async function absGet(path: string): Promise<Response> {
  const res = await fetch(`${abs}${path}`, { headers: { authorization: `Bearer ${env("ABS_TOKEN")}` } })
  if (!res.ok) throw new Error(`Audiobookshelf ${path}: ${res.status} ${await res.text()}`)
  return res
}

/** Every book library with its items that have an ebook Notable can open. */
async function readShelves(): Promise<Shelf[]> {
  const { libraries } = (await (await absGet("/api/libraries")).json()) as { libraries: { id: string; name: string; mediaType: string }[] }
  const shelves: Shelf[] = []
  for (const lib of libraries.filter(l => l.mediaType === "book")) {
    const shelf: Shelf = { name: lib.name, books: [], other: [] }
    for (let page = 0; ; page++) {
      const { results, total } = (await (await absGet(`/api/libraries/${lib.id}/items?limit=100&page=${page}`)).json()) as { results: any[]; total: number }
      for (const item of results) {
        const title = String(item.media?.metadata?.title ?? item.id)
        const format = String(item.media?.ebookFormat ?? item.media?.ebookFile?.ebookFormat ?? "").toLowerCase()
        if (format === "epub" || format === "pdf") shelf.books.push({ itemId: item.id, title, format })
        else if (format) shelf.other.push(`${title} (${format})`)
      }
      if (results.length === 0 || (page + 1) * 100 >= total) break
    }
    shelves.push(shelf)
  }
  return shelves
}

async function login() {
  const res = await fetch(`${server}/api/login`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: server },
    body: JSON.stringify({ username: env("NOTABLE_USER"), password: env("NOTABLE_PASSWORD") }),
  })
  if (res.status !== 200) throw new Error(`login: ${res.status} ${await res.text()}`)
  const { token } = (await res.json()) as { token: string }
  const me = await fetch(`${server}/api/me`, { headers: { authorization: `Bearer ${token}` } })
  const { libraryDocUrl, maxBlobBytes } = (await me.json()) as { libraryDocUrl: string; maxBlobBytes: number }
  return { token, libraryDocUrl: libraryDocUrl as AutomergeUrl, maxBlobBytes }
}

const connect = (token: string) =>
  new Repo({ network: [new HeaderClientAdapter(server.replace(/^http/, "ws") + "/sync", { authorization: `Bearer ${token}` })] })

async function find<T>(repo: Repo, url: AutomergeUrl): Promise<DocHandle<T>> {
  for (let i = 0; i < 20; i++) {
    try {
      return await repo.find<T>(url, { signal: AbortSignal.timeout(10_000) })
    } catch {
      await new Promise(r => setTimeout(r, 500))
    }
  }
  throw new Error(`server never sent ${url}`)
}

/** The top-level folder with this title. */
const folderNamed = (lib: Library, title: string) =>
  Object.values(lib.folders).find(f => f.parentId === null && f.title === title)

const titlesIn = (lib: Library, folderId: string | undefined) =>
  new Set(folderId === undefined ? [] : Object.values(lib.notebooks).filter(e => e.folderId === folderId).map(e => e.title))

async function list(lib: Library, shelves: Shelf[]) {
  for (const shelf of shelves) {
    const folder = folderNamed(lib, shelf.name)
    const have = titlesIn(lib, folder?.id)
    const fresh = shelf.books.filter(b => !have.has(b.title))
    console.log(`\n${shelf.name}: ${fresh.length} to import, ${shelf.books.length - fresh.length} already there${folder ? "" : " (folder will be created)"}`)
    for (const b of fresh) console.log(`  + ${b.title} (${b.format})`)
    for (const o of shelf.other) console.log(`  - ${o}: Notable can't open this format`)
  }
}

async function importAll(repo: Repo, library: DocHandle<any>, shelves: Shelf[], token: string, maxBlobBytes: number) {
  const transport = fetchTransport(server, { authorization: `Bearer ${token}` })
  const added: { notebookId: string; docUrl: string }[] = []
  const failed: string[] = []
  for (const shelf of shelves) {
    let folderId = folderNamed(readLibrary(library.doc()), shelf.name)?.id
    for (const book of shelf.books) {
      const lib = readLibrary(library.doc())
      if (titlesIn(lib, folderId).has(book.title)) { console.log(`= ${book.title}: already in ${shelf.name}`); continue }
      try {
        const bytes = await (await absGet(`/api/items/${book.itemId}/ebook`)).arrayBuffer()
        const file = new File([bytes], `${book.title}.${book.format}`, { type: MIME[book.format] })
        const hash = await sha256File(file)
        const same = Object.values(lib.notebooks).find(e => e.source.kind !== "none" && e.source.blob === hash)
        if (same) { console.log(`= ${book.title}: same file as "${same.title}"`); continue }
        // Made with its first book, so a library of only duplicates leaves no empty folder.
        if (folderId === undefined) {
          const id = crypto.randomUUID()
          const now = new Date()
          library.change((d: any) => { d.folders[id] = folderValue({ id, title: shelf.name, parentId: null, createdAt: now, updatedAt: now }) })
          folderId = id
          console.log(`created folder ${shelf.name}`)
        }
        added.push(await addDocument({ file, folderId, repo, library, transport, maxBlobBytes }))
        console.log(`+ ${book.title} (${book.format}, ${formatSize(file.size)})`)
      } catch (e) {
        const why = e instanceof UploadFailure ? e.message : String(e)
        failed.push(`${shelf.name} / ${book.title}: ${why}`)
        console.log(`! ${book.title}: ${why}`)
      }
    }
  }
  return { added, failed }
}

/** Reads the library and every new notebook back over a fresh connection. */
async function confirm(token: string, libraryDocUrl: AutomergeUrl, added: { notebookId: string; docUrl: string }[]) {
  const end = Date.now() + 120_000
  for (;;) {
    const repo = connect(token)
    try {
      const lib = readLibrary((await find(repo, libraryDocUrl)).doc())
      const missing = added.filter(a => !lib.notebooks[a.notebookId])
      if (missing.length === 0) {
        for (const a of added) await find(repo, a.docUrl as AutomergeUrl)
        return
      }
      if (Date.now() > end) throw new Error(`server's library still lacks ${missing.length} of ${added.length} new notebooks`)
    } finally {
      await repo.shutdown()
    }
    await new Promise(r => setTimeout(r, 2000))
  }
}

async function main() {
  const cmd = process.argv[2]
  if (cmd !== "list" && cmd !== "import") throw new Error("usage: abs-import.ts list|import")
  const shelves = await readShelves()
  const { token, libraryDocUrl, maxBlobBytes } = await login()
  const repo = connect(token)
  try {
    const library = await find<any>(repo, libraryDocUrl)
    if (cmd === "list") return await list(readLibrary(library.doc()), shelves)
    const { added, failed } = await importAll(repo, library, shelves, token, maxBlobBytes)
    if (added.length > 0) {
      console.log(`\nwaiting for the server to have all ${added.length} new notebooks…`)
      await confirm(token, libraryDocUrl, added)
    }
    console.log(`\nimported ${added.length}, failed ${failed.length}`)
    for (const f of failed) console.log(`  ! ${f}`)
    if (failed.length > 0) process.exitCode = 1
  } finally {
    await repo.shutdown()
  }
}

main().then(() => process.exit(process.exitCode ?? 0), e => { console.error(e); process.exit(1) })
