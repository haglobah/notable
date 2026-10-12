package com.ethran.notable.io.projection

import com.ethran.notable.sync.automerge.AmEpubLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubFilesTest {
    @Test
    fun the_boox_layout_is_the_pwa_layout() {
        // web/app/src/lib/pages.ts EPUB_LAYOUT: the Note Air 5C's screen at its physical size.
        assertEquals(AmEpubLayout(446.0, 595.0, 12.0), EpubFiles.BOOX_LAYOUT)
    }

    @Test
    fun a_file_name_carries_its_layout() {
        val name = EpubFiles.fileName("Book", EpubFiles.BOOX_LAYOUT)
        assertEquals("Book@446x595x12.epub", name)
        assertEquals(EpubFiles.BOOX_LAYOUT, EpubFiles.layoutOf("/sdcard/x/$name"))
        val odd = AmEpubLayout(595.0, 842.5, 12.0)
        assertEquals("abc@595x842.5x12.epub", EpubFiles.fileName("abc", odd))
        assertEquals(odd, EpubFiles.layoutOf("abc@595x842.5x12.epub"))
    }

    @Test
    fun only_epub_names_are_epubs() {
        assertTrue(EpubFiles.isEpub("/a/Book.EPUB"))
        assertFalse(EpubFiles.isEpub("/a/Book.pdf"))
        assertNull(EpubFiles.layoutOf("/a/Book.epub"))
        assertNull(EpubFiles.layoutOf("/a/Book@446x595x12.pdf"))
    }

    @Test
    fun a_path_matches_the_layout_a_background_needs() {
        assertTrue(EpubFiles.matches("/s/abc@446x595x12.epub", EpubFiles.BOOX_LAYOUT))
        assertFalse(EpubFiles.matches("/s/abc.pdf", EpubFiles.BOOX_LAYOUT))
        assertFalse(EpubFiles.matches("/s/abc@595x842x12.epub", EpubFiles.BOOX_LAYOUT))
        assertTrue(EpubFiles.matches("/s/abc.pdf", null))
        assertFalse(EpubFiles.matches("/s/abc@446x595x12.epub", null))
    }
}
