export type DocumentKind = "pdf" | "epub"

export type UploadReason = "unsupported" | "too-large" | "unreadable" | "hash-mismatch" | "unauthorized" | "network" | "server"

/** Every way adding a document can fail, each with a message the person can act on. */
export class UploadFailure extends Error {
  reason: UploadReason
  constructor(reason: UploadReason, message: string) {
    super(message)
    this.reason = reason
  }
}
