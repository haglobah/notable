package com.ethran.notable.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogShipperTest {
    @Test
    fun a_threadtime_year_line_gives_its_time() {
        assertEquals(
            "2026-11-22 12:07:13.123",
            lineTime("2026-11-22 12:07:13.123  4321  4350 E renderPdf: MuPdf: Error rendering page 3"),
        )
    }

    @Test
    fun other_lines_give_none() {
        assertNull(lineTime("--------- beginning of main"))
        assertNull(lineTime("\tat com.ethran.notable.io.RenderPdfKt.renderPdfPageMuPdf(renderPdf.kt:120)"))
        assertNull(lineTime(""))
    }
}
