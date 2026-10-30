/*
 * Filter controls edit the query text (docs/plans/ebook-features.md, "Filters and sort"): a chip
 * or menu adds a value to its field's token, `kind:pdf,epub`, or takes it out. The rest of the
 * query stays as typed.
 */
import { DEFAULT_DESCENDING, SORT_KEYS, normalize, type SortKey } from "@notable/schema"

/** Whitespace-separated, quotes kept: the query as written, token by token. */
export function rawTokens(query: string): string[] {
  const out: string[] = []
  let cur = "", quoted = false
  for (const ch of query) {
    if (ch === '"') quoted = !quoted
    if (!quoted && /\s/u.test(ch)) {
      if (cur) out.push(cur)
      cur = ""
      continue
    }
    cur += ch
  }
  if (cur) out.push(cur)
  return out
}

/** The values of a raw `field:a,"b c"` token, quotes removed; null when it isn't that field's. */
function valuesOf(token: string, field: string): string[] | null {
  const prefix = `${field}:`
  if (!normalize(token).startsWith(prefix)) return null
  const values: string[] = []
  let cur = "", quoted = false
  for (const ch of token.slice(prefix.length)) {
    if (ch === '"') { quoted = !quoted; continue }
    if (ch === "," && !quoted) { if (cur) values.push(cur); cur = ""; continue }
    cur += ch
  }
  if (cur) values.push(cur)
  return values
}

const quote = (v: string) => {
  const clean = v.replace(/"/g, "")
  return /[\s,:]/u.test(clean) ? `"${clean}"` : clean
}

/** The values selected for [field] (not negated), as written. */
export function selected(query: string, field: string): string[] {
  return rawTokens(query).flatMap(t => valuesOf(t, field) ?? [])
}

/** [value] in or out of [field]'s token; the token goes when its last value does. */
export function toggle(query: string, field: string, value: string): string {
  const tokens = rawTokens(query)
  const at = tokens.findIndex(t => valuesOf(t, field) !== null)
  const current = at >= 0 ? valuesOf(tokens[at], field)! : []
  const key = normalize(value)
  const next = current.some(v => normalize(v) === key) ? current.filter(v => normalize(v) !== key) : [...current, value]
  const token = next.length ? `${field}:${next.map(quote).join(",")}` : null
  if (at >= 0) {
    if (token) tokens[at] = token
    else tokens.splice(at, 1)
    // Other tokens of the same field are folded into the first one.
    for (let i = tokens.length - 1; i > at; i--) if (valuesOf(tokens[i], field) !== null) tokens.splice(i, 1)
  } else if (token) tokens.push(token)
  return tokens.join(" ")
}

/** The query without any token of [fields]: "Clear filters". */
export function withoutFields(query: string, fields: string[]): string {
  return rawTokens(query).filter(t => !fields.some(f => valuesOf(t, f) !== null)).join(" ")
}

/** True when the query holds something other than filter tokens: then relevance is the default. */
export const hasTerms = (query: string) => rawTokens(query).some(t => !/^-?[a-z]+:/i.test(t))

export type SortChoice = { sort: SortKey; descending: boolean }

/** `?sort=title&dir=asc` → a choice; unknown or absent → null (the view's own order). */
export function parseSort(sort: string | undefined, dir: string | undefined): SortChoice | null {
  if (!sort || !(SORT_KEYS as readonly string[]).includes(sort)) return null
  const key = sort as SortKey
  return { sort: key, descending: dir === "asc" ? false : dir === "desc" ? true : DEFAULT_DESCENDING[key] }
}

/** How a sort direction reads for its key. */
export function directionLabel(c: SortChoice): string {
  switch (c.sort) {
    case "title": case "author": case "series": return c.descending ? "Z to A" : "A to Z"
    case "length": return c.descending ? "Longest first" : "Shortest first"
    case "rating": return c.descending ? "Highest first" : "Lowest first"
    case "progress": return c.descending ? "Furthest first" : "Least read first"
    default: return c.descending ? "Newest first" : "Oldest first"
  }
}
