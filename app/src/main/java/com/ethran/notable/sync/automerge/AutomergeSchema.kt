package com.ethran.notable.sync.automerge

import org.automerge.AmValue
import org.automerge.Document
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Transaction
import java.util.Date

/**
 * Reads and writes the shared library and notebook docs (see web/schema/README.md).
 *
 * Writers store ids, enums and titles as scalar strings, never as collaborative Text. Readers
 * reject anything that does not match, naming the path, rather than guessing.
 *
 * Integers go through the `Int` overloads of Transaction.set/insert. In automerge-java 0.0.9,
 * NewValue.integer() widens to double and stores an F64.
 */
object AutomergeSchema {
    const val LIBRARY = "notable/library"
    const val NOTEBOOK = "notable/notebook"
    /** The live code image (ADR-002); versioned on its own. */
    const val CODE = "notable/code"
    /** The newest code doc version this reader understands. */
    const val CODE_VERSION = 1
    /** Version of new docs: they hold nothing a version-1 reader would misread. */
    const val VERSION = 1L
    /** The newest version this reader understands. */
    const val SUPPORTED_VERSION = 2L
    /** A notebook doc holding a page marked `deleted` (a version-1 reader would show it). */
    const val DELETED_PAGES_VERSION = 2

    /* ------------------------------ writing ------------------------------ */

    fun newLibraryDocument(library: AmLibrary): Document = Document().also { doc ->
        doc.startTransaction().apply {
            set(ObjectId.ROOT, "schema", NewValue.str(LIBRARY))
            set(ObjectId.ROOT, "version", VERSION.toInt())
            library.codeDocUrl?.let { set(ObjectId.ROOT, "codeDocUrl", NewValue.str(it)) }
            val folders = set(ObjectId.ROOT, "folders", ObjectType.MAP)
            library.folders.values.forEach { putFolder(this, folders, it) }
            val notebooks = set(ObjectId.ROOT, "notebooks", ObjectType.MAP)
            library.notebooks.values.forEach { putNotebookEntry(this, notebooks, it) }
            commit()
        }
    }

    fun newNotebookDocument(notebook: AmNotebook): Document = Document().also { doc ->
        doc.startTransaction().apply {
            set(ObjectId.ROOT, "schema", NewValue.str(NOTEBOOK))
            set(ObjectId.ROOT, "version", VERSION.toInt())
            set(ObjectId.ROOT, "notebookId", NewValue.str(notebook.notebookId))
            setStrOrNull(this, ObjectId.ROOT, "openPageId", notebook.openPageId)
            putBackground(this, ObjectId.ROOT, "defaultBackground", notebook.defaultBackground)
            notebook.canvasWidth?.let { set(ObjectId.ROOT, "canvasWidth", NewValue.f64(it)) }
            val order = set(ObjectId.ROOT, "pageOrder", ObjectType.LIST)
            notebook.pageOrder.forEachIndexed { i, id -> insert(order, i.toLong(), NewValue.str(id)) }
            val pages = set(ObjectId.ROOT, "pages", ObjectType.MAP)
            notebook.pages.values.forEach { putPage(this, pages, it) }
            commit()
        }
    }

    fun newCodeDocument(code: AmCodeImage): Document = Document().also { doc ->
        doc.startTransaction().apply {
            set(ObjectId.ROOT, "schema", NewValue.str(CODE))
            set(ObjectId.ROOT, "version", CODE_VERSION)
            val defs = set(ObjectId.ROOT, "defs", ObjectType.MAP)
            code.defs.values.forEach { putCodeDef(this, defs, it) }
            commit()
        }
    }

