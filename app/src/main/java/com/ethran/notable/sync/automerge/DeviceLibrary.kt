package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.automerge.Document
import org.automerge.repo.Repo
import org.automerge.repo.RepoConfig
import org.automerge.repo.storage.FileSystemStorage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** The account a device library belongs to. */
data class LibraryOwner(val serverUrl: String, val username: String) {
    companion object {
        fun of(account: StoredAccount) = LibraryOwner(account.serverUrl, account.username)
    }
}

/**
 * `library.json`: which library doc is this device's, and whose it is.
 *
 * @property libraryUrl null only in a folder adopted from an account that never reached `/me`.
 * @property owner null while the library is the device's own (never logged in); set by the first
 *   login, and kept by a logout.
 * @property migrate Room is still to be copied into the docs: set on the first start, after
 *   attaching to an account, and after adopting an old account folder; cleared by a run that
 *   copied everything.
 */
data class DeviceLibraryState(val libraryUrl: String?, val owner: LibraryOwner?, val migrate: Boolean)

/** What a login may do with the device library; see [DeviceLibrary.decideLogin]. */
sealed interface LoginDecision {
    /** The library is the device's own: it becomes the account's, and its docs go to the account. */
    data object Attach : LoginDecision

    /** The library is already this account's. */
    data object Same : LoginDecision

    /** The library belongs to another account. The login must not go ahead; [reason] says why. */
    data class Refused(val owner: LibraryOwner?, val reason: String) : LoginDecision
}

/** The device library's repo, opened; [state] tells its library doc. */
class OpenedLibrary(val repo: Repo, val storage: DurableStorage, val state: DeviceLibraryState)

/**
 * The one library of this device (ADR-001, local-only mode), in `<root>/library/`: the repo, the
 * outbox, queued files, migration and projection state, and [DeviceLibraryState].
 *
 * Without an account it is the device's own, made on the first start and authoritative. The first
 * login attaches it to that account: its library doc becomes the account's, and its notebook
 * docs go to the server under the same URLs. A logout keeps it all. A login to another account
 * is refused ([decideLogin]), so one account's notebooks never land silently in another's.
 *
 * Not thread-safe: the caller serializes (NotableSyncClient holds its lock).
 */
class DeviceLibrary(val root: File) {
    val dir = File(root, "library")
    val repoDir = File(dir, "repo")
    private val file = File(dir, "library.json")

    /** Null before the first start. @throws IOException when `library.json` can't be read. */
    fun state(): DeviceLibraryState? {
        if (!file.exists()) return null
        return try {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            DeviceLibraryState(
                libraryUrl = o["libraryUrl"]?.jsonPrimitive?.contentOrNull,
                owner = (o["owner"] as? JsonObject)?.let {
                    LibraryOwner(it.getValue("serverUrl").jsonPrimitive.content, it.getValue("username").jsonPrimitive.content)
                },
                migrate = o.getValue("migrate").jsonPrimitive.boolean,
            )
        } catch (e: Exception) {
            throw IOException("${file.path} can't be read: ${e.message}", e)
        }
    }

    /**
     * Adopts the folder of [account] from before local-only mode (`<root>/<hash>/`, see
     * [legacyDirName]) as the device library, owned by that account. With [account] logged in and
     * no library yet, the library is that account's. Call before opening the repo.
     */
    fun prepare(account: LibraryOwner?) {
        if (file.exists() || account == null) return
        val legacy = File(root, legacyDirName(account))
        if (!dir.exists() && legacy.isDirectory && !legacy.renameTo(dir)) throw IOException("could not move ${legacy.path} to ${dir.path}")
        val url = try {
            LibraryUrlCache(File(dir, "library-url")).load()
        } catch (e: IOException) {
            null // the server tells it again
        }
        save(DeviceLibraryState(url, account, migrate = true))
    }

