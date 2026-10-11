package com.ethran.notable.data

import com.ethran.notable.data.db.Image
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.editor.utils.Pen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteEditsTest {
    private fun s(id: String, x: Float = 1f) = Stroke(
        id = id, size = 2f, pen = Pen.BALLPEN, top = 0f, bottom = 1f, left = x, right = x + 1,
        points = listOf(StrokePoint(x, 0f)), pageId = "p",
    )

    private fun i(id: String) = Image(id = id, height = 1, width = 1, uri = "file:///$id", pageId = "p")

    @Test
    fun upserts_replace_in_place_add_new_ones_and_deletes_only_named_ids() {
        val cached = listOf(s("a"), s("b"), s("local"))
        val edit = RemotePageEdit("p", upsertStrokes = listOf(s("b", 9f), s("c")), deleteStrokes = setOf("a", "unknown"))

        val merged = cached.withRemote(edit)

        assertEquals(listOf("b", "local", "c"), merged.map { it.id })
        assertEquals(9f, merged.first { it.id == "b" }.left)
    }

    @Test
    fun images_merge_the_same_way() {
        val merged = listOf(i("x"), i("y")).withRemoteImages(RemotePageEdit("p", upsertImages = listOf(i("z")), deleteImages = setOf("x")))
        assertEquals(listOf("y", "z"), merged.map { it.id })
    }

    @Test
    fun an_edit_without_stroke_changes_returns_the_same_list() {
        val cached = listOf(s("a"))
        assertSame(cached, cached.withRemote(RemotePageEdit("p", pageChanged = true)))
    }
}
