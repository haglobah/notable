package com.ethran.notable.ui.dialogs

import android.text.format.DateFormat
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.getLinkedFilesDir
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.RatingPicker
import com.ethran.notable.ui.components.getFolderList
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NField
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NNotice
import com.ethran.notable.ui.design.NSettingRow
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Download
import compose.icons.feathericons.FolderPlus
import compose.icons.feathericons.Link
import compose.icons.feathericons.Trash2
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("NotebookConfig")

@Composable
fun NotebookConfigDialog(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    bookId: String,
    onClose: () -> Unit,
) {
    // Every write goes through the docs (LibraryEdits), off the main thread.
    val edits = appRepository.edits
    suspend fun io(write: suspend () -> Unit) = withContext(Dispatchers.IO) { write() }
    val observedBook by appRepository.bookRepository.getByIdLive(bookId).observeAsState()
    val scope = rememberCoroutineScope()
    val snackManager = LocalSnackContext.current
    val book = observedBook ?: return
    var title by remember(bookId) { mutableStateOf(book.title) }
    var savedTitle by remember(bookId) { mutableStateOf(book.title) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showBackground by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    val inspection = LocalInspectionMode.current
    val nameLabel = stringResource(R.string.details_name)
    LaunchedEffect(book.parentFolderId) { folders = getFolderList(appRepository, book.parentFolderId) }

    // Keep this composition alive until persistence completes. A dismiss must never discard a
    // focused field or cancel its save; failed actions retain the draft and can be retried.
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        retry = { perform(action) }
        scope.launch {
            try {
                val draft = title
                if (draft != savedTitle) {
                    io { edits.renameNotebook(bookId, draft) }
                    savedTitle = draft
                }
                action()
                retry = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log.e("Notebook action failed: ${error.message}")
                failed = true
            } finally {
                busy = false
            }
        }
    }
    val finish = { perform { onClose() } }

    if (showBackground) {
        BackgroundSelector(
            initialPageBackgroundType = book.defaultBackgroundType,
            initialPageBackground = book.defaultBackground,
            isNotebookBgSelector = true,
            notebookId = book.id,
            onChange = { type, background ->
                showBackground = false
                // No background chosen: keep the current one, change only the type.
                val value = background ?: book.defaultBackground
                perform { io { edits.setNotebookBackground(bookId, type, value) } }
            },
            onClose = { showBackground = false },
        )
        return
    }
    if (showDelete) {
        ShowSimpleConfirmationDialog(
            title = stringResource(R.string.details_delete_title),
            message = stringResource(R.string.details_delete_message, title),
            confirmButtonText = stringResource(R.string.dialog_delete),
            destructive = true,
            onConfirm = {
                showDelete = false
                perform {
                    io { edits.deleteNotebook(bookId) }
                    onClose()
                }
            }, onCancel = { showDelete = false },
        )
        return
    }
    if (showExport) {
        ShowExportDialog(exportEngine = exportEngine, snackManager = snackManager, bookId = bookId,
            onConfirm = { showExport = false; onClose() }, onCancel = { showExport = false })
        return
    }
    if (showMove) {
        ShowFolderSelectionDialog(appRepository = appRepository, notebookName = title,
            initialFolderId = book.parentFolderId, onCancel = { showMove = false },
            onConfirm = { folder ->
                showMove = false
                perform { io { edits.moveNotebook(bookId, folder) }; onClose() }
            })
        return
    }

    val c = NotableTheme.colors
    NDialog(
        title = stringResource(R.string.details_heading),
        onDismiss = finish,
        maxWidth = 560.dp,
        titleAction = {
            NButton(stringResource(if (busy) R.string.details_saving else R.string.details_done),
                onClick = finish, style = NButtonStyle.Primary, enabled = !busy)
        },
    ) {
        if (failed) {
            NNotice(stringResource(R.string.details_save_failed), actions = {
                NButton(stringResource(R.string.details_retry), onClick = { retry?.invoke() },
                    size = NControlSize.Compact, enabled = !busy)
            })
            Spacer(Modifier.height(NotableSpace.xl))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg), verticalAlignment = Alignment.CenterVertically) {
            val cover = book.pageIds.firstOrNull()
            if (cover != null) PagePreview(Modifier.width(72.dp).aspectRatio(3f / 4f)
                .border(NotableSize.hairline, c.rule, NotableShape.paper), cover)
            Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.xxs)) {
                Text(stringResource(R.string.details_notebook_pages, book.pageIds.size),
                    style = NotableTheme.type.body, color = c.ink)
                Text(stringResource(R.string.details_notebook_created,
                    DateFormat.format("dd MMM yyyy", book.createdAt)), style = NotableTheme.type.meta, color = c.secondary)
                Text(stringResource(R.string.details_notebook_last_updated,
                    DateFormat.format("dd MMM yyyy HH:mm", book.updatedAt)), style = NotableTheme.type.meta, color = c.secondary)
            }
        }
        Spacer(Modifier.height(NotableSpace.xl))
        NField(nameLabel) {
            NTextField(value = title, onValueChange = { title = it; failed = false },
                enabled = !busy, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { finish() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel })
        }
        Spacer(Modifier.height(NotableSpace.lg))
        NDivider()
        val typeName = when (BackgroundType.fromKey(book.defaultBackgroundType)) {
            BackgroundType.AutoPdf -> R.string.details_template_linked_pdf
            BackgroundType.CoverImage -> R.string.details_template_cover
            BackgroundType.Image -> R.string.details_template_image
            BackgroundType.ImageRepeating -> R.string.details_template_repeating
            BackgroundType.Native -> R.string.details_template_builtin
            is BackgroundType.Pdf -> R.string.details_template_pdf
        }
        NSettingRow(stringResource(R.string.details_template), onClick = { perform { showBackground = true } },
            enabled = !busy) {
            DisclosureValue(stringResource(typeName), enabled = !busy)
        }
        Column(Modifier.padding(vertical = NotableSpace.md)) {
            Text(stringResource(R.string.rating_menu), style = NotableTheme.type.body, color = c.ink,
                modifier = Modifier.padding(bottom = NotableSpace.sm))
            RatingPicker(book.rating, onRate = { r -> perform { io { edits.rateNotebook(bookId, r) } } })
        }
        NDivider()
        NotebookLinkRow(isLinkedInit = book.linkedExternalUri != null,
            linkedUriInit = book.linkedExternalUri, defaultPath = "",
            enabled = !busy, onLinkChanged = { uri ->
                perform {
                    // Resolve the folder only on an explicit Link action, never during composition.
                    val resolved = if (uri == "") {
                        if (inspection) "file:///preview/Linked" else getLinkedFilesDir().toUri().toString()
                    } else uri
                    io { edits.setNotebookLinkedUri(bookId, resolved) }
                }
            })
        NSettingRow(stringResource(R.string.details_location), description = locationPath(folders), divider = false)
        Spacer(Modifier.height(NotableSpace.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
            NButton(stringResource(R.string.details_notebook_buttons_move),
                onClick = { perform { showMove = true } }, icon = FeatherIcons.FolderPlus, enabled = !busy)
            NButton(stringResource(R.string.details_notebook_buttons_export),
                onClick = { perform { showExport = true } }, icon = FeatherIcons.Download, enabled = !busy)
        }
        Spacer(Modifier.height(NotableSpace.xl))
        NDivider()
        Spacer(Modifier.height(NotableSpace.xl))
        NButton(stringResource(R.string.details_delete_title),
            onClick = { perform { showDelete = true } }, style = NButtonStyle.Danger,
            icon = FeatherIcons.Trash2, enabled = !busy)
    }
}