    /**
     * Redefines [d.name]: a new whole map replaces the old entry, never single fields of it, so two
     * concurrent redefinitions resolve to one of them whole.
     */
    fun putCodeDef(tx: Transaction, defs: ObjectId, d: AmCodeDef) {
        require(AmCodeDef.isDefName(d.name)) { "not a definition name: '${d.name}' (expected kind/name, e.g. command/clear-page)" }
        require(d.hostApi >= 1) { "hostApi must be a positive integer, got ${d.hostApi}" }
        val o = tx.set(defs, d.name, ObjectType.MAP)
        tx.set(o, "source", NewValue.str(d.source))
        tx.set(o, "hostApi", d.hostApi)
        tx.set(o, "updatedAt", d.updatedAt)
        tx.set(o, "device", NewValue.str(d.device))
    }

    /** The defs map of a code doc, for edits in a transaction. */
    fun codeDefsOf(doc: Document): ObjectId {
        val r = Reader(doc)
        r.header(CODE, CODE_VERSION.toLong())
        return r.map(ObjectId.ROOT, "defs", "defs")
    }

    /** The library's code doc URL, or null before the first definition; read without the rest. */
    fun codeDocUrlOf(library: Document): String? {
        val r = Reader(library)
        r.header(LIBRARY)
        return if (r.has(ObjectId.ROOT, "codeDocUrl")) r.str(ObjectId.ROOT, "codeDocUrl", "") else null
    }

    /** Points the library at its code doc (set once, on first use; see ADR-002). */
    fun setCodeDocUrl(tx: Transaction, url: String) = tx.set(ObjectId.ROOT, "codeDocUrl", NewValue.str(url))

    fun putFolder(tx: Transaction, folders: ObjectId, f: AmFolder) {
        val o = tx.set(folders, f.id, ObjectType.MAP)
        tx.set(o, "title", NewValue.str(f.title))
        setStrOrNull(tx, o, "parentId", f.parentId)
        tx.set(o, "createdAt", f.createdAt)
        tx.set(o, "updatedAt", f.updatedAt)
    }

    fun putNotebookEntry(tx: Transaction, notebooks: ObjectId, n: AmNotebookEntry) {
        val o = tx.set(notebooks, n.id, ObjectType.MAP)
        tx.set(o, "docUrl", NewValue.str(n.docUrl))
        tx.set(o, "title", NewValue.str(n.title))
        setStrOrNull(tx, o, "folderId", n.folderId)
        tx.set(o, "kind", NewValue.str(n.kind.key))
        val s = tx.set(o, "source", ObjectType.MAP)
        when (val src = n.source) {
            AmSource.None -> {
                tx.set(s, "kind", NewValue.str("none")); tx.setNull(s, "blob")
            }
            is AmSource.Pdf -> {
                tx.set(s, "kind", NewValue.str("pdf")); tx.set(s, "blob", NewValue.str(src.blob))
            }
            is AmSource.Epub -> {
                tx.set(s, "kind", NewValue.str("epub")); tx.set(s, "blob", NewValue.str(src.blob))
                val l = tx.set(s, "layout", ObjectType.MAP)
                tx.set(l, "w", NewValue.f64(src.layout.w))
                tx.set(l, "h", NewValue.f64(src.layout.h))
                tx.set(l, "em", NewValue.f64(src.layout.em))
            }
        }
        tx.set(o, "createdAt", n.createdAt)
        tx.set(o, "updatedAt", n.updatedAt)
    }

    fun putPage(tx: Transaction, pages: ObjectId, p: AmPage) {
        val o = tx.set(pages, p.id, ObjectType.MAP)
        putBackground(tx, o, "background", p.background)
        val strokes = tx.set(o, "strokes", ObjectType.MAP)
        p.strokes.values.forEach { putStroke(tx, strokes, it) }
        val images = tx.set(o, "images", ObjectType.MAP)
        p.images.values.forEach { putImage(tx, images, it) }
        tx.set(o, "createdAt", p.createdAt)
        tx.set(o, "updatedAt", p.updatedAt)
    }

