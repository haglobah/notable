package com.ethran.notable.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdatesTest {
    private val latest = """
        {"versionCode": 1730, "versionName": "0.2.8", "apk": "notable-1730.apk",
         "sha256": "ab12", "size": 61000000, "commit": "48d1a1c2", "subject": "Talk to Flow from a dot",
         "builtAt": "2026-11-22T01:00:00Z"}
    """.trimIndent()

    @Test
    fun a_higher_build_is_offered_with_what_it_brings() {
        val r = newerRelease(latest, installedCode = 42)!!
        assertEquals(1730, r.versionCode)
        assertEquals("notable-1730.apk", r.apk)
        assertEquals("ab12", r.sha256)
        assertEquals("Talk to Flow from a dot", r.subject)
    }

    @Test
    fun the_same_or_an_older_build_is_not() {
        assertNull(newerRelease(latest, installedCode = 1730))
        assertNull(newerRelease(latest, installedCode = 1800))
    }

    @Test
    fun fields_it_does_not_need_may_be_missing() {
        val r = newerRelease("""{"versionCode": 50, "versionName": "x", "apk": "a.apk", "sha256": "00"}""", 42)!!
        assertEquals(0L, r.size)
        assertEquals("", r.subject)
    }
}
