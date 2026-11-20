package com.ethran.notable.flow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fold of Flow's chat events (shapes as hotlustre v0465 writes them, server/agent). */
class FlowChatTest {
    private fun ev(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun FlowChat.fold(vararg events: String) = events.fold(this) { chat, e -> chat.apply(ev(e), 1) }

    private val started = FlowChat(seq = 10).started("c1", "rename the toolbar", 0)

    @Test
    fun a_turn_folds_into_user_reply_and_tools() {
        val chat = started.fold(
            """{"seq":11,"type":"conversation","conv":"c1","text":"rename the toolbar"}""",
            """{"seq":12,"type":"user","conv":"c1","text":"rename the toolbar","uuid":"u"}""",
            """{"seq":13,"type":"text","conv":"c1","text":"Looking "}""",
            """{"seq":14,"type":"text","conv":"c1","text":"at it."}""",
            """{"seq":15,"type":"tool","conv":"c1","id":"t1","name":"bash","text":"ls"}""",
            """{"seq":16,"type":"tool_result","conv":"c1","id":"t1","ok":false,"text":"no such file"}""",
            """{"seq":17,"type":"status","conv":"c1","text":"gate: building the draft"}""",
            """{"seq":18,"type":"text","conv":"c1","text":"Done."}""",
            """{"seq":19,"type":"done","conv":"c1"}""",
        )
        val c = chat.conversations.getValue("c1")
        assertEquals(19L, chat.seq)
        assertEquals(
            listOf(
                FlowEntry.User("rename the toolbar"),
                FlowEntry.Assistant("Looking at it.", open = false),
                FlowEntry.Tool("t1", "bash", "ls", ToolState.Failed, "no such file"),
                FlowEntry.Assistant("Done.", open = false),
            ),
            c.entries,
        )
        assertFalse(c.running)
        assertNull(c.status)
        assertEquals("rename the toolbar", c.title)
    }

    @Test
    fun the_pending_message_is_confirmed_not_repeated() {
        val chat = started.fold("""{"seq":11,"type":"user","conv":"c1","text":"rename the toolbar "}""")
        assertEquals(listOf(FlowEntry.User("rename the toolbar ")), chat.conversations.getValue("c1").entries)
    }

    @Test
    fun other_conversations_only_move_the_seq() {
        val chat = started.fold("""{"seq":40,"type":"text","conv":"elsewhere","text":"hi"}""")
        assertEquals(setOf("c1"), chat.conversations.keys)
        assertEquals(40L, chat.seq)
    }

    @Test
    fun a_subagent_s_stream_stays_in_flow() {
        val chat = started.fold("""{"seq":11,"type":"text","conv":"c1","text":"inner","agent":"a1"}""")
        assertEquals(1, chat.conversations.getValue("c1").entries.size)
    }

    @Test
    fun title_error_and_delete() {
        var chat = started.fold(
            """{"seq":11,"type":"title","conv":"c1","text":"Toolbar names"}""",
            """{"seq":12,"type":"error","conv":"c1","text":"overloaded"}""",
        )
        val c = chat.conversations.getValue("c1")
        assertEquals("Toolbar names", c.title)
        assertEquals(FlowEntry.Failure("overloaded"), c.entries.last())
        assertFalse(c.running)
        chat = chat.fold("""{"seq":13,"type":"deleted","conv":"c1"}""")
        assertTrue(chat.conversations.isEmpty())
    }

    @Test
    fun a_new_message_runs_the_conversation_again() {
        val chat = started.fold("""{"seq":11,"type":"done","conv":"c1"}""").said("c1", "and the menu", 2)
        val c = chat.conversations.getValue("c1")
        assertTrue(c.running)
        assertEquals(FlowEntry.User("and the menu", pending = true), c.entries.last())
    }

    @Test
    fun the_chat_survives_serialization() {
        val chat = started.fold("""{"seq":11,"type":"tool","conv":"c1","id":"t","name":"read_file","text":"a.kt"}""")
        val json = Json { encodeDefaults = false }
        assertEquals(chat, json.decodeFromString<FlowChat>(json.encodeToString(chat)))
    }
}
