package com.ethran.notable.sync.automerge

import com.ethran.notable.data.LocalChange
import com.ethran.notable.data.PendingLocalWrites
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What a local edit that is in Room, but not yet in the docs, touched. A retry makes the doc match
 * what Room holds for it *now* ([LocalDocWriter.replay]), so debts merge by [key] and their order
 * doesn't matter: Room has the result of every local edit, in order.
 */
sealed interface DocDebt {
    val key: String

    data class Strokes(val pageId: String, val ids: Set<String>) : DocDebt {
        override val key get() = "strokes/$pageId"
    }

    data class Images(val pageId: String, val ids: Set<String>) : DocDebt {
        override val key get() = "images/$pageId"
    }

    /** A page row; [notebookId] tells where to delete it from when Room no longer has it. */
    data class PageRow(val pageId: String, val notebookId: String?) : DocDebt {
        override val key get() = "page/$pageId"
    }

    data class NotebookRow(val id: String) : DocDebt {
        override val key get() = "notebook/$id"
    }

    data class OpenPage(val notebookId: String) : DocDebt {
        override val key get() = "open/$notebookId"
    }

    data class FolderRow(val id: String) : DocDebt {
        override val key get() = "folder/$id"
    }

    companion object {
        fun of(change: LocalChange): DocDebt = when (change) {
            is LocalChange.StrokesUpserted -> Strokes(change.pageId, change.strokes.map { it.id }.toSet())
            is LocalChange.StrokesDeleted -> Strokes(change.pageId, change.ids.toSet())
            is LocalChange.ImagesUpserted -> Images(change.pageId, change.images.map { it.id }.toSet())
            is LocalChange.ImagesDeleted -> Images(change.pageId, change.ids.toSet())
            is LocalChange.PageTouched -> PageRow(change.pageId, null)
            is LocalChange.PageUpserted -> PageRow(change.page.id, change.page.notebookId)
            is LocalChange.PageDeleted -> PageRow(change.page.id, change.page.notebookId)
            is LocalChange.NotebookUpserted -> NotebookRow(change.notebook.id)
            is LocalChange.NotebookDeleted -> NotebookRow(change.id)
            is LocalChange.OpenPageChanged -> OpenPage(change.notebookId)
            is LocalChange.FolderUpserted -> FolderRow(change.folder.id)
            is LocalChange.FolderDeleted -> FolderRow(change.id)
        }
    }
}

private val DocDebt.contentIds: Set<String>
    get() = when (this) {
        is DocDebt.Strokes -> ids
        is DocDebt.Images -> ids
        else -> emptySet()
    }

/**
 * Local edits that are in Room but not yet in the docs, kept in [file] until a retry gets them
 * there (survives restarts). Stroke and image ids in here are [pending], so the projector leaves
 * their Room rows alone: those rows are what the retry will write.
 *
 * [record] writes the file before it returns and throws when it can't, so the caller can stop
 * before the Room write: Room must never hold an edit the doc will never get.
 */
