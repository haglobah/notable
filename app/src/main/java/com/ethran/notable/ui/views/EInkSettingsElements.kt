package com.ethran.notable.ui.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.ui.components.SettingsDivider

// E-ink styled section, text field and button colors used by the sync settings.

private val EInkFieldShape = RoundedCornerShape(4.dp)
private val EInkFieldBorderWidth = 1.dp

@Composable
fun EInkSection(
    title: String,
    icon: ImageVector,
    isExpandable: Boolean = false,
    isExpanded: Boolean = true,
    onHeaderClick: () -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = isExpandable) { onHeaderClick() }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.subtitle2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colors.onSurface
            )
            if (isExpandable) {
                Icon(
                    if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colors.onSurface
                )
            }
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                content()
            }
        }
        SettingsDivider()
    }
}

@Composable
fun EInkTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colors.onSurface
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface, EInkFieldShape)
                .border(EInkFieldBorderWidth, MaterialTheme.colors.onSurface, EInkFieldShape)
                .padding(12.dp)
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun eInkButtonColors(isSecondary: Boolean = false) = ButtonDefaults.buttonColors(
    backgroundColor = if (isSecondary) MaterialTheme.colors.onSurface.copy(alpha = 0.1f) else MaterialTheme.colors.onSurface,
    contentColor = if (isSecondary) MaterialTheme.colors.onSurface else MaterialTheme.colors.surface,
    disabledBackgroundColor = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
    disabledContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
)
