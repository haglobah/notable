package com.ethran.notable.data.db

import androidx.lifecycle.LiveData
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.ethran.notable.data.model.BackgroundType
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@Entity(
    foreignKeys = [ForeignKey(
        entity = Folder::class,
        parentColumns = arrayOf("id"),
        childColumns = arrayOf("parentFolderId"),
        onDelete = ForeignKey.CASCADE
    )]
)
data class Notebook(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New notebook",
    val openPageId: String? = null,
    val pageIds: List<String> = listOf(),

    @ColumnInfo(index = true)
    val parentFolderId: String? = null,

    @ColumnInfo(defaultValue = "blank")
    val defaultBackground: String = "blank",
    @ColumnInfo(defaultValue = "native")
    val defaultBackgroundType: String = "native",

    // File that its linked to:
    val linkedExternalUri: String? = null,
    val createdAt: Date = Date(),
    val updatedAt: Date = Date()
)

// DAO
@Dao
interface NotebookDao {
    @Query("SELECT * FROM notebook WHERE parentFolderId is :folderId")
    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>>

    @Query("SELECT * FROM notebook")
    fun getAll(): List<Notebook>

    @Query("SELECT * FROM notebook")
    fun getAllFlow(): Flow<List<Notebook>>

    // Nullable: Room emits null when the row is absent (e.g. the notebook was deleted while a
    // screen still observes it) and re-emits on every write to the table. Typing it non-null let
    // collectors dereference a null and NPE.
    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    fun getByIdLive(notebookId: String): LiveData<Notebook?>

    @Query("SELECT * FROM notebook WHERE id = (:notebookId)")
    suspend fun getById(notebookId: String): Notebook?

    @Query("UPDATE notebook SET openPageId=:pageId WHERE id=:notebookId")
    suspend fun setOpenPageId(notebookId: String, pageId: String)

