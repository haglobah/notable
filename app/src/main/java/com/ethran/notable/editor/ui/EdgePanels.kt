package com.ethran.notable.editor.ui

import android.text.format.DateUtils
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.ethran.notable.editor.utils.EpdRefreshArbiter
import com.ethran.notable.editor.utils.getThumbnailFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.db.Notebook
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.utils.autoEInkAnimationOnScroll
import com.ethran.notable.ui.components.PagePreview
import com.ethran.notable.ui.components.QuickNavEntryPoint
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NTabs
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.viewmodels.RecentNotebooks
import compose.icons.FeatherIcons
import compose.icons.feathericons.CornerUpLeft
import compose.icons.feathericons.X
import dagger.hilt.EntryPoints
import kotlin.math.roundToInt

/*
 * The panels the edge swipes open (gestures/EdgeSwipeGesture.kt), after reMarkable's drawer of
 * recent files and its quick browse. Each is a full-height sheet on the edge it was swiped in from;
 * a tap beside it closes it.
 */

/** Room for two columns of covers. */
private val SheetWidth = 460.dp

/** Thumbnails a panel shows but that were never rendered are queued for rendering. */
@Composable
private fun rememberRequestPreview(): (String) -> Unit {
    val context = LocalContext.current
    val queue = remember(context) {
        EntryPoints.get(context.applicationContext, QuickNavEntryPoint::class.java).thumbnailBackfillQueue()
    }
    return remember(queue) { { pageId: String -> queue.enqueue(listOf(pageId)) } }
}

/**
 * A full-height sheet on the left or right edge, a strong rule on its inner side; modal. Compose
 * passes a touch on to the layers below wherever nothing on top handles it, so a scrim covers the
 * whole screen (a tap on it closes the sheet) and the sheet takes every touch on itself; otherwise a
 * tap beside a button would land on the toolbar or the page underneath.
 */
@Composable
private fun SideSheet(left: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    val c = NotableTheme.colors
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().inkClickable(role = null, onClick = onClose))
        Row(
            Modifier.fillMaxHeight().align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
                .catchTouches()
        ) {
            val rule = @Composable { Box(Modifier.fillMaxHeight().width(NotableSize.strong).background(c.ink)) }
            if (!left) rule()
            Box(Modifier.width(SheetWidth).fillMaxHeight().background(c.paper)) { content() }
            if (left) rule()
        }
    }
}

/**
 * Takes every touch on this element, so none falls through to the layers below. It only listens:
 * whether it handles a touch is decided where the touch lands, and the controls inside still get
 * their taps, drags and scrolls.
 */
private fun Modifier.catchTouches(): Modifier = pointerInput(Unit) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false) }
}

/**
 * The drawer of recent files, on the left: the notebooks opened last as covers (newest first, each
 * at the page it was left on), and the favorite pages in a second tab. A tap opens one.
 */
