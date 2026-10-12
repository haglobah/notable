package com.ethran.notable.io

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.ethran.notable.data.LibraryEdits
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.data.db.PageWithData
import com.ethran.notable.data.ensureBackgroundsFolder
import com.ethran.notable.io.projection.EpubFiles
import android.provider.OpenableColumns
import java.io.File
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError
import com.ethran.notable.utils.plus
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shipbook.shipbooksdk.ShipBook
import javax.inject.Inject


/**
 * Defines the strategy to resolve conflicts when importing a book that already exists in the database.
 */
enum class ImportConflictStrategy {
    /** If a book with the same ID exists, overwrite it completely with the imported file. */
    OVERWRITE,

    /** On conflicts, merge changes, choosing the most recent change based on timestamps. */
    MERGE_TIME_BASED,

    /** On conflicts, prioritize the version of the data already in the database. */
    PRIORITIZE_DATABASE,

    /** On conflicts, prioritize the version of the data from the imported file. */
    PRIORITIZE_FILE,

    /** If conflicts are found, ask the user for resolution (not implemented). */
    ASK
}

/**
 * Configuration options for an import operation.
 * @param saveToBookId If specified, attempts to save the imported data into an existing book with this ID. If null, a new book is created.
 * @param folderId If specified, the new notebook will be created in this folder.
 * @param conflictStrategy The strategy to use for resolving conflicts.
 * @param linkToExternalFile If true, the app will link to the original file URI instead of copying it. This is applicable for file types like PDF.
 * @param fileType If specified, the app will only import files of this type.
 * @param bookTitle If specified, the filename will be overwritten to this value.
 */
data class ImportOptions(
    val saveToBookId: String? = null,
    val folderId: String? = null,
    val conflictStrategy: ImportConflictStrategy? = null,
    val linkToExternalFile: Boolean = false,
    val fileType: String? = null,
    val bookTitle: String? = null
)


/**
 * The engine responsible for handling the logic of importing files into the app.
 * It is agnostic of the UI and operates on URIs provided to it.
 */
fun isEpubFile(mimeType: String?, fileName: String?): Boolean =
    mimeType == "application/epub+zip" || fileName?.endsWith(".epub", ignoreCase = true) == true

class ImportEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    /** An import is one creation: Room first, then its doc (see [LibraryEdits.importNotebook]). */
    private val edits: LibraryEdits,
    private val appEventBus: AppEventBus
) {
    private val log = ShipBook.getLogger("ImportEngine")

    @Inject
    lateinit var xoppFile: XoppFile

    /**
     * Imports a notebook from the given URI. It recognizes the file type and
     * executes the correct import function.
     *
     * @param uri The URI of the file to import.
     * @param options The options for the import process.
     * @return An [String] indicating success or failure.
     */
    @WorkerThread
    suspend fun import(
        uri: Uri,
        options: ImportOptions = ImportOptions()
    ): AppResult<List<String>, DomainError> {
        val mimeType = context.contentResolver.getType(uri)
        if (options.fileType != null && mimeType != options.fileType)
            return AppResult.Error(DomainError.UnexpectedState("File type mismatch. Expected: ${options.fileType}, Actual: $mimeType"))

        val bookTitle = sanitizeNotebookName(options.bookTitle ?: getFileName(uri))
        log.d("Starting import for uri: $uri, mimeType: $mimeType, fileName: $bookTitle")

        // strip extension if present in bookTitle (from options or getFileName)
        val finalTitle = if (bookTitle.endsWith(".xopp", ignoreCase = true)) {
            bookTitle.removeSuffix(".xopp")
        } else if (bookTitle.endsWith(".pdf", ignoreCase = true)) {
            bookTitle.removeSuffix(".pdf")
        } else if (bookTitle.endsWith(".epub", ignoreCase = true)) {
            bookTitle.dropLast(".epub".length)
        } else {
            bookTitle
        }

        if (options.saveToBookId != null)
            TODO("Implement logic to save into an existing book (ID: ${options.saveToBookId})")

        val optionsWithTitle = options.copy(
            bookTitle = finalTitle,
        )

        return when {
            XoppFile.isXoppFile(mimeType, bookTitle) -> handleImportXopp(uri, optionsWithTitle)
            isPdfFile(mimeType, bookTitle) -> handleImportPDF(uri, optionsWithTitle)
            isEpubFile(mimeType, bookTitle) -> handleImportEpub(uri, optionsWithTitle)
            else -> {
                val errorMessage = "Unsupported file type: $mimeType"
                log.w(errorMessage)
                AppResult.Error(DomainError.UnexpectedState(errorMessage))
            }
        }
    }

    private suspend fun handleImportXopp(
        uri: Uri,
        options: ImportOptions
    ): AppResult<List<String>, DomainError> {
        log.d("Importing Xopp file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Xopp file" }
        val book = Notebook(
            title = options.bookTitle,
            parentFolderId = options.folderId,
            defaultBackground = "blank",
            defaultBackgroundType = BackgroundType.Native.key
        )
        val importedPageIds = mutableListOf<String>()
        var persistentError: DomainError? = null

        edits.importNotebook(book) {
            val rows = this
            xoppFile.importBook(
                uri = uri,
                onPageCreated = { page ->
                    try {
                        // TODO: Handle conflicts with existing pages.
                        rows.page(page)
                        importedPageIds.add(page.id)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import page ${page.id}: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                },
                onStrokeBatch = { batch ->
                    try {
                        rows.strokes(batch)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import stroke batch: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                },
                onPageFinalized = { _, pageImages ->
                    try {
                        rows.images(pageImages)
                    } catch (e: Exception) {
                        val errMessage = "Failed to import page images: ${e.message}"
                        appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                        val error = DomainError.DatabaseError(errMessage)
                        persistentError = persistentError?.let { it + error } ?: error
                    }
                }
            )
        }

        return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(importedPageIds)
    }

    private suspend fun handleImportPDF(
        uri: Uri,
        options: ImportOptions
    ): AppResult<List<String>, DomainError> {
        log.d("Importing Pdf file...")
        require(options.bookTitle != null) { "bookTitle cannot be null when importing Pdf file" }

        val fileToSave = handleFileSaving(context, uri, options)
            ?: return AppResult.Error(DomainError.UnexpectedState("Couldn't determine file path. Does the app have permission to read external storage?"))

        val filePath = fileToSave.toString()

        val book = Notebook(
            title = options.bookTitle,
            parentFolderId = options.folderId,
            defaultBackground = filePath,
            defaultBackgroundType = BackgroundType.AutoPdf.key
        )
        val importedPageIds = mutableListOf<String>()
        var persistentError: DomainError? = null

        edits.importNotebook(book) {
            val rows = this
            importPdf(fileToSave, options) { pageData ->
                try {
                    rows.page(pageData.page, pageData.strokes, pageData.images)
                    importedPageIds.add(pageData.page.id)
                } catch (e: Exception) {
                    val errMessage = "failed import book  ${e.message}"
                    appEventBus.emit(AppEvent.LogMessage("importBook", errMessage))
                    val error = DomainError.DatabaseError(errMessage)
                    persistentError = persistentError?.let { it + error } ?: error
                }
            }
        }

        return persistentError?.let { AppResult.Error(it) } ?: AppResult.Success(importedPageIds)
    }


    /**
     * An EPUB is copied into the backgrounds folder under a name that carries its layout (the Boox
     * screen, as the PWA lays out EPUBs: [EpubFiles.BOOX_LAYOUT]), laid out once with MuPDF, and
     * becomes one page per laid-out page ("pdfN"), like a PDF. Its doc gets an EPUB source with
     * that layout, so the PWA and the PDF projection show the same pages.
     */
    private suspend fun handleImportEpub(
        uri: Uri,
        options: ImportOptions
    ): AppResult<List<String>, DomainError> {
        log.d("Importing EPUB file...")
        val title = requireNotNull(options.bookTitle) { "bookTitle cannot be null when importing an EPUB" }
        val dir = File(ensureBackgroundsFolder(), BackgroundType.Pdf(0).folderName).apply { mkdirs() }
        val base = title.ifBlank { "book" }
        var file = File(dir, EpubFiles.fileName(base, EpubFiles.BOOX_LAYOUT))
        var n = 1
        while (file.exists()) file = File(dir, EpubFiles.fileName("$base ${n++}", EpubFiles.BOOX_LAYOUT))
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return AppResult.Error(DomainError.UnexpectedState("Can't read the EPUB file"))
            copyStreamToFile(input, file)
        } catch (e: Exception) {
            file.delete()
            return AppResult.Error(DomainError.UnexpectedState("Can't copy the EPUB file: ${e.message}"))
        }
        val pageCount = try {
            EpubBackground.laidOutPdf(file.path).let { getPdfPageCount(file.path) }
        } catch (e: Exception) {
            file.delete()
            return AppResult.Error(DomainError.UnexpectedState("This file can't be opened as an EPUB: ${e.message}"))
        }
        if (pageCount < 1) {
            file.delete()
            return AppResult.Error(DomainError.UnexpectedState("This EPUB has no pages after layout"))
        }

        val book = Notebook(
            title = title,
            parentFolderId = options.folderId,
            defaultBackground = file.path,
            defaultBackgroundType = BackgroundType.AutoPdf.key
        )
        val importedPageIds = mutableListOf<String>()
        edits.importNotebook(book) {
            for (i in 0 until pageCount) {
                val page = Page(background = file.path, backgroundType = BackgroundType.Pdf(i).key)
                page(page)
                importedPageIds.add(page.id)
            }
        }
        return AppResult.Success(importedPageIds)
    }

    private fun merge(fileData: PageWithData, options: ImportOptions) {
        require(options.saveToBookId != null) { "saveToBookId cannot be null when merging" }
        require(options.conflictStrategy != null) { "conflictStrategy cannot be null when merging" }
        log.d("Conflict detected. Strategy: ${options.conflictStrategy}")
        when (options.conflictStrategy) {
            ImportConflictStrategy.OVERWRITE -> TODO()
            ImportConflictStrategy.MERGE_TIME_BASED -> TODO()
            ImportConflictStrategy.PRIORITIZE_DATABASE -> TODO()
            ImportConflictStrategy.PRIORITIZE_FILE -> TODO()
            ImportConflictStrategy.ASK -> TODO()
        }
    }


    /**
     * Extracts the book title from a file URI.
     */
    private fun getFileName(uri: Uri): String {
        // The provider's display name ("Paper.pdf"); a content URI's last segment is often an id.
        val displayName = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val fileName = displayName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast("/") ?: "Imported Book"
        return if (fileName.endsWith(".xopp", ignoreCase = true)) {
            fileName.removeSuffix(".xopp")
        } else if (fileName.endsWith(".pdf", ignoreCase = true)) {
            fileName.removeSuffix(".pdf")
        } else {
            fileName
        }
    }


    private fun sanitizeNotebookName(raw: String, maxLen: Int = 100): String {
        var name = raw

        // Allow only letters, numbers, spaces, and dots
        name = name.replace(Regex("[^A-Za-z0-9. ]"), " ")

        // Collapse multiple spaces
        name = name.replace(Regex("\\s+"), " ")

        // Reduce multiple consecutive dots to a single dot
        name = name.replace(Regex("\\.+"), ".")

        // Trim whitespace from start and end
        name = name.trim()

        // Remove leading dot if present
        if (name.startsWith(".")) {
            name = name.removePrefix(".")
        }

        // Cut if too long
        if (name.length > maxLen) {
            name = name.take(maxLen).trimEnd()
        }

        return name
    }

}