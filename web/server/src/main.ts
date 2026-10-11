import { configFromEnv } from "./config.ts"
import { startServer } from "./server.ts"

const log = (msg: string) => console.log(`[notable-server] ${msg}`)

const config = configFromEnv()
const server = await startServer(config, log)
log(`listening on ${config.host}:${server.port}, state in ${config.stateDir}` +
  `${config.openSignup ? ", open signup" : ""}${config.publicOrigin ? `, origin ${config.publicOrigin}` : ""}`)
if (!config.publicOrigin) log("NOTABLE_PUBLIC_ORIGIN is not set: cookies are not marked Secure and origins are checked against the Host header")

let stopping = false
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, async () => {
    if (stopping) return
    stopping = true
    log(`${signal}: shutting down`)
    await server.close()
    process.exit(0)
  })
}
