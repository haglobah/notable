package com.ethran.notable.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading pace and time left (docs/plans/library-features.md, 34). */
class ReadingPaceTest {
    @Test
    fun `only plausible turns to the next or previous page count`() {
        var s = ReadingPace.State()
        s = ReadingPace.record(s, 60f, 1)
        assertEquals(60f, s.secondsPerPage)
        assertEquals(1, s.samples)
        assertEquals("a jump", s, ReadingPace.record(s, 60f, 5))
        assertEquals("a quick flip", s, ReadingPace.record(s, 2f, 1))
        assertEquals("left lying", s, ReadingPace.record(s, 3600f, 1))
        val back = ReadingPace.record(s, 80f, -1)
        assertEquals(60f + ReadingPace.ALPHA * 20f, back.secondsPerPage!!, 0.001f)
    }

    @Test
    fun `an estimate needs a few samples`() {
        var s = ReadingPace.State()
        repeat(ReadingPace.MIN_SAMPLES - 1) { s = ReadingPace.record(s, 30f, 1) }
        assertFalse(s.usable)
        s = ReadingPace.record(s, 30f, 1)
        assertTrue(s.usable)
    }

    @Test
    fun `pages left in the book and the chapter`() {
        assertEquals(290, ReadingPace.pagesLeft(10, 300))
        assertEquals(0, ReadingPace.pagesLeft(300, 300))
        assertEquals(5, ReadingPace.chapterPagesLeft(10, listOf(0, 15, 40)))
        assertEquals("on a chapter's first page", 30, ReadingPace.chapterPagesLeft(10, listOf(0, 10, 40)))
        assertNull("in the last chapter", ReadingPace.chapterPagesLeft(50, listOf(0, 15, 40)))
        assertEquals(125, ReadingPace.minutes(7490f))
    }
}
