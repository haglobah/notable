import { createContext, useContext, type Accessor } from "solid-js"
import type { Repo } from "@automerge/automerge-repo"
import type { LibraryState } from "../lib/library.ts"
import type { Me } from "../lib/session.ts"
import type { Uploads } from "./Uploads.tsx"

export type Session = {
  me: Me
  repo: Repo
  library: Accessor<LibraryState>
  uploads: Uploads
  connected: Accessor<boolean>
  logOut: () => Promise<void>
}

export const SessionContext = createContext<Session>()

export function useSession(): Session {
  const s = useContext(SessionContext)
  if (!s) throw new Error("useSession outside a logged-in view")
  return s
}
