package com.ethran.notable.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.utils.DeviceCompat
import com.ethran.notable.ui.design.NSelectRow
import com.ethran.notable.ui.design.NToggleRow
import com.ethran.notable.ui.viewmodels.GestureRowModel

@Composable
fun GesturesSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    listOfGestures: List<GestureRowModel>,
    availableGestures: List<Pair<AppSettings.GestureAction, Any>>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        listOfGestures.forEach { config ->
            GestureSelectorRow(
                title = stringResource(config.titleRes),
                currentAction = config.currentValue,
                onActionSelected = { action -> config.onUpdate(action) },
                availableGestures = availableGestures
            )
        }

        NToggleRow(
            title = stringResource(R.string.enable_quick_nav), checked = settings.enableQuickNav,
            onCheckedChange = { isChecked ->
                onSettingsChange(settings.copy(enableQuickNav = isChecked))
            })

        if (DeviceCompat.isOnyxDevice) {
            NToggleRow(
                title = stringResource(R.string.block_system_gestures),
                description = stringResource(R.string.block_system_gestures_description),
                checked = settings.blockSystemGestures,
                onCheckedChange = { isChecked ->
                    onSettingsChange(settings.copy(blockSystemGestures = isChecked))
                })
        }
    }
}

@Composable
fun GestureSelectorRow(
    title: String,
    currentAction: AppSettings.GestureAction,
    onActionSelected: (AppSettings.GestureAction) -> Unit,
    availableGestures: List<Pair<AppSettings.GestureAction, Any>>
) {
    val options = availableGestures.map { (action, resource) ->
        val label = when (resource) {
            is Int -> stringResource(resource)
            is String -> resource
            else -> resource.toString()
        }
        action to label
    }

    NSelectRow(
        title = title,
        options = options,
        value = currentAction,
        onChange = onActionSelected
    )
}
