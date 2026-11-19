package com.ethran.notable.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ethran.notable.R
import com.ethran.notable.editor.JumpBack
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.nSurface
import compose.icons.FeatherIcons
import compose.icons.feathericons.CornerUpLeft
import compose.icons.feathericons.X

/**
 * After a jump (contents, search hit, go to page): "Back to page 12" at the bottom of the page,
 * with a close button (docs/plans/library-features.md, 32). It goes when used, closed, or when the
 * reader turns away from the page the jump landed on.
 */
@Composable
fun JumpBackChip(jump: JumpBack, onBack: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = NotableSpace.xxl), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.nSurface(NotableTheme.colors).padding(horizontal = NotableSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs),
        ) {
            NButton(
                if (jump.fromIndex >= 0) stringResource(R.string.book_back_to_page, jump.fromIndex + 1)
                else stringResource(R.string.book_back),
                onBack, style = NButtonStyle.Quiet, icon = FeatherIcons.CornerUpLeft,
            )
            NIconButton(FeatherIcons.X, stringResource(R.string.book_back_dismiss), onDismiss)
        }
    }
}
