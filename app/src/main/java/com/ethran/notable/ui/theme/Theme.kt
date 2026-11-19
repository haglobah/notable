package com.ethran.notable.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.ui.design.DarkNotableColors
import com.ethran.notable.ui.design.DefaultNotableType
import com.ethran.notable.ui.design.LightNotableColors
import com.ethran.notable.ui.design.LocalNotableColors
import com.ethran.notable.ui.design.LocalNotableType
import com.ethran.notable.ui.design.NotableColors

private fun materialColors(c: NotableColors) = (if (c.isDark) darkColors() else lightColors()).copy(
    primary = c.ink,
    primaryVariant = c.secondary,
    secondary = c.ink,
    secondaryVariant = c.secondary,
    background = c.paper,
    surface = c.paper,
    onPrimary = c.paper,
    onSecondary = c.paper,
    onBackground = c.ink,
    onSurface = c.ink,
    isLight = !c.isDark,
)

@Composable
fun InkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    scaleFactor: Float = GlobalAppSettings.current.uiScale,
    content: @Composable () -> Unit
) {
    val tokens = if (darkTheme) DarkNotableColors else LightNotableColors

    CompositionLocalProvider(
        LocalDensity provides scaledDensity(scaleFactor),
        LocalNotableColors provides tokens,
        LocalNotableType provides DefaultNotableType,
    ) {
        MaterialTheme(
            colors = materialColors(tokens),
            typography = Typography,
            shapes = Shapes,
            content = content
        )
    }
}

/**
 * Device density multiplied by [scale]. Starts from the configuration, not LocalDensity.current,
 * so a nested InkaTheme does not scale twice. fontScale is left as is: sp already follow density.
 */
@Composable
private fun scaledDensity(scale: Float): Density {
    val configuration = LocalConfiguration.current
    return Density(configuration.densityDpi / 160f * scale, configuration.fontScale)
}