    /** Strokes are immutable entries: an edit replaces the whole entry, an erase deletes the key. */
    fun putStroke(tx: Transaction, strokes: ObjectId, s: AmStroke) {
        val o = tx.set(strokes, s.id, ObjectType.MAP)
        tx.set(o, "pen", NewValue.str(s.pen))
        tx.set(o, "color", s.color)
        tx.set(o, "size", NewValue.f64(s.size))
        val bbox = tx.set(o, "bbox", ObjectType.LIST)
        listOf(s.left, s.top, s.right, s.bottom).forEachIndexed { i, v -> tx.insert(bbox, i.toLong(), NewValue.f64(v)) }
        tx.set(o, "points", NewValue.bytes(s.points))
        tx.set(o, "createdAt", s.createdAt)
        tx.set(o, "updatedAt", s.updatedAt)
    }

    fun putImage(tx: Transaction, images: ObjectId, i: AmImage) {
        val o = tx.set(images, i.id, ObjectType.MAP)
        tx.set(o, "blob", NewValue.str(i.blob))
        val rect = tx.set(o, "rect", ObjectType.LIST)
        listOf(i.x, i.y, i.width, i.height).forEachIndexed { k, v -> tx.insert(rect, k.toLong(), v) }
        tx.set(o, "createdAt", i.createdAt)
        tx.set(o, "updatedAt", i.updatedAt)
    }

    /** The strokes map of a page, for adding strokes in a later transaction. */
    fun strokesOf(doc: Document, pageId: String): ObjectId {
        val r = Reader(doc)
        val pages = r.map(ObjectId.ROOT, "pages", "pages")
        val page = r.map(pages, pageId, "pages.$pageId")
        return r.map(page, "strokes", "pages.$pageId.strokes")
    }

    private fun putBackground(tx: Transaction, parent: ObjectId, key: String, b: AmBackground) {
        val o = tx.set(parent, key, ObjectType.MAP)
        tx.set(o, "type", NewValue.str(b.type))
        setStrOrNull(tx, o, "native", b.native)
        setStrOrNull(tx, o, "blob", b.blob)
    }

    private fun setStrOrNull(tx: Transaction, obj: ObjectId, key: String, v: String?) {
        if (v == null) tx.setNull(obj, key) else tx.set(obj, key, NewValue.str(v))
    }

    /* ------------------------------ reading ------------------------------ */

    fun readLibrary(doc: Document): AmLibrary {
        val r = Reader(doc)
        r.header(LIBRARY)
        val folders = r.map(ObjectId.ROOT, "folders", "folders")
        val notebooks = r.map(ObjectId.ROOT, "notebooks", "notebooks")
        return AmLibrary(
            // Optional (ADR-002): absent until the first definition is written.
            codeDocUrl = if (r.has(ObjectId.ROOT, "codeDocUrl")) r.str(ObjectId.ROOT, "codeDocUrl", "") else null,
            folders = r.keys(folders).associateWith { id ->
                val p = "folders.$id"
                val o = r.map(folders, id, p)
                AmFolder(id, r.str(o, "title", p), r.strOrNull(o, "parentId", p), r.date(o, "createdAt", p), r.date(o, "updatedAt", p))
            },
            notebooks = r.keys(notebooks).associateWith { id ->
                val p = "notebooks.$id"
                val o = r.map(notebooks, id, p)
                AmNotebookEntry(
                    id = id,
                    docUrl = r.str(o, "docUrl", p),
                    title = r.str(o, "title", p),
                    folderId = r.strOrNull(o, "folderId", p),
                    kind = r.str(o, "kind", p).let { k ->
                        AmEntryKind.entries.firstOrNull { it.key == k } ?: throw SchemaException("$p.kind: unknown kind '$k'")
                    },
                    source = readSource(r, r.map(o, "source", "$p.source"), "$p.source"),
                    createdAt = r.date(o, "createdAt", p),
                    updatedAt = r.date(o, "updatedAt", p),
                )
            },
        )
    }