@Composable
fun RecentsPanel(
    recent: List<Notebook>,
    favorites: List<Page>,
    currentBookId: String?,
    currentPageId: String?,
    onOpenNotebook: (Notebook) -> Unit,
    onOpenPage: (Page) -> Unit,
    onClose: () -> Unit,
) {
    val requestPreview = rememberRequestPreview()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    SideSheet(left = true, onClose = onClose) {
        Column(Modifier.fillMaxSize().padding(horizontal = NotableSpace.lg, vertical = NotableSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NTabs(
                    tabs = listOf(stringResource(R.string.panel_recent), stringResource(R.string.panel_favorites)),
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(NotableSpace.sm))
                NIconButton(FeatherIcons.X, stringResource(R.string.panel_close), onClick = onClose)
            }
            Spacer(Modifier.height(NotableSpace.lg))
            when (tab) {
                0 -> if (recent.isEmpty()) EmptyLine(stringResource(R.string.panel_no_recent))
                else CoverGrid {
                    itemsIndexed(recent, key = { _, nb -> nb.id }) { _, nb ->
                        Cover(
                            pageId = RecentNotebooks.pageOf(nb),
                            current = nb.id == currentBookId,
                            onPreviewMissing = requestPreview,
                            onClick = { onOpenNotebook(nb) },
                            title = nb.title,
                            meta = nb.readAt?.let { whenOpened(it.time) },
                        )
                    }
                }

                else -> if (favorites.isEmpty()) EmptyLine(stringResource(R.string.panel_no_favorites))
                else CoverGrid {
                    itemsIndexed(favorites.reversed(), key = { _, p -> p.id }) { _, page ->
                        Cover(
                            pageId = page.id,
                            current = page.id == currentPageId,
                            onPreviewMissing = requestPreview,
                            onClick = { onOpenPage(page) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quick browse, on the right: every page of the notebook in two columns, and down the sheet's whole
 * outer edge reMarkable's page slider — a line with a dot at the current page. Drag the dot (or press
 * anywhere on the line) to move through the pages; a preview of the page under the dot rides beside
 * it, and lifting opens that page. Tapping a page opens it too. A ring on the line marks where
 * browsing started; the return button goes back there.
 */
@Composable
fun QuickBrowsePanel(
    pageIds: List<String>,
    currentPageId: String?,
    onOpenPage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val c = NotableTheme.colors
    val density = LocalDensity.current
    val requestPreview = rememberRequestPreview()
    val start = pageIds.indexOf(currentPageId).coerceAtLeast(0)
    var cursor by remember(pageIds, start) { mutableIntStateOf(start) }
    var scrubbing by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    // Open with the current page in view, a row in from the top. Not while scrubbing: every scroll
    // redraws the grid, and the preview beside the dot already shows the page.
    LaunchedEffect(pageIds, start) {
        if (pageIds.isNotEmpty()) gridState.scrollToItem((start - start % 2 - 2).coerceAtLeast(0))
    }
    SideSheet(left = false, onClose = onClose) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val track = with(density) {
                SliderTrack(constraints.maxHeight.toFloat(), SliderInset.toPx(), pageIds.size)
            }
            Column(
                Modifier.fillMaxSize()
                    .padding(start = NotableSpace.lg, end = if (pageIds.size >= 2) SliderWidth else NotableSpace.sm, top = NotableSpace.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (pageIds.isEmpty()) "" else stringResource(R.string.browse_page, cursor + 1, pageIds.size),
                        style = NotableTheme.type.heading, color = c.ink, modifier = Modifier.weight(1f),
                    )
                    if (pageIds.size >= 2) NIconButton(
                        FeatherIcons.CornerUpLeft, stringResource(R.string.browse_return),
                        onClick = { pageIds.getOrNull(start)?.let(onOpenPage) },
                    )
                    NIconButton(FeatherIcons.X, stringResource(R.string.panel_close), onClick = onClose)
                }
                Spacer(Modifier.height(NotableSpace.md))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg),
                    verticalArrangement = Arrangement.spacedBy(NotableSpace.lg),
                    modifier = Modifier.weight(1f).fillMaxWidth().autoEInkAnimationOnScroll(),
                ) {
                    itemsIndexed(pageIds, key = { _, id -> id }) { i, id ->
                        Cover(
                            pageId = id,
                            current = i == cursor,
                            onPreviewMissing = requestPreview,
                            onClick = { onOpenPage(id) },
                            title = (i + 1).toString(),
                            centered = true,
                        )
                    }
                }
            }
            if (pageIds.size >= 2) {
                PageSlider(
                    track = track,
                    start = start,
                    cursor = cursor,
                    pressed = scrubbing,
                    onPress = { scrubbing = it },
                    onCursor = { cursor = it },
                    onRelease = { i -> pageIds.getOrNull(i)?.let(onOpenPage) },
                    modifier = Modifier.align(Alignment.TopEnd).width(SliderWidth).fillMaxHeight(),
                )
                if (scrubbing) pageIds.getOrNull(cursor)?.let { id ->
                    var cardHeight by remember { mutableIntStateOf(0) }
                    SliderPreview(
                        pageId = id,
                        number = cursor + 1,
                        onPreviewMissing = requestPreview,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = SliderWidth)
                            .onSizeChanged { cardHeight = it.height }
                            .offset {
                                val top = track.centerOf(cursor) - cardHeight / 2f
                                IntOffset(0, top.coerceIn(0f, (track.heightPx - cardHeight).coerceAtLeast(0f)).roundToInt())
                            },
                    )
                }
            }
        }
    }
}

/** The page slider's strip along the sheet's outer edge: wide enough to grab with a finger. */
private val SliderWidth = 56.dp

/** Room above and below the line, so the dot at either end isn't cut off. */
private val SliderInset = 24.dp

/** The slider's geometry: page [count] spread evenly down a line [insetPx] in from both ends. */
private class SliderTrack(val heightPx: Float, private val insetPx: Float, private val count: Int) {
    val lengthPx = (heightPx - 2 * insetPx).coerceAtLeast(1f)

    /** The page whose spot on the line is nearest [y]. */
    fun indexAt(y: Float): Int {
        if (count < 2) return 0
        val frac = ((y - insetPx) / lengthPx).coerceIn(0f, 1f)
        return (frac * (count - 1)).roundToInt()
    }

    /** Where page [i] sits on the line, from the top. */
    fun centerOf(i: Int): Float =
        if (count < 2) insetPx else insetPx + lengthPx * i / (count - 1)
}

/**
 * reMarkable's page slider: a line as long as the sheet, a dot at the cursor (larger while held),
 * a ring where browsing started. Pressing anywhere on the strip moves the dot there; dragging moves
 * it page by page; lifting opens the page. The screen refreshes in fast mode while it is held.
 */
@Composable
private fun PageSlider(
    track: SliderTrack,
    start: Int,
    cursor: Int,
    pressed: Boolean,
    onPress: (Boolean) -> Unit,
    onCursor: (Int) -> Unit,
    onRelease: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = NotableTheme.colors
    val trackNow by rememberUpdatedState(track)
    val onPressNow by rememberUpdatedState(onPress)
    val onCursorNow by rememberUpdatedState(onCursor)
    val onReleaseNow by rememberUpdatedState(onRelease)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                down.consume()
                val fast = EpdRefreshArbiter.acquire("page-slider")
                onPressNow(true)
                var index = trackNow.indexAt(down.position.y)
                onCursorNow(index)
                var lifted = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        change.consume()
                        if (!change.pressed) {
                            lifted = true
                            break
                        }
                        val next = trackNow.indexAt(change.position.y)
                        if (next != index) {
                            index = next
                            onCursorNow(index)
                        }
                    }
                } finally {
                    fast.releaseAfterMillis(300)
                    onPressNow(false)
                }
                if (lifted) onReleaseNow(index)
            }
        },
    ) {
        val dot = if (pressed) 30.dp else 22.dp
        val ring = 14.dp
        // The line.
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, track.centerOf(0).roundToInt()) }
                .width(NotableSize.strong)
                .height(with(LocalDensity.current) { track.lengthPx.toDp() })
                .background(c.ink)
        )
        // Where browsing started.
        if (start != cursor) Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (track.centerOf(start) - ring.toPx() / 2).roundToInt()) }
                .size(ring)
                .background(c.paper, NotableShape.pill)
                .border(NotableSize.strong, c.ink, NotableShape.pill)
        )
        // The dot at the cursor.
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (track.centerOf(cursor) - dot.toPx() / 2).roundToInt()) }
                .size(dot)
                .background(c.ink, NotableShape.pill)
                .border(NotableSize.strong, c.paper, NotableShape.pill)
        )
    }
}