    // Advances updatedAt alongside pageIds so a structural change (add/remove/reorder) marks the
    // notebook dirty for sync — otherwise the change would not be detected and could be lost.
    @Query("UPDATE notebook SET pageIds=:pageIds, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setPageIds(id: String, pageIds: List<String>, updatedAt: Date)

    @Query("UPDATE notebook SET title=:title, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setTitle(id: String, title: String, updatedAt: Date)

    @Query("UPDATE notebook SET parentFolderId=:folderId, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setParentFolder(id: String, folderId: String?, updatedAt: Date)

    @Query("UPDATE notebook SET defaultBackgroundType=:type, defaultBackground=:value, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setDefaultBackground(id: String, type: String, value: String, updatedAt: Date)

    /** Device-local: the docs don't hold it, so it's written to Room alone. */
    @Query("UPDATE notebook SET linkedExternalUri=:uri WHERE id=:id")
    suspend fun setLinkedExternalUri(id: String, uri: String?)

    @Insert
    suspend fun create(notebook: Notebook): Long

    @Update
    suspend fun update(notebook: Notebook)

    /** Insert or update without REPLACE, so pages don't cascade away. */
    @Upsert
    suspend fun upsert(notebook: Notebook)

    @Query("DELETE FROM notebook WHERE id=:id")
    suspend fun delete(id: String)
}

/**
 * Room access for notebooks. Writes here are Room-only: app code changes the library through
 * [com.ethran.notable.data.LibraryEdits], which puts each edit into the Automerge docs first. The
 * WebDAV sync still writes here directly until it is removed (plan Phase 5).
 */
class BookRepository @Inject constructor(
    private val notebookDao: NotebookDao,
    private val pageDao: PageDao
) {
    private val log = ShipBook.getLogger("BookRepository")

    fun getAll(): List<Notebook> {
        return notebookDao.getAll()
    }

    fun getAllFlow(): Flow<List<Notebook>> {
        return notebookDao.getAllFlow()
    }

    suspend fun create(notebook: Notebook) {
        notebookDao.create(notebook)
        val page = Page(
            notebookId = notebook.id,
            background = notebook.defaultBackground,
            backgroundType = notebook.defaultBackgroundType
        )
        pageDao.create(page)

        notebookDao.setPageIds(notebook.id, listOf(page.id), Date())
        notebookDao.setOpenPageId(notebook.id, page.id)
    }

    suspend fun createEmpty(notebook: Notebook) {
        notebookDao.create(notebook)
    }

    suspend fun update(notebook: Notebook) {
        log.i("updating DB")
        val updatedNotebook = notebook.copy(updatedAt = Date())
        notebookDao.update(updatedNotebook)
    }

    /**
     * Write the notebook exactly as given, unlike [update], which stamps `updatedAt = now()`.
     * Used during sync when downloading from server, to keep the remote timestamp.
     */
    suspend fun updateVerbatim(notebook: Notebook) {
        notebookDao.update(notebook)
    }

    fun getAllInFolder(folderId: String? = null): LiveData<List<Notebook>> {
        return notebookDao.getAllInFolder(folderId)
    }

    suspend fun getById(notebookId: String): Notebook? {
        return notebookDao.getById(notebookId)
    }

    fun getByIdLive(notebookId: String): LiveData<Notebook?> {
        return notebookDao.getByIdLive(notebookId)
    }

    suspend fun setOpenPageId(id: String, pageId: String) {
        notebookDao.setOpenPageId(id, pageId)
    }

    suspend fun addPage(bookId: String, pageId: String, index: Int? = null) {
        val notebook = notebookDao.getById(bookId) ?: return
        val pageIds = notebook.pageIds.toMutableList()
        if (index != null) pageIds.add(index, pageId)
        else pageIds.add(pageId)
        notebookDao.setPageIds(bookId, pageIds, Date())
    }

    suspend fun removePage(id: String, pageId: String) {
        val notebook = notebookDao.getById(id) ?: return
        val updatedNotebook = notebook.copy(
            // remove the page
            pageIds = notebook.pageIds.filterNot { it == pageId },
            // remove the "open page" if it's the one
            openPageId = if (notebook.openPageId == pageId) null else notebook.openPageId,
            // a structural change marks the notebook dirty for sync
            updatedAt = Date()
        )
        notebookDao.update(updatedNotebook)
        log.i("Cleaned $id $pageId")
    }

    /** Puts [pageId] right after [afterPageId] (first when null, last when it isn't listed). */
    suspend fun placePage(id: String, pageId: String, afterPageId: String?) {
        val notebook = notebookDao.getById(id) ?: return
        notebookDao.setPageIds(id, notebook.pageIds.placed(pageId, afterPageId), Date())
    }

    suspend fun setTitle(id: String, title: String) = notebookDao.setTitle(id, title, Date())
    suspend fun setParentFolder(id: String, folderId: String?) = notebookDao.setParentFolder(id, folderId, Date())
    suspend fun setDefaultBackground(id: String, type: String, value: String) =
        notebookDao.setDefaultBackground(id, type, value, Date())
    suspend fun setLinkedExternalUri(id: String, uri: String?) = notebookDao.setLinkedExternalUri(id, uri)

    suspend fun changePageIndex(id: String, pageId: String, index: Int) {
        val notebook = notebookDao.getById(id) ?: return
        val pageIds = notebook.pageIds.toMutableList()
        var correctedIndex = index
        if (correctedIndex < 0) correctedIndex = 0
        if (correctedIndex > pageIds.size - 1) correctedIndex = pageIds.size - 1

        pageIds.remove(pageId)
        pageIds.add(correctedIndex, pageId)
        notebookDao.setPageIds(id, pageIds, Date())
    }

    suspend fun getPageIndex(id: String, pageId: String): Int? {
        val notebook = notebookDao.getById(id) ?: return null
        val pageIds = notebook.pageIds
        val index = pageIds.indexOf(pageId)
        return if (index != -1) index else null
    }

    suspend fun getPageAtIndex(id: String, index: Int): String? {
        val notebook = notebookDao.getById(id) ?: return null
        val pageIds = notebook.pageIds
        if (index < 0 || index > pageIds.size - 1) return null
        return pageIds[index]
    }

    suspend fun delete(id: String) {
        notebookDao.delete(id)
    }

}


fun Notebook.getBackgroundType(): BackgroundType {
    return BackgroundType.fromKey(defaultBackgroundType)
}

fun Notebook.newPage(): Page {
    return Page(
        notebookId = id,
        background = defaultBackground,
        backgroundType = defaultBackgroundType
    )
}

/** This order with [pageId] right after [afterPageId]: first when null, last when it isn't listed. */
fun List<String>.placed(pageId: String, afterPageId: String?): List<String> {
    val rest = filter { it != pageId }
    val at = if (afterPageId == null) 0 else rest.indexOf(afterPageId).let { if (it < 0) rest.size else it + 1 }
    return rest.take(at) + pageId + rest.drop(at)
}

fun Notebook.getPageIndex(pageId: String): Int {
    return pageIds.indexOf(pageId)
}