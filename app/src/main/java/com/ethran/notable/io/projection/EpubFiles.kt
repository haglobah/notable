package com.ethran.notable.io.projection

import com.ethran.notable.sync.automerge.AmEpubLayout

/**
 * EPUB pages exist only for one layout (ADR-001), and Room keeps only a background's path. So an
 * EPUB file on the device carries its layout in its name, `<base>@<w>x<h>x<em>.epub`: the
 * renderer, the page count and the Room → doc mapping all read it from there.
 */
object EpubFiles {
    /**
     * The Note Air 5C's screen at its physical size: 1860 × 2480 px at 300 ppi is 446 × 595 pt, so
     * text on the Boox is printed size. The PWA uses the same (web/app/src/lib/pages.ts).
     */
    val BOOX_LAYOUT = AmEpubLayout(446.0, 595.0, 12.0)

    private val NAME = Regex("@(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)x(\\d+(?:\\.\\d+)?)\\.epub$", RegexOption.IGNORE_CASE)

    fun isEpub(path: String) = path.endsWith(".epub", ignoreCase = true)

    fun fileName(base: String, layout: AmEpubLayout) = "$base@${num(layout.w)}x${num(layout.h)}x${num(layout.em)}.epub"

    /** The layout in an EPUB file's name; null for other files and for EPUBs named without one. */
    fun layoutOf(path: String): AmEpubLayout? = NAME.find(path)?.groupValues?.let {
        AmEpubLayout(it[1].toDouble(), it[2].toDouble(), it[3].toDouble())
    }

    /** True when [path] is the file a background needs: an EPUB laid out as [layout], or (null) not an EPUB. */
    fun matches(path: String, layout: AmEpubLayout?) = if (layout == null) !isEpub(path) else layoutOf(path) == layout

    private fun num(d: Double) = if (d == Math.rint(d)) d.toLong().toString() else d.toString()
}