    /** The library; on the first start, makes an empty one with [createDoc]. */
    fun ensureLibrary(createDoc: (Document) -> String): DeviceLibraryState {
        state()?.let { return it }
        val doc = AutomergeSchema.newLibraryDocument(AmLibrary(emptyMap(), emptyMap()))
        val url = try {
            createDoc(doc)
        } finally {
            doc.free()
        }
        return DeviceLibraryState(url, owner = null, migrate = true).also(::save)
    }

    /** [prepare]s, opens the repo and [ensureLibrary]s. The caller closes the repo. */
    fun open(account: LibraryOwner?): OpenedLibrary {
        prepare(account)
        repoDir.mkdirs()
        val storage = DurableStorage(FileSystemStorage(repoDir.toPath()))
        val repo = Repo.load(RepoConfig.builder().storage(storage).build())
        return try {
            OpenedLibrary(repo, storage, ensureLibrary { RepoLocalDocs.createDurably(repo, storage, it).url.toString() })
        } catch (e: Exception) {
            repo.close()
            throw e
        }
    }

    /**
     * Whether [account], whose library doc on the server is [serverLibraryUrl], may log in here.
     * The device's own library goes to the first account; after that only its owner may log in.
     */
    fun decideLogin(account: LibraryOwner, serverLibraryUrl: String): LoginDecision {
        val s = state() ?: return LoginDecision.Attach
        val owner = s.owner ?: return LoginDecision.Attach
        if (owner != account) return LoginDecision.Refused(
            owner,
            "This device's notebooks belong to ${owner.username} on ${owner.serverUrl}. Log in as ${owner.username}: " +
                "logging in as ${account.username} would mix the two libraries.",
        )
        if (s.libraryUrl != null && s.libraryUrl != serverLibraryUrl) return LoginDecision.Refused(
            owner,
            "This device's notebooks belong to an earlier account named ${owner.username} on ${owner.serverUrl}; " +
                "the account there now has another library.",
        )
        return LoginDecision.Same
    }

    /**
     * Makes the library [account]'s, with [serverLibraryUrl] as its library doc. A library that was
     * the device's own is due for a copy of Room ([DeviceLibraryState.migrate]): that puts its
     * notebooks into the account's library, under their existing doc URLs.
     * @throws IllegalStateException when [decideLogin] refuses.
     */
    fun attach(account: LibraryOwner, serverLibraryUrl: String): DeviceLibraryState {
        val d = decideLogin(account, serverLibraryUrl)
        check(d !is LoginDecision.Refused) { (d as LoginDecision.Refused).reason }
        val s = state()
        val next = DeviceLibraryState(serverLibraryUrl, account, migrate = s == null || s.owner == null || s.migrate)
        save(next)
        return next
    }

    /** For a library adopted without its URL, once the server told it. */
    fun setLibraryUrl(url: String) {
        val s = state() ?: throw IllegalStateException("no device library")
        save(s.copy(libraryUrl = url))
    }

    /** A copy of Room into the docs finished with nothing left to copy. */
    fun migrationDone() {
        val s = state() ?: throw IllegalStateException("no device library")
        if (s.migrate) save(s.copy(migrate = false))
    }

    private fun save(s: DeviceLibraryState) {
        dir.mkdirs()
        val owner = s.owner?.let { JsonObject(mapOf("serverUrl" to JsonPrimitive(it.serverUrl), "username" to JsonPrimitive(it.username))) }
        val json = JsonObject(
            mapOf(
                "libraryUrl" to (s.libraryUrl?.let(::JsonPrimitive) ?: JsonNull),
                "owner" to (owner ?: JsonNull),
                "migrate" to JsonPrimitive(s.migrate),
            )
        )
        val tmp = File(dir, "library.json.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
    }

    companion object {
        /** The per-account folder name used before local-only mode. */
        fun legacyDirName(owner: LibraryOwner): String = MessageDigest.getInstance("SHA-256")
            .digest("${owner.serverUrl}\n${owner.username}".toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }
    }
}
