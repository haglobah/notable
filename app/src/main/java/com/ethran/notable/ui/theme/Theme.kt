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

private val DarkColorPalette = darkColors(
    primary = Paper,
    primaryVariant = PaperLine,
    secondary = Paper,
    background = Ink,
    surface = Ink,
    onPrimary = Ink,
    onSecondary = Ink,
    onBackground = Paper,
    onSurface = Paper
)

private val LightColorPalette = lightColors(
    primary = Ink,
    primaryVariant = SecondaryInk,
    secondary = Ink,
    background = Paper,
    surface = Paper,
    onPrimary = Paper,
    onSecondary = Paper,
    onBackground = Ink,
    onSurface = Ink
)

@Composable
fun InkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    scaleFactor: Float = GlobalAppSettings.current.uiScale,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColorPalette else LightColorPalette

    CompositionLocalProvider(LocalDensity provides scaledDensity(scaleFactor)) {
        MaterialTheme(
            colors = colors,
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
