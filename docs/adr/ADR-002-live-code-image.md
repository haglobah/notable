# ADR-002: Live code as data: a QuickJS layer fed by a synced code doc

**Date:** 2026-10-20

## Context

Beat wants Notable to behave like a malleable Lisp image: code lives as data, can be redefined
while the app runs, and new code reaches every device over the wire without a rebuild or an
APK install.

Constraints:

- **ART can't redefine loaded code.** `DexClassLoader` can add classes, but nothing replaces a
  function that is already loaded, and Hilt and Compose expect a closed set of classes at build
  time. The Kotlin app can't be the image itself.
- **Pen latency comes first.** Stroke input, OpenGL rendering and e-ink refresh must stay compiled
  Kotlin. Nothing interpreted may sit on that path.
- **Two clients.** The Android app and the PWA (ADR-001) should run the same definitions.
- **The transport already exists.** ADR-001's Automerge sync carries docs to every device of an
  account, offline-first and merged automatically. The server enforces per-account ownership.
- **The server stays small** (`formenos` is a 4 GB VPS).

## Decision

### Kernel and image

- The **kernel** is the compiled app: input, rendering, Room, sync, the interpreter, and a host
  API exposed to it. It changes only with a release.
- The **image** is the set of definitions stored in a code doc. Everything that is policy or glue
  can move there over time: commands, toolbar layout, gestures, page templates, export
  transforms. The kernel ships built-in versions of each one, which act as fallbacks.
- The kernel calls into the image only at named **extension points**, never per stroke or per
  frame.

### Language and engine: JavaScript on QuickJS

- Definitions are plain JavaScript source.
- Android embeds **QuickJS** through a thin JNI binding. Each device has one runtime on a
  dedicated thread, never the main or render thread, with a memory limit and an interrupt handler
  that stops any single evaluation after a time limit.
- The PWA runs the **same engine** compiled to WASM (`quickjs-emscripten`). That gives identical
  semantics on both clients and a sandbox with no DOM, cookies or `fetch`. Browser `eval` is
  rejected because it would hand definitions the session cookie.
- **Rejected:** Zipline (built for signed Kotlin/JS bundles, not one source string per name),
  Clojure on Android (slow start, heavy), and a second Lisp interpreter per client (two
  implementations to keep identical). Lisp syntax remains possible later through a Lisp-to-JS
  compiler, without changing the engine.

### The code doc

- One **code doc** per library, schema `notable/code`, with its own `version`. The library doc
  holds its URL in an optional `codeDocUrl` field, created on first use. Local-only devices get one
  as well, and the server needs no change: it owns the code doc like any other doc of the account.
- `defs[name] = { source, hostApi, updatedAt, device }`. `name` is namespaced
  (`command/clear-page`, `toolbar/layout`).
- **Each definition is a whole, immutable entry**, the same rule as strokes in ADR-001.
  Redefining writes a new map. `source` is a plain string, never Automerge Text. Two concurrent
  character merges of one function would produce code that doesn't parse. With whole entries the
  last writer wins per name.
- **Undefining** deletes the key.
- **Rollback** uses the doc's history. Earlier versions of a name are read from Automerge history,
  and restoring one writes it again as a new entry.

### Evaluation and late binding

- At start, and on every change of the code doc, a device evaluates each new or changed
  definition. The source evaluates to a value, usually a function, which is stored in a registry
  by name.
- **Calls go through the registry by name**, like Lisp's late binding. Redefining a name takes
  effect at the next call, without restarting or re-wiring anything.
- A definition whose evaluation throws, or one written for a newer host API than this kernel
  offers, is marked **failed on this device** and skipped. The kernel's built-in version is used
  instead, and the failure is visible in the image's status (settings and REPL).
- The registry lives in memory only. It is always rebuilt from the doc and is never persisted.

### Host API

- Definitions see one global, `notable`, versioned by an integer `hostApi`. Each version only adds
  functions, so old definitions keep working.
- It is the **only capability**: definitions have no file system, network or Android access
  except what it offers. Writes go through the same doc-first paths as the editor (ADR-001).
- Version 1 is small: logging, a snack/toast, reading the library and the open page, and
  registering commands. It grows with each extension point that moves into the image.

### Safe mode

- The kernel counts starts that don't reach "image loaded and first screen drawn". After two such
  failures in a row it starts with **built-ins only** and says so. A settings switch does the
  same by hand.
- A crash inside a definition never takes the editor down. Extension points catch errors and fall
  back to the built-in.

### Live REPL

- **Android:** in debug builds, or when enabled in settings, the app listens on a WebSocket bound
  to `127.0.0.1` only. A laptop reaches it through `adb forward`. A token shown on the device is
  required to connect.
- A small CLI under `web/` (`notable-repl`) connects to it. It supports:
  - `eval`: runs code in the live runtime and writes nothing to the doc.
  - `def name`: commits a definition to the code doc, which syncs it to every device.
  - `undef`, `ls`, `show`, `history`, and `rollback`.
- The REPL never goes through the sync server. Remote eval through the server would turn every
  session token into code execution on every device, and the server would have to grow.
- **PWA:** no socket. A REPL panel in the app talks to the same in-page runtime.

### Trust

- The code doc belongs to one account and syncs only to that account's devices. The server's
  ownership checks (ADR-001) are what keep one user's code off another user's devices.
- Inside an account, anyone who can log in can run code on all of its devices. That is accepted
  for now, because an account is one person.
- Signed definitions are deferred until sharing between accounts exists.

## Consequences

Easier:

- Behavior changes reach every device in seconds, offline-first, with no APK build or install.
- Bad definitions are cheap to undo: rollback through history, built-in fallbacks, and safe mode.
- One body of code serves Android and the PWA.

Harder:

- **Another native dependency.** QuickJS ships JNI libraries per ABI, like `automerge-java`. A
  spike must confirm it runs on the Onyx devices.
- **The host API becomes a public contract.** Each version stays forever, so it should grow slowly
  and only from real extension points.
- **Debugging spans two languages.** Errors thrown in JS must carry the definition name and the
  line number into logs and the REPL.
- **Extension points need care.** Every kernel feature moved into the image needs a built-in
  fallback and a defined call shape. Moving a feature is real work, not a switch.
- **Code docs grow with history,** like notebooks. They stay small, since source is short.
- **Out of scope for now:** definitions on the stroke or render path, sharing code between
  accounts, signed definitions, and a Lisp surface syntax.
