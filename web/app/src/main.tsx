import { render } from "solid-js/web"
import { App } from "./App.tsx"
import "./styles.css"

render(() => <App />, document.getElementById("root")!)

if (import.meta.env.PROD && "serviceWorker" in navigator) {
  navigator.serviceWorker.register("/sw.js").catch(e => console.error("service worker registration failed", e))
}