class DocOutbox(
    private val file: File,
    private val pending: PendingLocalWrites,
    private val onChange: (Int) -> Unit = {},
) {
    private val debts = LinkedHashMap<String, DocDebt>()

    init {
        load().forEach { debts[it.key] = it }
        debts.values.forEach { pending.add(it.contentIds) }
    }

    val size: Int @Synchronized get() = debts.size

    @Synchronized
    fun debts(): List<DocDebt> = debts.values.toList()

    /** Adds [change]'s debt, merged with one for the same rows. @throws IOException when it can't be stored. */
    @Synchronized
    fun record(change: LocalChange) {
        val debt = DocDebt.of(change)
        val old = debts[debt.key]
        val merged = merge(old, debt)
        if (merged == old) return
        val next = LinkedHashMap(debts).apply { put(debt.key, merged) }
        save(next.values)
        debts.clear()
        debts.putAll(next)
        pending.add(merged.contentIds - old?.contentIds.orEmpty())
        onChange(debts.size)
    }

    /** [debt] reached the doc. Ids recorded for the same rows since stay. */
    @Synchronized
    fun done(debt: DocDebt) {
        val current = debts[debt.key] ?: return
        val left: DocDebt? = when (current) {
            is DocDebt.Strokes -> current.copy(ids = current.ids - debt.contentIds).takeIf { it.ids.isNotEmpty() }
            is DocDebt.Images -> current.copy(ids = current.ids - debt.contentIds).takeIf { it.ids.isNotEmpty() }
            else -> null
        }
        val next = LinkedHashMap(debts).apply { if (left == null) remove(debt.key) else put(debt.key, left) }
        save(next.values)
        debts.clear()
        debts.putAll(next)
        pending.remove(current.contentIds - left?.contentIds.orEmpty())
        onChange(debts.size)
    }

    /** The session ended: its ids are no longer pending here. The file stays for the next session. */
    @Synchronized
    fun release() {
        debts.values.forEach { pending.remove(it.contentIds) }
    }

    /** Moves [debt] to the end, so one that keeps failing doesn't hold up the others. */
    @Synchronized
    fun defer(debt: DocDebt) {
        val current = debts.remove(debt.key) ?: return
        debts[debt.key] = current
        runCatching { save(debts.values) } // the order is a hint; losing it loses nothing
    }

    private fun merge(old: DocDebt?, new: DocDebt): DocDebt = when {
        old == null -> new
        old is DocDebt.Strokes && new is DocDebt.Strokes -> old.copy(ids = old.ids + new.ids)
        old is DocDebt.Images && new is DocDebt.Images -> old.copy(ids = old.ids + new.ids)
        old is DocDebt.PageRow && new is DocDebt.PageRow -> old.copy(notebookId = new.notebookId ?: old.notebookId)
        else -> old
    }

    private fun load(): List<DocDebt> {
        if (!file.exists()) return emptyList()
        return Json.parseToJsonElement(file.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            fun str(k: String) = o.getValue(k).jsonPrimitive.content
            fun ids() = o.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.toSet()
            when (val t = str("t")) {
                "strokes" -> DocDebt.Strokes(str("page"), ids())
                "images" -> DocDebt.Images(str("page"), ids())
                "page" -> DocDebt.PageRow(str("page"), o["notebook"]?.jsonPrimitive?.contentOrNull)
                "notebook" -> DocDebt.NotebookRow(str("id"))
                "open" -> DocDebt.OpenPage(str("id"))
                "folder" -> DocDebt.FolderRow(str("id"))
                else -> throw IOException("${file.path}: unknown entry type '$t'")
            }
        }
    }

    private fun save(all: Collection<DocDebt>) {
        val json = JsonArray(all.map { d ->
            fun obj(vararg kv: Pair<String, Any?>) = JsonObject(kv.associate { (k, v) ->
                k to when (v) {
                    null -> JsonNull
                    is Set<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                    else -> JsonPrimitive(v as String)
                }
            })
            when (d) {
                is DocDebt.Strokes -> obj("t" to "strokes", "page" to d.pageId, "ids" to d.ids)
                is DocDebt.Images -> obj("t" to "images", "page" to d.pageId, "ids" to d.ids)
                is DocDebt.PageRow -> obj("t" to "page", "page" to d.pageId, "notebook" to d.notebookId)
                is DocDebt.NotebookRow -> obj("t" to "notebook", "id" to d.id)
                is DocDebt.OpenPage -> obj("t" to "open", "id" to d.notebookId)
                is DocDebt.FolderRow -> obj("t" to "folder", "id" to d.id)
            }
        })
        writeDurably(file, json.toString().toByteArray())
    }
}

/** Writes [bytes] to a temp file, syncs it, then renames it over [file]. */
internal fun writeDurably(file: File, bytes: ByteArray) {
    val dir = file.absoluteFile.parentFile
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
    val tmp = File(dir, file.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.fd.sync()
    }
    if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
}