/**
 * The page under the slider's dot, beside it: its thumbnail and number. Decoded straight from the
 * thumbnail file into a small cache, and the last page stays up until the next is ready, so dragging
 * fast never flashes blank.
 */
@Composable
private fun SliderPreview(
    pageId: String,
    number: Int,
    onPreviewMissing: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = NotableTheme.colors
    val context = LocalContext.current
    val cache = remember { LruCache<String, ImageBitmap>(48) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageId) {
        val cached = cache.get(pageId)
        if (cached != null) {
            shown = cached
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) { decodeThumbnail(getThumbnailFile(context, pageId)) }
        if (bitmap == null) {
            onPreviewMissing(pageId)
            shown = null
        } else {
            cache.put(pageId, bitmap)
            shown = bitmap
        }
    }
    Column(
        modifier
            .padding(end = NotableSpace.sm)
            .width(PreviewWidth)
            .background(c.paper, NotableShape.surface)
            .border(NotableSize.strong, c.ink, NotableShape.surface)
            .padding(NotableSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(c.paper)
                .border(NotableSize.hairline, c.rule)
        ) {
            shown?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            number.toString(), style = NotableTheme.type.heading, color = c.ink,
            modifier = Modifier.padding(top = NotableSpace.xs),
        )
    }
}

private val PreviewWidth = 200.dp

/** A thumbnail file decoded at about the preview's width; null if it isn't there (yet). */
private fun decodeThumbnail(file: File): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 400) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}

/** Two columns of covers, scrolling down. */
@Composable
private fun CoverGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(NotableSpace.lg),
        verticalArrangement = Arrangement.spacedBy(NotableSpace.xl),
        modifier = Modifier.fillMaxSize().autoEInkAnimationOnScroll(),
        content = content,
    )
}

/** A page as paper: its preview with a hairline edge (the current one outlined in ink), a caption. */
@Composable
private fun Cover(
    pageId: String,
    current: Boolean,
    onPreviewMissing: (String) -> Unit,
    onClick: () -> Unit,
    title: String? = null,
    meta: String? = null,
    centered: Boolean = false,
) {
    val c = NotableTheme.colors
    Column(
        Modifier.fillMaxWidth().inkClickable(onClick = onClick),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).background(c.paper, NotableShape.paper)) {
            PagePreview(
                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                pageId = pageId,
                onPreviewMissing = onPreviewMissing,
            )
            Box(
                Modifier.matchParentSize().border(
                    if (current) NotableSize.strong else NotableSize.hairline,
                    if (current) c.ink else c.rule,
                    NotableShape.paper,
                )
            )
        }
        if (title != null) Text(
            title, style = NotableTheme.type.label, color = c.ink, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = NotableSpace.sm),
        )
        if (meta != null) Text(
            meta, style = NotableTheme.type.meta, color = c.secondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = NotableSpace.xxs),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text, style = NotableTheme.type.body, color = NotableTheme.colors.secondary,
        modifier = Modifier.padding(vertical = NotableSpace.xl),
    )
}

private fun whenOpened(at: Long): String = DateUtils.getRelativeTimeSpanString(
    at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()
