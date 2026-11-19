package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.automerge.ChangeHash
import java.io.File

/** One earlier state of a page: its notebook doc [url] at [heads], as it was at [at] (ms). */
class PageVersion(val pageId: String, val at: Long, val url: String, val heads: Array<ChangeHash>)

/**
 * Earlier states of pages (docs/plans/library-features.md, 40), per device. automerge-java 0.0.9
 * can read a doc at given heads ([org.automerge.Document.fork]) but can't list a doc's history, so
 * points to go back to are recorded here: before the first edit of a page after [INTERVAL_MS]
 * without one, the heads of its notebook doc then. At most [KEEP] per page, newest kept. The docs
 * keep every change, so a recorded version stays readable for as long as the doc does.
 */
class PageVersionStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    companion object {
        const val INTERVAL_MS = 10 * 60_000L
        const val KEEP = 30
        /** Pages with versions kept; the one recorded longest ago goes first (deleted pages age out). */
        const val MAX_PAGES = 2000
    }

    private class Entry(val at: Long, val url: String, val heads: List<String>)

    private val pages = LinkedHashMap<String, MutableList<Entry>>()

    init {
        runCatching { load() }
    }

    /** No version of [pageId] in the last [INTERVAL_MS]: the next edit should record one. */
    @Synchronized
    fun due(pageId: String): Boolean = pages[pageId]?.lastOrNull()?.let { now() - it.at >= INTERVAL_MS } ?: true

    /** Records [heads] of doc [url] as a version of [pageId] now, unless the last one has the same heads. */
    @Synchronized
    fun record(url: String, pageId: String, heads: Array<ChangeHash>) {
        val hex = heads.map { hexOf(it.bytes) }.sorted()
        val list = pages.remove(pageId) ?: mutableListOf()
        pages[pageId] = list // most recently recorded last
        if (list.lastOrNull()?.let { it.url == url && it.heads == hex } == true) return
        list += Entry(now(), url, hex)
        while (list.size > KEEP) list.removeAt(0)
        while (pages.size > MAX_PAGES) pages.remove(pages.keys.first())
        save()
    }

    /** [pageId]'s versions, newest first. */
    @Synchronized
    fun list(pageId: String): List<PageVersion> = pages[pageId].orEmpty().asReversed().map { e ->
        PageVersion(pageId, e.at, e.url, e.heads.map { StoredChangeHash(bytesOf(it)) }.toTypedArray())
    }

    private fun load() {
        if (!file.exists()) return
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        for ((pageId, v) in o) {
            pages[pageId] = v.jsonArray.map { e ->
                val m = e.jsonObject
                Entry(m.getValue("at").jsonPrimitive.long, m.getValue("url").jsonPrimitive.content,
                    m.getValue("heads").jsonArray.map { it.jsonPrimitive.content })
            }.toMutableList()
        }
    }

    private fun save() {
        val json = JsonObject(pages.mapValues { (_, list) ->
            JsonArray(list.map { e ->
                JsonObject(mapOf("at" to JsonPrimitive(e.at), "url" to JsonPrimitive(e.url),
                    "heads" to JsonArray(e.heads.map(::JsonPrimitive))))
            })
        })
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) throw java.io.IOException("could not write ${file.path}")
    }

    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytesOf(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
