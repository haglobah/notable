# notable-repl

A REPL into the live code image on a device ([ADR-002](../../docs/adr/ADR-002-live-code-image.md)).
`eval` runs code in the device's runtime and writes nothing. `def` commits a definition to the code
doc, which syncs it to every device of the account. The REPL never goes through the sync server.

## Connect to the Boox

1. On the device: Settings → Sync → **Live code**. Debug builds (`com.ethran.notable.dev`) always
   listen; release builds need **Live REPL over adb**. The section shows the port (7357) and a
   token, new on every start of the app.
2. On the laptop, with the device on USB (`nix shell nixpkgs#android-tools`):

   ```
   adb forward tcp:7357 tcp:7357
   ```

   The app listens on the device's `127.0.0.1` only; `adb forward` makes it the laptop's
   `127.0.0.1:7357`. `adb forward --remove tcp:7357` undoes it.
3. From `web/` (Node ≥ 24):

   ```
   export NOTABLE_REPL_TOKEN=<token from the device>
   node repl/src/cli.ts ls
   node repl/src/cli.ts            # interactive
   ```

## Commands

```
eval <code>                      run code in the live runtime; writes nothing to the doc
def <name> [file] [--host-api N] commit a definition (source from file, or stdin)
undef <name>                     remove a definition
ls                               definitions with their state on the device, and the menu commands
show <name>                      a definition's source and state
history <name>                   every version of a definition, from the code doc's history
rollback <name> [n]              write version n again (default: the one before the current)
```

Interactively, a line is evaluated; `:ls`, `:show`, `:history`, `:rollback`, `:undef` work as
above, and `:def <name> [hostApi]` reads the source until a line with a single `.`.

```
$ cat hello.js
() => notable.snack("Page " + (notable.page().index + 1))
$ node repl/src/cli.ts def command/hello hello.js
command/hello: loaded
```

## Definitions

- A name is `<kind>/<rest>`, e.g. `command/clear-page`. A source is one JavaScript expression,
  usually a function; several statements go in an IIFE: `(() => { const x = 1; return () => x })()`.
- `command/<x>` definitions are menu commands: a function, or `{ title, run }`. They show in the
  editor's menu (Android) and the reader (PWA).
- They see one global, `notable` (host API 1): `log(...)`, `snack(text)`, `library()`, `page()`,
  `registerCommand(id, title, run)` (while a definition is evaluated), and `hostApi`.
- A definition that throws while evaluated, doesn't parse, or needs a newer `hostApi` is *failed
  on this device* (`ls` shows why); a built-in of the same name runs instead.
- Each evaluation stops after 2 s; the runtime has 32 MB.

## Protocol

JSON over a WebSocket, one reply per request, matched by `id` (`src/protocol.ts`). The first
request must be `{op: "hello", token}`; a wrong token closes the socket. The device refuses
handshakes with an `Origin` header, so a web page on the device can't connect. `history` and
`rollback` fetch the code doc's `save()` bytes (`{op: "doc"}`) and read its Automerge history here.

The device side is `ReplProtocol.kt` on Android and `src/device.ts` for the PWA panel.

```
npm test            # against a fake device over a real socket
npm run typecheck
```
