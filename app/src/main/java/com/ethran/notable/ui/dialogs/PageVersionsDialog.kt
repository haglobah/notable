package com.ethran.notable.ui.dialogs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import com.ethran.notable.R
import com.ethran.notable.SCREEN_HEIGHT
import com.ethran.notable.SCREEN_WIDTH
import com.ethran.notable.data.db.Stroke
import com.ethran.notable.editor.drawing.AppStrokeRenderer
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NEmptyState
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.viewmodels.PageVersionsUi
import com.ethran.notable.ui.viewmodels.VersionRow
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

/**
 * A page's earlier states (docs/plans/library-features.md, 40), newest first: when, a sketch of
 * its handwriting, and Restore. Versions are recorded on this device before the first edit after
 * ten minutes without one; restoring brings back the handwriting (images stay as they are).
 */
@Composable
fun PageVersionsDialog(state: PageVersionsUi, onRestore: (VersionRow) -> Unit, onClose: () -> Unit) {
    val c = NotableTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    NDialog(
        title = stringResource(R.string.versions_title),
        onDismiss = onClose,
        maxWidth = 640.dp,
        titleAction = { NButton(stringResource(R.string.details_done), onClose, style = NButtonStyle.Primary) },
    ) {
        Text(stringResource(R.string.versions_hint), style = NotableTheme.type.meta, color = c.secondary)
        when {
            state.loading -> Text(stringResource(R.string.book_loading), style = NotableTheme.type.meta, color = c.secondary,
                modifier = Modifier.padding(vertical = NotableSpace.md))
            state.rows.isEmpty() -> NEmptyState(stringResource(R.string.versions_none))
            else -> state.rows.forEach { row ->
                NDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = NotableSpace.sm), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg)) {
                    StrokesSketch(row.strokes.orEmpty(), Modifier.width(96.dp).aspectRatio(3f / 4f)
                        .border(NotableSize.hairline, c.rule, NotableShape.paper))
                    Column(Modifier.weight(1f)) {
                        val at = row.version.at
                        Text(DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
                            style = NotableTheme.type.label, color = c.ink)
                        Text(
                            row.strokes?.let { pluralStringResource(R.plurals.versions_strokes, it.size, it.size) }
                                ?: stringResource(R.string.versions_unreadable),
                            style = NotableTheme.type.meta, color = c.secondary,
                        )
                    }
                    NButton(stringResource(R.string.trash_restore), { onRestore(row) }, size = NControlSize.Compact,
                        enabled = row.strokes != null)
                }
            }
        }
    }
}

/** The strokes drawn small on white, the page's width filling the sketch. */
@Composable
private fun StrokesSketch(strokes: List<Stroke>, modifier: Modifier) {
    val bitmap = remember(strokes) {
        val w = 240
        val h = 320
        val pageWidth = min(SCREEN_WIDTH, SCREEN_HEIGHT).toFloat().coerceAtLeast(1f)
        val bottom = max(strokes.maxOfOrNull { it.bottom } ?: 0f, pageWidth * 4f / 3f)
        val scale = min(w / pageWidth, h / bottom)
        createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            val canvas = Canvas(b)
            canvas.drawColor(Color.WHITE)
            canvas.withScale(scale, scale) { strokes.forEach { AppStrokeRenderer.drawStroke(this, it, Offset.Zero) } }
        }.asImageBitmap()
    }
    Image(bitmap, contentDescription = null, modifier = modifier)
}
