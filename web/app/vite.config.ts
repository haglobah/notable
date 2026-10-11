import { defineConfig, type Plugin } from "vite"
import solid from "vite-plugin-solid"
import wasm from "vite-plugin-wasm"
import { readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"

/**
 * public/sw.js is copied as is; this fills in what it precaches once the bundle's hashed names are
 * known. MuPDF's WASM (10 MB) is left out: the service worker caches it on first use instead.
 */
function precacheManifest(): Plugin {
  let outDir = "dist"
  const files: string[] = []
  return {
    name: "notable-precache",
    apply: "build",
    configResolved(c) { outDir = c.build.outDir },
    generateBundle(_opts, bundle) {
      for (const name of Object.keys(bundle)) if (!/mupdf/i.test(name) && !name.endsWith(".map")) files.push(`/${name}`)
    },
    closeBundle() {
      const path = join(outDir, "sw.js")
      const shell = ["/", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png", "/icon-maskable-512.png", "/apple-touch-icon.png", ...files.sort()]
      const version = Buffer.from(shell.join("\n")).toString("base64url").slice(-16)
      const sw = readFileSync(path, "utf8")
        .replace("self.__PRECACHE__", JSON.stringify(shell))
        .replace("self.__VERSION__", JSON.stringify(version))
      writeFileSync(path, sw)
    },
  }
}

export default defineConfig({
  plugins: [solid(), wasm(), precacheManifest()],
  build: { target: "esnext", sourcemap: false },
  worker: { format: "es", plugins: () => [wasm()] },
  server: { proxy: { "/api": "http://127.0.0.1:3030", "/sync": { target: "ws://127.0.0.1:3030", ws: true } } },
})
