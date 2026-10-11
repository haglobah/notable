package com.ethran.notable.sync.automerge

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A [BlobStore] that works offline. [upload] copies the file (path or `content://` URI) into [dir],
 * named by its sha256, and returns the hash at once, so an image edit can commit to the doc
 * without the server. [drain] uploads the queued files when the server can be reached. The queue
 * is the folder itself, so it survives restarts.
 *
 * - `<dir>/<sha256>`: queued, not uploaded yet.
 * - `<dir>/uploaded`: hashes the server has; such a file is not queued again.
 * - `<dir>/rejected/<sha256>`: refused by the server (too large); kept, not retried.
 */
class QueuedBlobStore(
    private val dir: File,
    private val open: (String) -> InputStream,
    private val onChange: (Int) -> Unit = {},
) : BlobStore {
    data class Rejected(val sha: String, val reason: String)
    data class DrainResult(val uploaded: Int, val rejected: List<Rejected>)

    /** File reference (with size and date for plain paths) → hash, for this session. */
    private val known = ConcurrentHashMap<String, String>()
    private val uploadedFile = File(dir, "uploaded")
    private val rejectedDir = File(dir, "rejected")

    override fun upload(pathOrUri: String): String {
        if (pathOrUri.isBlank()) throw MissingFileException("empty file reference")
        val memo = memoKey(pathOrUri)
        known[memo]?.let { return it }
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
        val tmp = File(dir, ".part-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            open(pathOrUri).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    out.fd.sync() // on disk before a doc commit refers to it
                }
            }
            val sha = DurableStorage.hex(digest.digest())
            synchronized(this) {
                val target = File(dir, sha)
                if (target.isFile || sha in uploaded()) {
                    tmp.delete()
                } else if (!tmp.renameTo(target)) {
                    throw IOException("could not queue $pathOrUri as ${target.path}")
                }
            }
            known[memo] = sha
            onChange(pendingCount())
            return sha
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun pendingCount(): Int = queued().size

    /**
     * Uploads every queued blob through [put] and removes it from the queue. A blob refused for
     * good ([refused], e.g. too large) moves to `rejected/` and is reported; any other failure stops
     * the drain and is thrown, and the rest stays queued.
     */
    fun drain(
        put: (sha: String, size: Long, open: () -> InputStream) -> Unit,
        refused: (Throwable) -> Boolean = { it is ServerError.TooLarge },
    ): DrainResult {
        var uploaded = 0
        val rejected = mutableListOf<Rejected>()
        for (f in queued()) {
            try {
                put(f.name, f.length()) { f.inputStream() }
            } catch (e: Exception) {
                if (!refused(e)) throw e
                rejectedDir.mkdirs()
                if (!f.renameTo(File(rejectedDir, f.name))) f.delete()
                rejected += Rejected(f.name, e.message ?: e.javaClass.simpleName)
                onChange(pendingCount())
                continue
            }
            synchronized(this) {
                uploadedFile.appendText(f.name + "\n")
                f.delete()
            }
            uploaded++
            onChange(pendingCount())
        }
        return DrainResult(uploaded, rejected)
    }

    /** Copies queued blob [sha] to [dest] if it's still queued here; false if not. */
    fun copyQueued(sha: String, dest: File): Boolean {
        if (!SHA.matches(sha)) return false
        val src = File(dir, sha)
        if (!src.isFile) return false
        dest.absoluteFile.parentFile?.mkdirs()
        val tmp = File(dest.absoluteFile.parentFile, dest.name + ".part")
        try {
            src.copyTo(tmp, overwrite = true)
        } catch (e: NoSuchFileException) {
            return false // uploaded and removed meanwhile
        }
        if (!tmp.renameTo(dest)) throw IOException("could not write ${dest.path}")
        return true
    }

    private fun queued(): List<File> =
        dir.listFiles { f -> f.isFile && SHA.matches(f.name) }?.sortedBy { it.lastModified() }.orEmpty()

    private fun uploaded(): Set<String> =
        if (uploadedFile.isFile) uploadedFile.readLines().filter { it.isNotBlank() }.toSet() else emptySet()

    private fun memoKey(pathOrUri: String): String {
        if (pathOrUri.contains("://") && !pathOrUri.startsWith("file://")) return pathOrUri
        val f = File(pathOrUri.removePrefix("file://"))
        return "$pathOrUri|${f.length()}|${f.lastModified()}"
    }

    private companion object {
        val SHA = Regex("[0-9a-f]{64}")
    }
}
