package com.ethran.notable.sync.automerge

import com.ethran.notable.data.RemotePageEdit
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.MAX_PRESSURE_NORMALIZED
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.editor.utils.Pen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.ChangeHash
import org.automerge.Document
import org.automerge.Patch
import org.automerge.PatchAction
import org.automerge.Prop
import java.io.File

/** Where projected rows go: Room on the device, a fake in tests. Blocking; runs off the main thread. */
interface ProjectionSink {
    fun <T> transaction(block: () -> T): T
    fun folder(id: String): Folder?
    fun upsertFolder(f: Folder)
    fun folderHasChildren(id: String): Boolean
    fun deleteFolder(id: String)
    fun notebook(id: String): Notebook?
    fun upsertNotebook(n: Notebook)
    fun deleteNotebook(id: String)
    fun page(id: String): Page?
    fun upsertPage(p: Page)
    fun deletePage(id: String)
    fun strokeIds(pageId: String): List<String>
    fun upsertStrokes(s: List<Stroke>)
    fun deleteStrokes(ids: Collection<String>)
    fun imageIds(pageId: String): List<String>
    fun upsertImages(i: List<Image>)
    fun deleteImages(ids: Collection<String>)
}

/** Turns blob hashes into files the editor can open. Downloads on demand. */
interface ProjectionFiles {
    /** Absolute path of a background; [type] is its `BackgroundType.key`. @throws MissingFileException */
    fun backgroundPath(blob: String, type: String): String

    /** URI string of an image, as [Image.uri] stores it. @throws MissingFileException */
    fun imageUri(blob: String): String

    /** True when [pathOrUri] already holds the blob's bytes, so a row can keep pointing at it. */
    fun holds(pathOrUri: String, blob: String): Boolean
}

/**
 * The heads each doc was last projected at, and which doc each library entry used. Survives
 * restarts, so a restart applies only what changed while the app was gone.
 */
class ProjectionStateStore(private val file: File) {
    private var docs: MutableMap<String, List<String>> = mutableMapOf()
    private var entries: MutableMap<String, String> = mutableMapOf()

    init {
        if (file.exists()) {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            docs = root["docs"]?.jsonObject?.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
                ?.toMutableMap() ?: mutableMapOf()
            entries = root["entries"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }
                ?.toMutableMap() ?: mutableMapOf()
        }
    }

    @Synchronized
    fun heads(docUrl: String): Array<ChangeHash>? = docs[docUrl]?.map { StoredChangeHash(hexToBytes(it)) }?.toTypedArray()

    @Synchronized
    fun setHeads(docUrl: String, heads: Array<ChangeHash>) {
        docs[docUrl] = heads.map { bytesToHex(it.bytes) }
        save()
    }

    /**
     * Sets [docUrl]'s heads to [after] if they are still [before]: a local commit that Room already
     * has, with nothing else in between. Otherwise leaves them, and the projector applies both.
     */
    @Synchronized
    fun compareAndSetHeads(docUrl: String, before: Array<ChangeHash>, after: Array<ChangeHash>): Boolean {
        val stored = docs[docUrl] ?: return false
        if (stored.toSet() != before.map { bytesToHex(it.bytes) }.toSet()) return false
        setHeads(docUrl, after)
        return true
    }

    @Synchronized
    fun setHeadsIfAbsent(docUrl: String, heads: Array<ChangeHash>) {
        if (docUrl !in docs) setHeads(docUrl, heads)
    }

    @Synchronized
    fun entries(): Map<String, String> = entries.toMap()

    @Synchronized
    fun setEntries(next: Map<String, String>) {
        entries = next.toMutableMap()
        save()
    }

    @Synchronized
    fun forget(docUrl: String) {
        docs.remove(docUrl)
        save()
    }

    /** The next projection of every doc is a full one (upsert only). */
    @Synchronized
    fun forgetAll() {
        docs.clear()
        save()
    }

