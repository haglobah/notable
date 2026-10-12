package com.ethran.notable.io.projection

import com.ethran.notable.data.db.decodeStrokePoints
import com.ethran.notable.sync.automerge.AmEpubLayout
import com.ethran.notable.sync.automerge.AmNotebook
import com.ethran.notable.sync.automerge.AmNotebookEntry
import com.ethran.notable.sync.automerge.AmPage
import com.ethran.notable.sync.automerge.AmSource
import com.ethran.notable.sync.automerge.AmStroke
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.automerge.Document
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Share-as-PDF (ADR-001 "PDF as a projection"): the Kotlin twin of web/app/src/lib/project.ts and
 * geometry.ts. This file is pure: it decides every page, its drawn content and its annotations.
 * [FitzPdfWriter] carries the plan out with MuPDF. ProjectionParityTest holds both sides to the
 * fixture the TS project() writes (web/schema/fixtures/projection-v1.json).
 */

/** A rectangle in MuPDF page space (origin top left, y down), in points. */
data class PdfRect(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val width get() = x1 - x0
}

data class PdfPoint(val x: Double, val y: Double)

enum class DocFormat(val key: String) { PDF("pdf"), EPUB("epub") }

/** A source document; an EPUB exists as pages only for its [layout]. */
data class DocumentRef(val format: DocFormat, val blob: String, val layout: AmEpubLayout?) {
    val key get() = "${format.key}:$blob"
}

sealed interface PageBackground {
    data class Document(val ref: DocumentRef, val index: Int) : PageBackground
    data class Native(val template: String) : PageBackground
    data class Image(val blob: String, val repeat: Boolean, val cover: Boolean) : PageBackground
    /** Drawn as a blank page with a warning; never silently replaced by something else. */
    data class Missing(val reason: String) : PageBackground
}

/** What only MuPDF can tell: source page sizes and image pixel sizes. */
interface ProjectionSources {
    /** Bounds of every page, an EPUB laid out with [DocumentRef.layout]. @throws ProjectionException */
    fun documentPages(ref: DocumentRef): List<PdfRect>

    /** Width and height in pixels. @throws ProjectionException */
    fun imageSize(blob: String): Pair<Int, Int>
}

class ProjectionException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed interface PageContent {
    /** The source document's page, copied with its text, links and fonts. */
    data class Graft(val ref: DocumentRef, val index: Int) : PageContent

    /** A page with Notable's own content stream; [backgroundImage] is the `/Bg` XObject. */
    data class Drawn(val ops: String, val backgroundImage: String?) : PageContent
}

data class StampPlan(val rect: PdfRect, val blob: String)

/** One `/Ink` annotation. [opacity] is null for opaque ink (no `/CA` written). */
data class InkPlan(val points: List<PdfPoint>, val rgb: DoubleArray, val opacity: Double?, val borderWidth: Double)

data class PagePlan(val content: PageContent, val bounds: PdfRect, val stamps: List<StampPlan>, val inks: List<InkPlan>)

data class ProjectionPlan(
    val title: String,
    val pages: List<PagePlan>,
    val warnings: List<String>,
    /** `notable.json`. */
    val metaJson: String,
)

object ProjectionGeometry {
    /** Note Air 5C portrait: 2480 / 1860. Native pages are at least one screen tall. */
    const val DEFAULT_CANVAS_HEIGHT_RATIO = 2480.0 / 1860.0
    const val DEFAULT_CANVAS_WIDTH = 1860.0
    const val LINE_HEIGHT = 80
    const val DOT_SIZE = 6
    const val HEX_VERTICAL_COUNT = 26
    /** Android's PDF export page width (A4_WIDTH). Native pages are this wide. */
    const val NATIVE_PAGE_WIDTH_PT = 595.0
    private const val CONTENT_MARGIN = 50.0
    val NATIVE_TEMPLATES = listOf("blank", "dotted", "lined", "squared", "hexed")

