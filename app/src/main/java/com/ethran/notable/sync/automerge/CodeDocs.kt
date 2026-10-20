package com.ethran.notable.sync.automerge

import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import kotlinx.coroutines.flow.StateFlow

/** The library's code doc (ADR-002) as this device has it. */
sealed interface CodeDocState {
    /** The library is being opened; nothing is known yet. */
    data object Opening : CodeDocState
    /** The library has no code doc yet, or couldn't be opened: the image is empty. */
    data object None : CodeDocState
    data class Ready(val url: String, val image: AmCodeImage) : CodeDocState
    /** It exists but can't be read (e.g. a newer version); the image keeps what it had. */
    data class Unreadable(val url: String, val reason: String) : CodeDocState
}

/** The code doc for the image kernel and the REPL. Implemented by [NotableSyncClient]. */
interface CodeDocs {
    val code: StateFlow<CodeDocState>

    /** Writes [def] as a new whole entry; makes the code doc first if the library has none. */
    suspend fun define(def: AmCodeDef): AppResult<Unit, DomainError>

    /** Deletes [name]; false when it wasn't defined. */
    suspend fun undefine(name: String): AppResult<Boolean, DomainError>

    /** The code doc's `save()` bytes (with history, for `history`/`rollback`), or null without one. */
    suspend fun codeDocBytes(): AppResult<ByteArray?, DomainError>
}

/**
 * Code doc edits over the device's [LocalDocs]. Blocking; one at a time per library (the caller
 * holds a lock), so a device never makes two code docs.
 *
 * The code doc is made on first use and the library points at it (`codeDocUrl`). Two devices that
 * both make one before they sync each set `codeDocUrl`; the merge keeps one, and definitions
 * written to the other stay in that doc only (a known gap, see docs/plans/live-code-image.md).
 */
class CodeDocWriter(private val docs: LocalDocs) {
    fun codeDocUrl(): String? = docs.edit(docs.libraryUrl()) { AutomergeSchema.codeDocUrlOf(it) }

    fun define(def: AmCodeDef) {
        val url = codeDocUrl() ?: create()
        docs.edit(url) { doc ->
            val defs = AutomergeSchema.codeDefsOf(doc)
            val tx = doc.startTransaction()
            try {
                AutomergeSchema.putCodeDef(tx, defs, def)
            } catch (e: Throwable) {
                tx.rollback()
                throw e
            }
            tx.commit()
        }
    }

    fun undefine(name: String): Boolean {
        val url = codeDocUrl() ?: return false
        return docs.edit(url) { doc ->
            val defs = AutomergeSchema.codeDefsOf(doc)
            if (!doc.get(defs, name).isPresent) return@edit false
            doc.startTransaction().apply { delete(defs, name); commit() }
            true
        }
    }

    fun read(): AmCodeImage? = codeDocUrl()?.let { url -> docs.edit(url) { AutomergeSchema.readCode(it) } }

    fun save(): ByteArray? = codeDocUrl()?.let { url -> docs.edit(url) { it.save() } }

    private fun create(): String {
        val url = docs.createDoc(AutomergeSchema.newCodeDocument(AmCodeImage(emptyMap())))
        docs.edit(docs.libraryUrl()) { doc ->
            doc.startTransaction().apply { AutomergeSchema.setCodeDocUrl(this, url); commit() }
        }
        return url
    }
}
