package com.ethran.notable.image

import com.ethran.notable.sync.automerge.AmCodeDef
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections

/** One definition as this device sees it. */
sealed interface DefState {
    val def: AmCodeDef

    /** Evaluated; its value is in the registry. [lastRunError] is why its last call threw, if it did. */
    data class Loaded(override val def: AmCodeDef, val lastRunError: String? = null) : DefState

    /** Threw, didn't parse, or needs a newer host API. Skipped here; a built-in of its name is used. */
    data class Failed(override val def: AmCodeDef, val reason: String) : DefState
}

sealed interface ImageMode {
    data object Starting : ImageMode
    data object Running : ImageMode
    /** Built-ins only: two starts in a row failed, or the settings switch is on. */
    data class Safe(val reason: String) : ImageMode
    /** The engine itself couldn't start; built-ins only. */
    data class Unavailable(val reason: String) : ImageMode
}

/** What settings and the REPL show about the image. */
data class ImageStatus(
    val mode: ImageMode = ImageMode.Starting,
    val defs: Map<String, DefState> = emptyMap(),
    /** Why the code doc couldn't be read; the image keeps what it had. */
    val codeDocError: String? = null,
) {
    val loaded get() = defs.values.count { it is DefState.Loaded }
    val failed get() = defs.values.count { it is DefState.Failed }
}

/** A menu entry. [builtIn] false: it comes from the image (a `command/` definition or `registerCommand`). */
data class ImageCommand(val id: String, val title: String, val builtIn: Boolean)

/** A command the kernel ships. A definition of the same [id] replaces it while it loads and runs. */
class BuiltInCommand(val id: String, val title: String, val run: suspend (ImageStatus) -> Unit)

/** What `eval` in the REPL returns: the value printed, or the error, and what it logged. */
data class EvalResult(val value: String?, val error: String?, val logs: List<String>)

/**
 * The live code image (ADR-002): the registry of definitions and the extension point "commands".
 *
 * - **Late binding.** Values live in the runtime under their name; every call looks the name up
 *   then, so a redefinition takes effect at the next call.
 * - **Failed on this device.** A definition that throws while evaluated, doesn't parse, or needs a
 *   host API newer than [HOST_API] is dropped and marked [DefState.Failed]; its name's built-in, if
 *   any, is used instead.
 * - **Never persisted.** [apply] rebuilds the registry from the code doc's defs.
 * - **Errors stay inside.** Every call returns an [AppResult]; nothing a definition does throws
 *   into the caller.
 */
