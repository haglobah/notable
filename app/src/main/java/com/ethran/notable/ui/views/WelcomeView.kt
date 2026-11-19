package com.ethran.notable.ui.views

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ethran.notable.PACKAGE_NAME
import com.ethran.notable.R
import com.ethran.notable.editor.utils.getCurRefreshModeString
import com.ethran.notable.editor.utils.isRecommendedRefreshMode
import com.ethran.notable.editor.utils.setRecommendedMode
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NNotice
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.WelcomeViewModel
import com.ethran.notable.utils.hasUsableStorage
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckCircle
import compose.icons.feathericons.Circle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


object WelcomeDestination : NavigationDestination {
    override val route = "welcome"
}

@Composable
fun WelcomeView(
    goToLibrary: () -> Unit = {},
    viewModel: WelcomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var filePermissionGranted by remember { mutableStateOf(hasUsableStorage(context)) }
    var recommendedRefreshMode by remember { mutableStateOf(isRecommendedRefreshMode()) }
    var refreshModeString by remember { mutableStateOf(getCurRefreshModeString()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                filePermissionGranted = hasUsableStorage(context)
                recommendedRefreshMode = isRecommendedRefreshMode()
                refreshModeString = getCurRefreshModeString()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    @RequiresApi(Build.VERSION_CODES.R)
    fun requestManageAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.fromParts("package", PACKAGE_NAME, null)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }

    fun requestPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    context as Activity,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
            }
        } else if (!Environment.isExternalStorageManager()) {
            requestManageAllFilesPermission()
        }
    }


    // Call the stateless content view
    WelcomeContent(
        filePermissionGranted = filePermissionGranted,
        recommendedRefreshMode = recommendedRefreshMode,
        refreshModeString = refreshModeString,
        onFilePermissionRequest = { requestPermissions() },
        onRefreshModeRequest = { setRecommendedMode() },
        onContinue = {
            scope.launch(Dispatchers.IO) {
                viewModel.removeWelcome()
            }
            goToLibrary()
        }
    )
}

@Composable
fun WelcomeContent(
    filePermissionGranted: Boolean,
    recommendedRefreshMode: Boolean,
    refreshModeString: String,
    onFilePermissionRequest: () -> Unit,
    onRefreshModeRequest: () -> Unit,
    onContinue: () -> Unit
) {
    val c = NotableTheme.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper)
    ) {
        NTopBar(title = stringResource(R.string.welcome_view_title))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NotableSpace.gutter, vertical = NotableSpace.xl)
        ) {
            Text(
                stringResource(R.string.welcome_view_subtitle),
                style = NotableTheme.type.body,
                color = c.secondary
            )

            // Pass values and callbacks down
            PermissionsRow(
                isFilePermissionGranted = filePermissionGranted,
                isRecommendedRefreshMode = recommendedRefreshMode,
                refreshModeString = refreshModeString,
                onFileClick = onFilePermissionRequest,
                onRefreshClick = onRefreshModeRequest
            )

            Spacer(Modifier.height(NotableSpace.xxl))
            OnyxUnfreezeInstruction()
            ShowInstructions()
        }

        NDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NotableSpace.gutter, vertical = NotableSpace.md),
            horizontalArrangement = Arrangement.End
        ) {
            NButton(
                text = if (filePermissionGranted)
                    stringResource(R.string.welcome_view_continue)
                else
                    stringResource(R.string.welcome_view_complete_setup_first),
                onClick = onContinue,
                enabled = filePermissionGranted,
                style = NButtonStyle.Primary,
                trailingIcon = FeatherIcons.ArrowRight,
            )
        }
    }
}

@Composable
private fun PermissionsRow(
    isFilePermissionGranted: Boolean,
    isRecommendedRefreshMode: Boolean,
    refreshModeString: String,
    onFileClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    // Two setup steps side by side.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NotableSpace.xl),
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.xl)
    ) {
        PermissionItem(
            title = stringResource(R.string.welcome_view_file_access),
            description = stringResource(R.string.welcome_view_file_access_explanation),
            isGranted = isFilePermissionGranted,
            buttonText = if (isFilePermissionGranted) stringResource(R.string.welcome_view_permissions_granted) else stringResource(
                R.string.welcome_view_permissions_button
            ),
            onClick = onFileClick,
            enabled = !isFilePermissionGranted,
            modifier = Modifier.weight(1f)
        )

        PermissionItem(
            title = stringResource(R.string.welcome_view_refresh_mode),
            description = stringResource(R.string.welcome_view_refresh_mode_details),
            isGranted = isRecommendedRefreshMode,
            buttonText = if (isRecommendedRefreshMode) stringResource(
                R.string.welcome_view_refresh_mode_applied, refreshModeString
            )
            else stringResource(
                R.string.welcome_view_refresh_mode_set_hd_mode, refreshModeString
            ),
            onClick = onRefreshClick,
            enabled = !isRecommendedRefreshMode,
            modifier = Modifier.weight(1f)
        )
    }
}


