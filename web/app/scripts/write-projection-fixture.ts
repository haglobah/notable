/*
 * Writes web/schema/fixtures/projection-v1.json: notebooks, the source facts Kotlin can't compute
 * without MuPDF, and what project() made of them. Kotlin's ProjectionParityTest must match it.
 *
 * Run: npm run projection-fixture (from web/app). Regenerate only for an intentional change.
 */
import { writeFileSync } from "node:fs"
import { cases, fixtureCase } from "../test/projectionParity.ts"

const out = new URL("../../schema/fixtures/projection-v1.json", import.meta.url)
const fixture = { format: "notable-projection-parity", version: 1, cases: [] as unknown[] }
for (const c of cases()) fixture.cases.push(await fixtureCase(c))
writeFileSync(out, JSON.stringify(fixture, null, 1) + "\n")
console.log(`wrote ${out.pathname}`)