/** "Library › Reading › Novels" in plain text. */
@Composable
private fun locationPath(folders: List<Folder>): String =
    (listOf(stringResource(R.string.home_view_name)) + folders.map { it.title }).joinToString(" › ")

/** The trailing value of a row that opens another dialog: the value in secondary text and a chevron. */
@Composable
private fun DisclosureValue(text: String, enabled: Boolean) {
    val c = NotableTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = NotableTheme.type.body, color = if (enabled) c.secondary else c.disabled, maxLines = 1)
        Spacer(Modifier.width(NotableSpace.xs))
        NIcon(FeatherIcons.ChevronRight, tint = if (enabled) c.ink else c.disabled, size = 18.dp)
    }
}

// Kept for the folder and page dialogs that share this API.
@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    NButton(text, onClick)
}

@Composable
fun NotebookLinkRow(
    isLinkedInit: Boolean,
    linkedUriInit: String?,
    defaultPath: String,
    onLinkChanged: (String?) -> Unit,
    enabled: Boolean = true,
) {
    NSettingRow(
        title = stringResource(R.string.details_link),
        description = linkedUriInit ?: stringResource(R.string.details_unlinked),
        enabled = enabled,
    ) {
        NButton(stringResource(if (isLinkedInit) R.string.details_unlink else R.string.details_link_action),
            onClick = { onLinkChanged(if (isLinkedInit) null else defaultPath) }, enabled = enabled,
            size = NControlSize.Compact, icon = if (isLinkedInit) null else FeatherIcons.Link)
    }
}
