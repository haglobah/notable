package com.ethran.notable.sync.automerge

import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageWithData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.Document
import java.io.File
import java.security.MessageDigest

/** Read access to the Room data being migrated. */
interface LocalLibrary {
    fun folders(): List<Folder>
    fun notebooks(): List<Notebook>
    /** Pages with no notebook ("quick pages"). */
    fun quickPages(): List<Page>
    fun page(pageId: String): PageWithData?
    /** The page row alone, without loading its strokes. */
    fun pageRow(pageId: String): Page? = page(pageId)?.page
    fun notebook(id: String): Notebook? = notebooks().firstOrNull { it.id == id }
}

/** Uploads a local file (path or content URI) and returns its sha256 hex digest. */
fun interface BlobStore {
    /** @throws MissingFileException when the file can't be read. */
    fun upload(pathOrUri: String): String
}

/** The Automerge side: the account's library doc and new notebook docs. */
interface DocStore {
    fun library(): AmLibrary
    /** Stores [doc] as a new repo document and returns its `automerge:` URL. */
    fun createDoc(doc: Document): String
    /** Runs [edit] on the library doc; [edit] commits its own transactions. */
    fun editLibrary(edit: (Document) -> Unit)
}

/**
 * notebookId → docUrl for notebook docs already created. A crash between creating a doc and
 * writing its library entry then reuses the doc instead of making a second one.
 */
class MigrationStateStore(private val file: File) {
    fun load(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    fun record(notebookId: String, docUrl: String) {
        val next = load() + (notebookId to docUrl)
        file.absoluteFile.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JsonObject(next.mapValues { JsonPrimitive(it.value) }).toString())
        if (!tmp.renameTo(file)) throw java.io.IOException("could not write ${file.path}")
    }
}

data class MigrationProgress(val done: Int, val total: Int, val current: String?)
data class MigrationFailure(val id: String, val title: String, val reason: String)
data class MigrationWarning(val id: String, val missingPages: List<String>)
data class MigrationReport(
    val migrated: List<String>,
    val alreadyDone: List<String>,
    val failed: List<MigrationFailure>,
    val warnings: List<MigrationWarning>,
)

/**
 * One-time copy of the Room library into Automerge docs (ADR-001, plan Phase 3).
 *
 * Idempotent: a notebook whose library entry exists is skipped, and ids are the Room UUIDs.
 * The library entry is written last, so it marks a finished notebook. A notebook whose files are
 * missing fails on its own, with the file named, and stays local until a later run succeeds.
 * Any other error (network, server) aborts the run; the next run resumes.
 */
