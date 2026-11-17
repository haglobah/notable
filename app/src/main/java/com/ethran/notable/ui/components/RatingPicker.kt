package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.ethran.notable.R
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable

/**
 * A rating, 1 to 10, plus Clear (docs/plans/ebook-features.md, round 3): two segmented strips of
 * five 44 dp cells, the chosen one inked, and a quiet "Clear rating". Flat for e-ink.
 */
@Composable
fun RatingPicker(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        for (row in listOf(1..5, 6..10)) RatingStrip(row, rating, onRate)
        NButton(stringResource(R.string.rating_clear), onClick = { onRate(null) }, style = NButtonStyle.Quiet,
            size = NControlSize.Compact, enabled = rating != null)
    }
}

/** Like [com.ethran.notable.ui.design.NSegmented], with square cells and a description per cell. */
@Composable
private fun RatingStrip(values: IntRange, rating: Int?, onRate: (Int?) -> Unit) {
    val c = NotableTheme.colors
    Row(
        Modifier.height(NotableSize.control).width(IntrinsicSize.Max)
            .clip(NotableShape.control).border(NotableSize.hairline, c.ink, NotableShape.control),
    ) {
        values.forEachIndexed { i, n ->
            val on = n == rating
            val label = stringResource(R.string.rating_set, n)
            if (i > 0) Box(Modifier.width(NotableSize.hairline).fillMaxHeight().background(c.ink))
            Box(
                Modifier.size(NotableSize.control).background(if (on) c.ink else c.paper)
                    .inkClickable(role = Role.RadioButton) { onRate(n) }
                    .semantics { contentDescription = label; selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text("$n", style = NotableTheme.type.label, color = if (on) c.paper else c.ink)
            }
        }
    }
}
