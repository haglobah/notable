/*
 * Edits and history of the code doc (ADR-002). Call the edits inside `A.change` on a code doc.
 *
 * History is Automerge's: every change that wrote `defs[name]` left a whole entry, and the doc
 * viewed at that change shows it. Rollback reads one of those entries and writes it again as a new
 * one (newer `updatedAt`), so it syncs like any other definition.
 */
import * as A from "@automerge/automerge"
import { codeDefValue } from "./write.ts"
import { readCode } from "./read.ts"
import type { CodeDef } from "./model.ts"

/** Redefines [def.name]: a new whole entry replaces the old one. */
export function putDef(d: Record<string, any>, def: CodeDef) {
  d.defs[def.name] = codeDefValue(def)
}

/** Undefines [name]; false when it wasn't defined. */
export function deleteDef(d: Record<string, any>, name: string): boolean {
  if (!(name in d.defs)) return false
  delete d.defs[name]
  return true
}

/** One version of a definition as some change left it. `current` marks the one the doc has now. */
export type DefVersion = { def: CodeDef; change: string; current: boolean }

/**
 * Every version [name] had in [doc]'s history, oldest first (by `updatedAt`, then change hash).
 * Includes versions that lost a concurrent redefinition. A version is listed once even if later
 * changes kept it.
 */
export function codeHistory(doc: A.Doc<unknown>, name: string): DefVersion[] {
  const current = readCode(doc).defs[name]
  const seen = new Map<string, DefVersion>()
  for (const bytes of A.getAllChanges(doc)) {
    const hash = A.decodeChange(bytes).hash
    const def = readCode(A.view(doc, [hash])).defs[name]
    if (def === undefined) continue
    const key = versionKey(def)
    if (!seen.has(key)) seen.set(key, { def, change: hash, current: current !== undefined && versionKey(current) === key })
  }
  return [...seen.values()].sort((a, b) =>
    a.def.updatedAt.getTime() - b.def.updatedAt.getTime() || (a.change < b.change ? -1 : a.change > b.change ? 1 : 0))
}

const versionKey = (d: CodeDef) => `${d.updatedAt.toISOString()}\u0000${d.device}\u0000${d.hostApi}\u0000${d.source}`
