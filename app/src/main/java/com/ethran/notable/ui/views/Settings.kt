package com.ethran.notable.ui.views

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ethran.notable.BuildConfig
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.components.DebugSettings
import com.ethran.notable.ui.components.GeneralSettings
import com.ethran.notable.ui.components.GesturesSettings
import com.ethran.notable.ui.components.ToolbarSettings
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NTabs
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.GestureRowModel
import com.ethran.notable.ui.viewmodels.SettingsViewModel
import com.ethran.notable.utils.isNext
import compose.icons.FeatherIcons
import compose.icons.feathericons.Heart
import kotlinx.coroutines.launch

/** Settings content stays readable on a wide tablet: a left-aligned column, not a full-bleed one. */
internal val SettingsMaxWidth = 760.dp


object SettingsDestination : NavigationDestination {
    override val route = "settings"
}

@Composable
fun SettingsView(
    onBack: () -> Unit,
    goToWelcome: () -> Unit,
    goToSystemInfo: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val settings = viewModel.settings

    val versionString = remember {
        "v${BuildConfig.VERSION_NAME}${if (isNext) " [NEXT]" else ""}"
    }

    SettingsContent(
        versionString = versionString,
        settings = settings,
        onBack = onBack,
        goToWelcome = goToWelcome,
        goToSystemInfo = goToSystemInfo,
        onUpdateSettings = { viewModel.updateSettings(it) },
        listOfGestures = viewModel.getGestureRows(),
        availableGestures = viewModel.availableGestures,
    )
}

@Composable
fun SettingsContent(
    versionString: String,
    settings: AppSettings,
    onBack: () -> Unit,
    goToWelcome: () -> Unit,
    goToSystemInfo: () -> Unit,
    onUpdateSettings: (AppSettings) -> Unit,
    selectedTabInitial: Int = 0,
    listOfGestures: List<GestureRowModel> = emptyList(),
    availableGestures: List<Pair<AppSettings.GestureAction, Any>> = emptyList(),
) {
    var selectedTab by remember { mutableIntStateOf(selectedTabInitial) }
    val tabs = listOf(
        stringResource(R.string.settings_tab_general_name),
        stringResource(R.string.settings_tab_gestures_name),
        stringResource(R.string.settings_tab_toolbar_name),
        stringResource(R.string.settings_tab_sync_name),
        stringResource(R.string.settings_tab_debug_name)
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(NotableTheme.colors.paper)
    ) {
        NTopBar(
            title = stringResource(R.string.settings_title),
            onBack = onBack,
            meta = versionString,
        )
        NTabs(
            tabs = tabs,
            selected = selectedTab,
            onSelect = { selectedTab = it },
            modifier = Modifier
                .padding(horizontal = NotableSpace.gutter)
                .widthIn(max = SettingsMaxWidth),
        )

        // The scroll spans the full width (a drag anywhere scrolls); the content is capped.
        val scrollState = rememberScrollState()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
        ) {
            Column(
                Modifier
                    .padding(horizontal = NotableSpace.gutter)
                    .padding(top = NotableSpace.sm, bottom = NotableSpace.xxl)
                    .widthIn(max = SettingsMaxWidth)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> {
                        GeneralSettings(settings, onUpdateSettings)
                        SettingsFooter()
                    }

                    1 -> GesturesSettings(
                        settings, onUpdateSettings, listOfGestures, availableGestures
                    )

                    2 -> ToolbarSettings(settings, onUpdateSettings, scrollState)

                    3 -> {
                        NotableSyncSection()
                        LiveImageSection()
                    }

                    4 -> DebugSettings(settings, onUpdateSettings, goToWelcome, goToSystemInfo)
                }
            }
        }
    }
}

/** Sponsoring: a quiet button under the General settings. */
@Composable
private fun SettingsFooter() {
    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    val scope = rememberCoroutineScope()

    NButton(
        stringResource(R.string.sponsor_button_text),
        onClick = {
            openInBrowser(context, "https://github.com/sponsors/ethran") {
                scope.launch { snackState.displaySnack(SnackConf(text = it, duration = 3000)) }
            }
        },
        modifier = Modifier.padding(top = NotableSpace.xl),
        style = NButtonStyle.Quiet,
        icon = FeatherIcons.Heart,
    )
}


fun openInBrowser(context: Context, uriString: String, onError: (String) -> Unit) {
    val urlIntent = Intent(Intent.ACTION_VIEW, uriString.toUri())
    try {
        context.startActivity(urlIntent)
    } catch (_: ActivityNotFoundException) {
        val message = "No application can handle this request. Please install a web browser."
        Log.w("openInBrowser", message)
        onError(message)
    }
}

// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(showBackground = true)
@Composable
fun SettingsPreviewGeneral() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onUpdateSettings = {},
            selectedTabInitial = 0
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreviewGestures() {
    val dummyRows = listOf(
        GestureRowModel(
            R.string.gestures_double_tap_action, AppSettings.GestureAction.None
        ) { }, GestureRowModel(
            R.string.gestures_two_finger_tap_action,
            AppSettings.GestureAction.Undo
        ) { })
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onUpdateSettings = {},
            selectedTabInitial = 1,
            listOfGestures = dummyRows
        )
    }
}

@Preview(showBackground = true)
@Composable
fun SettingsPreviewDebug() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onUpdateSettings = {},
            selectedTabInitial = 3
        )
    }
}
@Preview(
    name = "Light",
    showBackground = true,
    device = "spec:width=360dp,height=600dp"
)
@Preview(
    name = "Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    device = "spec:width=360dp,height=600dp"
)
@Composable
fun SettingsPreviewSync() {
    InkaTheme {
        SettingsContent(
            versionString = "v1.0.0",
            settings = AppSettings(version = 1),
            onBack = {},
            goToWelcome = {},
            goToSystemInfo = {},
            onUpdateSettings = {},
            selectedTabInitial = 2,
        )
    }
}
