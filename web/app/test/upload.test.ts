import { test, before, after } from "node:test"
import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import type { AutomergeUrl } from "@automerge/automerge-repo"
import { readLibrary, readNotebook } from "@notable/schema"
import { startHarness, userWithToken, clientRepo, waitFor, tryFind, shutdown, type Harness } from "@notable/server/test/helpers.ts"
import { addDocument, detectKind, fetchTransport, sha256File, UploadFailure, EPUB_LAYOUT, type Stage } from "../src/lib/upload.ts"
import { countPages } from "../src/lib/pages.ts"
import { makeEpub, makePdf } from "./files.ts"

let h: Harness
before(async () => { h = await startHarness() })
after(async () => { await h.close() })

const me = async (token: string) => {
  const res = await fetch(`${h.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })
  assert.equal(res.status, 200)
  return (await res.json()) as { libraryDocUrl: AutomergeUrl; maxBlobBytes: number }
}

async function session(name: string) {
  const token = await userWithToken(h, name)
  const { libraryDocUrl, maxBlobBytes } = await me(token)
  const { repo } = clientRepo(h, token)
  const library = await repo.find(libraryDocUrl)
  return { token, repo, library, libraryDocUrl, maxBlobBytes }
}

test("detectKind accepts PDF and EPUB by type or extension and nothing else", () => {
  assert.equal(detectKind(new File([], "a.pdf")), "pdf")
  assert.equal(detectKind(new File([], "x", { type: "application/pdf" })), "pdf")
  assert.equal(detectKind(new File([], "Book.EPUB")), "epub")
  assert.equal(detectKind(new File([], "x", { type: "application/epub+zip" })), "epub")
  assert.equal(detectKind(new File([], "notes.docx")), null)
})

test("sha256File hashes in chunks and matches node:crypto", async () => {
  const bytes = new Uint8Array(3 * 1024 * 1024 + 17).map((_, i) => (i * 31) & 0xff)
  const seen: number[] = []
  const hash = await sha256File(new File([bytes], "x"), done => seen.push(done), 1024 * 1024)
  assert.equal(hash, createHash("sha256").update(bytes).digest("hex"))
  assert.deepEqual(seen, [1048576, 2097152, 3145728, 3145745])
})

test("countPages reads PDFs and lays EPUBs out at the fixed page size", async () => {
  assert.equal(await countPages(makePdf(3), "pdf"), 3)
  const epubPages = await countPages(makeEpub(2), "epub")
  assert.ok(epubPages > 2, `expected the EPUB to span several pages, got ${epubPages}`)
  await assert.rejects(countPages(new TextEncoder().encode("not a pdf"), "pdf"), (e: unknown) => e instanceof UploadFailure && e.reason === "unreadable")
})

test("adding a PDF uploads the blob, creates the notebook doc and the library entry, and syncs to another device", async () => {
  const a = await session("reader")
  const pdf = makePdf(3)
  const stages: Stage[] = []
  const { notebookId, docUrl } = await addDocument({
    file: new File([pdf], "Attention Is All You Need.pdf", { type: "application/pdf" }),
    folderId: null,
    repo: a.repo,
    library: a.library,
    transport: fetchTransport(h.base, { authorization: `Bearer ${a.token}` }),
    maxBlobBytes: a.maxBlobBytes,
    onStage: s => stages.push(s),
  })
  const hash = createHash("sha256").update(pdf).digest("hex")
  assert.deepEqual([...new Set(stages.map(s => s.stage))], ["reading", "hashing", "uploading", "saving"])

  const blob = await fetch(`${h.base}/api/blobs/${hash}`, { headers: { authorization: `Bearer ${a.token}` } })
  assert.equal(blob.status, 200)
  assert.deepEqual(new Uint8Array(await blob.arrayBuffer()), pdf)

  const entry = readLibrary(a.library.doc()).notebooks[notebookId]
  assert.equal(entry.title, "Attention Is All You Need")
  assert.equal(entry.kind, "notebook")
  assert.equal(entry.folderId, null)
  assert.deepEqual(entry.source, { kind: "pdf", blob: hash })
  assert.equal(entry.docUrl, docUrl)

  // A second device of the same account sees both docs through the server.
  const { repo: other } = clientRepo(h, a.token)
  try {
    const lib = await other.find(a.libraryDocUrl)
    await waitFor(() => readLibrary(lib.doc()).notebooks[notebookId], "library entry on second device")
    const nb = await tryFind(other, docUrl as AutomergeUrl)
    assert.notEqual(typeof nb, "string", "notebook doc should reach the second device")
    const notebook = readNotebook((nb as Exclude<typeof nb, string>).doc())
    assert.equal(notebook.notebookId, notebookId)
    assert.equal(notebook.pageOrder.length, 3)
    assert.deepEqual(notebook.defaultBackground, { type: "autoPdf", native: null, blob: hash })
    notebook.pageOrder.forEach((id, i) => assert.deepEqual(notebook.pages[id].background, { type: `pdf${i}`, native: null, blob: hash }))
    assert.equal(notebook.openPageId, notebook.pageOrder[0])
  } finally {
    await shutdown(other, a.repo)
  }
})

test("adding an EPUB records its fixed layout and one page per laid-out page", async () => {
  const a = await session("epubreader")
  const epub = makeEpub(2)
  const expectedPages = await countPages(epub, "epub")
  const { notebookId, docUrl } = await addDocument({
    file: new File([epub], "Book.epub"),
    folderId: "folder-1",
    repo: a.repo,
    library: a.library,
    transport: fetchTransport(h.base, { authorization: `Bearer ${a.token}` }),
    maxBlobBytes: a.maxBlobBytes,
  })
  const entry = readLibrary(a.library.doc()).notebooks[notebookId]
  assert.equal(entry.folderId, "folder-1")
  assert.equal(entry.source.kind, "epub")
  assert.deepEqual(entry.source.kind === "epub" && entry.source.layout, EPUB_LAYOUT)
  const notebook = readNotebook((await a.repo.find(docUrl as AutomergeUrl)).doc())
  assert.equal(notebook.pageOrder.length, expectedPages)
  await shutdown(a.repo)
})

test("failures are explicit and leave the library untouched", async () => {
  const a = await session("failing")
  const transport = fetchTransport(h.base, { authorization: `Bearer ${a.token}` })
  const before = Object.keys(readLibrary(a.library.doc()).notebooks).length
  const attempt = (file: File, opts: Partial<Parameters<typeof addDocument>[0]> = {}) =>
    addDocument({ file, folderId: null, repo: a.repo, library: a.library, transport, maxBlobBytes: a.maxBlobBytes, ...opts })
  const reason = (r: string) => (e: unknown) => e instanceof UploadFailure && e.reason === r

  await assert.rejects(attempt(new File(["x"], "notes.txt")), reason("unsupported"))
  await assert.rejects(attempt(new File([makePdf(1)], "big.pdf"), { maxBlobBytes: 10 }), reason("too-large"))
  await assert.rejects(attempt(new File(["%PDF-garbage"], "broken.pdf")), reason("unreadable"))
  // A body that does not match its hash is refused by the server.
  const corrupting: typeof transport = (hash, _file, progress) => transport(hash, new File(["other bytes"], "x"), progress)
  await assert.rejects(attempt(new File([makePdf(1)], "a.pdf"), { transport: corrupting }), reason("hash-mismatch"))
  await assert.rejects(attempt(new File([makePdf(1)], "a.pdf"), { transport: fetchTransport(h.base, { authorization: "Bearer wrong" }) }), reason("unauthorized"))
  await assert.rejects(attempt(new File([makePdf(1)], "a.pdf"), { transport: fetchTransport("http://127.0.0.1:1", {}) }), reason("network"))

  assert.equal(Object.keys(readLibrary(a.library.doc()).notebooks).length, before)
  await shutdown(a.repo)
})

test("the server's size limit wins when the client's copy of it is stale", async () => {
  const small = await startHarness({ maxBlobBytes: 100 })
  try {
    const token = await userWithToken(small, "tiny")
    const res = await fetch(`${small.base}/api/me`, { headers: { authorization: `Bearer ${token}` } })
    const { libraryDocUrl, maxBlobBytes } = (await res.json()) as { libraryDocUrl: AutomergeUrl; maxBlobBytes: number }
    assert.equal(maxBlobBytes, 100)
    const { repo } = clientRepo(small, token)
    const library = await repo.find(libraryDocUrl)
    await assert.rejects(
      addDocument({
        file: new File([makePdf(1)], "a.pdf"), folderId: null, repo, library,
        transport: fetchTransport(small.base, { authorization: `Bearer ${token}` }), maxBlobBytes: Infinity,
      }),
      (e: unknown) => e instanceof UploadFailure && e.reason === "too-large",
    )
    await shutdown(repo)
  } finally {
    await small.close()
  }
})
