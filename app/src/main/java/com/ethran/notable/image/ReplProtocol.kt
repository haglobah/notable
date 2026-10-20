package com.ethran.notable.image

import com.ethran.notable.sync.automerge.AmCodeDef
import com.ethran.notable.sync.automerge.CodeDocState
import com.ethran.notable.sync.automerge.CodeDocs
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

/**
 * The live REPL's protocol (ADR-002), independent of the socket: one JSON request in, one JSON
 * reply out. `web/repl` speaks the other side; see its README for the messages.
 *
 * Every request carries an `id`, echoed in the reply. The first one must be
 * `{op: "hello", token}` with the token shown on the device; anything else, or a wrong token, gets
 * `unauthorized` and [Reply.close].
 */
class ReplProtocol(
    private val token: String,
    private val image: LiveImage,
    private val docs: CodeDocs,
    private val device: String,
    private val now: () -> Date = ::Date,
    /** How long `def` waits for this device to evaluate the new entry. */
    private val applyTimeoutMs: Long = 5_000,
) {
    class Connection {
        @Volatile
        var authorized = false
    }

    data class Reply(val text: String, val close: Boolean = false)

    suspend fun handle(connection: Connection, request: String): Reply {
        val req = runCatching { Json.parseToJsonElement(request).jsonObject }.getOrNull()
            ?: return Reply(error(JsonNull, "not a JSON object"), close = !connection.authorized)
        val id = req["id"] ?: JsonNull
        val op = req.str("op")
        if (!connection.authorized) {
            val given = req.str("token")
            if (op != "hello" || given == null || !sameToken(given)) return Reply(error(id, "unauthorized"), close = true)
            connection.authorized = true
            return Reply(ok(id) { put("device", device); put("hostApi", HOST_API); put("protocol", PROTOCOL) })
        }
        return Reply(
            when (op) {
                "eval" -> eval(id, req.str("code") ?: return Reply(error(id, "eval: missing code")))
                "def" -> define(id, req)
                "undef" -> undefine(id, req.str("name") ?: return Reply(error(id, "undef: missing name")))
                "ls" -> list(id)
                "show" -> show(id, req.str("name") ?: return Reply(error(id, "show: missing name")))
                "doc" -> doc(id)
                "hello" -> ok(id) { put("device", device); put("hostApi", HOST_API); put("protocol", PROTOCOL) }
                else -> error(id, "unknown op '$op'")
            }
        )
    }

    private fun sameToken(given: String) = MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

    private suspend fun eval(id: JsonElement, code: String): String {
        val r = image.eval(code)
        val logs = buildJsonArray { r.logs.forEach { add(JsonPrimitive(it)) } }
        return if (r.error == null) ok(id) { put("value", r.value); put("logs", logs) }
        else error(id, r.error) { put("logs", logs) }
    }

    private suspend fun define(id: JsonElement, req: JsonObject): String {
        val name = req.str("name") ?: return error(id, "def: missing name")
        val source = req.str("source") ?: return error(id, "def: missing source")
        val hostApi = (req["hostApi"] as? JsonPrimitive)?.intOrNull ?: HOST_API
        if (!AmCodeDef.isDefName(name)) return error(id, "def: '$name' is not a definition name (kind/name, e.g. command/clear-page)")
        if (hostApi < 1) return error(id, "def: hostApi must be a positive integer")
        val def = AmCodeDef(name, source, hostApi, now(), device)
        when (val w = docs.define(def)) {
            is AppResult.Error -> return error(id, w.error.userMessage)
            is AppResult.Success -> Unit
        }
        // The doc change comes back through the sync client; report how it evaluated here.
        val state = withTimeoutOrNull(applyTimeoutMs) { image.status.first { it.defs[name]?.def == def }.defs.getValue(name) }
        return ok(id) {
            put("state", stateName(state))
            if (state is DefState.Failed) put("error", state.reason)
        }
    }

    private suspend fun undefine(id: JsonElement, name: String): String = when (val r = docs.undefine(name)) {
        is AppResult.Error -> error(id, r.error.userMessage)
        is AppResult.Success -> ok(id) { put("existed", r.data) }
    }

    private fun list(id: JsonElement): String {
        val status = image.status.value
        return ok(id) {
            put("mode", LiveImage.modeText(status.mode))
            put("codeDoc", when (val c = docs.code.value) {
                CodeDocState.Opening -> "opening"
                CodeDocState.None -> "none"
                is CodeDocState.Ready -> "ready"
                is CodeDocState.Unreadable -> "unreadable: ${c.reason}"
            })
            put("defs", buildJsonArray { status.defs.values.sortedBy { it.def.name }.forEach { add(defJson(it, withSource = false)) } })
            put("commands", buildJsonArray {
                image.commands.value.forEach { c ->
                    add(buildJsonObject { put("id", c.id); put("title", c.title); put("builtIn", c.builtIn) })
                }
            })
        }
    }

    private fun show(id: JsonElement, name: String): String {
        val state = image.status.value.defs[name]
            ?: (docs.code.value as? CodeDocState.Ready)?.image?.defs?.get(name)?.let { DefState.Failed(it, "not evaluated yet") }
            ?: return error(id, "no definition $name")
        return ok(id) { put("def", defJson(state, withSource = true)) }
    }

    private suspend fun doc(id: JsonElement): String = when (val r = docs.codeDocBytes()) {
        is AppResult.Error -> error(id, r.error.userMessage)
        is AppResult.Success -> ok(id) { put("doc", r.data?.let { Base64.getEncoder().encodeToString(it) }) }
    }

    private fun defJson(s: DefState, withSource: Boolean) = buildJsonObject {
        put("name", s.def.name)
        put("hostApi", s.def.hostApi)
        put("device", s.def.device)
        put("updatedAt", ImageService.isoMillis(s.def.updatedAt))
        put("state", stateName(s))
        when (s) {
            is DefState.Failed -> put("error", s.reason)
            is DefState.Loaded -> s.lastRunError?.let { put("lastRunError", it) }
        }
        if (withSource) put("source", s.def.source)
    }

    private fun stateName(s: DefState?) = when (s) {
        is DefState.Loaded -> "loaded"
        is DefState.Failed -> "failed"
        null -> "pending"
    }

    private fun ok(id: JsonElement, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("id", id); put("ok", true); body() }.toString()

    private fun error(id: JsonElement, message: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
        buildJsonObject { put("id", id); put("ok", false); put("error", message); body() }.toString()

    private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val PROTOCOL = 1
    }
}