    /** The code doc (ADR-002). Entries are read whole; a malformed one fails the doc, naming it. */
    fun readCode(doc: Document): AmCodeImage {
        val r = Reader(doc)
        r.header(CODE, CODE_VERSION.toLong())
        val defs = r.map(ObjectId.ROOT, "defs", "defs")
        return AmCodeImage(r.keys(defs).associateWith { name ->
            val p = "defs.$name"
            val o = r.map(defs, name, p)
            val hostApi = r.int(o, "hostApi", p)
            if (hostApi < 1 || hostApi > Int.MAX_VALUE) throw SchemaException("$p.hostApi: invalid $hostApi")
            AmCodeDef(name, r.str(o, "source", p), hostApi.toInt(), r.date(o, "updatedAt", p), r.str(o, "device", p))
        })
    }

    private fun readSource(r: Reader, o: ObjectId, p: String): AmSource = when (val kind = r.str(o, "kind", p)) {
        "none" -> AmSource.None
        "pdf" -> AmSource.Pdf(r.str(o, "blob", p))
        "epub" -> {
            val l = r.map(o, "layout", "$p.layout")
            AmSource.Epub(r.str(o, "blob", p), AmEpubLayout(r.num(l, "w", "$p.layout"), r.num(l, "h", "$p.layout"), r.num(l, "em", "$p.layout")))
        }
        else -> throw SchemaException("$p.kind: unknown source kind '$kind'")
    }

    fun readNotebook(doc: Document): AmNotebook = readNotebook(doc, withContent = true)

    /**
     * The notebook without page content: every page with its background and timestamps, but empty
     * strokes and images. Cheap on a large notebook; used to apply a diff.
     */
    fun readNotebookShell(doc: Document): AmNotebook = readNotebook(doc, withContent = false)

    /** One page with its strokes and images, or null when the doc has no such page. */
    fun readPage(doc: Document, pageId: String): AmPage? {
        val r = Reader(doc)
        r.header(NOTEBOOK)
        val pagesObj = r.map(ObjectId.ROOT, "pages", "pages")
        if (!r.has(pagesObj, pageId)) return null
        val o = r.map(pagesObj, pageId, "pages.$pageId")
        if (deleted(r, o, "pages.$pageId")) return null
        return readPage(r, o, pageId, withContent = true)
    }

    private fun readNotebook(doc: Document, withContent: Boolean): AmNotebook {
        val r = Reader(doc)
        r.header(NOTEBOOK)
        val pagesObj = r.map(ObjectId.ROOT, "pages", "pages")
        // A page marked deleted is left out with its content (see [isPageDeleted]).
        val pages = r.keys(pagesObj)
            .map { it to r.map(pagesObj, it, "pages.$it") }
            .filterNot { (id, o) -> deleted(r, o, "pages.$id") }
            .associate { (id, o) -> id to readPage(r, o, id, withContent) }
        val rawOrder = r.list(ObjectId.ROOT, "pageOrder", "").mapIndexed { i, v -> r.scalar(v, "pageOrder[$i]") }
        return AmNotebook(
            notebookId = r.str(ObjectId.ROOT, "notebookId", ""),
            openPageId = r.strOrNull(ObjectId.ROOT, "openPageId", ""),
            defaultBackground = readBackground(r, ObjectId.ROOT, "defaultBackground", ""),
            pageOrder = normalizeOrder(rawOrder, pages),
            pages = pages,
            canvasWidth = readCanvasWidth(r),
        )
    }

    private fun readCanvasWidth(r: Reader): Double? {
        if (!r.has(ObjectId.ROOT, "canvasWidth")) return null
        val w = r.num(ObjectId.ROOT, "canvasWidth", "")
        if (!(w > 0)) throw SchemaException("canvasWidth: expected a positive number, got $w")
        return w
    }

    /** The doc's canvas width in stroke units, or null in docs written before it existed. */
    fun canvasWidthOf(doc: Document): Double? = readCanvasWidth(Reader(doc))

    /** A page's updatedAt, read without its content. */
    fun pageUpdatedAt(doc: Document, page: ObjectId): Date = Reader(doc).date(page, "updatedAt", "page")