    /** Pens whose line follows pen pressure on the Boox. */
    private val PRESSURE_PENS = setOf("FOUNTAIN", "BRUSH", "CALLIGRAPHY", "PENCIL", "CHARCOAL")
    /** penStrokes.kt drawMarkerStroke: alpha 100. */
    private const val MARKER_ALPHA = 100.0 / 255.0

    fun canvasWidthOf(nb: AmNotebook) = nb.canvasWidth ?: DEFAULT_CANVAS_WIDTH

    fun toPagePoint(x: Double, y: Double, canvasWidth: Double, bounds: PdfRect): PdfPoint {
        val s = bounds.width / canvasWidth
        return PdfPoint(bounds.x0 + x * s, bounds.y0 + y * s)
    }

    fun resolveBackground(nb: AmNotebook, entry: AmNotebookEntry, pageId: String): PageBackground {
        val page = nb.pages[pageId] ?: return PageBackground.Missing("no page $pageId")
        val bg = page.background
        val source = entry.source
        val docBlob = bg.blob ?: nb.defaultBackground.blob ?: when (source) {
            AmSource.None -> null
            is AmSource.Pdf -> source.blob
            is AmSource.Epub -> source.blob
        }
        fun document(index: Int): PageBackground {
            if (docBlob == null) return PageBackground.Missing("${bg.type} background without a document")
            if (source is AmSource.Epub && source.blob == docBlob) {
                return PageBackground.Document(DocumentRef(DocFormat.EPUB, docBlob, source.layout), index)
            }
            return PageBackground.Document(DocumentRef(DocFormat.PDF, docBlob, null), index)
        }
        Regex("^pdf(\\d+)$").find(bg.type)?.let { return document(it.groupValues[1].toInt()) }
        return when (bg.type) {
            "autoPdf" -> document(nb.pageOrder.indexOf(pageId))
            "native" -> {
                val t = bg.native ?: "blank"
                if (t in NATIVE_TEMPLATES) PageBackground.Native(t) else PageBackground.Missing("unknown native background '$t'")
            }
            "image", "imagerepeating", "coverImage" ->
                bg.blob?.let { PageBackground.Image(it, repeat = bg.type == "imagerepeating", cover = bg.type == "coverImage") }
                    ?: PageBackground.Missing("${bg.type} background without an image")
            else -> PageBackground.Missing("unknown background type '${bg.type}'")
        }
    }

    /** Height in canvas px of a page without a fixed size (native and image backgrounds). */
    fun contentHeight(page: AmPage, canvasWidth: Double): Double {
        val bottoms = page.strokes.values.map { it.bottom } + page.images.values.map { (it.y + it.height).toDouble() }
        val screen = canvasWidth * DEFAULT_CANVAS_HEIGHT_RATIO
        return if (bottoms.isEmpty()) screen else max(bottoms.max() + CONTENT_MARGIN, screen)
    }

    class StrokeStyle(val rgb: DoubleArray, val alpha: Double, private val stroke: AmStroke, private val pressured: Boolean) {
        /** Width in canvas px at a normalized pressure. */
        fun width(p: Double?): Double =
            if (pressured && p != null) stroke.size * (0.35 + 0.9 * min(1.0, max(0.0, p))) else stroke.size

        /** Average width over the stroke, for /Ink, which has one width per stroke. */
        fun meanWidth(ps: List<Double?>): Double =
            if (ps.isEmpty()) stroke.size else ps.fold(0.0) { sum, p -> sum + width(p) } / ps.size
    }

    fun strokeStyle(s: AmStroke): StrokeStyle {
        val c = s.color.toLong() and 0xffffffffL
        val a = ((c ushr 24) and 0xff) / 255.0
        val rgb = doubleArrayOf(((c ushr 16) and 0xff) / 255.0, ((c ushr 8) and 0xff) / 255.0, (c and 0xff) / 255.0)
        return StrokeStyle(rgb, if (s.pen == "MARKER") MARKER_ALPHA * a else a, s, s.pen in PRESSURE_PENS)
    }
}

