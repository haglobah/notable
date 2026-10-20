package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.Date

/**
 * Reads the expected-content JSON that sits next to each golden Automerge fixture
 * (`web/schema/fixtures/<name>.json`, written by `web/schema/scripts/write-fixtures.ts`).
 * Dates are ISO-8601 strings, byte arrays are base64.
 */
object FixtureJson {
    fun library(file: File): AmLibrary = library(Json.parseToJsonElement(file.readText()).jsonObject)
    fun notebook(file: File): AmNotebook = notebook(Json.parseToJsonElement(file.readText()).jsonObject)
    fun code(file: File): AmCodeImage = code(Json.parseToJsonElement(file.readText()).jsonObject)
    /** The code doc URL `code-v1.json` names, which `library-code-v1.automerge` points at. */
    fun codeDocUrl(file: File): String = Json.parseToJsonElement(file.readText()).jsonObject.str("codeDocUrl")

    fun code(o: JsonObject) = AmCodeImage(o.obj("defs").mapValues { (name, v) ->
        val d = v.jsonObject
        AmCodeDef(name, d.str("source"), d.getValue("hostApi").jsonPrimitive.int, d.date("updatedAt"), d.str("device"))
    })

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.strOrNull(k: String) = get(k).let { if (it == null || it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.date(k: String) = Date.from(Instant.parse(str(k)))
    private fun JsonObject.obj(k: String) = getValue(k).jsonObject

    fun library(o: JsonObject) = AmLibrary(
        codeDocUrl = o.strOrNull("codeDocUrl"),
        folders = o.obj("folders").mapValues { (id, v) ->
            val f = v.jsonObject
            AmFolder(id, f.str("title"), f.strOrNull("parentId"), f.date("createdAt"), f.date("updatedAt"))
        },
        notebooks = o.obj("notebooks").mapValues { (id, v) ->
            val n = v.jsonObject
            AmNotebookEntry(
                id = id,
                docUrl = n.str("docUrl"),
                title = n.str("title"),
                folderId = n.strOrNull("folderId"),
                kind = AmEntryKind.fromKey(n.str("kind")),
                source = source(n.obj("source")),
                createdAt = n.date("createdAt"),
                updatedAt = n.date("updatedAt"),
            )
        },
    )

    private fun source(o: JsonObject): AmSource = when (o.str("kind")) {
        "none" -> AmSource.None
        "pdf" -> AmSource.Pdf(o.str("blob"))
        "epub" -> o.obj("layout").let {
            AmSource.Epub(o.str("blob"), AmEpubLayout(it.d("w"), it.d("h"), it.d("em")))
        }
        else -> error("unknown source kind")
    }

    private fun JsonObject.d(k: String) = getValue(k).jsonPrimitive.double

    private fun background(o: JsonObject) = AmBackground(o.str("type"), o.strOrNull("native"), o.strOrNull("blob"))

    fun notebook(o: JsonObject) = AmNotebook(
        notebookId = o.str("notebookId"),
        openPageId = o.strOrNull("openPageId"),
        defaultBackground = background(o.obj("defaultBackground")),
        pageOrder = o.getValue("pageOrder").jsonArray.map { it.jsonPrimitive.content },
        pages = o.obj("pages").mapValues { (pid, v) ->
            val p = v.jsonObject
            AmPage(
                id = pid,
                background = background(p.obj("background")),
                strokes = p.obj("strokes").mapValues { (sid, sv) ->
                    val s = sv.jsonObject
                    val bbox = s.getValue("bbox").jsonArray.map { it.jsonPrimitive.double }
                    AmStroke(
                        id = sid, pen = s.str("pen"), color = s.getValue("color").jsonPrimitive.int,
                        size = s.d("size"), left = bbox[0], top = bbox[1], right = bbox[2], bottom = bbox[3],
                        points = Base64.getDecoder().decode(s.str("points")),
                        createdAt = s.date("createdAt"), updatedAt = s.date("updatedAt"),
                    )
                },
                images = p.obj("images").mapValues { (iid, iv) ->
                    val i = iv.jsonObject
                    val r = (i.getValue("rect") as JsonArray).map { it.jsonPrimitive.int }
                    AmImage(iid, i.str("blob"), r[0], r[1], r[2], r[3], i.date("createdAt"), i.date("updatedAt"))
                },
                createdAt = p.date("createdAt"),
                updatedAt = p.date("updatedAt"),
            )
        },
    )
}
