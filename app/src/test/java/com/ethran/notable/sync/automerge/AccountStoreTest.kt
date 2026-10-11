package com.ethran.notable.sync.automerge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AccountStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversible but not identity, so a plaintext token in the file shows up. */
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(sealed: String): String {
            require(sealed.startsWith("enc:")) { "not sealed" }
            return sealed.removePrefix("enc:").reversed()
        }
    }

    private val file get() = tmp.root.resolve("account.json")
    private val account = StoredAccount("https://notable.example", "beat", "tok-1234567890")

    @Test
    fun round_trips_and_never_writes_the_token_in_plain_text() {
        AccountStore(file, cipher).save(account)
        assertFalse(file.readText().contains("tok-1234567890"))
        assertEquals(AccountLoad.Present(account), AccountStore(file, cipher).load())
    }

    @Test
    fun save_creates_missing_parent_folders() {
        // On a fresh install `files/notable-sync/` does not exist yet (found on the Boox).
        val nested = tmp.root.resolve("notable-sync/account.json")
        AccountStore(nested, cipher).save(account)
        assertEquals(AccountLoad.Present(account), AccountStore(nested, cipher).load())
    }

    @Test
    fun empty_when_nothing_was_saved() {
        assertEquals(AccountLoad.None, AccountStore(file, cipher).load())
    }

    @Test
    fun clear_removes_the_file() {
        val store = AccountStore(file, cipher)
        store.save(account)
        store.clear()
        assertFalse(file.exists())
        assertEquals(AccountLoad.None, store.load())
    }

    @Test
    fun an_unreadable_file_is_reported_not_treated_as_logged_out() {
        file.writeText("""{"serverUrl":"https://x","username":"u","token":"garbage"}""")
        val load = AccountStore(file, cipher).load()
        assertTrue(load.toString(), load is AccountLoad.Unreadable)
    }
}

/** The library doc URL, kept per account so the editor and projection work offline at launch. */
class LibraryUrlCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `nothing is cached at first`() {
        assertEquals(null, LibraryUrlCache(tmp.root.resolve("acct/library-url")).load())
    }

    @Test
    fun `a saved URL survives a restart, in a folder that did not exist`() {
        val file = tmp.root.resolve("acct/library-url")
        LibraryUrlCache(file).save("automerge:4A78qrc8w2U9PjqSmkNxpsG854bR")
        assertEquals("automerge:4A78qrc8w2U9PjqSmkNxpsG854bR", LibraryUrlCache(file).load())
    }

    @Test
    fun `a file that is not an automerge URL is an error, not a missing cache`() {
        val file = tmp.root.resolve("library-url").apply { writeText("garbage") }
        val e = org.junit.Assert.assertThrows(java.io.IOException::class.java) { LibraryUrlCache(file).load() }
        assertTrue(e.message!!.contains("garbage"))
    }
}
