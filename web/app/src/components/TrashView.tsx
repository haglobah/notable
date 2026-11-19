import { For, Show, createEffect, createMemo, createSignal } from "solid-js"
import { A } from "@solidjs/router"
import { TRASH_DAYS, expiredTrash, trashOf, type Library, type TrashItem } from "@notable/schema"
import { deleteForever, restore } from "../lib/libraryActions.ts"
import { edited } from "../lib/library.ts"
import { useSession } from "./session.tsx"
import { Glyph } from "./Marks.tsx"

const titleOf = (t: TrashItem) => (t.kind === "folder" ? t.folder.title : t.entry.title) || "Untitled"
const trashedAt = (t: TrashItem) => (t.kind === "folder" ? t.folder.trashedAt! : t.entry.trashedAt!)
const DAY = 86_400_000

/**
 * The trash (docs/plans/library-features.md): what was moved there, newest first; Restore puts it
 * back (to the library's top when its folder is gone or in the trash too), Delete removes it for
 * good. Anything older than TRASH_DAYS goes when the trash is opened here or the Boox library opens.
 */
export function TrashView() {
  const s = useSession()
  const lib = (): Library | null => { const l = s.library(); return l.ok ? l.library : null }
  const items = createMemo(() => { const l = lib(); return l ? trashOf(l) : [] })
  // Once, as soon as the library can be read (it may not be yet when the view opens).
  let emptied = false
  createEffect(() => {
    const l = lib()
    if (!l || emptied) return
    emptied = true
    const due = expiredTrash(l, new Date())
    if (due.length) s.libraryHandle.change((d: any) => due.forEach(t => deleteForever(d, t)))
  })
  const back = (t: TrashItem) => { const l = lib(); if (l) s.libraryHandle.change((d: any) => restore(d, l, t, new Date())) }
  // Deleting for good asks twice, in place: the first tap turns the button into the question.
  const [asking, setAsking] = createSignal<string | null>(null)
  const keyOf = (t: TrashItem) => (t.kind === "folder" ? `f:${t.folder.id}` : `n:${t.entry.id}`)
  const gone = (t: TrashItem) => {
    if (asking() !== keyOf(t)) return setAsking(keyOf(t))
    setAsking(null)
    s.libraryHandle.change((d: any) => deleteForever(d, t))
  }
  const empty = () => {
    if (asking() !== "all") return setAsking("all")
    setAsking(null)
    s.libraryHandle.change((d: any) => items().forEach(t => deleteForever(d, t)))
  }
  return (
    <section aria-labelledby="trash-title" class="trash">
      <nav aria-label="Folders"><ol class="crumbs"><li><A href="/">Library</A></li><li aria-current="page">Trash</li></ol></nav>
      <div class="library-head">
        <h1 id="trash-title" class="place">Trash</h1>
        <Show when={items().length > 0}><button type="button" class="button quiet danger" onClick={empty}>{asking() === "all" ? "Delete everything for good?" : "Empty trash"}</button></Show>
      </div>
      <p class="item-meta">Items are deleted for good after {TRASH_DAYS} days in the trash.</p>
      <Show when={items().length > 0} fallback={<div class="empty"><h2>The trash is empty</h2></div>}>
        <ul class="shelf trash-list">
          <For each={items()}>
            {t => {
              const left = () => Math.max(0, TRASH_DAYS - Math.floor((Date.now() - trashedAt(t).getTime()) / DAY))
              return (
                <li class="trash-row">
                  <Glyph kind={t.kind === "folder" ? "Folder" : "Notebook"} />
                  <span class="trash-what">
                    <span class="item-title">{titleOf(t)}</span>
                    <span class="item-meta" style={{ display: "block" }}>Trashed {edited(trashedAt(t))} · {left()} days left</span>
                  </span>
                  <button type="button" class="button quiet" onClick={() => back(t)}>Restore</button>
                  <button type="button" class="button text" classList={{ danger: asking() === keyOf(t) }} onClick={() => gone(t)}>
                    {asking() === keyOf(t) ? "Delete for good?" : "Delete"}
                  </button>
                </li>
              )
            }}
          </For>
        </ul>
      </Show>
    </section>
  )
}
