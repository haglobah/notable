import { For, Show, createMemo } from "solid-js"
import { A, useParams } from "@solidjs/router"
import { childFolders, countIn, edited, entriesIn, folderPath, kindOf } from "../lib/library.ts"
import { useSession } from "./session.tsx"
import { Glyph } from "./Marks.tsx"
import { Cover } from "./Cover.tsx"
import { DropZone, UploadList } from "./Uploads.tsx"

export function LibraryView() {
  const params = useParams<{ folderId?: string }>()
  const s = useSession()
  const folderId = () => params.folderId ?? null

  return (
    <Show when={s.library().ok ? s.library() : null} keyed fallback={<LibraryProblem />}>
      {state => {
        const lib = () => (state as Extract<typeof state, { ok: true }>).library
        const path = createMemo(() => folderPath(lib(), folderId()))
        const missing = () => folderId() !== null && !lib().folders[folderId()!]
        const folders = createMemo(() => childFolders(lib(), folderId()))
        const entries = createMemo(() => entriesIn(lib(), folderId()))
        const here = () => (path().length ? path()[path().length - 1].title : "Library")

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
                    <For each={entries()}>
                      {e => (
                        <li>
                          <A class="card" href={`/n/${e.id}`}>
                            <Cover entry={e} />
                            <span class="card-title">{e.title || "Untitled"}</span>
                            <span class="item-meta">{kindOf(e)}, edited {edited(e.updatedAt)}</span>
                          </A>
                        </li>
                      )}
                    </For>
                  </ul>
                </Show>
              </Show>
            </section>
          </Show>
        )
      }}
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
