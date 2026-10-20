package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.image.DefState
import com.ethran.notable.image.ImageService
import com.ethran.notable.image.LiveImage
import com.ethran.notable.image.ReplState
import com.ethran.notable.ui.components.SettingToggleRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LiveImageViewModel @Inject constructor(val service: ImageService) : ViewModel()

/** The live code image's status, safe mode and the REPL (ADR-002). */
@Composable
fun LiveImageSection(viewModel: LiveImageViewModel = hiltViewModel()) {
    val service = viewModel.service
    val status by service.image.status.collectAsState()
    val repl by service.repl.collectAsState()
    var safeMode by remember { mutableStateOf(service.safeModeSetting) }
    var replOn by remember { mutableStateOf(service.replSetting) }

    EInkSection(title = stringResource(R.string.image_title), icon = Icons.Default.Code) {
        Column {
            Text(stringResource(R.string.image_intro), style = MaterialTheme.typography.body2)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.image_status, LiveImage.modeText(status.mode), status.loaded, status.failed))
            status.codeDocError?.let { Text(stringResource(R.string.image_code_doc_error, it)) }
            status.defs.values.filterIsInstance<DefState.Failed>().forEach {
                Text(stringResource(R.string.image_failed_def, it.def.name, it.reason), style = MaterialTheme.typography.caption)
            }
            Spacer(Modifier.height(8.dp))
            SettingToggleRow(stringResource(R.string.image_safe_mode), safeMode) {
                safeMode = it
                service.safeModeSetting = it
            }
            SettingToggleRow(stringResource(R.string.image_repl), replOn || BuildConfig.DEBUG) {
                replOn = it
                service.replSetting = it
            }
            if (BuildConfig.DEBUG) Text(stringResource(R.string.image_repl_debug), style = MaterialTheme.typography.caption)
            when (val r = repl) {
                is ReplState.Listening -> SelectionContainer {
                    Text(stringResource(R.string.image_repl_listening, r.port, r.token), style = MaterialTheme.typography.caption)
                }
                is ReplState.Failed -> Text(stringResource(R.string.image_repl_failed, r.reason))
                ReplState.Off -> Unit
            }
        }
    }
}
