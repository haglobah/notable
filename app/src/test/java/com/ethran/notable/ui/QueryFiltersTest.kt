package com.ethran.notable.ui

import com.ethran.notable.ui.viewmodels.QueryFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryFiltersTest {
    @Test
    fun values_of_one_field_become_one_any_of_token() {
        var q = QueryFilters.toggle("", "kind", "pdf")
        assertEquals("kind:pdf", q)
        q = QueryFilters.toggle(q, "kind", "epub")
        assertEquals("kind:pdf,epub", q)
        q = QueryFilters.toggle(q, "kind", "pdf")
        assertEquals("kind:epub", q)
        assertEquals("", QueryFilters.toggle(q, "kind", "epub"))
    }

    @Test
    fun values_with_spaces_commas_or_colons_are_quoted_and_typed_text_stays() {
        var q = QueryFilters.toggle("dune -kind:pdf", "author", "Ursula K. Le Guin")
        assertEquals("dune -kind:pdf author:\"ursula k. le guin\"", q)
        q = QueryFilters.toggle(q, "author", "Camus")
        assertEquals("dune -kind:pdf author:\"ursula k. le guin\",camus", q)
        assertEquals("x tag:\"a,b\",\"c:d\"", QueryFilters.toggle(QueryFilters.toggle("x", "tag", "a,b"), "tag", "C:D"))
        assertTrue(QueryFilters.isSelected(q, "author", "ursula K. le guin"))
        assertFalse(QueryFilters.isSelected(q, "kind", "pdf")) // negated: not a selection
    }

    @Test
    fun typed_tokens_of_the_field_are_merged() {
        assertEquals("guin is:reading,finished", QueryFilters.toggle("is:reading guin", "is", "finished"))
        assertEquals(listOf("le guin", "camus"), QueryFilters.selected("author:\"le guin\",camus", "author"))
    }
}
