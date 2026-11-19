package com.ethran.notable.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.ui.PageMenu
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.onyx.android.sdk.extension.isNullOrEmpty
import compose.icons.FeatherIcons
import compose.icons.feathericons.FilePlus

private val PageWidth = 112.dp

/**
 * A row of page previews under a section heading. Pages are paper (square, a hairline edge);
 * the current page has the 2 dp ink outline.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowPagesRow(
    appRepository: AppRepository,
    pages: List<Page>?,
    currentPageId: String? = null,
    title: String? = "Quick pages",
    onSelectPage: (String) -> Unit,
    showAddQuickPage: Boolean = false,
    onCreateNewQuickPage: () -> Unit = {},
    onPreviewMissing: (String) -> Unit = {},
) {
    val c = NotableTheme.colors

    if (title != null) {
        Text(text = title, style = NotableTheme.type.heading, color = c.ink)
        Spacer(Modifier.height(NotableSpace.md))
    }

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg),
        modifier = Modifier
            .fillMaxWidth()
            .autoEInkAnimationOnScroll()
    ) {
        // Add the "Add quick page" button
        if (showAddQuickPage) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(PageWidth)
                        .aspectRatio(3f / 4f)
                        .border(NotableSize.hairline, c.rule, NotableShape.paper)
                        .inkClickable { onCreateNewQuickPage() }) {
                    NIcon(FeatherIcons.FilePlus, contentDescription = "Add quick page", size = NotableSize.iconButtonIcon)
                }
            }
        }
        // Render existing pages
        if (!pages.isNullOrEmpty()) {
            items(pages.reversed(), key = { it.id }) { page ->
                val pageId = page.id
                val current = currentPageId == pageId
                var isPageSelected by remember { mutableStateOf(false) }
                Box {
                    PagePreview(
                        modifier = Modifier
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelectPage(pageId) },
                                onLongClick = { isPageSelected = true },
                            )
                            .width(PageWidth)
                            .aspectRatio(3f / 4f)
                            .border(
                                if (current) NotableSize.strong else NotableSize.hairline,
                                if (current) c.ink else c.rule,
                                NotableShape.paper
                            ),
                        pageId = pageId,
                        onPreviewMissing = onPreviewMissing
                    )
                    if (isPageSelected) PageMenu(
                        appRepository = appRepository,
                        pageId = pageId, canDelete = true, onClose = { isPageSelected = false })
                }
            }
        }
    }
}