object ProjectionPlanner {
    const val EMBEDDED_DOC = "notable.automerge"
    const val EMBEDDED_META = "notable.json"

    /** The notebook doc as it travels inside the PDF, so a re-upload finds its notebook again. */
    fun embeddedDoc(doc: Document): ByteArray = doc.save()

    /**
     * Plans the PDF for [nb]. [onlyPages] limits it to those pages (a single-page share), in
     * notebook order; the embedded doc is still the whole notebook.
     */
    fun plan(nb: AmNotebook, entry: AmNotebookEntry, sources: ProjectionSources, onlyPages: Set<String>? = null): ProjectionPlan {
        val cw = ProjectionGeometry.canvasWidthOf(nb)
        val warnings = ArrayList<String>()
        val pages = ArrayList<PagePlan>()
        val documents = HashMap<String, List<PdfRect>>()

        for ((i, pageId) in nb.pageOrder.withIndex()) {
            if (onlyPages != null && pageId !in onlyPages) continue
            val page = nb.pages.getValue(pageId)
            val bg = ProjectionGeometry.resolveBackground(nb, entry, pageId)
            val content: PageContent
            val bounds: PdfRect
            when (bg) {
                is PageBackground.Document -> {
                    val srcPages = documents.getOrPut(bg.ref.key) { sources.documentPages(bg.ref) }
                    if (bg.index >= 0 && bg.index < srcPages.size) {
                        content = PageContent.Graft(bg.ref, bg.index)
                        bounds = srcPages[bg.index]
                    } else {
                        // Android draws a white page here ("out of pages in pdf"); keep the ink, say so.
                        warnings += "page ${i + 1}: document page ${bg.index + 1} does not exist; drawn blank"
                        drawnPage(page, PageBackground.Native("blank"), cw, sources).let { content = it.first; bounds = it.second }
                    }
                }
                else -> {
                    if (bg is PageBackground.Missing) warnings += "page ${i + 1}: ${bg.reason}; drawn blank"
                    drawnPage(page, bg, cw, sources).let { content = it.first; bounds = it.second }
                }
            }
            val scale = bounds.width / cw
            val stamps = page.images.values.sortedWith(byCreated({ it.createdAt }, { it.id })).map { im ->
                val p0 = ProjectionGeometry.toPagePoint(im.x.toDouble(), im.y.toDouble(), cw, bounds)
                StampPlan(PdfRect(p0.x, p0.y, p0.x + im.width * scale, p0.y + im.height * scale), im.blob)
            }
            val inks = page.strokes.values.sortedWith(byCreated({ it.createdAt }, { it.id })).mapNotNull { st ->
                val pts = decodeStrokePoints(st.points)
                if (pts.isEmpty()) return@mapNotNull null
                val style = ProjectionGeometry.strokeStyle(st)
                val mapped = pts.map { ProjectionGeometry.toPagePoint(it.x.toDouble(), it.y.toDouble(), cw, bounds) }.toMutableList()
                // A single tap is a dot: give the ink a tiny length so viewers draw its round cap.
                if (mapped.size == 1) mapped += PdfPoint(mapped[0].x + 0.01, mapped[0].y)
                InkPlan(
                    points = mapped,
                    rgb = style.rgb,
                    opacity = style.alpha.takeIf { it < 1 },
                    borderWidth = style.meanWidth(pts.map { p -> p.pressure?.toDouble() }) * scale,
                )
            }
            pages += PagePlan(content, bounds, stamps, inks)
        }
        return ProjectionPlan(entry.title.ifEmpty { "Untitled" }, pages, warnings, metaJson(nb, entry, warnings))
    }

    private fun <T> byCreated(created: (T) -> Date, id: (T) -> String) =
        compareBy<T> { created(it).time }.thenBy { id(it) }

