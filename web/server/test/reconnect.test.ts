/*
 * A device that loses its network comes back on a new socket with the same peer id. The server
 * may have sent it changes on the old socket before it noticed that socket was dead. Those
 * changes must still reach the device after it reconnects (found on the Note Air 5C: after wifi
 * off and on, a notebook stopped receiving remote edits until the app restarted).
 */
import { test, describe, before, after } from "node:test"
import assert from "node:assert/strict"
import net from "node:net"
import { Repo, parseAutomergeUrl, type PeerId } from "@automerge/automerge-repo"
import { startHarness, userWithToken, clientRepo, tryFind, waitFor, shutdown, HeaderClientAdapter, type Harness } from "./helpers.ts"

type Note = { k?: string; local?: string; remote?: string }

/**
 * A TCP proxy standing in for a device's wifi. `wifiOff` closes the device's side of every
 * connection at once, while the server's side stays open and hears nothing more, as when a
 * phone loses its network: the server finds out only by its keep-alive.
 */
class FlakyNetwork {
  #server: net.Server
  #links = new Set<{ device: net.Socket; upstream: net.Socket; dead: boolean }>()
  #online = true
  port = 0
  target: number

  constructor(target: number) {
    this.target = target
    this.#server = net.createServer(device => {
      if (!this.#online) return void device.destroy()
      const upstream = net.connect(this.target, "127.0.0.1")
      const link = { device, upstream, dead: false }
      this.#links.add(link)
      device.on("data", b => { if (!link.dead) upstream.write(b) })
      upstream.on("data", b => { if (!link.dead) device.write(b) })
      const end = () => { if (!link.dead) { device.destroy(); upstream.destroy(); this.#links.delete(link) } }
      device.on("close", end)
      upstream.on("close", end)
      device.on("error", () => {})
      upstream.on("error", () => {})
    })
  }

  async listen() {
    await new Promise<void>(r => this.#server.listen(0, "127.0.0.1", r))
    this.port = (this.#server.address() as net.AddressInfo).port
  }

  wifiOff() {
    this.#online = false
    for (const l of this.#links) {
      l.dead = true
      l.device.destroy()
    }
    this.#links.clear()
  }

  wifiOn() { this.#online = true }

  close() {
    this.#server.close()
  }
}

describe("reconnect", () => {
  let h: Harness
  let token: string

  before(async () => {
    h = await startHarness()
    token = await userWithToken(h, "carol")
  })
  after(async () => h.close())

  test("changes the server sent on a dead socket reach the device after it reconnects", async () => {
    const wifi = new FlakyNetwork(h.server.port)
    await wifi.listen()
    const viaWifi = () => new HeaderClientAdapter(`ws://127.0.0.1:${wifi.port}/sync`, { authorization: `Bearer ${token}` })
    const device = new Repo({ network: [viaWifi()], peerId: "device-a" as PeerId })
    const other = clientRepo(h, token)
    try {

    const handle = device.create<Note>({ k: "v0" })
    await waitFor(() => h.server.accounts.docOwner(parseAutomergeUrl(handle.url).documentId), "claim")
    const found = await tryFind<Note>(other.repo, handle.url)
    assert.notEqual(typeof found, "string", "the other device must find the doc")
    const remote = found as Exclude<typeof found, string>
    remote.change(d => { d.remote = "b0" })
    await waitFor(() => handle.doc().remote === "b0", "online sync")

    wifi.wifiOff()
    handle.change(d => { d.local = "a1" })
    remote.change(d => { d.remote = "b1" })
    // The server sends b1 to the device's old socket, which it still thinks is open.
    await new Promise(r => setTimeout(r, 500))
    wifi.wifiOn()
    device.networkSubsystem.addNetworkAdapter(viaWifi())

    await waitFor(() => remote.doc().local === "a1", "the device's offline edit to reach the other device")
    await waitFor(() => handle.doc().remote === "b1", "the edit made while the device was offline to reach it")
    remote.change(d => { d.remote = "b2" })
    await waitFor(() => handle.doc().remote === "b2", "a later edit to reach the device")

    } finally {
      wifi.close()
      await shutdown(device, other.repo)
    }
  })
})
