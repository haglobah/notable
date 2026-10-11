/** Counts failures per key in a fixed window; in memory, so a restart clears it. */
export class FailureLimiter {
  readonly max: number
  readonly windowMs: number
  #hits = new Map<string, { count: number; resetAt: number }>()

  constructor(max: number, windowMs: number) {
    this.max = max
    this.windowMs = windowMs
  }

  /** Seconds until `key` may try again, or 0 when it is not blocked. */
  retryAfter(key: string, now = Date.now()): number {
    const e = this.#hits.get(key)
    if (!e || e.resetAt <= now) return 0
    return e.count >= this.max ? Math.ceil((e.resetAt - now) / 1000) : 0
  }

  fail(key: string, now = Date.now()) {
    const e = this.#hits.get(key)
    if (!e || e.resetAt <= now) this.#hits.set(key, { count: 1, resetAt: now + this.windowMs })
    else e.count++
    if (this.#hits.size > 100_000) this.prune(now)
  }

  reset(key: string) { this.#hits.delete(key) }

  prune(now = Date.now()) {
    for (const [k, e] of this.#hits) if (e.resetAt <= now) this.#hits.delete(k)
  }
}