class LiveImage(
    private val runtime: JsRuntime,
    private val prelude: () -> String,
    private val host: ImageHost,
    private val builtIns: List<BuiltInCommand>,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(ImageStatus())
    val status: StateFlow<ImageStatus> = _status.asStateFlow()
    private val _commands = MutableStateFlow(builtIns.map { ImageCommand(it.id, it.title, builtIn = true) })
    val commands: StateFlow<List<ImageCommand>> = _commands.asStateFlow()

    /** Lines logged during the REPL `eval` running now; null otherwise. */
    @Volatile
    private var capture: MutableList<String>? = null
    private val capturingHost = object : ImageHost by host {
        override fun log(line: String) {
            capture?.add(line)
            host.log(line)
        }
    }

    /** Image commands by id, as of the last [apply]. */
    private var imageCommands: Map<String, ImageCommand> = emptyMap()

    /** Starts the runtime, unless [safeReason] puts the image in safe mode (built-ins only). */
    suspend fun start(safeReason: String?) = lock.withLock {
        if (safeReason != null) {
            _status.update { it.copy(mode = ImageMode.Safe(safeReason)) }
            return@withLock
        }
        when (val r = runtime.start(prelude(), capturingHost)) {
            is AppResult.Success -> _status.update { it.copy(mode = ImageMode.Running) }
            is AppResult.Error -> _status.update { it.copy(mode = ImageMode.Unavailable(r.error.message)) }
        }
    }

    val running get() = _status.value.mode == ImageMode.Running

    /**
     * Brings the registry in line with [defs] (the code doc's, whole): evaluates new and changed
     * definitions, drops removed ones. Unchanged ones keep their value.
     */
    suspend fun apply(defs: Map<String, AmCodeDef>) = lock.withLock {
        if (!running) {
            _status.update { s -> s.copy(defs = defs.mapValues { (_, d) -> DefState.Failed(d, "not evaluated: ${modeText(s.mode)}") }) }
            return@withLock
        }
        val known = _status.value.defs
        val next = LinkedHashMap<String, DefState>()
        for (name in known.keys - defs.keys) runtime.eval("__image.drop(${js(name)});null", "kernel")
        for ((name, def) in defs.toSortedMap()) {
            val old = known[name]
            next[name] = if (old != null && old.def == def) old else evaluate(def)
        }
        _status.update { it.copy(defs = next, codeDocError = null) }
        refreshCommands()
    }

    /** The code doc couldn't be read; the registry keeps what it had. */
    fun codeDocUnreadable(reason: String) = _status.update { it.copy(codeDocError = reason) }

    private suspend fun evaluate(def: AmCodeDef): DefState {
        if (def.hostApi > HOST_API) {
            runtime.eval("__image.drop(${js(def.name)});null", "kernel")
            return DefState.Failed(def, "needs host API ${def.hostApi}; this device has $HOST_API (update the app)")
        }
        val n = js(def.name)
        // The source starts on line 1 of the file, so error lines match it.
        val code = "__image.begin($n);try{__image.put($n,(" + def.source + "\n))}finally{__image.end()};null"
        return when (val r = runtime.eval(code, "def:${def.name}")) {
            is AppResult.Success -> DefState.Loaded(def)
            is AppResult.Error -> {
                runtime.eval("__image.drop($n);null", "kernel")
                DefState.Failed(def, r.error.message)
            }
        }
    }

    private suspend fun refreshCommands() {
        val listed = when (val r = runtime.eval("__image.commands()", "kernel")) {
            is AppResult.Success -> Json.parseToJsonElement(r.data ?: "[]").jsonArray.map { it.jsonObject }
            is AppResult.Error -> emptyList()
        }
        val invalid = listed.filter { it["invalid"] != null && it["invalid"] !is JsonNull }
        if (invalid.isNotEmpty()) _status.update { s ->
            s.copy(defs = s.defs + invalid.mapNotNull { o ->
                val id = o.getValue("id").jsonPrimitive.content
                (s.defs[id] as? DefState.Loaded)?.let { id to DefState.Failed(it.def, "${id}: ${o.getValue("invalid").jsonPrimitive.content}") }
            })
        }
        imageCommands = listed.filter { it !in invalid }.associate { o ->
            val id = o.getValue("id").jsonPrimitive.content
            val title = (o["title"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            id to ImageCommand(id, title ?: builtIns.firstOrNull { it.id == id }?.title ?: id.substringAfter('/'), builtIn = false)
        }
        // Built-ins keep their place in the menu; a loaded definition of the same id takes it over.
        _commands.value = builtIns.map { imageCommands[it.id] ?: ImageCommand(it.id, it.title, builtIn = true) } +
            imageCommands.values.filter { c -> builtIns.none { it.id == c.id } }.sortedBy { it.title.lowercase() }
    }

    /**
     * Runs command [id]: the image's version if it has a loaded one, else the built-in. When the
     * image's version throws, the built-in of that id runs instead, and the error is returned so
     * the caller can show it.
     */
    suspend fun runCommand(id: String): AppResult<String?, DomainError.ScriptError> {
        val builtIn = builtIns.firstOrNull { it.id == id }
        val result = lock.withLock {
            if (!running || id !in imageCommands) null
            else runtime.eval("__image.run(${js(id)})", "command:$id").also { r ->
                val owner = _status.value.defs[id]
                if (owner is DefState.Loaded) _status.update {
                    it.copy(defs = it.defs + (id to owner.copy(lastRunError = (r as? AppResult.Error)?.error?.message)))
                }
            }
        }
        if (result is AppResult.Success) return result
        if (builtIn == null) return result ?: AppResult.Error(DomainError.ScriptError("no command $id"))
        try {
            builtIn.run(_status.value)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return AppResult.Error(DomainError.ScriptError("built-in $id failed: ${e.message}"))
        }
        val error = (result as? AppResult.Error)?.error ?: return AppResult.Success(null)
        return AppResult.Error(DomainError.ScriptError("${error.message} (ran the built-in instead)"))
    }

    /** REPL: runs [code] in the live runtime, writing nothing to the doc. */
    suspend fun eval(code: String): EvalResult = lock.withLock {
        if (!running) return@withLock EvalResult(null, "the image is not running: ${modeText(_status.value.mode)}", emptyList())
        val logs = Collections.synchronizedList(ArrayList<String>())
        capture = logs
        try {
            when (val r = runtime.eval("__image.show((0, eval)(${js(code)}))", "repl")) {
                is AppResult.Success -> EvalResult(r.data, null, logs.toList())
                is AppResult.Error -> EvalResult(null, r.error.message, logs.toList())
            }
        } finally {
            capture = null
        }
    }

    companion object {
        /** A JS string literal. */
        internal fun js(s: String) = JsonPrimitive(s).toString()

        fun modeText(mode: ImageMode) = when (mode) {
            ImageMode.Starting -> "starting"
            ImageMode.Running -> "running"
            is ImageMode.Safe -> "safe mode (${mode.reason})"
            is ImageMode.Unavailable -> "unavailable (${mode.reason})"
        }
    }
}
