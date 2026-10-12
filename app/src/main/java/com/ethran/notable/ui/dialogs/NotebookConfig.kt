package com.ethran.notable.ui.dialogs

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.ethran.notable.R
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.model.BackgroundType
import com.ethran.notable.io.ExportEngine
import com.ethran.notable.io.getLinkedFilesDir
import com.ethran.notable.sync.SyncRequest
import com.ethran.notable.sync.SyncScheduler
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.*
import com.ethran.notable.ui.theme.*
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val log = ShipBook.getLogger("NotebookConfig")

@Composable
fun NotebookConfigDialog(
    appRepository: AppRepository,
    exportEngine: ExportEngine,
    syncScheduler: SyncScheduler,
    bookId: String,
    onClose: () -> Unit,
) {
    val repository = appRepository.bookRepository
    val observedBook by repository.getByIdLive(bookId).observeAsState()
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
    val syncFailure = stringResource(R.string.details_delete_sync_failed)
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
                    repository.rename(bookId, draft)
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
                perform { repository.setBackground(bookId, type, background) }
            },
            onClose = { showBackground = false },
        )
        return
    }
    if (showDelete) {
        ShowSimpleConfirmationDialog(
            title = stringResource(R.string.details_delete_title),
            message = stringResource(R.string.details_delete_message, title),
            onConfirm = {
                showDelete = false
                perform {
                    repository.delete(bookId)
                    // Queue the durable worker before disposing the dialog's coroutine scope.
                    try {
                        syncScheduler.triggerImmediateSync(SyncRequest.UploadDeletion(notebookId = bookId))
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        log.e("Notebook deleted, but queuing remote deletion failed: ${error.message}")
                        snackManager.displaySnack(SnackConf(text = syncFailure, duration = 5000))
                    }
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
                perform { repository.move(bookId, folder); onClose() }
            })
        return
    }

    ScaledDialog(onDismissRequest = finish, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth()
            .background(Paper).border(1.dp, PaperLine).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.details_heading), style = MaterialTheme.typography.h5,
                    modifier = Modifier.weight(1f))
                PaperAction(stringResource(if (busy) R.string.details_saving else R.string.details_done),
                    onClick = finish, primary = true, enabled = !busy)
            }
            if (failed) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.details_save_failed), style = MaterialTheme.typography.body2,
                        modifier = Modifier.weight(1f))
                    PaperAction(stringResource(R.string.details_retry), onClick = { retry?.invoke() }, enabled = !busy)
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = book.pageIds.firstOrNull()
                    if (cover != null) PagePreview(Modifier.width(80.dp).aspectRatio(3f / 4f)
                        .border(1.dp, PaperLine, RectangleShape), cover)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.details_notebook_pages, book.pageIds.size), style = MaterialTheme.typography.subtitle1)
                        Text(stringResource(R.string.details_notebook_created,
                            DateFormat.format("dd MMM yyyy", book.createdAt)), style = MaterialTheme.typography.body2, color = SecondaryInk)
                        Text(stringResource(R.string.details_notebook_last_updated,
                            DateFormat.format("dd MMM yyyy HH:mm", book.updatedAt)), style = MaterialTheme.typography.body2, color = SecondaryInk)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.details_name), style = MaterialTheme.typography.subtitle2)
                    BasicTextField(value = title, onValueChange = { title = it; failed = false },
                        enabled = !busy, singleLine = true,
                        textStyle = MaterialTheme.typography.body1.copy(color = Ink),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finish() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = nameLabel }
                            .background(PaperInset).border(1.dp, PaperLine)
                            .padding(horizontal = 12.dp, vertical = 14.dp))
                }
                Divider(color = PaperLine)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.details_template), style = MaterialTheme.typography.subtitle2)
                    val typeName = when (BackgroundType.fromKey(book.defaultBackgroundType)) {
                        BackgroundType.AutoPdf -> R.string.details_template_linked_pdf
                        BackgroundType.CoverImage -> R.string.details_template_cover
                        BackgroundType.Image -> R.string.details_template_image
                        BackgroundType.ImageRepeating -> R.string.details_template_repeating
                        BackgroundType.Native -> R.string.details_template_builtin
                        is BackgroundType.Pdf -> R.string.details_template_pdf
                    }
                    PaperAction(stringResource(typeName), onClick = { perform { showBackground = true } },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy)
                }
                NotebookLinkRow(isLinkedInit = book.linkedExternalUri != null,
                    linkedUriInit = book.linkedExternalUri, defaultPath = "",
                    enabled = !busy, onLinkChanged = { uri ->
                        perform {
                            // Resolve the folder only on an explicit Link action, never during composition.
                            val resolved = if (uri == "") {
                                if (inspection) "file:///preview/Linked" else getLinkedFilesDir().toUri().toString()
                            } else uri
                            repository.setLinkedUri(bookId, resolved)
                        }
                    })
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.details_location), style = MaterialTheme.typography.subtitle2)
                    BreadCrumb(folders = folders, fontSize = 16) { }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PaperAction(stringResource(R.string.details_notebook_buttons_move),
                        onClick = { perform { showMove = true } }, modifier = Modifier.weight(1f), enabled = !busy)
                    PaperAction(stringResource(R.string.details_notebook_buttons_export),
                        onClick = { perform { showExport = true } }, modifier = Modifier.weight(1f), enabled = !busy)
                }
                Divider(color = PaperLine)
                PaperAction(stringResource(R.string.details_delete_title),
                    onClick = { perform { showDelete = true } }, enabled = !busy)
            }
        }
    }
}

// Kept for the folder and page dialogs that share this API.
@Composable
fun ActionButton(text: String, onClick: () -> Unit) {
    PaperAction(label = text, onClick = onClick)
}

@Composable
fun NotebookLinkRow(
    isLinkedInit: Boolean,
    linkedUriInit: String?,
    defaultPath: String,
    onLinkChanged: (String?) -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.details_link), style = MaterialTheme.typography.subtitle2)
        Text(linkedUriInit ?: stringResource(R.string.details_unlinked),
            style = MaterialTheme.typography.body2, color = SecondaryInk)
        PaperAction(stringResource(if (isLinkedInit) R.string.details_unlink else R.string.details_link_action),
            onClick = { onLinkChanged(if (isLinkedInit) null else defaultPath) }, enabled = enabled)
    }
}