    private fun save() {
        file.absoluteFile.parentFile?.mkdirs()
        val json = JsonObject(
            mapOf(
                "docs" to JsonObject(docs.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }),
                "entries" to JsonObject(entries.mapValues { JsonPrimitive(it.value) }),
            )
        )
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) throw java.io.IOException("could not write ${file.path}")
    }

    private companion object {
        fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Heads compared by their bytes. ChangeHash has no value equality, and stored heads come back as
 * [StoredChangeHash], so `contentEquals` never matched and every projection re-read the doc.
 */
internal fun sameHeads(a: Array<ChangeHash>, b: Array<ChangeHash>): Boolean =
    a.size == b.size && a.map { it.bytes.toList() }.toSet() == b.map { it.bytes.toList() }.toSet()

/** automerge-java only creates ChangeHash itself; heads read back from disk come through here. */
private class StoredChangeHash(bytes: ByteArray) : ChangeHash(bytes)

data class LibraryProjection(val follow: List<AmNotebookEntry>, val warnings: List<String>)
data class NotebookProjection(val edits: List<RemotePageEdit>, val warnings: List<String>)

/**
 * Projects the shared Automerge docs into Room (ADR-001, plan Phase 3 part B).
 *
 * - **Incremental.** Each doc's last projected heads are stored. A change is applied from
 *   `doc.diff(lastHeads, heads)`: only touched pages are read and only touched rows written.
 *   A doc without stored heads (first sync, rebuild) is projected in full.
 * - **Deletions are explicit.** A stroke, image, page, notebook or folder is deleted only when the
 *   diff deletes its key. A full projection never deletes, so rows that exist only in Room (drawn
 *   here after the upload, not synced yet) survive both paths.
 * - **No echo.** The projector writes Room only, never a doc. The migration skips every notebook
 *   that already has a library entry, so projected rows are never uploaded again.
 * - **Local writes win.** Strokes and images with a queued local write are neither upserted nor
 *   deleted here; the local write lands in Room after its doc commit, and a later projection
 *   applies whatever the doc holds then.
 * - **Failures are visible.** A doc that fails (schema error, missing blob) throws; its heads are
 *   not advanced, so the next change or reconnect retries it.
 *
 * Call [prepareNotebook]/[prepareLibrary] inside the doc's `withDocument` and [apply] outside it,
 * so the doc's executor is not blocked on Room. [projectNotebook]/[projectLibrary] do both.
 */
class AutomergeProjector(
    private val sink: ProjectionSink,
    private val files: ProjectionFiles,
    private val state: ProjectionStateStore,
    private val localCanvasWidth: Double,
    /** Stroke and image ids with a queued local write; see [com.ethran.notable.data.PendingLocalWrites]. */
    private val localPending: (String) -> Boolean = { false },
) {
    @Volatile
    private var library: AmLibrary? = null

    fun forgetAll() = state.forgetAll()

    /* ------------------------------ library ------------------------------ */

    class LibraryPlan internal constructor(
        val url: String,
        val heads: Array<ChangeHash>,
        val library: AmLibrary,
        val deletedFolders: Set<String>,
        val deletedEntries: Set<String>,
        /** Folders and entries the diff touched; null means all (a full projection). */
        val touched: Set<String>?,
    )

    fun prepareLibrary(doc: Document, url: String): LibraryPlan {
        val heads = doc.heads
        val library = AutomergeSchema.readLibrary(doc)
        val last = state.heads(url)
        val deletedFolders = HashSet<String>()
        val deletedEntries = HashSet<String>()
        // Only what the diff touched is applied. Room may hold newer values than the doc (its own
        // "edited" dates move with every stroke), and an untouched entry must not overwrite them.
        var touched: HashSet<String>? = if (last == null) null else HashSet()
        if (last != null && !sameHeads(last, heads)) {
            for (patch in doc.diff(last, heads)) {
                val keys = keysOf(patch)
                if (keys.size < 2) { touched = null; continue }
                touched?.add("${keys[0]}/${keys[1]}")
                if (keys.size == 2 && patch.action is PatchAction.DeleteMap) {
                    when (keys[0]) {
                        "folders" -> if (keys[1] !in library.folders) deletedFolders += keys[1]
                        "notebooks" -> if (keys[1] !in library.notebooks) deletedEntries += keys[1]
                    }
                }
            }
        }
        return LibraryPlan(url, heads, library, deletedFolders, deletedEntries, touched)
    }

    fun apply(plan: LibraryPlan): LibraryProjection {
        val warnings = mutableListOf<String>()
        val lib = plan.library
        val touched = plan.touched
        sink.transaction {
            if (touched == null || touched.any { it.startsWith("folders/") }) upsertFolders(lib, warnings)
            for (e in lib.notebooks.values)
                if (touched == null || "notebooks/${e.id}" in touched) updateEntryRow(e, lib, warnings)
            val known = state.entries()
            for (id in plan.deletedEntries) {
                when {
                    sink.notebook(id) != null -> sink.deleteNotebook(id)
                    sink.page(id)?.notebookId == null && sink.page(id) != null -> sink.deletePage(id)
                }
                known[id]?.let { state.forget(it) }
            }
            for (id in plan.deletedFolders) {
                if (sink.folder(id) == null) continue
                if (sink.folderHasChildren(id)) warnings += "folder $id was deleted elsewhere but still has content here; kept"
                else sink.deleteFolder(id)
            }
        }
        library = lib
        state.setEntries(lib.notebooks.mapValues { it.value.docUrl })
        state.setHeads(plan.url, plan.heads)
        return LibraryProjection(lib.notebooks.values.toList(), warnings)
    }

    fun projectLibrary(doc: Document, url: String) = apply(prepareLibrary(doc, url))

    /** Parents before children; a parent the library doesn't have becomes the root. */
    private fun upsertFolders(lib: AmLibrary, warnings: MutableList<String>) {
        val done = HashSet<String>()
        fun visit(f: AmFolder, depth: Int) {
            if (f.id in done) return
            val parent = f.parentId?.let { lib.folders[it] }
            if (parent != null && depth < lib.folders.size) visit(parent, depth + 1)
            done += f.id
            val parentId = when {
                f.parentId == null -> null
                parent != null || sink.folder(f.parentId) != null -> f.parentId
                else -> null.also { warnings += "folder ${f.id}: parent ${f.parentId} is unknown; placed at the top" }
            }
            val row = Folder(f.id, f.title, parentId, f.createdAt, f.updatedAt)
            if (sink.folder(f.id) != row) sink.upsertFolder(row)
        }
        lib.folders.values.forEach { visit(it, 0) }
    }

    private fun folderOrRoot(id: String?, lib: AmLibrary): String? =
        id?.takeIf { it in lib.folders || sink.folder(it) != null }

    /** Title and folder changes of a notebook or quick page that already has a row. */
    private fun updateEntryRow(e: AmNotebookEntry, lib: AmLibrary, warnings: MutableList<String>) {
        val folder = folderOrRoot(e.folderId, lib)
        if (e.folderId != null && folder == null) warnings += "${e.id}: folder ${e.folderId} is unknown; placed at the top"
        when (e.kind) {
            AmEntryKind.NOTEBOOK -> {
                val nb = sink.notebook(e.id) ?: return
                val next = nb.copy(title = e.title, parentFolderId = folder, createdAt = e.createdAt, updatedAt = e.updatedAt)
                if (next != nb) sink.upsertNotebook(next)
            }
            AmEntryKind.QUICK_PAGE -> {
                val p = sink.page(e.id) ?: return
                val next = p.copy(parentFolderId = folder)
                if (next != p) sink.upsertPage(next)
            }
        }
    }

    /* ------------------------------ notebooks ------------------------------ */

    /** What a diff touched, by page. */
    private class Delta {
        var notebookMeta = false
        val pagesCreated = HashSet<String>()
        val pagesMeta = HashSet<String>()
        val pagesDeleted = HashSet<String>()
        val strokesReplaced = HashSet<String>()
        val imagesReplaced = HashSet<String>()
        val strokesTouched = HashMap<String, HashSet<String>>()
        val strokesDeleted = HashMap<String, HashSet<String>>()
        val imagesTouched = HashMap<String, HashSet<String>>()
        val imagesDeleted = HashMap<String, HashSet<String>>()

        fun pagesToRead(): Set<String> =
            pagesCreated + pagesMeta + strokesReplaced + imagesReplaced + strokesTouched.keys + imagesTouched.keys

        fun add(keys: List<String>, action: PatchAction) {
            if (keys.isEmpty() || keys[0] != "pages") { notebookMeta = true; return }
            if (keys.size == 1) { notebookMeta = true; return } // the pages map itself replaced: rare
            val pageId = keys[1]
            when {
                keys.size == 2 -> if (action is PatchAction.DeleteMap) pagesDeleted += pageId else pagesCreated += pageId
                keys[2] == "strokes" -> child(keys, action, pageId, strokesReplaced, strokesTouched, strokesDeleted)
                keys[2] == "images" -> child(keys, action, pageId, imagesReplaced, imagesTouched, imagesDeleted)
                else -> pagesMeta += pageId
            }
            notebookMeta = notebookMeta || keys.size == 2
        }

        private fun child(
            keys: List<String>, action: PatchAction, pageId: String, replaced: HashSet<String>,
            touched: HashMap<String, HashSet<String>>, deleted: HashMap<String, HashSet<String>>,
        ) {
            if (keys.size == 3) { replaced += pageId; return }
            val id = keys[3]
            if (keys.size == 4 && action is PatchAction.DeleteMap) deleted.getOrPut(pageId) { HashSet() } += id
            else touched.getOrPut(pageId) { HashSet() } += id
        }
    }

    class NotebookPlan internal constructor(
        val url: String,
        val heads: Array<ChangeHash>,
        internal val shell: AmNotebook?,
        internal val pages: Map<String, AmPage>,
        internal val delta: Any?, // Delta, or null for a full projection
    )

    fun prepareNotebook(doc: Document, url: String): NotebookPlan {
        val heads = doc.heads
        val last = state.heads(url)
        if (last == null) {
            val nb = AutomergeSchema.readNotebook(doc)
            return NotebookPlan(url, heads, nb, nb.pages, null)
        }
        if (sameHeads(last, heads)) return NotebookPlan(url, heads, null, emptyMap(), Delta())
        val delta = Delta()
        for (patch in doc.diff(last, heads)) delta.add(keysOf(patch), patch.action)
        val shell = AutomergeSchema.readNotebookShell(doc)
        val pages = delta.pagesToRead().mapNotNull { AutomergeSchema.readPage(doc, it) }.associateBy { it.id }
        return NotebookPlan(url, heads, shell, pages, delta)
    }

    fun apply(plan: NotebookPlan): NotebookProjection {
        val shell = plan.shell
        if (shell == null) { // nothing changed since the last projection
            state.setHeads(plan.url, plan.heads)
            return NotebookProjection(emptyList(), emptyList())
        }
        val lib = library ?: throw IllegalStateException("project the library before its notebooks")
        val entry = lib.notebooks.values.firstOrNull { it.docUrl == plan.url }
            ?: throw IllegalStateException("${plan.url} is not in the library")
        val warnings = mutableListOf<String>()
        val factor = localCanvasWidth / (shell.canvasWidth ?: DEFAULT_CANVAS_WIDTH)
        val delta = plan.delta as Delta?

        // Resolve every file before writing, so a missing blob leaves Room untouched.
        val backgrounds = HashMap<String, Pair<String, String>>() // pageId -> (type, value)
        val pageRows = HashMap<String, Page>()
        val notebookId = if (entry.kind == AmEntryKind.NOTEBOOK) entry.id else null
        val folderId = if (entry.kind == AmEntryKind.QUICK_PAGE) folderOrRoot(entry.folderId, lib) else null
        for (p in plan.pages.values) {
            val existing = sink.page(p.id)
            val writeRow = delta == null || p.id in delta.pagesCreated || p.id in delta.pagesMeta || existing == null
            if (!writeRow) continue
            if (entry.kind == AmEntryKind.QUICK_PAGE && p.id != entry.id) {
                warnings += "quick page ${entry.id} has an extra page ${p.id}; skipped"
                continue
            }
            val bg = background(p.background, existing?.backgroundType, existing?.background)
            backgrounds[p.id] = bg
            pageRows[p.id] = Page(
                id = p.id, scroll = existing?.scroll ?: 0, notebookId = notebookId,
                background = bg.second, backgroundType = bg.first, parentFolderId = folderId,
                createdAt = p.createdAt, updatedAt = p.updatedAt,
            )
        }
        val images = HashMap<String, List<Image>>()
        for (p in plan.pages.values) {
            if (p.id !in pageRows && sink.page(p.id) == null) continue
            val wanted = if (delta == null || p.id in delta.pagesCreated || p.id in delta.imagesReplaced) p.images.keys
            else delta.imagesTouched[p.id].orEmpty().intersect(p.images.keys)
            images[p.id] = wanted.filterNot(localPending).map { id ->
                val i = p.images.getValue(id)
                Image(i.id, (i.x * factor).toInt(), (i.y * factor).toInt(), (i.height * factor).toInt(), (i.width * factor).toInt(),
                    files.imageUri(i.blob), p.id, i.createdAt, i.updatedAt)
            }
        }
        val notebookRow = if (entry.kind == AmEntryKind.NOTEBOOK) notebookRow(entry, shell, lib, delta, warnings) else null

        val edits = ArrayList<RemotePageEdit>()
        sink.transaction {
            notebookRow?.let { if (sink.notebook(it.id) != it) sink.upsertNotebook(it) }
            for ((id, row) in pageRows) if (sink.page(id) != row) sink.upsertPage(row)
            delta?.pagesDeleted?.filter { it !in shell.pages && sink.page(it) != null }?.forEach { id ->
                sink.deletePage(id)
                edits += RemotePageEdit(id, pageDeleted = true)
            }
            for (p in plan.pages.values) {
                if (sink.page(p.id) == null) continue
                val all = delta == null || p.id in delta.pagesCreated || p.id in delta.strokesReplaced
                val strokeIds = if (all) p.strokes.keys else delta!!.strokesTouched[p.id].orEmpty().intersect(p.strokes.keys)
                val strokes = strokeIds.filterNot(localPending).map { toStroke(p.strokes.getValue(it), p.id, factor, warnings) }
                val goneStrokes = when {
                    delta == null -> emptySet()
                    p.id in delta.strokesReplaced -> sink.strokeIds(p.id).toSet() - p.strokes.keys
                    else -> delta.strokesDeleted[p.id].orEmpty() - p.strokes.keys
                }.filterNotTo(HashSet(), localPending)
                val goneImages = when {
                    delta == null -> emptySet()
                    p.id in delta.imagesReplaced -> sink.imageIds(p.id).toSet() - p.images.keys
                    else -> delta.imagesDeleted[p.id].orEmpty() - p.images.keys
                }.filterNotTo(HashSet(), localPending)
                strokes.chunked(BATCH).forEach(sink::upsertStrokes)
                sink.deleteStrokes(goneStrokes)
                val imgs = images[p.id].orEmpty()
                sink.upsertImages(imgs)
                sink.deleteImages(goneImages)
                val pageChanged = p.id in pageRows
                if (strokes.isNotEmpty() || goneStrokes.isNotEmpty() || imgs.isNotEmpty() || goneImages.isNotEmpty() || pageChanged)
                    edits += RemotePageEdit(p.id, strokes, goneStrokes, imgs, goneImages, pageChanged = pageChanged)
            }
            // Strokes deleted from a page that wasn't read (only deletions in the diff).
            delta?.strokesDeleted?.filterKeys { it !in plan.pages && it in shell.pages }?.forEach { (pageId, all) ->
                val ids = all.filterNotTo(HashSet(), localPending)
                if (sink.page(pageId) == null || ids.isEmpty()) return@forEach
                sink.deleteStrokes(ids)
                edits += RemotePageEdit(pageId, deleteStrokes = ids)
            }
            delta?.imagesDeleted?.filterKeys { it !in plan.pages && it in shell.pages }?.forEach { (pageId, all) ->
                val ids = all.filterNotTo(HashSet(), localPending)
                if (sink.page(pageId) == null || ids.isEmpty()) return@forEach
                sink.deleteImages(ids)
                edits += RemotePageEdit(pageId, deleteImages = ids)
            }
        }
        state.setHeads(plan.url, plan.heads)
        return NotebookProjection(edits, warnings)
    }

    fun projectNotebook(doc: Document, url: String) = apply(prepareNotebook(doc, url))

    private fun notebookRow(
        e: AmNotebookEntry, nb: AmNotebook, lib: AmLibrary, delta: Delta?, warnings: MutableList<String>,
    ): Notebook {
        val existing = sink.notebook(e.id)
        // Pages only Room knows (made here after the upload) keep their place at the end, unless the
        // diff explicitly deleted them.
        val deleted = delta?.pagesDeleted.orEmpty()
        val localOnly = existing?.pageIds.orEmpty().filter { it !in nb.pages && it !in deleted }
        val bg = background(nb.defaultBackground, existing?.defaultBackgroundType, existing?.defaultBackground)
        if (e.folderId != null && folderOrRoot(e.folderId, lib) == null) warnings += "${e.id}: folder ${e.folderId} is unknown"
        return Notebook(
            id = e.id, title = e.title,
            openPageId = existing?.openPageId?.takeIf { it in nb.pages || it in localOnly } ?: nb.openPageId,
            pageIds = nb.pageOrder + localOnly,
            parentFolderId = folderOrRoot(e.folderId, lib),
            defaultBackground = bg.second, defaultBackgroundType = bg.first,
            linkedExternalUri = existing?.linkedExternalUri,
            createdAt = e.createdAt, updatedAt = e.updatedAt,
        )
    }

    /** (type, value) for a Room row. Keeps an existing file that already holds the same blob. */
    private fun background(b: AmBackground, existingType: String?, existingValue: String?): Pair<String, String> {
        if (b.type == NATIVE) return NATIVE to (b.native ?: throw SchemaException("native background without a name"))
        val blob = b.blob ?: throw SchemaException("background '${b.type}' without a blob")
        if (existingType == b.type && existingValue != null && files.holds(existingValue, blob)) return b.type to existingValue
        return b.type to files.backgroundPath(blob, b.type)
    }

    private fun toStroke(s: AmStroke, pageId: String, factor: Double, warnings: MutableList<String>): Stroke {
        val pen = Pen.entries.firstOrNull { it.penName == s.pen }
            ?: Pen.BALLPEN.also { warnings += "stroke ${s.id}: unknown pen '${s.pen}', drawn as BALLPEN" }
        val f = factor.toFloat()
        val points = decodeStrokePoints(s.points).let { pts ->
            if (factor == 1.0) pts else pts.map { it.copy(x = it.x * f, y = it.y * f) }
        }
        return Stroke(
            id = s.id, size = (s.size * factor).toFloat(), pen = pen, color = s.color, maxPressure = MAX_PRESSURE_NORMALIZED,
            top = (s.top * factor).toFloat(), bottom = (s.bottom * factor).toFloat(),
            left = (s.left * factor).toFloat(), right = (s.right * factor).toFloat(),
            points = points, pageId = pageId, createdAt = s.createdAt, updatedAt = s.updatedAt,
        )
    }

    companion object {
        /** The Note Air 5C's canvas width; docs written before canvasWidth existed came from it. */
        const val DEFAULT_CANVAS_WIDTH = 1860.0
        private const val NATIVE = "native"
        private const val BATCH = 500

        /** Path from the doc root to the patched property, as strings. */
        internal fun keysOf(patch: Patch): List<String> {
            val keys = patch.path.map { propString(it.prop) }.toMutableList()
            when (val a = patch.action) {
                is PatchAction.PutMap -> keys += a.key
                is PatchAction.DeleteMap -> keys += a.key
                else -> Unit
            }
            return keys
        }

        private fun propString(p: Prop): String = when (p) {
            is Prop.Key -> p.value
            is Prop.Index -> "#${p.value}"
            else -> p.toString()
        }
    }
}
