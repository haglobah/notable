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
    const val VERSION = 1L

    /* ------------------------------ writing ------------------------------ */

    fun newLibraryDocument(library: AmLibrary): Document = Document().also { doc ->
        doc.startTransaction().apply {
            set(ObjectId.ROOT, "schema", NewValue.str(LIBRARY))
            set(ObjectId.ROOT, "version", VERSION.toInt())
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

    private fun readSource(r: Reader, o: ObjectId, p: String): AmSource = when (val kind = r.str(o, "kind", p)) {
        "none" -> AmSource.None
        "pdf" -> AmSource.Pdf(r.str(o, "blob", p))
        "epub" -> {
            val l = r.map(o, "layout", "$p.layout")
            AmSource.Epub(r.str(o, "blob", p), AmEpubLayout(r.num(l, "w", "$p.layout"), r.num(l, "h", "$p.layout"), r.num(l, "em", "$p.layout")))
        }
        else -> throw SchemaException("$p.kind: unknown source kind '$kind'")
    }

    fun readNotebook(doc: Document): AmNotebook {
        val r = Reader(doc)
        r.header(NOTEBOOK)
        val pagesObj = r.map(ObjectId.ROOT, "pages", "pages")
        val pages = r.keys(pagesObj).associateWith { readPage(r, r.map(pagesObj, it, "pages.$it"), it) }
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

    /** The pages map of a notebook doc, for adding pages in later transactions. */
    fun pagesOf(doc: Document): ObjectId = Reader(doc).map(ObjectId.ROOT, "pages", "pages")

    /** The folders and notebooks maps of a library doc, for edits in a transaction. */
    fun libraryMapsOf(doc: Document): Pair<ObjectId, ObjectId> {
        val r = Reader(doc)
        r.header(LIBRARY)
        return r.map(ObjectId.ROOT, "folders", "folders") to r.map(ObjectId.ROOT, "notebooks", "notebooks")
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

    private fun readPage(r: Reader, o: ObjectId, id: String): AmPage {
        val p = "pages.$id"
        val strokes = r.map(o, "strokes", "$p.strokes")
        val images = r.map(o, "images", "$p.images")
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

        fun header(expected: String) {
            val schema = str(ObjectId.ROOT, "schema", "")
            if (schema != expected) throw SchemaException("schema: expected '$expected', got '$schema'")
            val version = int(ObjectId.ROOT, "version", "")
            if (version > VERSION) throw SchemaException("version: doc is version $version, newer than supported $VERSION; update the app")
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