class RoomToAutomergeMigration(
    private val local: LocalLibrary,
    blobs: BlobStore,
    private val docs: DocStore,
    private val state: MigrationStateStore,
    private val canvasWidth: Double,
    private val strokesPerTransaction: Int = 500,
) {
    private val uploaded = HashMap<String, String>()
    private val mapping = RoomMapping { path -> uploaded.getOrPut(path) { blobs.upload(path) } }

    private sealed interface Item {
        val id: String
        val title: String
    }

    private data class NotebookItem(val nb: Notebook) : Item {
        override val id get() = nb.id
        override val title get() = nb.title
    }

    private data class QuickPageItem(val page: Page) : Item {
        override val id get() = page.id
        override val title get() = QUICK_PAGE_TITLE
    }

    fun run(onProgress: (MigrationProgress) -> Unit = {}): MigrationReport {
        migrateFolders()
        val items = local.notebooks().map(::NotebookItem) + local.quickPages().map(::QuickPageItem)
        val existing = docs.library().notebooks.keys
        val created = state.load()
        val migrated = mutableListOf<String>()
        val already = mutableListOf<String>()
        val failed = mutableListOf<MigrationFailure>()
        val warnings = mutableListOf<MigrationWarning>()

        items.forEachIndexed { i, item ->
            onProgress(MigrationProgress(i, items.size, item.title))
            if (item.id in existing) {
                already += item.id
                return@forEachIndexed
            }
            try {
                val (entry, missing) = when (item) {
                    is NotebookItem -> migrateNotebook(item.nb, created[item.id])
                    is QuickPageItem -> migrateQuickPage(item.page, created[item.id]) to emptyList()
                }
                docs.editLibrary { doc ->
                    val (_, notebooks) = AutomergeSchema.libraryMapsOf(doc)
                    doc.startTransaction().apply { AutomergeSchema.putNotebookEntry(this, notebooks, entry); commit() }
                }
                migrated += item.id
                if (missing.isNotEmpty()) warnings += MigrationWarning(item.id, missing)
            } catch (e: MissingFileException) {
                failed += MigrationFailure(item.id, item.title, e.message ?: "missing file")
            } catch (e: ServerError.TooLarge) {
                failed += MigrationFailure(item.id, item.title, "a file is larger than the server accepts")
            }
        }
        onProgress(MigrationProgress(items.size, items.size, null))
        return MigrationReport(migrated, already, failed, warnings)
    }

    /**
     * Makes sure the notebook or quick page [id] has a doc and a library entry, migrating it from
     * Room if not, and returns its doc URL. Null when Room has no such notebook or quick page.
     * @throws MissingFileException when a file it refers to can't be read.
     */
    fun ensureEntry(id: String): String? {
        docs.library().notebooks[id]?.let { return it.docUrl }
        val created = state.load()[id]
        val entry = local.notebook(id)?.let { migrateNotebook(it, created).first }
            ?: local.page(id)?.page?.takeIf { it.notebookId == null }?.let { migrateQuickPage(it, created) }
            ?: return null
        docs.editLibrary { doc ->
            val (_, notebooks) = AutomergeSchema.libraryMapsOf(doc)
            doc.startTransaction().apply { AutomergeSchema.putNotebookEntry(this, notebooks, entry); commit() }
        }
        return entry.docUrl
    }

    private fun migrateFolders() {
        val have = docs.library().folders.keys
        val missing = local.folders().filter { it.id !in have }
        if (missing.isEmpty()) return
        docs.editLibrary { doc ->
            val (folders, _) = AutomergeSchema.libraryMapsOf(doc)
            doc.startTransaction().apply {
                missing.forEach { AutomergeSchema.putFolder(this, folders, mapping.folder(it)) }
                commit()
            }
        }
    }

    /** Returns the library entry and the page ids the notebook lists but Room no longer has. */
    private fun migrateNotebook(nb: Notebook, existingDocUrl: String?): Pair<AmNotebookEntry, List<String>> {
        val listed = nb.pageIds.distinct()
        val pages = listed.mapNotNull { local.page(it) }
        val missing = listed.filter { id -> pages.none { it.page.id == id } }
        // Resolve every file before writing anything, so a missing one fails cleanly.
        val source = mapping.source(nb, pages)
        val mapped = pages.map { mapping.page(it) }
        val docUrl: String = existingDocUrl ?: kotlin.run {
            val shell = AmNotebook(
                notebookId = nb.id,
                openPageId = nb.openPageId?.takeIf { id -> mapped.any { it.id == id } },
                defaultBackground = mapping.background(nb.defaultBackgroundType, nb.defaultBackground),
                pageOrder = mapped.map { it.id },
                pages = emptyMap(),
                canvasWidth = canvasWidth,
            )
            val url: String = createDoc(shell, mapped)
            state.record(nb.id, url)
            url
        }
        val entry = AmNotebookEntry(
            id = nb.id, docUrl = docUrl, title = nb.title, folderId = nb.parentFolderId,
            kind = AmEntryKind.NOTEBOOK, source = source, createdAt = nb.createdAt, updatedAt = nb.updatedAt,
        )
        return entry to missing
    }

    private fun migrateQuickPage(page: Page, existingDocUrl: String?): AmNotebookEntry {
        val data = local.page(page.id) ?: PageWithData(page, emptyList(), emptyList())
        val mapped = mapping.page(data)
        val source = if (mapped.background.type == "native") AmSource.None
        else if (mapped.background.type.startsWith("pdf") || mapped.background.type == "autoPdf") AmSource.Pdf(mapped.background.blob!!)
        else AmSource.None
        val docUrl: String = existingDocUrl ?: kotlin.run {
            val shell = AmNotebook(
                notebookId = page.id, openPageId = page.id, defaultBackground = mapped.background,
                pageOrder = listOf(page.id), pages = emptyMap(), canvasWidth = canvasWidth,
            )
            val url: String = createDoc(shell, listOf(mapped))
            state.record(page.id, url)
            url
        }
        return AmNotebookEntry(
            id = page.id, docUrl = docUrl, title = QUICK_PAGE_TITLE, folderId = page.parentFolderId,
            kind = AmEntryKind.QUICK_PAGE, source = source, createdAt = page.createdAt, updatedAt = page.updatedAt,
        )
    }

    /** Builds the doc page by page, strokes in batches, so no single transaction holds a whole notebook. */
    private fun createDoc(shell: AmNotebook, pages: List<AmPage>): String {
        val doc = AutomergeSchema.newNotebookDocument(shell)
        try {
            val pagesObj = AutomergeSchema.pagesOf(doc)
            for (page in pages) {
                doc.startTransaction().apply {
                    AutomergeSchema.putPage(this, pagesObj, page.copy(strokes = emptyMap()))
                    commit()
                }
                val strokesObj = AutomergeSchema.strokesOf(doc, page.id)
                page.strokes.values.chunked(strokesPerTransaction.coerceAtLeast(1)).forEach { chunk ->
                    doc.startTransaction().apply {
                        chunk.forEach { AutomergeSchema.putStroke(this, strokesObj, it) }
                        commit()
                    }
                }
            }
            return docs.createDoc(doc)
        } finally {
            doc.free()
        }
    }

    companion object {
        /** Quick pages have no title in Room; the PWA shows this one. */
        const val QUICK_PAGE_TITLE = "Quick page"
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Opens a plain file path, turning "not there" into [MissingFileException]. */
fun openLocalFile(path: String): java.io.InputStream {
    val f = File(path)
    if (!f.isFile) throw MissingFileException(path)
    return try {
        f.inputStream()
    } catch (e: java.io.FileNotFoundException) {
        throw MissingFileException("$path (${e.message})")
    }
}

/** Hashes a file in one pass, then streams it to the server's blob store. */
class UploadingBlobStore(
    private val api: ServerApi,
    private val token: String,
    private val open: (String) -> java.io.InputStream,
) : BlobStore {
    override fun upload(pathOrUri: String): String {
        if (pathOrUri.isBlank()) throw MissingFileException("empty file reference")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        open(pathOrUri).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                size += n
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        api.putBlob(token, hash, size) { open(pathOrUri) }
        return hash
    }
}