@Composable
fun OnyxUnfreezeInstruction() {
    val c = NotableTheme.colors
    NNotice(
        title = stringResource(R.string.welcome_view_prevent_frozen),
        text = stringResource(R.string.welcome_view_prevent_frozen_details).trim(),
        icon = FeatherIcons.AlertTriangle,
        actions = {
            Column {
                Text(
                    text = stringResource(R.string.welcome_view_prevent_frozen_instructions_title),
                    style = NotableTheme.type.label,
                    color = c.ink
                )
                Spacer(Modifier.height(NotableSpace.xs))
                Text(
                    text = stringResource(R.string.welcome_view_prevent_frozen_instructions),
                    style = NotableTheme.type.body,
                    color = c.ink
                )
                Spacer(Modifier.height(NotableSpace.sm))
                Text(
                    text = stringResource(R.string.welcome_view_prevent_frozen_recommended),
                    style = NotableTheme.type.meta,
                    color = c.secondary
                )
            }
        }
    )
}

@Composable
fun ShowInstructions() {
    NSection(title = stringResource(R.string.welcome_view_quick_start_title)) {
        // Split into two columns for compactness
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.xl)
        ) {
            // First column: navigation, editing, selection
            Column(Modifier.weight(1f)) {
                InstructionBlock(
                    stringResource(R.string.welcome_view_quick_start_navigation),
                    stringResource(R.string.welcome_view_quick_start_navigation_details)
                )
                Spacer(modifier = Modifier.height(NotableSpace.lg))
                InstructionBlock(
                    stringResource(R.string.welcome_view_quick_start_selection_editing_title),
                    stringResource(R.string.welcome_view_quick_start_selection_editing_details)
                )
            }
            // Second column: extra features
            Column(Modifier.weight(1f)) {
                InstructionBlock(
                    stringResource(R.string.welcome_view_quick_start_tips_title),
                    stringResource(R.string.welcome_view_quick_start_tips_details)
                )
            }
        }
    }
}

@Composable
private fun InstructionBlock(title: String, details: String) {
    val c = NotableTheme.colors
    Text(text = title, style = NotableTheme.type.label, color = c.ink)
    Spacer(Modifier.height(NotableSpace.xs))
    Text(text = details, style = NotableTheme.type.body, color = c.ink)
}

/** A setup step: its state (check or empty circle), what it is for, and the button that does it. */
@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val c = NotableTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NIcon(
                if (isGranted) FeatherIcons.CheckCircle else FeatherIcons.Circle,
                contentDescription = if (isGranted) stringResource(R.string.welcome_view_permissions_granted) else null,
            )
            Spacer(Modifier.width(NotableSpace.sm))
            Text(text = title, style = NotableTheme.type.heading, color = c.ink)
        }
        Text(
            text = description,
            style = NotableTheme.type.meta,
            color = c.secondary,
            modifier = Modifier.padding(top = NotableSpace.xs, bottom = NotableSpace.md)
        )
        NButton(
            text = buttonText,
            onClick = onClick,
            enabled = enabled,
            style = NButtonStyle.Primary,
            icon = if (isGranted) FeatherIcons.Check else null,
        )
    }
}



// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //


@Preview(
    showBackground = true,
    name = "Setup Required"
)
@Composable
fun WelcomePreviewSetup() {
    InkaTheme {
        WelcomeContent(
            filePermissionGranted = false,
            recommendedRefreshMode = false,
            refreshModeString = "Normal",
            onFilePermissionRequest = {},
            onRefreshModeRequest = {},
            onContinue = {}
        )
    }
}

@Preview(
    showBackground = true,
    name = "Ready to Go"
)
@Composable
fun WelcomePreviewReady() {
    InkaTheme {
        WelcomeContent(
            filePermissionGranted = true,
            recommendedRefreshMode = true,
            refreshModeString = "Extreme",
            onFilePermissionRequest = {},
            onRefreshModeRequest = {},
            onContinue = {}
        )
    }
}