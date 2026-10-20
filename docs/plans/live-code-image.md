# Plan: Live code image (QuickJS + synced code doc)

Implements [ADR-002](../adr/ADR-002-live-code-image.md). The transport is ADR-001's Automerge
sync. This is the first vertical slice: one code doc, one extension point (user commands), a REPL
on both clients. Each phase ends in something tested. Items marked **needs device check** can't be
verified without the Onyx device.

## Decisions made while building

- **Engine binding: `io.github.dokar3:quickjs-kt` 1.0.15** (Android `quickjs-kt-android`, JVM
  `quickjs-kt-jvm` for unit tests). Released 2026-09-03 (Kotlin 2.4.10, same as the app).
  - Ships `jni/arm64-v8a/libquickjs.so` (plus armeabi-v7a, x86, x86_64) in the AAR, and
    `linux_x64` / `linux_aarch64` / macOS / Windows libraries in the JVM jar, so the unit tests
    run the real engine on the build machine.
  - Has what the ADR asks for: `memoryLimit`, `maxStackSize`, `evaluationTimeoutMillis` (per
    evaluation, interrupt-handler based, stops busy loops) and `interruptEvaluation()`.
  - Host functions are plain Kotlin lambdas (`defineBinding`), no code generation.
  - Considered: `app.cash.zipline` (its `QuickJs` class has an interrupt handler, but host calls go
    through Zipline's service/JSON bridge and its Kotlin compiler plugin; the ADR rejects Zipline),
    `wang.harlon.quickjs:wrapper-android` 3.2.3 (last release 2025-07, no per-eval time limit in
    its API), Javet (V8/Node, far too large).
  - Unit tests: the Android variant only carries Android `.so`s, so the test runtime classpath
    swaps it for `quickjs-kt-jvm` (`app/build.gradle`).
- **A definition's source is one JavaScript expression.** The kernel evaluates
  `__image.put(name, (` + source + `\n))` with the file name `def:<name>`, so line numbers in errors
  match the source and the value (usually a function) never leaves the engine. Several statements
  go in an IIFE: `(() => { const x = 1; return () => x })()`.
- **Values stay inside the engine.** A small JS prelude (`web/schema/runtime/prelude.js`, one file
  for both clients) holds the registry of values and builds the `notable` global from raw host
  functions, then hides them. The kernels (Kotlin `LiveImage`, TS `LiveImage`) keep the status of
  each name (loaded / failed / skipped) and decide the fallback.
- **History and rollback are computed with `@automerge/automerge`.** automerge-java 0.0.9 can read
  a doc at given heads but can't list changes, so it can't walk history. The device sends the code
  doc's `save()` bytes over the REPL (`doc` request); `notable-repl` and the PWA panel read the
  versions of a name with `codeHistory()` from `@notable/schema`. Rollback writes the chosen
  version again as a new entry (`def`), as the ADR says.
- **Code doc created on first use.** The first `def` on a device creates the code doc and sets
  `codeDocUrl` in the library. A library without it has an empty image (built-ins only).
- **No library version bump.** `codeDocUrl` is optional and additive. Both library readers read
  named fields only and ignore unknown keys, so readers that predate it read a library that has
  it, and the new readers read old libraries (tests on both sides). This matches the schema rule
  "adding an optional field keeps `version`".
- **REPL transport on Android: `org.java-websocket:Java-WebSocket` 1.6.0**, bound to
  `127.0.0.1`. OkHttp has no server side. Token: 128 random bits, shown in Settings, new on each
  start of the REPL; checked on the first message (`hello`), and the socket closes on a wrong one.

## Phase 1 — Schema

- [x] TS `notable/code` model, builders (`codeValue`, `codeDefValue`, `putDef`, `deleteDef`) and
      strict reader (`readCode`); optional `codeDocUrl` on the library. `codeHistory()` in
      `web/schema/src/code.ts`.
- [x] Kotlin twin in `AmModel.kt` (`AmCodeImage`, `AmCodeDef`) / `AutomergeSchema.kt`
      (`newCodeDocument`, `putCodeDef`, `readCode`, `codeDocUrlOf`, `setCodeDocUrl`).
- [x] Golden fixtures: `code-v1.json` (expected; also loaded by both runtimes' tests),
      `code-v1.automerge` + `library-code-v1.automerge` (TS), `code-from-kotlin.automerge` +
      `library-code-from-kotlin.automerge` (Kotlin `CodeDocSchemaTest`). The old library fixtures
      stay as they were and prove that libraries without `codeDocUrl` still read.
- [x] Tests: `web/schema/test/code.test.ts` (16), `CodeDocSchemaTest` (10): round trip,
      cross-written fixtures, concurrent redefinition keeps one whole entry, old libraries,
      `codeHistory` with a concurrent loser, rollback.

## Phase 2 — Android runtime

- [x] `image/JsRuntime.kt`: QuickJS on one thread (`notable-image`), 32 MB memory limit, 512 KB
      stack, 2 s per evaluation (busy loops are interrupted).
- [x] `image/LiveImage.kt`: registry (late binding by name), failed-on-this-device, host API
      version gating, built-in fallbacks, status flow. `image/BootGuard.kt`: safe mode counter.
- [x] Host API v1 (`notable.hostApi`, `log`, `snack`, `library()`, `page()`, `registerCommand`),
      built by the shared prelude from `ImageHost`.
- [x] Code doc in `NotableSyncClient` (implements `CodeDocs`): `code` flow follows the library's
      `codeDocUrl` and the doc's changes; `define`/`undefine`/`codeDocBytes` through
      `CodeDocWriter` (`sync/automerge/CodeDocs.kt`), which makes the code doc on first use.
- [x] `image/ImageService.kt` (Hilt singleton, started in `NotableApp.onCreate`): wires it all,
      safe mode from the counter or the settings switch, healthy after the first `apply` and
      MainActivity's first screen.
- [x] Tests: `LiveImageTest` (16) on the real engine, `CodeDocWriterTest` (5).
- [x] **Device-checked (2026-10-20):** `libquickjs.so` loads and runs on the Boox (arm64, Android 11+); APK
      size; first evaluation time; host calls (`library()`) stay fast with a large library.

## Phase 3 — First extension point: commands

- [x] Defs `command/<x>` (a function, or `{ title, run }`) and commands registered with
      `notable.registerCommand` appear in the editor's toolbar menu (`ToolbarMenu`, below "Bug
      report"), after the built-ins "Page info" and "Live code status". A def with a built-in's id
      replaces it while it loads and runs; when it fails or throws, the built-in runs and the error
      shows as a snack. `EditorImage` is the editor's interface to the image.
- [x] **Device-checked (2026-10-20):** the menu entries on e-ink, a snack from a command.

## Phase 4 — Live REPL

- [x] Android WebSocket server (`image/ReplServer.kt`, Java-WebSocket, 127.0.0.1:7357, refuses
      handshakes with `Origin`), protocol in `image/ReplProtocol.kt` (`ReplProtocolTest`, 7, incl.
      a real socket). On in debug builds or with the settings switch; token in Settings → Sync →
      Live code.
- [x] `web/repl` workspace (`@notable/repl`): `notable-repl` CLI (`src/cli.ts`) with `eval`,
      `def`, `undef`, `ls`, `show`, `history`, `rollback`, and an interactive mode. `history` and
      `rollback` load the device's code doc bytes and use `codeHistory()`. `src/device.ts` is the
      JS device side (the PWA panel; the tests' fake device). `adb forward` usage in its README.
      Tests: `repl.test.ts` (6) over a real socket.
- [x] Checked by hand: the CLI against the Kotlin `ReplServer` on the JVM (temporary harness, not
      committed): eval with logs, def loaded/failed with line, ls, show, history, rollback, undef,
      wrong token refused, interactive mode with piped input.
- [ ] **Needs device check:** `adb forward tcp:7357 tcp:7357`, the token from Settings, eval/def on
      the Boox, a def syncing to the PWA and back.

## Phase 5 — PWA

- [x] `web/app/src/image/`: `engine.ts` (quickjs-emscripten-core 0.32.0 +
      `@jitl/quickjs-wasmfile-release-sync` 0.32.0, same limits and error form as Android),
      `liveImage.ts` (TS twin of `LiveImage.kt`), `codeDoc.ts` (follows `codeDocUrl`, makes the
      code doc on first use), `host.ts` (`library()` JSON byte-identical to Android's, checked
      against `fixtures/library-view-v1.json` on both sides), `service.ts` (signals, toast, safe
      mode in localStorage, the in-page REPL device).
- [x] Commands as buttons in the reader (under Download) and on the new `/code` page ("Code" in
      the top bar), which also has the image status, failed definitions, the REPL panel (same
      commands as the CLI, `:def name` + source lines) and the safe mode switch. The reader's page
      across the viewport's middle is `notable.page()`.
- [x] Tests: `web/app/test/image.test.ts` (14) on the real WASM engine with the golden defs (same
      outcomes as `LiveImageTest`), plus the panel's REPL writing a code doc through a real
      automerge-repo `Repo`. `npm run build` bundles it (QuickJS WASM 503 kB, 236 kB gzipped).
- [ ] **Needs browser check:** the `/code` page and reader commands in a real browser (not run in
      a browser yet; only typecheck, unit tests and the Vite build).

## ADR deviations

- **`__host` stays visible on Android.** quickjs-kt defines host objects on the global as
  non-configurable and read-only, so the prelude can't remove them. It offers exactly what
  `notable` offers (log, snack, library, page), so no capability leaks, but a definition could call
  it and skip `notable`'s versioning. In the PWA it is removed. Fix later with a binding that
  supports a non-global parent, or by keeping it and treating it as internal.
- **History is read off the device.** automerge-java 0.0.9 can read a doc at given heads but can't
  list changes, so Android can't walk history. The REPL fetches the code doc's `save()` bytes and
  `@notable/schema`'s `codeHistory()` reads them with `@automerge/automerge`. Still Automerge's
  history, as the ADR says, and rollback still writes a new entry; Settings on Android shows no
  history.
- **Host API v1 reads, it doesn't navigate.** `page()` is `{ id, notebookId, index, pageCount }`,
  not the page's strokes; `library()` is ids, titles, folders, kinds and `updatedAt`. Enough for
  commands now, and each later field is an addition.
- **`registerCommand` only while a definition is evaluated**, so every registered command has an
  owner and goes when that definition is redefined or removed.
- **The safe mode switch applies at the next start** (Android and PWA), not immediately.
- **Safe mode in the PWA** uses a localStorage counter, reset once the code doc state was applied.

## Known gaps

- **Two code docs.** Two devices that write their first definition before they sync each make a
  code doc and set `codeDocUrl`; the merge keeps one URL and the other doc's definitions are
  orphaned. Fix: on seeing `codeDocUrl` change away from a doc made here, copy its newer entries
  over.
- The REPL token on Android is new on every process start; there is no "copy token" button yet
  (the text is selectable).
- `notable.library()` reads the whole library doc on each call on Android
  (`NotableSyncClient.libraryNow`); fine for commands, not for anything called often.
- One flaky run of the existing `RepoLocalDocsTest` "peek reads a doc as stored" (a file deleted by
  compaction during the read, `NoSuchFileException`); it passed on the rerun. Not touched here.

## Needs device check (summary)

Checked on the Note Air 5C (2026-10-20, `.dev` build, over USB):

1. Done: `libquickjs.so` loads (`nativeloader … libquickjs.so … ok`). A REPL `eval` round trip,
   CLI start included, takes 0.14 s; a 1e6-iteration loop runs in 28 ms. Cold start `TotalTime`
   is 1.8–1.9 s with the image; no baseline without it was measured.
2. Done: the built-ins and live `command/…` entries show in the toolbar menu on e-ink; a failed
   definition (syntax error, host API 99) stays out of the menu. A throwing command shows "Command
   failed: def:command/boom:1: Error: boom on purpose", and the process stays up. Redefining
   `command/hello` over the REPL changed its title and behaviour without a restart.
3. Done: starts killed after ≥ 1.1 s count (kills before that land before `Application.onCreate`
   and don't count). With the counter at 5, the next start came up in safe mode: "not evaluated"
   for every definition, built-ins only, counter reset; the start after that loaded everything
   again. Small wording issue: safe mode reports skipped definitions as "4 failed".
4. Done: `adb forward tcp:7357 tcp:7357` + token from Settings; eval, def, undef, ls, show,
   history, rollback; `while(true){}` stops after 2000 ms and the runtime keeps answering.
   **Open:** a def reaching the PWA. The `.dev` app's login is rejected by the server ("log in
   again"), so this needs an account.
5. Open: the PWA's `/code` page and reader commands in a real browser.

## State / Next

- State (2026-10-20): all five phases built and unit-tested; nothing checked on the device or in
  a browser. Results:
  - `./gradlew test` (debug unit tests): 413 tests, 0 failures, 4 skipped (baseline 373; 40 new).
    The first run had one flaky failure in `RepoLocalDocsTest` (see Known gaps), the rerun passed.
  - `npm test` / `npm run typecheck`: schema 50/50, server 46/46, app 53 pass + 2 skipped (qpdf and
    poppler not on PATH, as before) of 55, repl 6/6; typecheck clean in all four.
  - `./gradlew :app:assembleDebug` builds; the APK has `lib/arm64-v8a/libquickjs.so` and
    `assets/prelude.js`.
  - `web/nix/package.nix`: `npmDepsHash` updated for the new lockfile (computed with
    `prefetch-npm-deps`, which reproduced the old hash from the old lockfile); fileset gained
    `schema/runtime` and `repl/`. `nix build path:web#notable-server` succeeds and its PWA bundle
    has the QuickJS WASM.
- Next: the device checks above; then the browser check of `/code`; then the "two code docs"
  gap. Later extension points (toolbar layout, gestures, templates) each need a built-in and a
  call shape first.
