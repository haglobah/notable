/*
 * The committed Android parity fixture still describes what project() makes of its notebooks. If
 * this fails after an intentional change to the projection, run `npm run projection-fixture` and
 * make Kotlin's ProjectionParityTest pass again.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import * as A from "@automerge/automerge"
import { project } from "../src/lib/project.ts"
import { blobSource, cases, sourceInfo, summarize } from "./projectionParity.ts"

const committed = JSON.parse(readFileSync(new URL("../../schema/fixtures/projection-v1.json", import.meta.url), "utf8"))
const plain = (x: unknown) => JSON.parse(JSON.stringify(x))

for (const fresh of cases()) {
  test(`projection fixture '${fresh.name}' matches project()`, async () => {
    const want = committed.cases.find((x: { name: string }) => x.name === fresh.name)
    assert.ok(want, `fixture has no case '${fresh.name}'`)
    // The committed doc, not a fresh one: a new doc has new actor ids and heads.
    const c = { ...fresh, doc: A.load(Buffer.from(want.notebook, "base64")) }
    const { documents, images } = sourceInfo(c)
    assert.deepEqual(plain({ documents, images }), { documents: want.documents, images: want.images })
    assert.deepEqual(plain(summarize(await project(c.doc, c.entry, blobSource(c.blobs)))), want.expected)
  })
}
