package com.ethran.notable.flow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The tablet's view of its Flow conversations (flow.humane.tools, repo hotlustre), folded from
 * Flow's chat event log (`GET /api/chat/events`, server/agent/events.ts there). Pure: no I/O, so
 * the fold is tested on the JVM. Only conversations this tablet started are kept; Flow itself
 * shows every conversation, these included.
 */

@Serializable
sealed interface FlowEntry {
    /** What was said. [pending] until Flow's log has it (its `user` event). */
    @Serializable
    data class User(val text: String, val pending: Boolean = false) : FlowEntry

    /** Claude's reply, built from `text` deltas; [open] until its `point` or the turn's end. */
    @Serializable
    data class Assistant(val text: String, val open: Boolean = true) : FlowEntry

    @Serializable
    data class Tool(val id: String, val name: String, val text: String, val state: ToolState = ToolState.Running, val result: String = "") : FlowEntry

    /** Quiet lines: notices, "Published: …". */
    @Serializable
    data class Note(val text: String) : FlowEntry

    @Serializable
    data class Failure(val text: String) : FlowEntry
}

@Serializable
enum class ToolState { Running, Ok, Failed }

@Serializable
data class FlowConversation(
    val id: String,
    val title: String = "",
    val entries: List<FlowEntry> = emptyList(),
    /** A turn runs: from its `user` (or `resumed`) to its `done` or `error`. */
    val running: Boolean = false,
    /** The last progress line of the running turn ("gate: building the draft"). */
    val status: String? = null,
    /** When the tablet last saw something happen here, epoch ms. */
    val updatedAt: Long = 0,
)

@Serializable
data class FlowChat(
    val conversations: Map<String, FlowConversation> = emptyMap(),
    /** The last event seq folded: where the stream picks up. Null until known. */
    val seq: Long? = null,
    /** The Flow these conversations and [seq] belong to. */
    val server: String? = null,
) {
    /** Most recently active first. */
    val ordered: List<FlowConversation> get() = conversations.values.sortedByDescending { it.updatedAt }

    /** A conversation the tablet starts, with the message it starts with (shown before Flow confirms it). */
    fun started(id: String, text: String, now: Long): FlowChat =
        copy(conversations = conversations + (id to FlowConversation(id, title = text.take(200), entries = listOf(FlowEntry.User(text, pending = true)), running = true, updatedAt = now)))

    /** A message the tablet sends into one of its conversations. */
    fun said(id: String, text: String, now: Long): FlowChat {
        val c = conversations[id] ?: return started(id, text, now)
        return put(c.copy(entries = (c.entries + FlowEntry.User(text, pending = true)).capped(), running = true, updatedAt = now))
    }

    /** Folds one event of Flow's log. Events of conversations the tablet doesn't keep only move [seq]. */
    fun apply(e: JsonObject, now: Long): FlowChat {
        val seq = (e["seq"] as? JsonPrimitive)?.longOrNull
        val next = if (seq != null) copy(seq = maxOf(this.seq ?: 0, seq)) else this
        val id = e.str("conv") ?: return next
        val c = next.conversations[id] ?: return next
        // A subagent's own stream (its text, its tools) stays in Flow; the tablet shows the call.
        if (e.str("agent") != null) return next
        val type = e.str("type") ?: return next
        if (type == "deleted") return next.copy(conversations = next.conversations - id)
        val changed = c.fold(type, e) ?: return next
        return next.put(changed.copy(updatedAt = now))
    }

    private fun put(c: FlowConversation) = copy(conversations = conversations + (c.id to c))
}

private fun FlowConversation.fold(type: String, e: JsonObject): FlowConversation? = when (type) {
    "user" -> {
        val text = e.str("text").orEmpty()
        val i = entries.indexOfFirst { it is FlowEntry.User && it.pending && it.text.trim() == text.trim() }
        val list = if (i >= 0) entries.toMutableList().also { it[i] = FlowEntry.User(text) } else entries + FlowEntry.User(text)
        copy(entries = list.capped(), running = true, status = null)
    }

    "resumed" -> copy(running = true)

    "text" -> {
        val delta = e.str("text").orEmpty()
        val last = entries.lastOrNull()
        val list = if (last is FlowEntry.Assistant && last.open) entries.dropLast(1) + last.copy(text = last.text + delta)
        else entries + FlowEntry.Assistant(delta)
        copy(entries = list.capped())
    }

    "point" -> copy(entries = entries.closed())

    "tool" -> copy(entries = (entries.closed() + FlowEntry.Tool(e.str("id").orEmpty(), e.str("name").orEmpty(), e.str("text").orEmpty())).capped())

    "tool_result" -> {
        val id = e.str("id")
        val ok = (e["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
        copy(entries = entries.map {
            if (it is FlowEntry.Tool && it.id == id) it.copy(state = if (ok) ToolState.Ok else ToolState.Failed, result = e.str("text").orEmpty().take(2000)) else it
        })
    }

    "status" -> copy(status = e.str("text"))

    "notice" -> copy(entries = (entries + FlowEntry.Note(e.str("text").orEmpty())).capped())

    "published" -> copy(entries = (entries + FlowEntry.Note("Published: " + e.str("text").orEmpty())).capped())

    "error" -> copy(entries = (entries.closed() + FlowEntry.Failure(e.str("text").orEmpty())).capped(), running = false, status = null)

    "done" -> copy(entries = entries.closed().settled(), running = false, status = null)

    "conversation" -> if (title.isBlank()) copy(title = e.str("text").orEmpty()) else this

    "title" -> e.str("text")?.takeIf { it.isNotBlank() }?.let { copy(title = it) }

    else -> null
}

/** The last reply is complete. */
private fun List<FlowEntry>.closed(): List<FlowEntry> {
    val last = lastOrNull()
    return if (last is FlowEntry.Assistant && last.open) dropLast(1) + last.copy(open = false) else this
}

/** A finished turn has no tool still running (a stopped turn doesn't report them all). */
private fun List<FlowEntry>.settled() = map { if (it is FlowEntry.Tool && it.state == ToolState.Running) it.copy(state = ToolState.Ok) else it }

/** A long conversation keeps its latest entries; Flow has the rest. */
private fun List<FlowEntry>.capped() = if (size > MAX_ENTRIES) takeLast(MAX_ENTRIES) else this

private const val MAX_ENTRIES = 400

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
