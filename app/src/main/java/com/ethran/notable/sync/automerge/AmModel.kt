package com.ethran.notable.sync.automerge

import java.util.Date

/*
 * Plain model of the shared Automerge documents (ADR-001). The TypeScript twin lives in
 * web/schema/src; both read the golden fixtures in web/schema/fixtures. Map keys are the ids, so
 * every `id` field here comes from the key it is stored under.
 */

/** The per-account index: folder tree and one entry per notebook doc. */
data class AmLibrary(
    val folders: Map<String, AmFolder>,
    val notebooks: Map<String, AmNotebookEntry>,
)

data class AmFolder(
    val id: String,
    val title: String,
    val parentId: String?,
    val createdAt: Date,
    val updatedAt: Date,
)

/** A notebook, or a single "quick page" that lives directly in a folder (Room: page.notebookId == null). */
enum class AmEntryKind(val key: String) {
    NOTEBOOK("notebook"), QUICK_PAGE("quickPage");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
            ?: throw SchemaException("unknown notebook kind '$key'")
    }
}

data class AmNotebookEntry(
    val id: String,
    /** `automerge:` URL of the notebook doc. */
    val docUrl: String,
    val title: String,
    val folderId: String?,
    val kind: AmEntryKind,
    val source: AmSource,
    val createdAt: Date,
    val updatedAt: Date,
)

/** The file a notebook was created from. Blobs are sha256 hex digests in the server's blob store. */
sealed interface AmSource {
    data object None : AmSource
    data class Pdf(val blob: String) : AmSource
    /** EPUB pages exist only for one fixed layout; changing it would move every annotation. */
    data class Epub(val blob: String, val layout: AmEpubLayout) : AmSource
}

data class AmEpubLayout(val w: Double, val h: Double, val em: Double)

/** One notebook doc: pages, their strokes and images. */
data class AmNotebook(
    val notebookId: String,
    val openPageId: String?,
    val defaultBackground: AmBackground,
    /** Normalized on read: no dangling ids, no duplicates, every page present. */
    val pageOrder: List<String>,
    val pages: Map<String, AmPage>,
)

/**
 * Page background. [type] is a `BackgroundType.key` ("native", "image", "imagerepeating",
 * "coverImage", "autoPdf", "pdfN"). [native] holds the native template name for "native";
 * [blob] holds the image or PDF for the other types.
 */
data class AmBackground(val type: String, val native: String?, val blob: String?)

data class AmPage(
    val id: String,
    val background: AmBackground,
    val strokes: Map<String, AmStroke>,
    val images: Map<String, AmImage>,
    val createdAt: Date,
    val updatedAt: Date,
)

/** [points] is Notable's SB2 encoding with pressure normalized to [0, 1]. */
data class AmStroke(
    val id: String,
    val pen: String,
    val color: Int,
    val size: Double,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val points: ByteArray,
    val createdAt: Date,
    val updatedAt: Date,
) {
    override fun equals(other: Any?): Boolean =
        other is AmStroke && id == other.id && pen == other.pen && color == other.color &&
            size == other.size && left == other.left && top == other.top && right == other.right &&
            bottom == other.bottom && points.contentEquals(other.points) &&
            createdAt == other.createdAt && updatedAt == other.updatedAt

    override fun hashCode(): Int = id.hashCode() * 31 + points.contentHashCode()
}

data class AmImage(
    val id: String,
    val blob: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val createdAt: Date,
    val updatedAt: Date,
)

/** A document that does not match the schema. Never repaired silently. */
class SchemaException(message: String) : RuntimeException(message)
