package com.ethran.notable.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.ui.theme.Ink
import com.ethran.notable.ui.theme.Paper
import com.ethran.notable.ui.theme.PaperLine

/**
 * A rating, 1 to 10, plus Clear (docs/plans/ebook-features.md, round 3): two rows of five
 * 48 dp cells, the chosen one inked. Flat for e-ink.
 */
@Composable
fun RatingPicker(rating: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in listOf(1..5, 6..10)) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in row) {
                val on = n == rating
                val label = stringResource(R.string.rating_set, n)
                Box(
                    Modifier.size(48.dp).background(if (on) Ink else Paper).border(1.dp, if (on) Ink else PaperLine)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            role = Role.RadioButton) { onRate(n) }
                        .semantics { contentDescription = label; selected = on },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$n", style = MaterialTheme.typography.subtitle1, color = if (on) Paper else Ink)
                }
            }
        }
        PaperAction(stringResource(R.string.rating_clear), onClick = { onRate(null) }, enabled = rating != null)
    }
}
