package com.ethran.notable.io

import com.ethran.notable.io.projection.EpubFiles
import com.ethran.notable.io.projection.FitzEpub
import io.shipbook.shipbooksdk.ShipBook
import java.io.File

private val log = ShipBook.getLogger("EpubBackground")

/**
 * EPUB page backgrounds. An EPUB is laid out once, with the layout its file name carries
 * ([EpubFiles]), into a hidden PDF next to it (`.<name>.pdf`); the page renderers and page counts
 * then use that PDF. The layout is the one the PWA and the PDF projection use, so a stroke sits on
 * the same text everywhere.
 */
object EpubBackground {
    private val lock = Any()

    fun isEpub(path: String) = EpubFiles.isEpub(path)

    /** The laid-out PDF of the EPUB at [path], made on first use. @throws Exception when MuPDF can't lay it out */
    fun laidOutPdf(path: String): File {
        val epub = File(path)
        if (!epub.isFile) throw java.io.FileNotFoundException(path)
        val layout = EpubFiles.layoutOf(path) ?: EpubFiles.BOOX_LAYOUT.also {
            log.w("$path carries no layout in its name; laid out as the Boox screen")
        }
        val pdf = File(epub.absoluteFile.parentFile, ".${epub.name}.pdf")
        synchronized(lock) {
            if (pdf.isFile && pdf.lastModified() >= epub.lastModified() && pdf.length() > 0) return pdf
            val tmp = File(pdf.parentFile, pdf.name + ".part")
            tmp.writeBytes(FitzEpub.layOutAsPdf(epub, layout))
            if (!tmp.renameTo(pdf)) {
                tmp.delete()
                throw java.io.IOException("could not write ${pdf.path}")
            }
            log.i("laid out $path as ${pdf.name}")
            return pdf
        }
    }
}
