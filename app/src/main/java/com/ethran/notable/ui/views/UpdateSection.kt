package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.update.AppUpdates
import com.ethran.notable.update.UpdateState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUpCircle
import compose.icons.feathericons.RefreshCw
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class AppUpdatesViewModel @Inject constructor(val updates: AppUpdates) : ViewModel()

/** Updates from cirdan (docs/plans/self-update.md): the installed build, a newer one, Install. */
@Composable
fun UpdateSection(viewModel: AppUpdatesViewModel = hiltViewModel()) {
    val updates = viewModel.updates
    val state by updates.state.collectAsState()
    LaunchedEffect(Unit) { updates.check() }

    NSection(
        title = stringResource(R.string.update_title),
        description = stringResource(R.string.update_installed, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
            val line = when (val s = state) {
                UpdateState.Unknown -> null
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateState.Available -> stringResource(R.string.update_available, s.release.versionCode, s.release.subject)
                is UpdateState.Downloading -> stringResource(R.string.update_downloading, s.percent)
                is UpdateState.Installing -> stringResource(R.string.update_installing)
                is UpdateState.Failed -> stringResource(R.string.update_failed, s.message)
            }
            if (line != null) Text(line, style = NotableTheme.type.body, color = NotableTheme.colors.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
                val release = when (val s = state) {
                    is UpdateState.Available -> s.release
                    is UpdateState.Failed -> s.release
                    else -> null
                }
                if (release != null) NButton(
                    stringResource(R.string.update_install, release.versionCode),
                    { updates.install(release) },
                    style = NButtonStyle.Primary,
                    icon = FeatherIcons.ArrowUpCircle,
                )
                NButton(
                    stringResource(R.string.update_check),
                    { updates.check(force = true) },
                    style = NButtonStyle.Quiet,
                    icon = FeatherIcons.RefreshCw,
                    enabled = state !is UpdateState.Downloading && state !is UpdateState.Installing,
                )
            }
        }
    }
}
