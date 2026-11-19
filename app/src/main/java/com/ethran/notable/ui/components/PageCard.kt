package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.ui.design.NBadge
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.FilePlus
import compose.icons.feathericons.Trash2

@Composable
fun PageCard(
    pageId: String,
    pageIndex: Int,
    isOpen: Boolean,
    isEditMode: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onAddAfter: () -> Unit,
    modifier: Modifier = Modifier,
    touchModifier: Modifier = Modifier,
    isReorderDragging: Boolean = false,
) {
    val c = NotableTheme.colors
    Box(modifier = modifier) {
        // Paper: square corners, a hairline edge; the open page gets the 2 dp ink outline.
        PagePreview(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .border(
                    if (isOpen) NotableSize.strong else NotableSize.hairline,
                    if (isOpen) c.ink else c.rule,
                    NotableShape.paper
                )
                .inkClickable(enabled = isReorderDragging) { onOpen() }
                .then(touchModifier), pageId
        )

        // The page number; filled (inverted) on the open page.
        NBadge(
            text = (pageIndex + 1).toString(),
            filled = isOpen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(NotableSpace.sm)
        )

        if (isEditMode) {
            // Bottom-right actions
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(NotableSpace.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PaperIconButton(FeatherIcons.Trash2, stringResource(R.string.page_menu_delete), onDelete)
                PaperIconButton(FeatherIcons.Copy, stringResource(R.string.page_menu_duplicate), onDuplicate)
                PaperIconButton(FeatherIcons.FilePlus, stringResource(R.string.page_menu_add_after), onAddAfter)
            }
        }
    }
}

/**
 * A compact outlined [NIconButton] on an opaque paper plate, so it reads over the page preview
 * it sits on. Drawn 36 dp, hit area 44 dp.
 */
@Composable
private fun PaperIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(NotableSize.compact)
                .background(NotableTheme.colors.paper, NotableShape.control)
        )
        NIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            outlined = true,
            size = NControlSize.Compact,
        )
    }
}
