/*
 * Serves the built PWA from the same origin as the API, so the session cookie reaches /sync and
 * /api without CORS. Vite's hashed files under /assets never change; everything else (index.html,
 * sw.js, the manifest) is revalidated on every load so a deploy reaches clients.
 */
import { createReadStream } from "node:fs"
import { stat } from "node:fs/promises"
import { extname, join, resolve, sep } from "node:path"
import type { IncomingMessage, ServerResponse } from "node:http"

const TYPES: Record<string, string> = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json",
  ".webmanifest": "application/manifest+json",
  ".wasm": "application/wasm",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
}

/**
 * MuPDF and Automerge compile WASM ('wasm-unsafe-eval'); nothing needs eval. Blob URLs carry
 * rendered pages and downloads. Styles are inline-capable because Solid sets style attributes.
 */
export const APP_CSP = [
  "default-src 'self'",
  "script-src 'self' 'wasm-unsafe-eval'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' blob: data:",
  "font-src 'self'",
  "connect-src 'self'",
  "worker-src 'self' blob:",
  "manifest-src 'self'",
  "base-uri 'none'",
  "form-action 'self'",
  "frame-ancestors 'none'",
  "object-src 'none'",
].join("; ")

async function fileAt(path: string): Promise<number | null> {
  try {
    const s = await stat(path)
    return s.isFile() ? s.size : null
  } catch {
    return null
  }
}

/** Returns false when the request is not for a static file, so the caller can answer 404. */
export function staticHandler(dir: string) {
  const root = resolve(dir)
  const send = (req: IncomingMessage, res: ServerResponse, path: string, size: number, urlPath: string) => {
    const type = TYPES[extname(path).toLowerCase()] ?? "application/octet-stream"
    const headers: Record<string, string | number> = {
      "content-type": type,
      "content-length": size,
      "cache-control": urlPath.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache",
    }
    if (type.startsWith("text/html")) headers["content-security-policy"] = APP_CSP
    res.writeHead(200, headers)
    if (req.method === "HEAD") return void res.end()
    createReadStream(path).on("error", () => res.destroy()).pipe(res)
  }

  return async (req: IncomingMessage, res: ServerResponse, urlPath: string): Promise<boolean> => {
    if (req.method !== "GET" && req.method !== "HEAD") return false
    if (urlPath.startsWith("/api/") || urlPath === "/api" || urlPath === "/sync") return false
    let decoded: string
    try {
      decoded = decodeURIComponent(urlPath)
    } catch {
      return false
    }
    if (decoded.includes("\0")) return false
    const target = resolve(root, "." + (decoded.endsWith("/") ? decoded + "index.html" : decoded))
    if (target !== root && !target.startsWith(root + sep)) return false

    const size = await fileAt(target)
    if (size !== null) return send(req, res, target, size, urlPath), true
    // Client-side routes (/n/…, /f/…) have no extension; a missing file with one is a real 404.
    if (extname(decoded) !== "") return false
    const index = join(root, "index.html")
    const indexSize = await fileAt(index)
    if (indexSize === null) return false
    send(req, res, index, indexSize, "/index.html")
    return true
  }
}
