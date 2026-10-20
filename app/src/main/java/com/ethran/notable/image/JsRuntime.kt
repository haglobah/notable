package com.ethran.notable.image

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.define
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** The host API version this kernel offers (ADR-002). Each version only adds to the one before. */
const val HOST_API = 1

/**
 * What definitions can reach, through the `notable` global (`web/schema/runtime/prelude.js`).
 * Called on the runtime's thread; implementations may block briefly but never touch the UI.
 */
interface ImageHost {
    fun log(line: String)
    fun snack(text: String)
    /** The library as JSON (see the prelude's `notable.library`). */
    fun libraryJson(): String
    /** The open page as JSON, or "null". */
    fun pageJson(): String
}

/**
 * The device's one QuickJS runtime (ADR-002), on its own thread: never the main or render thread.
 * Every call moves to that thread. An evaluation that runs longer than [Limits.evalTimeoutMs] is
 * interrupted; the runtime can't allocate more than [Limits.memoryBytes].
 *
 * The runtime has no file system, network or Android access: QuickJS without its `std`/`os`
 * modules, plus `notable` built by the prelude from [ImageHost].
 */
class JsRuntime(private val limits: Limits = Limits()) : AutoCloseable {
    data class Limits(
        val memoryBytes: Long = 32L shl 20,
        val stackBytes: Long = 512L shl 10,
        val evalTimeoutMs: Long = 2_000,
    )

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var js: QuickJs? = null

    /** A fresh runtime: [prelude] evaluated, `notable` bound to [host]. Replaces the old one. */
    suspend fun start(prelude: String, host: ImageHost): AppResult<Unit, DomainError.ScriptError> = withContext(dispatcher) {
        js?.close()
        js = null
        val q = try {
            QuickJs.create(dispatcher)
        } catch (e: QuickJsException) {
            return@withContext AppResult.Error(DomainError.ScriptError("QuickJS could not start: ${e.message}"))
        }
        q.memoryLimit = limits.memoryBytes
        q.maxStackSize = limits.stackBytes
        q.evaluationTimeoutMillis = limits.evalTimeoutMs
        q.define("__host") {
            property<Int>("hostApi") { getter { HOST_API } }
            function("log") { args -> host.log(args.firstOrNull()?.toString().orEmpty()); null }
            function("snack") { args -> host.snack(args.firstOrNull()?.toString().orEmpty()); null }
            function("library") { _ -> host.libraryJson() }
            function("page") { _ -> host.pageJson() }
        }
        js = q
        eval(prelude, "prelude.js").let { r ->
            if (r is AppResult.Error) {
                q.close()
                js = null
                return@withContext AppResult.Error(DomainError.ScriptError("the image prelude failed: ${r.error.message}"))
            }
        }
        AppResult.Success(Unit)
    }

    /**
     * Evaluates [code] as a script named [filename]. Its completion value must be a string or
     * null; callers return JSON. A throw, a syntax error or a timeout is a [DomainError.ScriptError]
     * naming [filename] and the line.
     */
    suspend fun eval(code: String, filename: String): AppResult<String?, DomainError.ScriptError> = withContext(dispatcher) {
        val q = js ?: return@withContext AppResult.Error(DomainError.ScriptError("the image runtime is not running"))
        try {
            AppResult.Success(q.evaluate<String?>(code, filename))
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuickJsInterruptedException) {
            AppResult.Error(DomainError.ScriptError("$filename: stopped after ${limits.evalTimeoutMs} ms"))
        } catch (e: QuickJsException) {
            AppResult.Error(DomainError.ScriptError(describe(e, filename)))
        }
    }

    override fun close() {
        executor.execute {
            js?.close()
            js = null
        }
        executor.shutdown()
    }

    companion object {
        const val THREAD_NAME = "notable-image"

        /** "def:command/x:3: Error: boom": where it was thrown, when the stack says, then the message. */
        internal fun describe(e: QuickJsException, filename: String): String {
            val message = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: e.javaClass.simpleName
            val at = if (e.fileName != null && e.lineNumber != null) "${e.fileName}:${e.lineNumber}" else filename
            return "$at: $message"
        }
    }
}
