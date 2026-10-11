import { Show, createMemo, createResource } from "solid-js"
import { A, useParams } from "@solidjs/router"
import type { AutomergeUrl } from "@automerge/automerge-repo"
import { readNotebook } from "@notable/schema"
import { edited, kindOf } from "../lib/library.ts"
import { useSession } from "./session.tsx"

/** Placeholder until the reader lands (Phase 2 part B): title, facts, and the download button's place. */
export function ReaderView() {
  const params = useParams<{ notebookId: string }>()
  const s = useSession()
  const entry = createMemo(() => {
    const l = s.library()
    return l.ok ? l.library.notebooks[params.notebookId] ?? null : null
  })
  const [pages] = createResource(
    () => entry()?.docUrl,
    async url => {
      const handle = await s.repo.find(url as AutomergeUrl)
      return readNotebook(handle.doc()).pageOrder.length
    },
  )
  const back = () => (entry()?.folderId ? `/f/${entry()!.folderId}` : "/")
  const backLabel = createMemo(() => {
    const l = s.library()
    const folderId = entry()?.folderId
    if (!folderId) return "Library"
    return (l.ok && l.library.folders[folderId]?.title) || "folder"
  })

  return (
    <Show when={entry()} fallback={
      <div class="empty">
        <h2>This document isn't in your library</h2>
        <p>It may have been deleted on another device. <A href="/">Go to the library</A>.</p>
      </div>
    }>
      {e => (
        <article>
          <A class="back" href={back()}>Back to {backLabel()}</A>
          <h1 class="place">{e().title || "Untitled"}</h1>
          <p class="reader-meta">
            {kindOf(e())}
            <Show when={pages.state === "ready" && pages()}>{n => `, ${n()} page${n() === 1 ? "" : "s"}`}</Show>
            , edited {edited(e().updatedAt)}
          </p>
          <Show when={pages.error}>
            <p class="alert" role="alert">This notebook can't be opened: {(pages.error as Error).message}</p>
          </Show>
          <div class="reader-actions">
            <button class="button" type="button" disabled aria-describedby="download-later">Download PDF</button>
          </div>
          <p class="reader-later" id="download-later">
            Reading here and downloading a PDF with your handwriting come in the next update. Until then, open it on
            the Boox.
          </p>
        </article>
      )}
    </Show>
  )
}
