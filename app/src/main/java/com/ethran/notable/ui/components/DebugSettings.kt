package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NSettingRow
import com.ethran.notable.ui.design.NToggleRow
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.PatternLibraryDialog
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight

@Composable
fun DebugSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    goToWelcome: () -> Unit,
    goToSystemInfo: () -> Unit
) {
    var patterns by remember { mutableStateOf(false) }
    Column {
        NSettingRow("Pattern library", description = "Every component of the design system, in each state.") {
            NButton("Open", { patterns = true }, size = NControlSize.Compact)
        }
        if (patterns) PatternLibraryDialog { patterns = false }
        NToggleRow(
            title = "Show welcome screen",
            checked = settings.showWelcome,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(showWelcome = isChecked))
            }
        )
        NSettingRow(
            title = "System information",
            description = "Device, screen, pen and storage details",
            onClick = goToSystemInfo,
        ) { NIcon(FeatherIcons.ChevronRight, tint = NotableTheme.colors.secondary) }
        NToggleRow(
            title = "Show changed area",
            description = "Debug mode: outline the area each redraw touches",
            checked = settings.debugMode,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(debugMode = isChecked))
            }
        )
        NToggleRow(
            title = "Simple rendering for scroll and zoom",
            description = "Uses more resources",
            checked = settings.simpleRendering,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(simpleRendering = isChecked))
            }
        )
        NToggleRow(
            title = "OpenGL rendering for the eraser",
            checked = settings.openGLRendering,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(openGLRendering = isChecked))
            }
        )
        NToggleRow(
            title = "Render PDFs with MuPDF",
            checked = settings.muPdfRendering,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(muPdfRendering = isChecked))
            }
        )
        NToggleRow(
            title = "Allow destructive migrations",
            checked = settings.destructiveMigrations,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(destructiveMigrations = isChecked))
            }
        )
        MemorySettings()
        CrashLogsSettings()
    }
}
