package com.ethran.notable.sync.automerge

import org.automerge.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The device library's folder and who it belongs to (local-only mode, ADR-001). */
class DeviceLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val alice = LibraryOwner("https://notable.example/", "alice")
    private val bob = LibraryOwner("https://notable.example/", "bob")

    /** Stands in for the repo: counts the docs made. */
    private class Docs {
        val made = mutableListOf<String>()
        fun create(doc: Document): String {
            AutomergeSchema.readLibrary(doc) // it is a library doc
            return "automerge:local${made.size + 1}".also { made += it }
        }
    }

    @Test
    fun `the first start creates an unowned library and marks Room for copying`() {
        val root = tmp.newFolder()
        val docs = Docs()
        val lib = DeviceLibrary(root)
        lib.prepare(null)

        val state = lib.ensureLibrary(docs::create)

        assertEquals(DeviceLibraryState("automerge:local1", owner = null, migrate = true), state)
        assertEquals("a restart finds the same library", state, DeviceLibrary(root).ensureLibrary(docs::create))
        assertEquals(1, docs.made.size)
    }

    @Test
    fun `a folder of an account logged in before local-only mode is adopted`() {
        val root = tmp.newFolder()
        val legacy = File(root, DeviceLibrary.legacyDirName(alice)).apply { mkdirs() }
        File(legacy, "library-url").writeText("automerge:alicelib")
        File(legacy, "repo").mkdirs()
        File(legacy, "repo/doc").writeText("x")
        File(legacy, "migration.json").writeText("{}")
        val lib = DeviceLibrary(root)

        lib.prepare(alice)

        assertEquals(DeviceLibraryState("automerge:alicelib", alice, migrate = true), lib.state())
        assertEquals("x", File(lib.dir, "repo/doc").readText())
        assertTrue(File(lib.dir, "migration.json").isFile)
        assertFalse(legacy.exists())
        assertEquals("no new library doc", DeviceLibraryState("automerge:alicelib", alice, true), lib.ensureLibrary { error("no doc expected") })
    }

    @Test
    fun `an adopted folder that never learned its library URL keeps waiting for the server`() {
        val root = tmp.newFolder()
        File(root, DeviceLibrary.legacyDirName(alice)).mkdirs()
        val lib = DeviceLibrary(root)
        lib.prepare(alice)

        assertEquals(DeviceLibraryState(null, alice, migrate = true), lib.ensureLibrary { error("no doc expected") })
        lib.setLibraryUrl("automerge:alicelib")
        assertEquals("automerge:alicelib", DeviceLibrary(root).state()?.libraryUrl)
    }

    @Test
    fun `an unowned library goes to the first account that logs in`() {
        val lib = DeviceLibrary(tmp.newFolder())
        assertEquals("no library yet", LoginDecision.Attach, lib.decideLogin(alice, "automerge:alicelib"))
        lib.ensureLibrary(Docs()::create)
        lib.migrationDone()
        assertEquals(LoginDecision.Attach, lib.decideLogin(alice, "automerge:alicelib"))

        val attached = lib.attach(alice, "automerge:alicelib")

        assertEquals(DeviceLibraryState("automerge:alicelib", alice, migrate = true), attached)
        assertEquals("written to disk", attached, DeviceLibrary(lib.root).state())
    }

    @Test
    fun `the owner logs in again to the same library`() {
        val lib = DeviceLibrary(tmp.newFolder())
        lib.attach(alice, "automerge:alicelib")
        lib.migrationDone()

        assertEquals(LoginDecision.Same, lib.decideLogin(alice, "automerge:alicelib"))
        assertEquals(DeviceLibraryState("automerge:alicelib", alice, migrate = false), lib.attach(alice, "automerge:alicelib"))
    }

    @Test
    fun `another account is refused and the library stays as it is`() {
        val lib = DeviceLibrary(tmp.newFolder())
        lib.attach(alice, "automerge:alicelib")
        val before = lib.state()

        val d = lib.decideLogin(bob, "automerge:boblib")

        assertTrue(d is LoginDecision.Refused)
        d as LoginDecision.Refused
        assertEquals(alice, d.owner)
        assertTrue(d.reason, "alice" in d.reason)
        assertEquals(before, lib.state())
    }

    @Test
    fun `an account recreated under the same name is refused`() {
        val lib = DeviceLibrary(tmp.newFolder())
        lib.attach(alice, "automerge:alicelib")

        val d = lib.decideLogin(alice, "automerge:newalicelib")

        assertTrue(d is LoginDecision.Refused)
    }

    @Test
    fun `the copy of Room stays due until a run succeeds`() {
        val lib = DeviceLibrary(tmp.newFolder())
        lib.ensureLibrary(Docs()::create)
        assertEquals(true, DeviceLibrary(lib.root).state()?.migrate)
        lib.migrationDone()
        assertEquals(false, DeviceLibrary(lib.root).state()?.migrate)
    }

    @Test
    fun `no library before the first start`() {
        assertNull(DeviceLibrary(tmp.newFolder()).state())
    }
}