    /** The pages map of a notebook doc, for adding pages in later transactions. */
    fun pagesOf(doc: Document): ObjectId = Reader(doc).map(ObjectId.ROOT, "pages", "pages")

    /** The folders and notebooks maps of a library doc, for edits in a transaction. */
    fun libraryMapsOf(doc: Document): Pair<ObjectId, ObjectId> {
        val r = Reader(doc)
        r.header(LIBRARY)
        return r.map(ObjectId.ROOT, "folders", "folders") to r.map(ObjectId.ROOT, "notebooks", "notebooks")
    }

    /* ------------------------------ in-place edits ------------------------------ */
    // Edits of single fields, so concurrent changes to other fields of the same object merge.

    /** A page's map, or null when the doc has no such page. */
    fun pageObjOrNull(doc: Document, pageId: String): ObjectId? {
        val pages = pagesOf(doc)
        return (doc.get(pages, pageId).orElse(null) as? AmValue.Map)?.id
    }

    /** The images map of a page, for adding images in a later transaction. */
    fun imagesOf(doc: Document, pageId: String): ObjectId {
        val r = Reader(doc)
        val pages = r.map(ObjectId.ROOT, "pages", "pages")
        val page = r.map(pages, pageId, "pages.$pageId")
        return r.map(page, "images", "pages.$pageId.images")
    }

    fun setBackground(tx: Transaction, parent: ObjectId, key: String, b: AmBackground) = putBackground(tx, parent, key, b)

    fun setDate(tx: Transaction, obj: ObjectId, key: String, d: Date) = tx.set(obj, key, d)

    fun setOpenPageId(tx: Transaction, id: String?) = setStrOrNull(tx, ObjectId.ROOT, "openPageId", id)

    /** The pageOrder list as stored: it may repeat a page or name a deleted one (see [normalizeOrder]). */
    fun rawPageOrder(doc: Document): Pair<ObjectId, List<String>> {
        val r = Reader(doc)
        val v = r.get(ObjectId.ROOT, "pageOrder", "")
        val id = (v as? AmValue.List)?.id ?: throw SchemaException("pageOrder: expected a list")
        return id to doc.listItems(id).get().mapIndexed { i, item -> r.scalar(item, "pageOrder[$i]") }
    }

    /**
     * Edits the pageOrder list in place into [target]. Items outside the longest common
     * subsequence are deleted, the missing ones inserted; everything else stays untouched. So two
     * devices that move different pages at the same time both keep their move after a merge,
     * where replacing the whole list would keep only one of them.
     */
    fun editPageOrder(doc: Document, tx: Transaction, target: List<String>) {
        val (list, current) = rawPageOrder(doc)
        val kept = longestCommonSubsequence(current, target)
        val keptSet = kept.toHashSet()
        for (i in current.indices.reversed()) if (i !in keptSet) tx.delete(list, i.toLong())
        var k = 0
        target.forEachIndexed { pos, id ->
            if (k < kept.size && current[kept[k]] == id) k++ else tx.insert(list, pos.toLong(), NewValue.str(id))
        }
    }

    /** Deletes every occurrence of [pageId] from pageOrder. */
    fun removeFromPageOrder(doc: Document, tx: Transaction, pageId: String) {
        val (list, current) = rawPageOrder(doc)
        for (i in current.indices.reversed()) if (current[i] == pageId) tx.delete(list, i.toLong())
    }

    /**
     * Puts [pageId] right after [afterPageId] in pageOrder (first when null; last when the doc's
     * order doesn't have [afterPageId] any more). Its other occurrences are deleted.
     */
    fun placeInPageOrder(doc: Document, tx: Transaction, pageId: String, afterPageId: String?) {
        val (list, current) = rawPageOrder(doc)
        for (i in current.indices.reversed()) if (current[i] == pageId) tx.delete(list, i.toLong())
        val rest = current.filter { it != pageId }
        val at = if (afterPageId == null) 0 else rest.indexOf(afterPageId).let { if (it < 0) rest.size else it + 1 }
        tx.insert(list, at.toLong(), NewValue.str(pageId))
    }

