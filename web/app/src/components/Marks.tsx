import type { KindLabel } from "../lib/library.ts"

/** The wordmark, underlined by a pen stroke the way Notable draws one. */
export function Wordmark(props: { large?: boolean; drawn?: boolean; href?: string }) {
  const inner = (
    <>
      <span class="wordmark-text">Notable</span>
      <svg class="wordmark-stroke" viewBox="0 0 120 12" preserveAspectRatio="none" aria-hidden="true">
        <path d="M2 8.5c9-1.2 15-5.8 24-5.8s12 5.2 21 5.2 14-6 24-6 11 5.6 20 5.6 13-2.6 27-3.4" />
      </svg>
    </>
  )
  const cls = `wordmark${props.large ? " large" : ""}${props.drawn ? " drawn" : ""}`
  return props.href ? <a class={cls} href={props.href} aria-label="Notable, library">{inner}</a> : <div class={cls}>{inner}</div>
}

/** A page-shaped mark per kind: the sheet's furniture tells PDF, EPUB and handwriting apart. */
export function Glyph(props: { kind: KindLabel | "Folder" }) {
  return (
    <svg class={`glyph${props.kind === "Folder" ? " folder" : ""}`} viewBox="0 0 36 48" aria-hidden="true">
      {props.kind === "Folder" ? (
        <>
          <path class="tab" d="M3 9h11l3 3H3z" />
          <rect class="sheet" x="2.5" y="11.5" width="31" height="27" rx="2" />
        </>
      ) : props.kind === "Quick page" ? (
        <>
          <rect class="sheet" x="6.5" y="10.5" width="23" height="30" rx="1.5" />
          <path class="ink" d="M10 30c3-1 4-5 7-5s3 4 6 4 3-3 4-3" />
        </>
      ) : (
        <>
          <path class="sheet" d={props.kind === "PDF" ? "M3.5 2.5h20l9 9v34h-29z" : "M3.5 2.5h29v43h-29z"} />
          {props.kind === "PDF" && <path class="mark" d="M23.5 2.5v9h9" />}
          {props.kind === "EPUB" && <path class="mark" d="M8 2.5v43" />}
          {props.kind === "Notebook" ? (
            <>
              <path class="mark" d="M8 14h20M8 21h20M8 28h20M8 35h20" opacity=".45" />
              <path class="ink" d="M9 25c3-2 5-6 8-6s3 6 6 6 4-4 6-4" />
            </>
          ) : (
            <path class="mark" d={props.kind === "EPUB" ? "M13 15h14M13 21h14M13 27h10" : "M8 18h16M8 24h20M8 30h14"} opacity=".55" />
          )}
        </>
      )}
    </svg>
  )
}
