package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.db.encodeStrokePoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Pins Notable's real SB2 stroke encoding into `web/schema/fixtures/sb2-strokes.json`, so the
 * TypeScript decoder is tested against bytes this encoder produced, raw and LZ4.
 *
 * Regenerate after an intentional encoder change with `UPDATE_FIXTURES=1`. Without it, the test
 * fails when the encoder output drifts from the committed fixture.
 */
class Sb2FixtureTest {

    private data class Case(val name: String, val compression: Int, val points: List<StrokePoint>)

    private val cases = listOf(
        Case(
            "xy-only-raw", 0,
            listOf(
                StrokePoint(10f, 20f), StrokePoint(10.5f, 21.25f), StrokePoint(11f, 22.75f),
                StrokePoint(12.25f, 22.5f), StrokePoint(14f, 21f)
            )
        ),
        Case(
            "full-mask-raw", 0,
            List(8) { i ->
                StrokePoint(
                    x = 300f + i * 3.25f, y = 1200.5f - i * 1.5f,
                    pressure = i / 7f, tiltX = -30 + i * 5, tiltY = 45 - i * 3, dt = (i * 9).toUShort()
                )
            }
        ),
        Case(
            "negative-coords-raw", 0,
            listOf(
                StrokePoint(-5.5f, -0.25f, 0f, -90, 90, 0u),
                StrokePoint(-4f, 0.5f, 1f, 89, -89, 65534u),
            )
        ),
        Case(
            "long-stroke-lz4", 1,
            List(300) { i ->
                StrokePoint(
                    x = 100f + i * 0.5f, y = 2000f + i * 0.25f,
                    pressure = 0.5f, tiltX = 10, tiltY = -10, dt = (i * 8 % 65000).toUShort()
                )
            }
        ),
    )

    private fun render(): String {
        val root = buildJsonObject {
            put("generatedBy", "app/src/test/java/com/ethran/notable/sync/automerge/Sb2FixtureTest.kt")
            put("format", "SB2: see app/src/main/java/com/ethran/notable/data/db/StrokePointConverter.kt")
            put("cases", buildJsonArray {
                for (c in cases) {
                    val bytes = encodeStrokePoints(c.points)
                    assertEquals("compression flag of ${c.name}", c.compression, bytes[8].toInt())
                    add(buildJsonObject {
                        put("name", c.name)
                        put("compression", c.compression)
                        put("bytes", Base64.getEncoder().encodeToString(bytes))
                        put("points", buildJsonArray {
                            for (p in c.points) add(buildJsonObject {
                                put("x", p.x.toDouble())
                                put("y", p.y.toDouble())
                                put("pressure", p.pressure?.let { JsonPrimitive(it.toDouble()) } ?: JsonNull)
                                put("tiltX", p.tiltX?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("tiltY", p.tiltY?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("dt", p.dt?.let { JsonPrimitive(it.toInt()) } ?: JsonNull)
                            })
                        })
                    })
                }
            })
        }
        return Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root) + "\n"
    }

    @Test
    fun committed_fixture_matches_the_encoder() {
        val file = File(FixturePaths.dir, "sb2-strokes.json")
        val rendered = render()
        if (FixturePaths.update) file.writeText(rendered)
        assertEquals(
            "sb2-strokes.json drifted from the encoder; rerun with UPDATE_FIXTURES=1 if intended",
            file.readText(), rendered
        )
    }
}

object FixturePaths {
    /** Unit tests run with the app module as working directory. */
    val dir = File("../web/schema/fixtures").also {
        require(it.isDirectory) { "fixture dir not found from ${File(".").absolutePath}" }
    }
    val update: Boolean get() = System.getenv("UPDATE_FIXTURES") == "1"
}
