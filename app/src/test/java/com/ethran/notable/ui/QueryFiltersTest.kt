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

    @Test
    fun the_state_is_single_choice_and_all_removes_it() {
        assertEquals("guin is:reading", QueryFilters.setState("is:unread guin is:finished", "reading"))
        assertEquals("guin", QueryFilters.setState("guin is:reading", null))
        assertEquals(listOf("reading", "finished"), QueryFilters.selected("is:reading,finished", "is"))
    }

    @Test
    fun clear_removes_the_bars_filters_and_keeps_typed_words() {
        val q = "dune is:reading kind:pdf author:camus -tag:x rating:>7"
        assertTrue(QueryFilters.hasFilters(q))
        assertEquals("dune -tag:x rating:>7", QueryFilters.clearFilters(q))
        assertFalse(QueryFilters.hasFilters("dune -tag:x"))
    }
}
