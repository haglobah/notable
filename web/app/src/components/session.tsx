import { createContext, useContext, type Accessor } from "solid-js"
import type { DocHandle, Repo } from "@automerge/automerge-repo"
import type { LibraryState } from "../lib/library.ts"
import type { Me } from "../lib/session.ts"
import type { Uploads } from "./Uploads.tsx"
import type { ImageService } from "../image/service.ts"

export type Session = {
  me: Me
  repo: Repo
  library: Accessor<LibraryState>
  /** The library doc itself, for writes (reading state). */
  libraryHandle: DocHandle<unknown>
  uploads: Uploads
  connected: Accessor<boolean>
  /** The live code image (ADR-002). */
  image: ImageService
  logOut: () => Promise<void>
}

export const SessionContext = createContext<Session>()

export function useSession(): Session {
  const s = useContext(SessionContext)
  if (!s) throw new Error("useSession outside a logged-in view")
  return s
}
