/* Library choices of this device (docs/plans/library-features.md): in localStorage, not the docs. */
import { createSignal } from "solid-js"

const KEY = "notable.library.hideFinished"
const load = () => { try { return localStorage.getItem(KEY) === "1" } catch { return false } }
const [hideFinished, setSignal] = createSignal(load())
export { hideFinished }

/** Storage may be unavailable (private mode): the choice then lasts for this visit. */
export function setHideFinished(on: boolean) {
  setSignal(on)
  try { localStorage.setItem(KEY, on ? "1" : "0") } catch { /* this visit only */ }
}
