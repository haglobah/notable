import { For, Show, createSignal, onCleanup, onMount } from "solid-js"
import { createStore, produce } from "solid-js/store"
import type { DocHandle, Repo } from "@automerge/automerge-repo"
import { addDocument, formatSize, UploadFailure, xhrTransport, type Stage } from "../lib/upload.ts"

type Job = {
  id: number
  name: string
  stage: Stage | null
  error: string | null
  done: boolean
}

export type Uploads = ReturnType<typeof createUploads>

/** One file at a time, so a 300 MB PDF doesn't sit in memory next to the next one. */
export function createUploads(ctx: { repo: Repo; library: DocHandle<unknown>; maxBlobBytes: number; onUnauthorized: () => void }) {
  const [jobs, setJobs] = createStore<Job[]>([])
  let next = 0
  let queue = Promise.resolve()
  const transport = xhrTransport()

  const update = (id: number, fn: (j: Job) => void) => setJobs(produce(list => { const j = list.find(x => x.id === id); if (j) fn(j) }))

  const add = (files: Iterable<File>, folderId: string | null, after?: (file: File, ok: boolean) => Promise<void> | void) => {
    for (const file of files) {
      const id = next++
      setJobs(list => [{ id, name: file.name, stage: null, error: null, done: false }, ...list])
      queue = queue.then(async () => {
        try {
          await addDocument({
            file, folderId, repo: ctx.repo, library: ctx.library, transport, maxBlobBytes: ctx.maxBlobBytes,
            onStage: s => update(id, j => { j.stage = s }),
          })
          update(id, j => { j.done = true })
          await after?.(file, true)
          // Finished uploads clear themselves; the new entry is now in the list below.
          setTimeout(() => setJobs(list => list.filter(j => j.id !== id)), 2500)
        } catch (e) {
          const message = e instanceof UploadFailure ? e.message : `Adding ${file.name} failed: ${(e as Error).message}`
          update(id, j => { j.error = message })
          await after?.(file, false)
          if (e instanceof UploadFailure && e.reason === "unauthorized") ctx.onUnauthorized()
        }
      })
    }
  }

  const dismiss = (id: number) => setJobs(list => list.filter(j => j.id !== id))
  return { jobs, add, dismiss }
}

const stageText = (j: Job): string => {
  if (j.error) return "Not added"
  if (j.done) return "Added"
  const s = j.stage
  if (!s) return "Waiting"
  switch (s.stage) {
    case "reading": return "Opening"
    case "hashing": return "Checking"
    case "uploading": return `Uploading ${formatSize(s.done)} of ${formatSize(s.total)}`
    case "saving": return "Saving"
  }
}

const fraction = (j: Job): number => {
  if (j.done) return 1
  const s = j.stage
  if (!s) return 0
  if (s.stage === "reading") return 0.03
  if (s.stage === "hashing") return 0.03 + 0.12 * (s.done / Math.max(1, s.total))
  if (s.stage === "uploading") return 0.15 + 0.8 * (s.done / Math.max(1, s.total))
  return 0.97
}

export function UploadList(props: { uploads: Uploads }) {
  return (
    <Show when={props.uploads.jobs.length > 0}>
      <ul class="uploads" aria-live="polite">
        <For each={props.uploads.jobs}>
          {j => (
            <li class={`upload${j.error ? " failed" : ""}`}>
              <div class="upload-row">
                <span class="upload-name">{j.name}</span>
                <span class="upload-stage">{stageText(j)}</span>
              </div>
              <Show when={j.error} fallback={<div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow={Math.round(fraction(j) * 100)}><span style={{ width: `${fraction(j) * 100}%` }} /></div>}>
                <p class="upload-error">{j.error}</p>
                <button class="button text" type="button" onClick={() => props.uploads.dismiss(j.id)}>Dismiss</button>
              </Show>
            </li>
          )}
        </For>
      </ul>
    </Show>
  )
}

/** Desktop drag and drop over the whole window. */
export function DropZone(props: { label: string; onFiles: (files: File[]) => void }) {
  const [over, setOver] = createSignal(false)
  let depth = 0
  const hasFiles = (e: DragEvent) => [...(e.dataTransfer?.types ?? [])].includes("Files")
  const enter = (e: DragEvent) => { if (!hasFiles(e)) return; e.preventDefault(); depth++; setOver(true) }
  const leave = () => { depth = Math.max(0, depth - 1); if (depth === 0) setOver(false) }
  const dragover = (e: DragEvent) => { if (hasFiles(e)) e.preventDefault() }
  const drop = (e: DragEvent) => {
    if (!hasFiles(e)) return
    e.preventDefault()
    depth = 0
    setOver(false)
    props.onFiles([...(e.dataTransfer?.files ?? [])])
  }
  onMount(() => {
    window.addEventListener("dragenter", enter)
    window.addEventListener("dragleave", leave)
    window.addEventListener("dragover", dragover)
    window.addEventListener("drop", drop)
  })
  onCleanup(() => {
    window.removeEventListener("dragenter", enter)
    window.removeEventListener("dragleave", leave)
    window.removeEventListener("dragover", dragover)
    window.removeEventListener("drop", drop)
  })
  return (
    <Show when={over()}>
      <div class="drop" aria-hidden="true"><div class="drop-sheet">{props.label}</div></div>
    </Show>
  )
}
