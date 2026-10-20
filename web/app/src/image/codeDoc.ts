/*
 * The library's code doc in the PWA (ADR-002): follows `codeDocUrl` in the library doc (another
 * device may set it at any time) and the code doc's changes, and writes definitions to it. The
 * Kotlin twin is CodeDocWriter + NotableSyncClient.startCodeWatch.
 */
import * as A from "@automerge/automerge"
import type { AutomergeUrl, DocHandle, Repo } from "@automerge/automerge-repo"
import { codeValue, deleteDef, putDef, readCode, readLibrary, type CodeDef, type CodeImage } from "@notable/schema"
import type { CodeDocSide } from "@notable/repl"

export type CodeState = { s: "none" } | { s: "ready"; url: string; image: CodeImage } | { s: "unreadable"; url: string; reason: string }

export class CodeDoc implements CodeDocSide {
  private handle: DocHandle<unknown> | null = null
  private followed: string | null = null
  private current: CodeState = { s: "none" }
  private readonly onLibraryChange = () => { void this.follow() }
  private readonly onCodeChange = () => this.read()
  private readonly repo: Repo
  private readonly library: DocHandle<unknown>
  private readonly onState: (s: CodeState) => void

  constructor(repo: Repo, library: DocHandle<unknown>, onState: (s: CodeState) => void) {
    this.repo = repo
    this.library = library
    this.onState = onState
  }

  start() {
    this.library.on("change", this.onLibraryChange)
    void this.follow()
  }

  stop() {
    this.library.off("change", this.onLibraryChange)
    this.handle?.off("change", this.onCodeChange)
  }

  state() {
    return this.current.s === "unreadable" ? `unreadable: ${this.current.reason}` : this.current.s
  }

  private codeDocUrl(): string | null {
    try {
      return readLibrary(this.library.doc()).codeDocUrl ?? null
    } catch {
      return null
    }
  }

  private async follow() {
    const url = this.codeDocUrl()
    if (url === this.followed) return
    this.handle?.off("change", this.onCodeChange)
    this.handle = null
    this.followed = url
    if (url === null) return this.publish({ s: "none" })
    try {
      const h = await this.repo.find(url as AutomergeUrl)
      if (this.followed !== url) return
      this.handle = h
      h.on("change", this.onCodeChange)
      this.read()
    } catch (e) {
      this.publish({ s: "unreadable", url, reason: (e as Error).message })
    }
  }

  private read() {
    const h = this.handle
    if (!h) return
    try {
      this.publish({ s: "ready", url: h.url, image: readCode(h.doc()) })
    } catch (e) {
      this.publish({ s: "unreadable", url: h.url, reason: (e as Error).message })
    }
  }

  private publish(s: CodeState) {
    this.current = s
    this.onState(s)
  }

  /** Makes the code doc on first use and points the library at it. */
  private async ensure(): Promise<DocHandle<unknown>> {
    if (this.handle) return this.handle
    const url = this.codeDocUrl()
    if (url !== null) {
      await this.follow()
      if (this.handle) return this.handle
      throw new Error(`the code doc ${url} isn't available yet`)
    }
    const created = this.repo.create(codeValue({ defs: {} }) as Record<string, unknown>)
    this.library.change((d: any) => { d.codeDocUrl = new A.ImmutableString(created.url) })
    await this.follow()
    return created
  }

  async define(def: CodeDef) {
    const h = await this.ensure()
    h.change((d: any) => putDef(d, def))
  }

  async undefine(name: string) {
    if (!this.handle) return false
    let existed = false
    this.handle.change((d: any) => { existed = deleteDef(d, name) })
    return existed
  }

  async save() {
    return this.handle ? A.save(this.handle.doc() as A.Doc<unknown>) : null
  }
}
