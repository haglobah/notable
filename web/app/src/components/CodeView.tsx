import { For, Show, createSignal } from "solid-js"
import { A } from "@solidjs/router"
import * as cmd from "@notable/repl"
import type { Reply, Request } from "@notable/repl"
import { modeText } from "../image/liveImage.ts"
import { useSession } from "./session.tsx"

/**
 * The live code image (ADR-002): its status, the commands, and a REPL. The REPL speaks the same
 * protocol as `notable-repl` does with a device, to the image in this page.
 */
export function CodeView() {
  const s = useSession()
  const img = s.image
  const conn = { authorized: false }
  let next = 1
  const call = cmd.checked(async (req: Request) => JSON.parse((await img.repl.handle(conn, JSON.stringify({ id: next++, ...req }))).text) as Reply)
  const [input, setInput] = createSignal("")
  const [out, setOut] = createSignal<{ in: string; out: string; error: boolean }[]>([])

  /** A line as in notable-repl's interactive mode: `:command args`, or code to evaluate. */
  const run = async (line: string) => {
    const text = line.trim()
    if (!text) return
    let result: string
    let error = false
    try {
      if (text.startsWith(":def ")) {
        // ":def name" on the first line, the source on the following ones.
        const [head, ...body] = text.split("\n")
        result = await cmd.def(call, head.slice(5).trim(), body.join("\n"))
      } else if (text.startsWith(":")) {
        const [op, a, b] = text.slice(1).split(/\s+/)
        result = op === "ls" ? await cmd.ls(call)
          : op === "show" ? await cmd.show(call, a ?? "")
            : op === "undef" ? await cmd.undef(call, a ?? "")
              : op === "history" ? await cmd.history(call, a ?? "")
                : op === "rollback" ? await cmd.rollback(call, a ?? "", b === undefined ? undefined : Number(b))
                  : `unknown command :${op} (:ls :show :def :undef :history :rollback)`
      } else result = await cmd.evalCode(call, text)
    } catch (e) {
      result = (e as Error).message
      error = true
    }
    setOut(o => [...o, { in: text, out: result, error }])
  }

  return (
    <article class="code-view">
      <A class="back" href="/">Back to Library</A>
      <h1 class="place">Live code</h1>
      <p class="reader-meta">
        {modeText(img.mode())} · {img.defs().filter(d => d.state === "loaded").length} loaded · {img.defs().filter(d => d.state === "failed").length} failed
        <Show when={img.code().s === "unreadable"}> · the code doc can't be read</Show>
      </p>

      <h2 class="code-head">Commands</h2>
      <div class="reader-actions">
        <For each={img.commands()}>
          {c => <button class="button quiet" type="button" onClick={() => img.runCommand(c.id)}>{c.title}</button>}
        </For>
      </div>

      <Show when={img.defs().some(d => d.state === "failed")}>
        <ul class="code-failed">
          <For each={img.defs().filter(d => d.state === "failed")}>
            {d => <li><code>{d.def.name}</code>: {d.error}</li>}
          </For>
        </ul>
      </Show>

      <h2 class="code-head">REPL</h2>
      <p class="code-hint">
        Code runs in this page's sandbox and writes nothing. <code>:def command/name</code>, then the source on the next lines,
        writes a definition that syncs to every device. Also <code>:ls :show :undef :history :rollback</code>. Ctrl+Enter runs.
      </p>
      <ol class="code-log" aria-live="polite">
        <For each={out()}>
          {e => (
            <li>
              <pre class="code-in">{e.in}</pre>
              <pre class={e.error ? "code-out alert" : "code-out"}>{e.out}</pre>
            </li>
          )}
        </For>
      </ol>
      <form class="code-form" onSubmit={ev => { ev.preventDefault(); const v = input(); setInput(""); void run(v) }}>
        <textarea
          class="code-input" rows={4} spellcheck={false} value={input()} aria-label="Code"
          onInput={ev => setInput(ev.currentTarget.value)}
          onKeyDown={ev => { if (ev.key === "Enter" && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); ev.currentTarget.form?.requestSubmit() } }}
        />
        <button class="button" type="submit">Run</button>
      </form>

      <label class="code-safe">
        <input type="checkbox" checked={img.safeModeSetting()} onChange={ev => img.setSafeModeSetting(ev.currentTarget.checked)} />
        Safe mode: built-in commands only (from the next load)
      </label>
    </article>
  )
}
