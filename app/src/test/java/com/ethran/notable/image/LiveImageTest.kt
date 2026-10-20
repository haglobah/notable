package com.ethran.notable.image

import com.ethran.notable.sync.automerge.AmCodeDef
import com.ethran.notable.sync.automerge.FixtureJson
import com.ethran.notable.sync.automerge.FixturePaths
import com.ethran.notable.utils.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.Date

/** The image kernel on the real QuickJS engine (quickjs-kt-jvm), with the shared prelude. */
class LiveImageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    class FakeHost : ImageHost {
        val logs: MutableList<String> = Collections.synchronizedList(ArrayList())
        val snacks: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads: MutableSet<String> = Collections.synchronizedSet(HashSet())
        override fun log(line: String) { threads += Thread.currentThread().name; logs += line }
        override fun snack(text: String) { threads += Thread.currentThread().name; snacks += text }
        override fun libraryJson() = """{"folders":{},"notebooks":{"a":{"id":"a","title":"A"},"b":{"id":"b","title":"B"}}}"""
        override fun pageJson() = """{"id":"p-1","notebookId":"a","index":0,"pageCount":3}"""
    }

    private val host = FakeHost()
    private var builtInRuns = 0
    private val builtIns = listOf(
        BuiltInCommand("command/page-info", "Page info") { builtInRuns++ },
    )
    private val runtime = JsRuntime(JsRuntime.Limits(memoryBytes = 16L shl 20, evalTimeoutMs = 500))
    private val prelude = File(FixturePaths.dir, "../runtime/prelude.js").readText()
    private val image = LiveImage(runtime, { prelude }, host, builtIns)
    private val fixture = FixtureJson.code(File(FixturePaths.dir, "code-v1.json")).defs

    @After
    fun close() = runtime.close()

    private fun def(name: String, source: String, hostApi: Int = 1, at: Long = 1) = AmCodeDef(name, source, hostApi, Date(at), "test")
    private fun started() = runBlocking { image.start(null) }.let { image }
    private fun LiveImage.state(name: String) = status.value.defs.getValue(name)
    private fun ok(r: AppResult<String?, *>) = (r as AppResult.Success).data
    private fun err(r: AppResult<String?, *>) = (r as AppResult.Error).error.userMessage

    @Test
    fun the_golden_defs_load_or_fail_with_name_and_line() = runBlocking {
        started().apply(fixture)
        assertTrue(image.state("command/hello") is DefState.Loaded)
        assertTrue(image.state("command/count-notebooks") is DefState.Loaded)
        val broken = image.state("command/broken") as DefState.Failed
        assertTrue(broken.reason, broken.reason.startsWith("def:command/broken:3:") && "broken on purpose" in broken.reason)
        assertTrue((image.state("command/syntax") as DefState.Failed).reason.startsWith("def:command/syntax"))
        assertEquals("needs host API 2; this device has 1 (update the app)", (image.state("command/future") as DefState.Failed).reason)
        assertEquals(listOf("command/page-info", "command/count-notebooks", "command/hello"), image.commands.value.map { it.id })
        assertEquals("Count notebooks", image.commands.value[1].title)
        assertEquals("2", ok(image.runCommand("command/count-notebooks")))
        assertEquals(listOf("2 notebooks"), host.snacks)
    }

    @Test
    fun calls_bind_late_so_a_redefinition_takes_effect_at_the_next_call() = runBlocking {
        started().apply(mapOf("command/a" to def("command/a", "() => 'one'")))
        assertEquals("one", ok(image.runCommand("command/a")))
        image.apply(mapOf("command/a" to def("command/a", "() => 'two'", at = 2)))
        assertEquals("two", ok(image.runCommand("command/a")))
    }

    @Test
    fun an_unchanged_definition_is_not_evaluated_again() = runBlocking {
        val counting = def("command/a", "(notable.log('evaluated'), () => 1)")
        started().apply(mapOf("command/a" to counting))
        image.apply(mapOf("command/a" to counting, "command/b" to def("command/b", "() => 2")))
        assertEquals(listOf("evaluated"), host.logs)
    }

    @Test
    fun a_failed_redefinition_drops_the_old_value_and_the_built_in_takes_over() = runBlocking {
        started().apply(mapOf("command/page-info" to def("command/page-info", "() => 'from the image'")))
        assertEquals("from the image", ok(image.runCommand("command/page-info")))
        assertFalse(image.commands.value.first().builtIn)
        image.apply(mapOf("command/page-info" to def("command/page-info", "(() => { throw new Error('nope') })()", at = 2)))
        assertTrue(image.state("command/page-info") is DefState.Failed)
        assertTrue(image.commands.value.first().builtIn)
        assertEquals(null, ok(image.runCommand("command/page-info")))
        assertEquals(1, builtInRuns)
    }

    @Test
    fun a_throwing_command_falls_back_to_its_built_in_and_reports_the_error() = runBlocking {
        started().apply(mapOf("command/page-info" to def("command/page-info", "() => {\n  null.x\n}")))
        val e = err(image.runCommand("command/page-info"))
        assertTrue(e, e.startsWith("def:command/page-info:2") && e.endsWith("(ran the built-in instead)"))
        assertEquals(1, builtInRuns)
        assertTrue((image.state("command/page-info") as DefState.Loaded).lastRunError != null)
    }

    @Test
    fun a_throwing_command_without_a_built_in_is_an_error_not_a_crash() = runBlocking {
        started().apply(mapOf("command/x" to def("command/x", "() => { throw new Error('boom') }")))
        assertTrue(err(image.runCommand("command/x")).contains("boom"))
        assertTrue(err(image.runCommand("command/missing")).contains("no command command/missing"))
    }

    @Test
    fun a_definition_for_a_newer_host_api_is_skipped_and_its_old_value_dropped() = runBlocking {
        started().apply(mapOf("command/a" to def("command/a", "() => 1")))
        image.apply(mapOf("command/a" to def("command/a", "() => notable.v2Only()", hostApi = HOST_API + 1, at = 2)))
        assertTrue((image.state("command/a") as DefState.Failed).reason.startsWith("needs host API ${HOST_API + 1}"))
        assertTrue(image.commands.value.none { it.id == "command/a" })
    }

    @Test
    fun busy_loops_are_stopped_and_the_runtime_keeps_working() = runBlocking {
        started().apply(mapOf(
            "command/spin" to def("command/spin", "() => { while (true) {} }"),
            "command/spin-at-load" to def("command/spin-at-load", "(() => { while (true) {} })()"),
            "command/ok" to def("command/ok", "() => 'fine'"),
        ))
        assertTrue((image.state("command/spin-at-load") as DefState.Failed).reason.contains("stopped after 500 ms"))
        assertTrue(err(image.runCommand("command/spin")).contains("stopped after 500 ms"))
        assertEquals("fine", ok(image.runCommand("command/ok")))
    }

    @Test
    fun the_memory_limit_stops_a_runaway_allocation() = runBlocking {
        started().apply(mapOf(
            "command/hog" to def("command/hog", "() => { const a = []; for (;;) a.push(new Array(100000).fill(1)) }"),
            "command/ok" to def("command/ok", "() => 'fine'"),
        ))
        assertTrue(image.runCommand("command/hog") is AppResult.Error)
        assertEquals("fine", ok(image.runCommand("command/ok")))
    }

    @Test
    fun definitions_see_notable_and_nothing_else_of_the_host() = runBlocking {
        started()
        val r = image.eval("[typeof fetch, typeof std, typeof os, typeof require, typeof XMLHttpRequest, typeof notable.snack, notable.hostApi].join()")
        assertEquals("undefined,undefined,undefined,undefined,undefined,function,1", r.value)
        // quickjs-kt's host object can't be removed from the global; it offers what `notable` does.
        assertEquals("hostApi,library,log,page,snack", image.eval("Object.getOwnPropertyNames(globalThis.__host || {}).sort().join()").value)
        assertEquals("""{"id":"p-1","notebookId":"a","index":0,"pageCount":3}""", image.eval("notable.page()").value)
    }

    @Test
    fun host_calls_run_on_the_image_thread() = runBlocking {
        started().apply(mapOf("command/a" to def("command/a", "() => notable.snack('hi')")))
        image.runCommand("command/a")
        assertTrue(host.threads.toString(), host.threads.all { it.startsWith(JsRuntime.THREAD_NAME) })
    }

    @Test
    fun registered_commands_live_as_long_as_the_definition_that_registered_them() = runBlocking {
        val tools = def("lib/tools", "(notable.registerCommand('command/from-lib', 'From lib', () => 'lib ran'), 0)")
        started().apply(mapOf("lib/tools" to tools))
        assertEquals("From lib", image.commands.value.single { it.id == "command/from-lib" }.title)
        assertEquals("lib ran", ok(image.runCommand("command/from-lib")))
        image.apply(emptyMap())
        assertTrue(image.commands.value.none { it.id == "command/from-lib" })
        assertTrue(image.eval("notable.registerCommand('x', 'x', () => 1)").error!!.contains("while a definition is evaluated"))
    }

    @Test
    fun a_command_definition_that_is_not_callable_fails() = runBlocking {
        started().apply(mapOf("command/n" to def("command/n", "42")))
        assertTrue((image.state("command/n") as DefState.Failed).reason.contains("not a function"))
        assertTrue(image.commands.value.none { it.id == "command/n" })
    }

    @Test
    fun eval_returns_the_value_and_what_it_logged_and_writes_nothing() = runBlocking {
        started().apply(mapOf("command/a" to def("command/a", "() => 1")))
        val r = image.eval("notable.log('a', {b: 1}); var kept = 41; kept + 1")
        assertEquals(EvalResult("42", null, listOf("a {\"b\":1}")), r)
        assertEquals("41", image.eval("kept").value)
        assertEquals(setOf("command/a"), image.status.value.defs.keys)
        assertTrue(image.eval("(").error != null)
    }

    @Test
    fun safe_mode_keeps_definitions_out_and_built_ins_working() = runBlocking {
        image.start("the last 2 starts didn't finish loading the image")
        image.apply(fixture)
        assertTrue(image.status.value.mode is ImageMode.Safe)
        assertTrue(image.status.value.defs.values.all { it is DefState.Failed })
        assertEquals(listOf("command/page-info"), image.commands.value.map { it.id })
        assertEquals(null, ok(image.runCommand("command/page-info")))
        assertTrue(image.eval("1").error!!.contains("safe mode"))
    }

    @Test
    fun boot_guard_enters_safe_mode_after_two_failed_starts() {
        val file = File(tmp.root, "image/boot-failures")
        assertEquals(null, BootGuard(file).begin())                // start 1 crashes
        assertEquals(null, BootGuard(file).begin())                // start 2 crashes
        val third = BootGuard(file)
        assertTrue(third.begin()!!.contains("2 starts"))           // built-ins only
        third.healthy()                                             // and it got there
        assertEquals(null, BootGuard(file).begin())
    }
}

/** `notable.library()` returns the same JSON on Android and in the PWA (web/app/src/image/host.ts). */
class LibraryViewTest {
    @Test
    fun matches_the_shared_fixture() {
        val lib = FixtureJson.library(File(FixturePaths.dir, "library-v1.json"))
        assertEquals(File(FixturePaths.dir, "library-view-v1.json").readText().trim(), ImageService.libraryView(lib))
    }

    @Test
    fun an_unopened_library_is_empty() {
        assertEquals("""{"folders":{},"notebooks":{}}""", ImageService.libraryView(null))
    }
}
