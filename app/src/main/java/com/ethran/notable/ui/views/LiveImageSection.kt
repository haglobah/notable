package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.image.DefState
import com.ethran.notable.image.ImageService
import com.ethran.notable.image.LiveImage
import com.ethran.notable.image.ReplState
import com.ethran.notable.ui.design.NCodeBlock
import com.ethran.notable.ui.design.NNotice
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NToggleRow
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
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
    val c = NotableTheme.colors

    NSection(
        title = stringResource(R.string.image_title),
        description = stringResource(R.string.image_intro),
        modifier = Modifier.padding(top = NotableSpace.xl),
    ) {
        Column {
            Text(
                stringResource(R.string.image_status, LiveImage.modeText(status.mode), status.loaded, status.failed),
                style = NotableTheme.type.body, color = c.ink,
            )
            status.codeDocError?.let {
                NNotice(
                    stringResource(R.string.image_code_doc_error, it),
                    Modifier.padding(top = NotableSpace.sm),
                    icon = FeatherIcons.AlertTriangle,
                )
            }
            status.defs.values.filterIsInstance<DefState.Failed>().forEach {
                Text(
                    stringResource(R.string.image_failed_def, it.def.name, it.reason),
                    style = NotableTheme.type.meta, color = c.secondary,
                    modifier = Modifier.padding(top = NotableSpace.xs),
                )
            }
            NToggleRow(
                title = stringResource(R.string.image_safe_mode),
                description = stringResource(R.string.image_safe_mode_description),
                checked = safeMode,
                onCheckedChange = {
                    safeMode = it
                    service.safeModeSetting = it
                },
                modifier = Modifier.padding(top = NotableSpace.sm),
            )
            val replDescription = stringResource(R.string.image_repl_description) +
                if (BuildConfig.DEBUG) ". " + stringResource(R.string.image_repl_debug) else ""
            NToggleRow(
                title = stringResource(R.string.image_repl),
                description = replDescription,
                checked = replOn || BuildConfig.DEBUG,
                onCheckedChange = {
                    replOn = it
                    service.replSetting = it
                },
            )
            when (val r = repl) {
                is ReplState.Listening -> SelectionContainer(Modifier.padding(top = NotableSpace.md)) {
                    NCodeBlock(stringResource(R.string.image_repl_listening, r.port, r.token))
                }
                is ReplState.Failed -> NNotice(
                    stringResource(R.string.image_repl_failed, r.reason),
                    Modifier.padding(top = NotableSpace.md),
                    icon = FeatherIcons.AlertTriangle,
                )
                ReplState.Off -> Unit
            }
        }
    }
}
