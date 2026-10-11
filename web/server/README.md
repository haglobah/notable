# notable-server

The always-on peer for Notable sync. It does three things:
- relays and stores Automerge documents for each account,
- stores uploaded files (PDF, EPUB, images) by SHA-256,
- holds the accounts.

It never renders or converts anything. Clients generate PDFs themselves (ADR-001).

Runtime: Node 24. The `.ts` files run directly, with no build step. Passwords use the built-in
`crypto.argon2`, and the database is the built-in `node:sqlite`.

## Run

```sh
npm install                      # in web/
NOTABLE_STATE_DIR=/var/lib/notable node src/main.ts
printf 'a password\n' | NOTABLE_STATE_DIR=/var/lib/notable node src/cli.ts user add alice
```

| Variable | Default | Meaning |
|---|---|---|
| `NOTABLE_STATE_DIR` | required | Database, docs, files |
| `NOTABLE_LISTEN` | `127.0.0.1:3030` | `host:port` |
| `NOTABLE_PUBLIC_ORIGIN` | unset | e.g. `https://notable.example.org`. Cookie-authenticated requests must come from this origin, and cookies get `Secure` when it is https. When unset, the `Origin` header must match the `Host` header (development only). |
| `NOTABLE_TRUST_PROXY` | `0` | `1`: take the client IP from the last `X-Forwarded-For` entry. Needed behind a reverse proxy, or every login shares the proxy's IP for rate limiting. |
| `NOTABLE_OPEN_SIGNUP` | `0` | `1`: allow `POST /api/signup` |
| `NOTABLE_MAX_BLOB_MIB` | `512` | Largest file upload |
| `NOTABLE_SESSION_IDLE_DAYS` | `180` | A session unused this long expires |
| `NOTABLE_ARGON2_MEMORY_KIB`, `NOTABLE_ARGON2_PASSES` | `19456`, `2` | argon2id cost (OWASP baseline). Existing hashes keep their own parameters. |

State layout:
- `notable.db`: SQLite in WAL mode.
- `docs/`: automerge-repo NodeFS storage.
- `blobs/ab/<sha256>`: uploaded files.
- `tmp/`: in-flight uploads, emptied on start.

Back up the whole directory.

## HTTP API

Authentication works one of two ways:
- **Bearer:** `Authorization: Bearer <token>`, for Android and scripts.
- **Cookie:** the `notable_session` cookie (`HttpOnly`, `SameSite=Strict`, `Path=/`), for the PWA.

A cookie-authenticated request that changes state, including the sync WebSocket upgrade, must
carry an allowed `Origin`.

| Request | Result |
|---|---|
| `GET /api/health` | `{ok: true}`, no auth |
| `POST /api/login {username, password}` | `200 {token, username}` and sets the cookie. Returns `401` for a wrong password or an unknown user (same body, same argon2 cost). Returns `429` with `Retry-After` after 5 failures per username or 30 per IP in 15 minutes. |
| `POST /api/signup {username, password}` | `201` like login. `404` unless open signup is enabled; `409` if the name is taken; `400` for a bad name or a password under 10 characters. |
| `POST /api/logout` | `204`. Revokes this session and closes its sync sockets. |
| `GET /api/me` | `{username, libraryDocUrl}`. The library doc is created on first use. |
| `PUT /api/blobs/<sha256>` | `201`. The server streams the body to disk and verifies the hash. Returns `400` on a mismatch and `413` over the limit. |
| `GET`/`HEAD /api/blobs/<sha256>` | The file, with `immutable` caching. Served as `application/octet-stream` with `CSP: sandbox` and `nosniff`, so uploaded bytes never run on this origin. A file you don't own returns `404`, the same as a missing one. |
| `GET /sync` (WebSocket) | automerge-repo protocol v1. Returns `401` without a session. |

The file store keeps one copy per content. Uploading content that already exists only adds you as
an owner.

## Isolation design

Accounts must never see each other's documents. Two independent layers enforce this, and each one
alone passes the isolation tests. That was checked by disabling one layer at a time; with both
disabled, four tests fail.

1. **`src/syncAdapter.ts`** replaces automerge-repo's `WebSocketServerAdapter`. The stock adapter
   trusts the peer id a client announces in `join`. A second client announcing the same id takes
   over the first socket and receives its traffic. Here each socket is authenticated at upgrade
   time, and its peer id is bound to the account:
   - **Peer ids:** another account's live peer id is refused. The same account reconnecting
     replaces its stale socket. A socket can't switch peer ids. After the handshake, every message
     must carry the socket's own peer id as `senderId` and the server's as `targetId`.
   - **Inbound:** every message with a `documentId` (`sync`, `request`, `ephemeral`,
     `doc-unavailable`) is checked against `doc_owners`.
   - **New docs:** an unknown doc is claimed by the first account that sends a `sync` for it, which
     means it has the doc. A `request` never claims anything; the server answers
     `doc-unavailable`, exactly as for a doc owned by someone else, so nothing leaks.
   - **Remote-heads gossip:** `remote-subscription-change` and `remote-heads-changed` are dropped,
     because the server doesn't enable that gossip. Unknown message types are dropped too.
   - **Outbound:** every message is checked against the target peer's account before it is sent.
2. **`Repo` `shareConfig`** on the server: `announce` is always false, and `access` repeats the
   ownership check for the peer's current account.

Revoked sessions lose their sockets: immediately on logout, or within 30 seconds when revoked via
the CLI or by expiry.

Known limits:
- Doc ids are 128-bit random. Claim-on-first-sync assumes nobody learns a doc's id before its owner
  first syncs it.
- There are no per-account quotas yet.

## Admin CLI

```
notable-server user add <name>         # password on stdin (no echo on a TTY)
notable-server user passwd <name>      # also logs the user out everywhere
notable-server user del <name>         # type the name to confirm
notable-server user list
notable-server sessions revoke <name>
```

Use `node src/cli.ts …` until the Nix package wraps it.

The CLI works on the same database as a running server. Changes apply on the server's next
request.

**Deleting a user** removes the account, its sessions and its ownership records. Its docs become
tombstones. A tombstoned id can never be claimed again, so nobody who learned a URL can take over
the server's copy. The server's garbage collection (on start, and every 6 hours) then deletes the
tombstoned docs and every file nobody owns. Devices keep their local copies.

## Tests

`npm test` runs real automerge-repo clients over real WebSockets against a server on an ephemeral
port. `test/helpers.ts` has a small header-capable client adapter that mirrors what Android does.
