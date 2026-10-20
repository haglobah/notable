/*
 * The image's side of the kernel (ADR-002). Both clients evaluate this file first in a fresh
 * QuickJS runtime: Android (quickjs-kt, app/src/main/java/com/ethran/notable/image/) and the PWA
 * (quickjs-emscripten, web/app/src/image/). Keep it plain ES2020 with no imports.
 *
 * The kernel puts raw host functions on `globalThis.__host` before this runs:
 *   hostApi: number, log(line), snack(text), library(): JSON string, page(): JSON string ("null")
 * This file builds the `notable` global (the only capability definitions get) from them, then
 * removes `__host`.
 *
 * `__image` is what the kernel calls. It holds the values of definitions by name; the kernel keeps
 * their status (loaded, failed) and the built-in fallbacks. Calls look a name up at call time, so
 * a redefinition takes effect at the next call (late binding).
 */
;(function (host) {
  "use strict"
  /** name → value of the definition */
  const values = new Map()
  /** id → { title, run, owner } registered through notable.registerCommand */
  const registered = new Map()
  /** The definition being evaluated: commands it registers belong to it. */
  let loading = null

  const text = v => {
    if (typeof v === "string") return v
    if (v instanceof Error) return String(v)
    try {
      const s = JSON.stringify(v)
      return s === undefined ? String(v) : s
    } catch (e) {
      return String(v)
    }
  }

  const dropOwned = owner => {
    for (const [id, c] of registered) if (c.owner === owner) registered.delete(id)
  }

  const notable = Object.freeze({
    hostApi: host.hostApi,
    log: (...args) => { host.log(args.map(text).join(" ")) },
    snack: message => { host.snack(text(message)) },
    /** { folders: { id: { id, title, parentId } }, notebooks: { id: { id, title, folderId, kind, source, updatedAt } } } */
    library: () => JSON.parse(host.library()),
    /** The page open in the editor or reader: { id, notebookId, index, pageCount }, or null. */
    page: () => JSON.parse(host.page()),
    /**
     * Adds a command to the menu while the calling definition stays loaded. Call it while a
     * definition is evaluated; redefining or undefining that definition removes the command.
     */
    registerCommand: (id, title, run) => {
      if (loading === null) throw new Error("registerCommand: call it while a definition is evaluated")
      if (typeof id !== "string" || id === "") throw new TypeError("registerCommand: id must be a non-empty string")
      if (typeof run !== "function") throw new TypeError("registerCommand: run must be a function")
      registered.set(id, { title: String(title || id), run, owner: loading })
    },
  })
  Object.defineProperty(globalThis, "notable", { value: notable, enumerable: true })

  const commandOf = value =>
    typeof value === "function" ? { title: null, run: value }
      : value !== null && typeof value === "object" && typeof value.run === "function"
        ? { title: value.title === undefined ? null : String(value.title), run: value.run }
        : null

  const image = Object.freeze({
    /** Before evaluating definition [name]: what its old value registered goes. */
    begin(name) {
      dropOwned(name)
      values.delete(name)
      loading = name
    },
    /** The value of a definition's source; the kernel wraps every source in a call of this. */
    put(name, value) {
      values.set(name, value)
    },
    end() {
      loading = null
    },
    /** Undefines [name], or drops a failed one: its value and the commands it registered. */
    drop(name) {
      values.delete(name)
      dropOwned(name)
      if (loading === name) loading = null
    },
    /** JSON: [{ id, title, owner }] for `command/*` definitions and registered commands. */
    commands() {
      const out = []
      for (const [name, value] of values) {
        if (!name.startsWith("command/")) continue
        const c = commandOf(value)
        out.push(c === null ? { id: name, title: null, owner: name, invalid: "not a function or { title, run }" } : { id: name, title: c.title, owner: name })
      }
      for (const [id, c] of registered) if (!values.has(id)) out.push({ id, title: c.title, owner: c.owner })
      return JSON.stringify(out)
    },
    /** Runs command [id], looked up now. JSON of its result ("null" for undefined). */
    run(id) {
      const value = values.has(id) ? values.get(id) : undefined
      const c = value !== undefined ? commandOf(value) : registered.get(id)
      if (!c) throw new Error(values.has(id) ? `${id} is not a function or { title, run }` : `no command ${id}`)
      const result = c.run()
      return result === undefined ? "null" : text(result)
    },
    has(name) {
      return values.has(name)
    },
    /** REPL: JSON of a value, for printing. */
    show(value) {
      return value === undefined ? "undefined" : typeof value === "function" ? `[function ${value.name || "anonymous"}]` : text(value)
    },
  })
  Object.defineProperty(globalThis, "__image", { value: image })
  // quickjs-kt defines host objects non-configurable, so delete may fail; then overwrite it.
  try { delete globalThis.__host } catch (e) { /* not configurable */ }
  if ("__host" in globalThis) {
    try { globalThis.__host = undefined } catch (e) { /* read-only: it offers nothing `notable` doesn't */ }
  }
})(globalThis.__host)
