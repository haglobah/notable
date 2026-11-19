package com.ethran.notable.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.font.FontFamily
import com.ethran.notable.ui.design.DefaultNotableType as T

// Material roles mapped onto the design system's type roles (ui/design/Tokens.kt).
val Typography = Typography(
    defaultFontFamily = FontFamily.SansSerif,
    h4 = T.display,
    h5 = T.title,
    h6 = T.heading,
    subtitle1 = T.body.copy(fontWeight = T.label.fontWeight),
    subtitle2 = T.label,
    body1 = T.body,
    body2 = T.body.copy(fontSize = T.label.fontSize, lineHeight = T.label.lineHeight),
    button = T.label,
    caption = T.meta,
)