    /** A page with our own content stream: native template or image background, in canvas px. */
    private fun drawnPage(page: AmPage, bg: PageBackground, cw: Double, sources: ProjectionSources): Pair<PageContent, PdfRect> {
        val h = ProjectionGeometry.contentHeight(page, cw)
        val s = ProjectionGeometry.NATIVE_PAGE_WIDTH_PT / cw
        val w = ProjectionGeometry.NATIVE_PAGE_WIDTH_PT
        val hPt = h * s
        val ops = StringBuilder()
        var image: String? = null
        // Canvas px, y down → PDF points, y up.
        ops.append("q ${js(s)} 0 0 ${js(-s)} 0 ${js(hPt)} cm\n")
        if (bg is PageBackground.Native) ops.append(templateOps(bg.template, cw, h))
        if (bg is PageBackground.Image) {
            image = bg.blob
            val (iw, ih0) = sources.imageSize(bg.blob)
            val ih = (ih0 * cw) / iw
            var y = 0.0
            while (y == 0.0 || (bg.repeat && y < h)) {
                ops.append("q ${js(cw)} 0 0 ${js(-ih)} 0 ${js(y + ih)} cm /Bg Do Q\n")
                y += ih
            }
            if (bg.cover) ops.append(coverBoxOps(cw))
        }
        ops.append("Q\n")
        return PageContent.Drawn(ops.toString(), image) to PdfRect(0.0, 0.0, w, hPt)
    }

    /** Color.GRAY, 1 px lines (backgrounds.kt defaultPaint). */
    private const val GRAY = "0.533 G 0.533 g 1 w\n"

    /** Notable's native templates in canvas px (backgrounds.kt, scroll 0, scale 1). */
    private fun templateOps(template: String, w: Double, h: Double): String {
        val o = StringBuilder()
        val lh = ProjectionGeometry.LINE_HEIGHT.toDouble()
        when (template) {
            "lined" -> {
                o.append(GRAY)
                var y = lh; while (y <= h) { o.append("0 ${js(y)} m ${js(w)} ${js(y)} l\n"); y += lh }
                o.append("S\n")
            }
            "squared" -> {
                o.append(GRAY)
                var y = lh; while (y <= h) { o.append("0 ${js(y)} m ${js(w)} ${js(y)} l\n"); y += lh }
                var x = lh; while (x <= w) { o.append("${js(x)} 0 m ${js(x)} ${js(h)} l\n"); x += lh }
                o.append("S\n")
            }
            "dotted" -> {
                // Zero-length round-capped segments are dots of the line width.
                o.append("0.533 G 1 J ${ProjectionGeometry.DOT_SIZE} w\n")
                var y = lh
                while (y <= h) {
                    var x = lh
                    while (x <= w) { o.append("${js(x)} ${js(y)} m ${js(x)} ${js(y)} l\n"); x += lh }
                    y += lh
                }
                o.append("S\n")
            }
            "hexed" -> {
                o.append(GRAY)
                val vc = ProjectionGeometry.HEX_VERTICAL_COUNT
                val r = max(w, h) / (vc * 1.5)
                val hexH = r * 2
                val hexW = r * sqrt(3.0)
                val rows = floor(h / vc).toInt()
                val cols = floor(w / hexW).toInt() + 1
                for (row in 0..rows) {
                    val y = row * hexH * 0.75
                    if (y - r > h) break
                    for (col in 0..cols) {
                        val x = col * hexW + (if (row % 2 != 0) hexW / 2 else 0.0) - hexW
                        for (k in 0 until 6) {
                            val a = ((30 + 60 * k) * PI) / 180
                            o.append("${fixed2(x + r * cos(a))} ${fixed2(y + r * sin(a))} ${if (k != 0) "l" else "m"}\n")
                        }
                        o.append("h\n")
                    }
                }
                o.append("S\n")
            }
        }
        return o.toString()
    }

