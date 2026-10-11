package com.ethran.notable.sync.automerge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/** Seals the session token at rest. On Android: AES-GCM with a key in the Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(sealed: String): String
}

data class StoredAccount(val serverUrl: String, val username: String, val token: String) {
    override fun toString() = "StoredAccount(serverUrl=$serverUrl, username=$username, token=<redacted>)"
}

sealed interface AccountLoad {
    data object None : AccountLoad
    data class Present(val account: StoredAccount) : AccountLoad
    /** The file exists but can't be read, e.g. the Keystore key is gone. Not the same as logged out. */
    data class Unreadable(val reason: String) : AccountLoad
}

/** The Notable sync account, in a private file with the token encrypted. */
class AccountStore(private val file: File, private val cipher: SecretCipher) {

    fun load(): AccountLoad {
        if (!file.exists()) return AccountLoad.None
        return try {
            val o = Json.parseToJsonElement(file.readText()).jsonObject
            AccountLoad.Present(
                StoredAccount(
                    serverUrl = o.getValue("serverUrl").jsonPrimitive.content,
                    username = o.getValue("username").jsonPrimitive.content,
                    token = cipher.decrypt(o.getValue("token").jsonPrimitive.content),
                )
            )
        } catch (e: Exception) {
            AccountLoad.Unreadable(e.message ?: e.javaClass.simpleName)
        }
    }

    fun save(account: StoredAccount) {
        val json = JsonObject(
            mapOf(
                "serverUrl" to JsonPrimitive(account.serverUrl),
                "username" to JsonPrimitive(account.username),
                "token" to JsonPrimitive(cipher.encrypt(account.token)),
            )
        )
        file.absoluteFile.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
    }

    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("could not delete ${file.path}")
    }
}

/**
 * The account's library doc URL, from the server's `/me`. Cached so the editor can write to the
 * docs, and the projection can start, at launch without a connection. It never changes for an
 * account; the file lives in the account's own folder.
 */
class LibraryUrlCache(private val file: File) {
    /** @throws IOException when the file holds something other than an `automerge:` URL. */
    fun load(): String? {
        if (!file.exists()) return null
        val url = file.readText().trim()
        if (!url.startsWith("automerge:")) throw IOException("${file.path} holds '$url', not an automerge URL")
        return url
    }

    fun save(url: String) {
        file.absoluteFile.parentFile.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        tmp.writeText(url)
        if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
    }
}