    /* Page deletion (ADR-001, "Delete against draw"). A delete keeps the page's content and marks it
     * `deleted: true`; every stroke or image write sets `deleted: false`. A write the delete didn't
     * see conflicts with it, and a page counts as deleted only when every concurrent value is true,
     * so drawing on a page deleted elsewhere brings it back. Must match web/schema `isPageDeleted`. */

    /** True when every concurrent value of [page]'s `deleted` is true; false without the field. */
    fun isPageDeleted(doc: Document, page: ObjectId): Boolean {
        val values = doc.getAll(page, "deleted").map { it.values() }.orElse(emptyList())
        return values.isNotEmpty() && values.all { it is AmValue.Bool && it.value }
    }

    /** A page's map unless it is missing or marked deleted. */
    fun livePageObjOrNull(doc: Document, pageId: String): ObjectId? =
        pageObjOrNull(doc, pageId)?.takeUnless { isPageDeleted(doc, it) }

    /** Marks [pageId] deleted and takes it out of pageOrder; its strokes and images stay. */
    fun deletePage(doc: Document, tx: Transaction, pageId: String) {
        val page = pageObjOrNull(doc, pageId) ?: throw SchemaException("pages.$pageId: missing")
        tx.set(page, "deleted", true)
        removeFromPageOrder(doc, tx, pageId)
        val version = (doc.get(ObjectId.ROOT, "version").orElse(null) as? AmValue.Int)?.value ?: VERSION
        if (version < DELETED_PAGES_VERSION) tx.set(ObjectId.ROOT, "version", DELETED_PAGES_VERSION)
    }

    /**
     * Call with every stroke or image write on [pageId], in the same transaction. A page deleted
     * earlier in this doc's history comes back at the end of pageOrder.
     */
    fun markPageLive(doc: Document, tx: Transaction, pageId: String) {
        val page = pageObjOrNull(doc, pageId) ?: throw SchemaException("pages.$pageId: missing")
        if (isPageDeleted(doc, page)) {
            val (list, current) = rawPageOrder(doc)
            if (pageId !in current) tx.insert(list, current.size.toLong(), NewValue.str(pageId))
        }
        // A fresh op every time: Automerge skips a put of the value already there, and that old op
        // may be one a concurrent delete has seen, so this write would not conflict with it.
        if (doc.get(page, "deleted").isPresent) tx.delete(page, "deleted")
        tx.set(page, "deleted", false)
    }

    private fun deleted(r: Reader, page: ObjectId, p: String): Boolean {
        if (r.has(page, "deleted") && r.get(page, "deleted", p) !is AmValue.Bool)
            throw SchemaException("$p.deleted: expected a boolean, got ${r.get(page, "deleted", p).javaClass.simpleName}")
        return isPageDeleted(r.doc, page)
    }