    /** The cover image's label box (backgrounds.kt drawTitleBox). */
    private fun coverBoxOps(w: Double): String {
        val hh = w * ProjectionGeometry.DEFAULT_CANVAS_HEIGHT_RATIO
        val bw = w * 0.8
        val bh = hh * 0.25
        val x = (w - bw) / 2
        val y = (hh - bh) / 2
        val r = 64.0
        val k = r * 0.5523
        val path =
            "${js(x + r)} ${js(y)} m ${js(x + bw - r)} ${js(y)} l ${js(x + bw - r + k)} ${js(y)} ${js(x + bw)} ${js(y + r - k)} ${js(x + bw)} ${js(y + r)} c " +
                "${js(x + bw)} ${js(y + bh - r)} l ${js(x + bw)} ${js(y + bh - r + k)} ${js(x + bw - r + k)} ${js(y + bh)} ${js(x + bw - r)} ${js(y + bh)} c " +
                "${js(x + r)} ${js(y + bh)} l ${js(x + r - k)} ${js(y + bh)} ${js(x)} ${js(y + bh - r + k)} ${js(x)} ${js(y + bh - r)} c " +
                "${js(x)} ${js(y + r)} l ${js(x)} ${js(y + r - k)} ${js(x + r - k)} ${js(y)} ${js(x + r)} ${js(y)} c h"
        return "1 g 0.267 G 4 w $path B\n"
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun iso(d: Date) = ISO.format(Instant.ofEpochMilli(d.time))

    /** `notable.json`, as project.ts writes it. */
    private fun metaJson(nb: AmNotebook, entry: AmNotebookEntry, warnings: List<String>): String {
        val source: JsonElement = when (val s = entry.source) {
            AmSource.None -> JsonObject(mapOf("kind" to JsonPrimitive("none")))
            is AmSource.Pdf -> JsonObject(mapOf("kind" to JsonPrimitive("pdf"), "blob" to JsonPrimitive(s.blob)))
            is AmSource.Epub -> JsonObject(mapOf(
                "kind" to JsonPrimitive("epub"), "blob" to JsonPrimitive(s.blob),
                "layout" to JsonObject(mapOf("w" to num(s.layout.w), "h" to num(s.layout.h), "em" to num(s.layout.em))),
            ))
        }
        return JsonObject(mapOf(
            "format" to JsonPrimitive("notable-projection"),
            "version" to JsonPrimitive(1),
            "notebookId" to JsonPrimitive(nb.notebookId),
            "warnings" to JsonArray(warnings.map(::JsonPrimitive)),
            "entry" to JsonObject(mapOf(
                "id" to JsonPrimitive(entry.id),
                "docUrl" to JsonPrimitive(entry.docUrl),
                "title" to JsonPrimitive(entry.title),
                "kind" to JsonPrimitive(entry.kind.key),
                "source" to source,
                "createdAt" to JsonPrimitive(iso(entry.createdAt)),
                "updatedAt" to JsonPrimitive(iso(entry.updatedAt)),
            )),
        )).toString()
    }

    private fun num(d: Double): JsonPrimitive = if (d == Math.rint(d) && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    /** A number as JavaScript's `${n}` writes it, for the content streams to match project.ts. */
    internal fun js(d: Double): String {
        if (d == 0.0) return "0"
        if (d == Math.rint(d) && kotlin.math.abs(d) < 1e21) return BigDecimal(d).toPlainString()
        val t = d.toString()
        return if ('E' in t) BigDecimal(t).stripTrailingZeros().toPlainString() else t
    }

    /** JavaScript's `n.toFixed(2)`: the exact binary value, half away from zero, keeping "-0.00". */
    internal fun fixed2(d: Double): String {
        val s = BigDecimal(d).setScale(2, RoundingMode.HALF_UP).toPlainString()
        return if (d < 0 && !s.startsWith("-")) "-$s" else s
    }
}
