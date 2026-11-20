package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.ui.design.NSelectRow
import com.ethran.notable.ui.design.NSliderRow
import com.ethran.notable.ui.design.NToggleRow
import com.ethran.notable.navigation.LinkFormat


@Composable
fun GeneralSettings(
    settings: AppSettings, onSettingsChange: (AppSettings) -> Unit
) {
    Column {
        NSelectRow(
            title = stringResource(R.string.default_page_background_template), options = listOf(
                "blank" to stringResource(R.string.blank_page),
                "dotted" to stringResource(R.string.dot_grid),
                "lined" to stringResource(R.string.lines),
                "squared" to stringResource(R.string.small_squares_grid),
                "hexed" to stringResource(R.string.hexagon_grid),
            ), value = settings.defaultNativeTemplate, onChange = {
                onSettingsChange(settings.copy(defaultNativeTemplate = it))
            })
        NSelectRow(
            title = stringResource(R.string.toolbar_position), options = listOf(
                AppSettings.Position.Top to stringResource(R.string.toolbar_position_top),
                AppSettings.Position.Bottom to stringResource(R.string.toolbar_position_bottom)
            ), value = settings.toolbarPosition, onChange = { newPosition ->
                onSettingsChange(settings.copy(toolbarPosition = newPosition))
            })
        NSelectRow(
            title = stringResource(R.string.link_format), options = listOf(
                LinkFormat.Plain to "Plain",
                LinkFormat.Markdown to "Markdown",
                LinkFormat.Org to "Org",
            ), value = settings.linkFormat, onChange = {
                onSettingsChange(settings.copy(linkFormat = it))
            })

        NToggleRow(
            title = stringResource(R.string.enable_scribble_to_erase),
            description = stringResource(R.string.enable_scribble_to_erase_description),
            checked = settings.scribbleToEraseEnabled,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(scribbleToEraseEnabled = isChecked))
            })

        NToggleRow(
            title = stringResource(R.string.enable_smooth_scrolling),
            checked = settings.smoothScroll,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(smoothScroll = isChecked))
            })

        NToggleRow(
            title = stringResource(R.string.continuous_zoom),
            checked = settings.continuousZoom,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(continuousZoom = isChecked))
            })
        NToggleRow(
            title = stringResource(R.string.continuous_stroke_slider),
            checked = settings.continuousStrokeSlider,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(continuousStrokeSlider = isChecked))
            })
        NToggleRow(
            title = stringResource(R.string.steady_rotation),
            description = stringResource(R.string.steady_rotation_description),
            checked = settings.steadyRotation,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(steadyRotation = isChecked))
            })
        NToggleRow(
            title = stringResource(R.string.rename_on_create),
            checked = settings.renameOnCreate,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(renameOnCreate = isChecked))
            })

        // Zoom level (UI scale)
        NSliderRow(
            title = stringResource(R.string.uiScale),
            value = settings.uiScale,
            valueRange = 0.5f..1.5f,
            steps = 9,
            onValueChangeFinished = { newValue ->
                onSettingsChange(settings.copy(uiScale = newValue))
            }
        )
    }
}
