package com.ethran.notable.ui.dialogs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.LibraryEdits
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.FolderRepository
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NField
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NotableSpace
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = ShipBook.getLogger("FolderConfig")

@Composable
fun FolderConfigDialog(folderRepository: FolderRepository,
                       edits: LibraryEdits,
                       folderId: String,
                       onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf<Folder?>(null) }
    var folderTitle by remember { mutableStateOf("") }

    LaunchedEffect(folderId) {
        val f = folderRepository.get(folderId)
        if (f == null) {
            io.shipbook.shipbooksdk.Log.e("FolderConfigDialog", "Folder not found")
            onClose()
        } else {
            folder = f
            folderTitle = f.title
        }
    }

    if (folder == null) return

    val focusManager = LocalFocusManager.current
    val nameLabel = stringResource(R.string.details_name)
    val close = {
        log.i("Closing Directory Dialog - upstream")
        // Losing focus saves a changed name (see onFocusChanged below).
        focusManager.clearFocus()
        onClose()
    }

    NDialog(
        title = stringResource(R.string.folder_heading),
        onDismiss = close,
        maxWidth = 480.dp,
        titleAction = { NButton(stringResource(R.string.details_done), close, style = NButtonStyle.Primary) },
    ) {
        NField(nameLabel) {
            NTextField(
                value = folderTitle,
                onValueChange = { folderTitle = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = nameLabel }
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            val currentFolder = folder
                            if (currentFolder != null && currentFolder.title != folderTitle) {
                                val title = folderTitle
                                scope.launch(Dispatchers.IO) {
                                    edits.renameFolder(currentFolder.id, title)
                                }
                            }
                        }
                    }
            )
        }
        Spacer(Modifier.height(NotableSpace.xl))
        NDivider()
        Spacer(Modifier.height(NotableSpace.xl))
        NButton(
            stringResource(R.string.folder_delete),
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { edits.deleteFolder(folderId) }
                    onClose()
                }
            },
            style = NButtonStyle.Danger,
            icon = FeatherIcons.Trash2,
        )
    }
}
