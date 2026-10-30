/*
 * How this device shows pages: a tint and scroll or one page at a time. Per device, so it lives
 * in localStorage, not in the docs. Font and size are fixed by the EPUB layout (ADR-001).
 */
import { createSignal } from "solid-js"

export const THEMES = ["paper", "sepia", "night"] as const
export const LAYOUTS = ["scroll", "paged"] as const
export type ReaderTheme = (typeof THEMES)[number]
export type ReaderLayout = (typeof LAYOUTS)[number]
export type ReaderSettings = { theme: ReaderTheme; layout: ReaderLayout }

const KEY = "notable.reader"
export const DEFAULT_SETTINGS: ReaderSettings = { theme: "paper", layout: "scroll" }

/** Unknown or damaged values fall back to the defaults, field by field. */
export function parseSettings(raw: string | null): ReaderSettings {
  let v: Partial<Record<keyof ReaderSettings, unknown>> = {}
  try { v = raw ? JSON.parse(raw) ?? {} : {} } catch { /* damaged: defaults */ }
  return {
    theme: (THEMES as readonly unknown[]).includes(v.theme) ? (v.theme as ReaderTheme) : DEFAULT_SETTINGS.theme,
    layout: (LAYOUTS as readonly unknown[]).includes(v.layout) ? (v.layout as ReaderLayout) : DEFAULT_SETTINGS.layout,
  }
}

function load(): ReaderSettings {
  try { return parseSettings(localStorage.getItem(KEY)) } catch { return DEFAULT_SETTINGS }
}

const [settings, setSettingsSignal] = createSignal<ReaderSettings>(load())
export { settings as readerSettings }

/** Storage may be unavailable (private mode): the choice then lasts for this visit. */
export function setReaderSettings(change: Partial<ReaderSettings>) {
  const next = { ...settings(), ...change }
  setSettingsSignal(next)
  try { localStorage.setItem(KEY, JSON.stringify(next)) } catch { /* this visit only */ }
}