    /** Indices into [a] of a longest common subsequence of [a] and [b], ascending. */
    private fun longestCommonSubsequence(a: List<String>, b: List<String>): List<Int> {
        val len = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            len[i][j] = if (a[i] == b[j]) len[i + 1][j + 1] + 1 else maxOf(len[i + 1][j], len[i][j + 1])
        val out = ArrayList<Int>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) when {
            a[i] == b[j] -> { out += i; i++; j++ }
            len[i + 1][j] >= len[i][j + 1] -> i++
            else -> j++
        }
        return out
    }

    /** A library entry's map, or null when the library has no such entry. */
    fun entryObjOrNull(doc: Document, id: String): ObjectId? {
        val (_, notebooks) = libraryMapsOf(doc)
        return (doc.get(notebooks, id).orElse(null) as? AmValue.Map)?.id
    }

    /** A folder's map, or null when the library has no such folder. */
    fun folderObjOrNull(doc: Document, id: String): ObjectId? {
        val (folders, _) = libraryMapsOf(doc)
        return (doc.get(folders, id).orElse(null) as? AmValue.Map)?.id
    }

    /** Sets the given fields of a folder; null leaves a field as it is. */
    fun setFolderFields(tx: Transaction, folder: ObjectId, title: String?, parentId: String?, setParent: Boolean, updatedAt: Date?) {
        title?.let { tx.set(folder, "title", NewValue.str(it)) }
        if (setParent) setStrOrNull(tx, folder, "parentId", parentId)
        updatedAt?.let { tx.set(folder, "updatedAt", it) }
    }

    /** Sets the given fields of a library entry; null leaves a field as it is. */
    fun setEntryFields(tx: Transaction, entry: ObjectId, title: String?, folderId: String?, setFolder: Boolean, updatedAt: Date?) {
        title?.let { tx.set(entry, "title", NewValue.str(it)) }
        if (setFolder) setStrOrNull(tx, entry, "folderId", folderId)
        updatedAt?.let { tx.set(entry, "updatedAt", it) }
    }

    /**
     * Concurrent "delete page" and "move page" merge into a pageOrder that may name deleted pages,
     * repeat a page, or miss one. Keep the first occurrence of each existing page and append the
     * missing ones, oldest first.
     */
    private fun normalizeOrder(raw: List<String>, pages: Map<String, AmPage>): List<String> {
        val ordered = LinkedHashSet<String>()
        raw.filter { it in pages }.forEach { ordered.add(it) }
        pages.values.filter { it.id !in ordered }
            .sortedWith(compareBy<AmPage> { it.createdAt }.thenBy { it.id })
            .forEach { ordered.add(it.id) }
        return ordered.toList()
    }

    private fun readPage(r: Reader, o: ObjectId, id: String, withContent: Boolean): AmPage {
        val p = "pages.$id"
        val strokes = r.map(o, "strokes", "$p.strokes")
        val images = r.map(o, "images", "$p.images")
        if (!withContent) return AmPage(
            id = id, background = readBackground(r, o, "background", p), strokes = emptyMap(), images = emptyMap(),
            createdAt = r.date(o, "createdAt", p), updatedAt = r.date(o, "updatedAt", p),
        )
        return AmPage(
            id = id,
            background = readBackground(r, o, "background", p),
            strokes = r.keys(strokes).associateWith { sid ->
                val sp = "$p.strokes.$sid"
                val s = r.map(strokes, sid, sp)
                val bbox = r.list(s, "bbox", sp).mapIndexed { i, v -> r.numValue(v, "$sp.bbox[$i]") }
                if (bbox.size != 4) throw SchemaException("$sp.bbox: expected 4 numbers, got ${bbox.size}")
                AmStroke(
                    id = sid, pen = r.str(s, "pen", sp), color = r.int(s, "color", sp).toInt(),
                    size = r.num(s, "size", sp), left = bbox[0], top = bbox[1], right = bbox[2], bottom = bbox[3],
                    points = r.bytes(s, "points", sp),
                    createdAt = r.date(s, "createdAt", sp), updatedAt = r.date(s, "updatedAt", sp),
                )
            },
            images = r.keys(images).associateWith { iid ->
                val ip = "$p.images.$iid"
                val i = r.map(images, iid, ip)
                val rect = r.list(i, "rect", ip).mapIndexed { k, v -> r.intValue(v, "$ip.rect[$k]").toInt() }
                if (rect.size != 4) throw SchemaException("$ip.rect: expected 4 integers, got ${rect.size}")
                AmImage(iid, r.str(i, "blob", ip), rect[0], rect[1], rect[2], rect[3], r.date(i, "createdAt", ip), r.date(i, "updatedAt", ip))
            },
            createdAt = r.date(o, "createdAt", p),
            updatedAt = r.date(o, "updatedAt", p),
        )
    }

    private fun readBackground(r: Reader, parent: ObjectId, key: String, p: String): AmBackground {
        val bp = if (p.isEmpty()) key else "$p.$key"
        val o = r.map(parent, key, bp)
        return AmBackground(r.str(o, "type", bp), r.strOrNull(o, "native", bp), r.strOrNull(o, "blob", bp))
    }

    /** Typed access with the dotted path in every error. */
    private class Reader(val doc: Document) {
        private fun at(p: String, key: String) = if (p.isEmpty()) key else "$p.$key"

        fun get(obj: ObjectId, key: String, p: String): AmValue =
            doc.get(obj, key).orElseThrow { SchemaException("${at(p, key)}: missing") }

        fun header(expected: String, supported: Long = SUPPORTED_VERSION) {
            val schema = str(ObjectId.ROOT, "schema", "")
            if (schema != expected) throw SchemaException("schema: expected '$expected', got '$schema'")
            val version = int(ObjectId.ROOT, "version", "")
            if (version > supported) throw SchemaException("version: doc is version $version, newer than supported $supported; update the app")
            if (version < 1) throw SchemaException("version: invalid $version")
        }

        fun keys(obj: ObjectId): List<String> = doc.keys(obj).get().toList()
        fun has(obj: ObjectId, key: String): Boolean = doc.get(obj, key).isPresent

        /** [path] is the full path of the map itself. */
        fun map(obj: ObjectId, key: String, path: String): ObjectId {
            val v = doc.get(obj, key).orElseThrow { SchemaException("$path: missing") }
            return (v as? AmValue.Map)?.id ?: throw SchemaException("$path: expected a map")
        }

        fun list(obj: ObjectId, key: String, p: String): List<AmValue> {
            val v = get(obj, key, p)
            val id = (v as? AmValue.List)?.id ?: throw SchemaException("${at(p, key)}: expected a list")
            return doc.listItems(id).get().toList()
        }

        fun scalar(v: AmValue, p: String): String = when (v) {
            is AmValue.Str -> v.value
            is AmValue.Text -> throw SchemaException("$p: expected a scalar string, got collaborative Text")
            else -> throw SchemaException("$p: expected a scalar string, got ${v.javaClass.simpleName}")
        }

        fun str(obj: ObjectId, key: String, p: String) = scalar(get(obj, key, p), at(p, key))

        fun strOrNull(obj: ObjectId, key: String, p: String): String? =
            when (val v = get(obj, key, p)) {
                is AmValue.Null -> null
                else -> scalar(v, at(p, key))
            }

        /**
         * Integers accept whole-number F64: @automerge/automerge 3.5.0 stores zero and negative
         * JS integers as float64. A fractional value is still an error.
         */
        fun intValue(v: AmValue, p: String): Long = when (v) {
            is AmValue.Int -> v.value
            is AmValue.UInt -> v.value
            is AmValue.F64 -> v.value.let { d ->
                if (d % 1.0 == 0.0 && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong()
                else throw SchemaException("$p: expected an integer, got $d")
            }
            else -> throw SchemaException("$p: expected an integer, got ${v.javaClass.simpleName}")
        }

        fun int(obj: ObjectId, key: String, p: String) = intValue(get(obj, key, p), at(p, key))

        /** Floats accept integers too: JS stores whole numbers such as 3.0 as ints. */
        fun numValue(v: AmValue, p: String): Double = when (v) {
            is AmValue.F64 -> v.value
            is AmValue.Int -> v.value.toDouble()
            is AmValue.UInt -> v.value.toDouble()
            else -> throw SchemaException("$p: expected a number, got ${v.javaClass.simpleName}")
        }

        fun num(obj: ObjectId, key: String, p: String) = numValue(get(obj, key, p), at(p, key))

        fun bytes(obj: ObjectId, key: String, p: String): ByteArray =
            (get(obj, key, p) as? AmValue.Bytes)?.value ?: throw SchemaException("${at(p, key)}: expected bytes")

        fun date(obj: ObjectId, key: String, p: String): Date =
            (get(obj, key, p) as? AmValue.Timestamp)?.value ?: throw SchemaException("${at(p, key)}: expected a timestamp")
    }
}
