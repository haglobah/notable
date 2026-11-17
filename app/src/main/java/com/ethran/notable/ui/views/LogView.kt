package com.ethran.notable.ui.views

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NCheckbox
import com.ethran.notable.ui.design.NCodeBlock
import com.ethran.notable.ui.design.NField
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.theme.InkaTheme
import com.ethran.notable.ui.viewmodels.BugReportUiState
import com.ethran.notable.ui.viewmodels.BugReportViewModel
import com.onyx.android.sdk.utils.ClipboardUtils.copyToClipboard
import java.net.URLEncoder



object BugReportDestination : NavigationDestination {
    override val route = "bugReport"
}

@Composable
fun BugReportScreen(
    goBack: () -> Unit, viewModel: BugReportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun submitBugReport() {
        try {
            val title = viewModel.getIssueTitle()
            val body = uiState.finalMarkdown
            val url = "https://github.com/ethran/notable/issues/new?" +
                    "title=${URLEncoder.encode("Bug: $title", "UTF-8")}" +
                    "&body=${URLEncoder.encode(body, "UTF-8")}"

            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to submit report", Toast.LENGTH_LONG).show()
        }
    }

    fun copyReportToClipboard() {
        copyToClipboard(context, uiState.finalMarkdown)
        Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    BugReportScreenContent(
        uiState = uiState,
        onBack = goBack,
        onDescriptionChange = viewModel::onDescriptionChange,
        onIncludeLogsChange = viewModel::onIncludeLogsToggle,
        onTagChange = viewModel::onTagToggle,
        onIncludeLibrariesChange = viewModel::onIncludeLibrariesToggle,
        onCopyClick = ::copyReportToClipboard,
        onSubmitClick = ::submitBugReport
    )
}


// 2. STATELESS UI
@Composable
fun BugReportScreenContent(
    uiState: BugReportUiState,
    onBack: () -> Unit,
    onDescriptionChange: (String) -> Unit,
    onIncludeLogsChange: (Boolean) -> Unit,
    onTagChange: (String, Boolean) -> Unit,
    onIncludeLibrariesChange: (Boolean) -> Unit,
    onCopyClick: () -> Unit,
    onSubmitClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val c = NotableTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.paper)
    ) {
        NTopBar(title = "Report an issue", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = NotableSpace.gutter)
                .padding(top = NotableSpace.xl, bottom = NotableSpace.xl)
                .widthIn(max = SettingsMaxWidth)
                .fillMaxWidth()
        ) {
            NField("Issue description") {
                NTextField(
                    value = uiState.description, onValueChange = onDescriptionChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                )
            }

            CheckRow("Include diagnostic logs", uiState.includeLogs, onIncludeLogsChange,
                Modifier.padding(top = NotableSpace.md))

            if (uiState.includeLogs) {
                Text(
                    "Include logs from",
                    style = NotableTheme.type.meta,
                    color = c.secondary,
                    modifier = Modifier.padding(top = NotableSpace.sm)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg),
                ) {
                    uiState.availableTags.forEach { tag ->
                        CheckRow(tag, uiState.selectedTags[tag] == true, { checked -> onTagChange(tag, checked) })
                    }
                    CheckRow("Library logs", uiState.includeLibrariesLogs, onIncludeLibrariesChange)
                }
            }

            ReportPreview(
                uiState = uiState,
                modifier = Modifier
                    .padding(top = NotableSpace.lg)
                    .weight(1f) // Grow to fill space above buttons
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = NotableSpace.lg),
                horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm, Alignment.End)
            ) {
                NButton("Copy report", onClick = onCopyClick)
                NButton(
                    "Submit via GitHub",
                    onClick = onSubmitClick,
                    style = NButtonStyle.Primary,
                    enabled = uiState.description.isNotBlank() && !uiState.isGenerating
                )
            }
        }
    }
}

/** A check box with its label; the whole row toggles it. */
@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .heightIn(min = NotableSize.control)
            .toggleable(
                value = checked, role = Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onValueChange = onCheckedChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NCheckbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(NotableSpace.md))
        Text(label, style = NotableTheme.type.body, color = NotableTheme.colors.ink)
    }
}

@Composable
private fun ReportPreview(
    uiState: BugReportUiState,
    modifier: Modifier = Modifier
) {
    val c = NotableTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .border(NotableSize.hairline, c.rule, NotableShape.surface)
            .padding(NotableSpace.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Report preview", style = NotableTheme.type.heading, color = c.ink)
            // No spinner on e-ink: a still word says the same without redrawing.
            if (uiState.isGenerating) Text(
                "Generating…", style = NotableTheme.type.meta, color = c.secondary,
                modifier = Modifier.padding(start = NotableSpace.md)
            )
        }

        PreviewLabel("Description")
        Text(
            uiState.description.ifBlank { "No description yet" },
            style = NotableTheme.type.body,
            color = if (uiState.description.isBlank()) c.secondary else c.ink,
        )

        PreviewLabel("Device info")
        Text(uiState.deviceInfo, style = NotableTheme.type.body, color = c.ink)

        if (uiState.includeLogs) {
            PreviewLabel("Logs")
            NCodeBlock(uiState.formattedLogs, Modifier.weight(1f), maxHeight = Dp.Infinity)
        }
    }
}

@Composable
private fun PreviewLabel(text: String) {
    Text(
        text,
        style = NotableTheme.type.label,
        color = NotableTheme.colors.secondary,
        modifier = Modifier.padding(top = NotableSpace.md, bottom = NotableSpace.xs)
    )
}

// 3. PREVIEWS
@Preview(showBackground = true, name = "Report Screen - Logs Enabled")
@Composable
fun BugReportScreenPreview() = InkaTheme {
    BugReportScreenContent(
        uiState = BugReportUiState(
            description = "App crashes when I click the favorite button.",
            includeLogs = true,
            isGenerating = false,
            deviceInfo = "• Device: Google Pixel 7\n• Memory: 150MB used",
            formattedLogs = "🟢 12-01 10:15 PageDataManager: Saved successfully\n🔴 12-01 10:16 GestureReceiver: Crash on tap"
        ),
        onBack = {},
        onDescriptionChange = {},
        onIncludeLogsChange = {},
        onTagChange = { _, _ -> },
        onIncludeLibrariesChange = {},
        onCopyClick = {},
        onSubmitClick = {})
}