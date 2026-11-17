package com.ethran.notable.ui.components

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.editor.ui.toolbar.model.IconRef
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElementId
import com.ethran.notable.editor.ui.toolbar.model.ToolbarElements
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayout
import com.ethran.notable.editor.ui.toolbar.model.ToolbarLayoutFile
import com.ethran.notable.editor.ui.toolbar.model.ToolbarPen
import com.ethran.notable.editor.utils.Pen
import com.ethran.notable.ui.LocalSnackContext
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.design.nColorMark
import com.ethran.notable.ui.design.LightNotableColors
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NConfirmDialog
import com.ethran.notable.ui.design.NControlSize
import com.ethran.notable.ui.design.NDialog
import com.ethran.notable.ui.design.NDivider
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSize
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.theme.InkaTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Download
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import android.graphics.Color as AndroidColor

/**
 * The "Toolbar" settings panel (design doc §5.2): a live preview, the two layout zones
 * plus a "Hidden" section as one long-press-drag reorderable list, pen preset
 * create/edit/delete, add-divider, and reset.
 *
 * Every commit writes a **concrete** layout (never null), materializing
 * [ToolbarLayout.DEFAULT] on first edit — otherwise editing the default presets while
 * `toolbarLayout == null` could silently drop buttons whose seed ids disappeared.
 */
@Composable
fun ToolbarSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    /** The settings page's own scroll — auto-scrolled when a drag nears the screen edge. */
    pageScrollState: ScrollState = rememberScrollState(),
) {
    val pens = settings.toolbarPens
    val layout = (settings.toolbarLayout ?: ToolbarLayout.DEFAULT).validated(pens)

    fun commit(newLayout: ToolbarLayout, newPens: List<ToolbarPen> = pens) {
        onSettingsChange(
            settings.copy(toolbarLayout = newLayout.validated(newPens), toolbarPens = newPens)
        )
    }

    var addPenOpen by remember { mutableStateOf(false) }
    var editedPresetId by remember { mutableStateOf<String?>(null) }
    // Decoded but not yet applied — importing replaces the layout *and* every pen
    // preset irreversibly, so it waits behind a confirmation dialog.
    var pendingImport by remember { mutableStateOf<ToolbarLayoutFile.ImportResult?>(null) }

    val context = LocalContext.current
    val snackState = LocalSnackContext.current
    fun snack(text: String) = snackState.showOrUpdateSnack(SnackConf(text = text, duration = 3000))

    // Resolved at composition — the launcher callbacks can't call stringResource.
    // Format args are applied with the configuration locale, not the JVM default.
    val locale = LocalConfiguration.current.locales[0]
    val exportDoneMsg = stringResource(R.string.toolbar_settings_export_done)
    val exportFailedMsg = stringResource(R.string.toolbar_settings_export_failed)
    val importDoneMsg = stringResource(R.string.toolbar_settings_import_done)
    val importDroppedMsg = stringResource(R.string.toolbar_settings_import_dropped)
    val importFailedMsg = stringResource(R.string.toolbar_settings_import_failed)

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(ToolbarLayoutFile.encode(layout, pens).toByteArray())
            } ?: error("Cannot open the selected file")
            snack(exportDoneMsg)
        } catch (e: Exception) {
            // CreateDocument already created the target — don't leave an empty .json
            // behind to contradict the failure message.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            snack(String.format(locale, exportFailedMsg, e.message ?: ""))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: error("Cannot open the selected file")
            pendingImport = ToolbarLayoutFile.decode(text)
        } catch (e: Exception) {
            snack(String.format(locale, importFailedMsg, e.message ?: ""))
        }
    }

    pendingImport?.let { result ->
        NConfirmDialog(
            title = stringResource(R.string.toolbar_settings_import_confirm_title),
            message = stringResource(R.string.toolbar_settings_import_confirm_message),
            confirmText = stringResource(R.string.toolbar_settings_import_confirm),
            cancelText = stringResource(android.R.string.cancel),
            destructive = true,
            onConfirm = {
                onSettingsChange(
                    settings.copy(toolbarLayout = result.layout, toolbarPens = result.pens)
                )
                snack(
                    if (result.droppedCount == 0) importDoneMsg
                    else String.format(locale, importDroppedMsg, result.droppedCount)
                )
                pendingImport = null
            },
            onDismiss = { pendingImport = null },
        )
    }

    Column {
        ToolbarPreview(layout, pens)

        Text(
            text = stringResource(R.string.toolbar_settings_drag_hint),
            style = NotableTheme.type.meta,
            color = NotableTheme.colors.secondary,
            modifier = Modifier.padding(top = NotableSpace.md, bottom = NotableSpace.xs)
        )

        ReorderableElementList(
            layout = layout,
            pens = pens,
            pageScrollState = pageScrollState,
            onLayoutChange = { commit(it) },
            onEditPen = { editedPresetId = it },
            onDeletePen = { presetId ->
                val entry = ToolbarPen.LAYOUT_PREFIX + presetId
                commit(
                    ToolbarLayout(
                        layout.scrollable.filterNot { it == entry },
                        layout.pinned.filterNot { it == entry },
                    ),
                    pens.filterNot { it.id == presetId },
                )
            },
            onDeleteDivider = { zone, index ->
                fun List<String>.dropAt(i: Int) = filterIndexed { idx, _ -> idx != i }
                commit(
                    if (zone == Zone.SCROLLABLE)
                        layout.copy(scrollable = layout.scrollable.dropAt(index))
                    else layout.copy(pinned = layout.pinned.dropAt(index))
                )
            },
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NotableSpace.xl),
            horizontalArrangement = Arrangement.spacedBy(NotableSpace.sm),
            verticalArrangement = Arrangement.spacedBy(NotableSpace.sm),
        ) {
            NButton(
                stringResource(R.string.toolbar_settings_add_pen),
                onClick = { addPenOpen = true },
                icon = FeatherIcons.Plus,
            )
            NButton(
                stringResource(R.string.toolbar_settings_add_divider),
                onClick = {
                    commit(layout.copy(scrollable = layout.scrollable + ToolbarElementId.DIVIDER.name))
                },
                icon = FeatherIcons.Plus,
            )
            NButton(
                stringResource(R.string.toolbar_settings_export),
                onClick = { exportLauncher.launch("notable-toolbar.json") },
                icon = FeatherIcons.Upload,
            )
            NButton(
                stringResource(R.string.toolbar_settings_import),
                // OpenDocument filters on MIME; some file managers report .json as
                // text/plain or octet-stream, so accept broadly and validate content.
                onClick = {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                },
                icon = FeatherIcons.Download,
            )
        }
        NButton(
            stringResource(R.string.toolbar_settings_reset),
            onClick = {
                onSettingsChange(
                    settings.copy(toolbarLayout = null, toolbarPens = ToolbarPen.DEFAULT_PENS)
                )
            },
            modifier = Modifier.padding(top = NotableSpace.xl),
            style = NButtonStyle.Danger,
            icon = FeatherIcons.RotateCcw,
        )
    }

    if (addPenOpen) {
        AddPenDialog(
            onPick = { pen ->
                val preset = ToolbarPen(
                    id = ToolbarPen.newId(),
                    pen = pen,
                    color = if (pen == Pen.MARKER) AndroidColor.LTGRAY else AndroidColor.BLACK,
                    size = if (pen == Pen.MARKER) 40f else 5f,
                )
                commit(
                    layout.copy(scrollable = layout.scrollable + preset.layoutEntry),
                    pens + preset,
                )
                addPenOpen = false
                editedPresetId = preset.id
            },
            onClose = { addPenOpen = false },
        )
    }

    settings.toolbarPens.find { it.id == editedPresetId }?.let { preset ->
        PenEditDialog(
            preset = preset,
            onChange = { updated ->
                commit(layout, pens.map { if (it.id == updated.id) updated else it })
            },
            onClose = { editedPresetId = null },
        )
    }
}

// ---------------------------------------------------------------------------
// Reorderable list
// ---------------------------------------------------------------------------

private enum class Zone { SCROLLABLE, PINNED, HIDDEN }

private sealed interface ToolbarRow {
    data class SectionHeader(val zone: Zone) : ToolbarRow

    /** A layout entry string: a [ToolbarElementId] name or `"PEN:<id>"`. */
    data class Element(val entry: String) : ToolbarRow
}

private val ROW_HEIGHT = 48.dp

private fun buildRows(layout: ToolbarLayout, pens: List<ToolbarPen>): List<ToolbarRow> {
    val placed = (layout.scrollable + layout.pinned).toSet()
    // Hidden = placeable statics absent from the layout, plus pen presets whose layout
    // entry was dragged out (the preset survives so it can be dragged back or deleted).
    val hidden = ToolbarElementId.entries.filter {
        it != ToolbarElementId.TOGGLE && it != ToolbarElementId.PEN &&
                it != ToolbarElementId.DIVIDER && it.name !in placed
    }.map { it.name } + pens.map { it.layoutEntry }.filter { it !in placed }

    return buildList {
        add(ToolbarRow.SectionHeader(Zone.SCROLLABLE))
        layout.scrollable.forEach { add(ToolbarRow.Element(it)) }
        add(ToolbarRow.SectionHeader(Zone.PINNED))
        layout.pinned.forEach { add(ToolbarRow.Element(it)) }
        add(ToolbarRow.SectionHeader(Zone.HIDDEN))
        hidden.forEach { add(ToolbarRow.Element(it)) }
    }
}

/** Rebuilds a layout from row order: entries belong to the section header above them. */
private fun rowsToLayout(rows: List<ToolbarRow>): ToolbarLayout {
    var zone = Zone.SCROLLABLE
    val scrollable = mutableListOf<String>()
    val pinned = mutableListOf<String>()
    for (row in rows) when (row) {
        is ToolbarRow.SectionHeader -> zone = row.zone
        is ToolbarRow.Element -> when (zone) {
            Zone.SCROLLABLE -> scrollable.add(row.entry)
            Zone.PINNED -> pinned.add(row.entry)
            Zone.HIDDEN -> Unit // hidden = absent from the layout
        }
    }
    return ToolbarLayout(scrollable, pinned)
}

/** The zone the row at [index] belongs to: the nearest section header above it. */
private fun zoneAt(rows: List<ToolbarRow>, index: Int): Zone {
    for (i in index downTo 0) (rows[i] as? ToolbarRow.SectionHeader)?.let { return it.zone }
    return Zone.SCROLLABLE
}

/**
 * All three sections rendered as one column so a single drag can reorder within a zone
 * *and* move across zones (an entry dragged past a header changes section). Slot-based:
 * rows swap in whole [ROW_HEIGHT] steps rather than free-floating — e-ink friendly, per
 * the design doc. Plain Column (not lazy): ~25 fixed-height rows, inside Settings' scroll.
 *
 * The drag detector sits on the whole Column, not on the rows: every slot swap
 * recomposes the list, and a per-row pointerInput would be re-keyed (its gesture
 * cancelled) the moment its row's index changed under the finger. All rows —
 * headers included — are exactly [ROW_HEIGHT] tall so `y / height` is the row index.
 */
@Composable
private fun ReorderableElementList(
    layout: ToolbarLayout,
    pens: List<ToolbarPen>,
    pageScrollState: ScrollState,
    onLayoutChange: (ToolbarLayout) -> Unit,
    onEditPen: (String) -> Unit,
    onDeletePen: (String) -> Unit,
    onDeleteDivider: (Zone, Int) -> Unit,
) {
    // Local order during a drag; rebuilt whenever the persisted settings change.
    var rows by remember(layout, pens) { mutableStateOf(buildRows(layout, pens)) }
    var draggingIndex by remember(layout, pens) { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    // -1 = page scrolls up, +1 = down, 0 = idle; set while a drag holds near a screen edge.
    var edgeScrollDir by remember { mutableIntStateOf(0) }
    var columnCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val snackState = LocalSnackContext.current
    val menuLockedMsg = stringResource(R.string.toolbar_settings_menu_locked)
    // Fixed id: repeated attempts refresh the one snack instead of stacking copies.
    fun menuLockedSnack() = snackState.showOrUpdateSnack(
        SnackConf(id = "toolbar-menu-locked", text = menuLockedMsg, duration = 3000)
    )

    /** One-slot move of the dragged row; false when blocked (list end, or MENU into
     * Hidden — the validator would silently snap it back, so refuse the drop instead). */
    fun moveDragged(step: Int): Boolean {
        val from = draggingIndex ?: return false
        val to = from + step
        // Row 0 is the Scrollable header; nothing may move above it.
        if (to < 1 || to > rows.lastIndex) return false
        val moved = rows.toMutableList().apply { add(to, removeAt(from)) }
        val entry = (moved[to] as? ToolbarRow.Element)?.entry
        if (entry == ToolbarElementId.MENU.name && zoneAt(moved, to) == Zone.HIDDEN) {
            menuLockedSnack()
            return false
        }
        rows = moved
        draggingIndex = to
        dragOffset -= step * rowHeightPx
        return true
    }

    /** Converts accumulated offset into slot moves, in both directions. */
    fun settleDrag() {
        while (dragOffset > rowHeightPx * 0.6f) if (!moveDragged(1)) break
        while (dragOffset < -rowHeightPx * 0.6f) if (!moveDragged(-1)) break
    }

    // Dragging to the top/bottom of the screen scrolls the settings page, so long lists
    // can be reordered end to end in one drag. Runs on a timer: the finger holds still
    // at the edge, so no further onDrag events arrive. The page scrolling under the
    // stationary finger is pointer movement in column coordinates — feed the consumed
    // amount back into dragOffset so the slot math tracks the finger, not the column.
    LaunchedEffect(edgeScrollDir) {
        while (edgeScrollDir != 0 && draggingIndex != null) {
            val consumed = pageScrollState.scrollBy(edgeScrollDir * rowHeightPx)
            if (consumed == 0f) break // page end reached
            dragOffset += consumed
            settleDrag()
            delay(200.milliseconds)
        }
    }

    Column(
        modifier = Modifier
            .onGloballyPositioned { columnCoords = it }
            .pointerInput(layout, pens) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val index = (offset.y / rowHeightPx).toInt()
                        if (index > 0 && rows.getOrNull(index) is ToolbarRow.Element) {
                            draggingIndex = index
                            dragOffset = 0f
                        }
                    },
                    onDrag = { change, amount ->
                        if (draggingIndex == null) return@detectDragGesturesAfterLongPress
                        change.consume()
                        dragOffset += amount.y
                        settleDrag()
                        edgeScrollDir = columnCoords?.let { coords ->
                            val rootY = coords.localToRoot(change.position).y
                            val rootHeight = coords.findRootCoordinates().size.height
                            when {
                                rootY < rowHeightPx * 2 -> -1
                                rootY > rootHeight - rowHeightPx * 2 -> 1
                                else -> 0
                            }
                        } ?: 0
                    },
                    onDragEnd = {
                        edgeScrollDir = 0
                        if (draggingIndex != null) {
                            draggingIndex = null
                            dragOffset = 0f
                            onLayoutChange(rowsToLayout(rows))
                        }
                    },
                    onDragCancel = {
                        edgeScrollDir = 0
                        draggingIndex = null
                        dragOffset = 0f
                        rows = buildRows(layout, pens)
                    },
                )
            }
    ) {
        rows.forEachIndexed { index, row ->
            when (row) {
                is ToolbarRow.SectionHeader -> SectionHeaderRow(row.zone)

                is ToolbarRow.Element -> {
                    val isDragged = index == draggingIndex
                    // Hidden = dropped from the layout. Not offered for rows already in
                    // Hidden, for MENU (locked, see menuLockedSnack), or for dividers
                    // (hiding one equals deleting it — they have the delete button).
                    val hidable = zoneAt(rows, index) != Zone.HIDDEN &&
                            row.entry != ToolbarElementId.MENU.name &&
                            row.entry != ToolbarElementId.DIVIDER.name
                    ElementRow(
                        entry = row.entry,
                        pens = pens,
                        onEditPen = onEditPen,
                        onHide = if (!hidable) null else ({
                            onLayoutChange(
                                rowsToLayout(rows.filterIndexed { i, _ -> i != index })
                            )
                        }),
                        onDelete = deleteActionFor(
                            row.entry, rows, index, onDeletePen, onDeleteDivider
                        ),
                        modifier = Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f },
                    )
                }
            }
        }
    }
}

/** Pens and dividers get a trailing delete button; statics are hidden by dragging. */
private fun deleteActionFor(
    entry: String,
    rows: List<ToolbarRow>,
    index: Int,
    onDeletePen: (String) -> Unit,
    onDeleteDivider: (Zone, Int) -> Unit,
): (() -> Unit)? = when {
    entry.startsWith(ToolbarPen.LAYOUT_PREFIX) ->
        ({ onDeletePen(entry.removePrefix(ToolbarPen.LAYOUT_PREFIX)) })

    entry == ToolbarElementId.DIVIDER.name -> {
        // Dividers repeat, so identify this one by its position within its zone.
        var zone = Zone.SCROLLABLE
        var indexInZone = 0
        for (i in 0 until index) when (val row = rows[i]) {
            is ToolbarRow.SectionHeader -> {
                zone = row.zone
                indexInZone = 0
            }

            is ToolbarRow.Element -> indexInZone++
        }
        val z = zone
        val i = indexInZone
        ({ onDeleteDivider(z, i) })
    }

    else -> null
}

@Composable
private fun SectionHeaderRow(zone: Zone) {
    // Exactly ROW_HEIGHT tall — the list's drag detector maps y-offset to row index.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(bottom = NotableSpace.xs),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = stringResource(
                when (zone) {
                    Zone.SCROLLABLE -> R.string.toolbar_settings_section_scrollable
                    Zone.PINNED -> R.string.toolbar_settings_section_pinned
                    Zone.HIDDEN -> R.string.toolbar_settings_section_hidden
                }
            ),
            style = NotableTheme.type.heading,
            color = NotableTheme.colors.ink,
        )
    }
}

/** Ink that reads on a pen color: pens are drawn on paper, so this uses the light palette. */
private fun inkOn(background: Color?): Color =
    if (background != null && background.alpha > 0.5f && background.luminance() < 0.4f)
        LightNotableColors.paper else LightNotableColors.ink

/**
 * A tool's icon at list/dialog scale: [ToolbarButton]'s look (the pen-color dot) but smaller — the real 38 dp toolbar buttons
 * read oversized next to settings text.
 */
@Composable
private fun ToolIcon(icon: IconRef?, penColor: Color? = null, onClick: (() -> Unit)? = null) {
    val tint = NotableTheme.colors.ink
    Box(
        modifier = Modifier
            .size(28.dp)
            .nColorMark(penColor, NotableTheme.colors, radius = 3.5.dp, inset = 4.dp)
            .then(if (onClick != null) Modifier.inkClickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            is IconRef.Drawable -> Icon(
                painterResource(icon.resId), null, tint = tint, modifier = Modifier.size(18.dp)
            )

            is IconRef.Vector -> Icon(
                icon.imageVector, null, tint = tint, modifier = Modifier.size(18.dp)
            )

            null -> Unit
        }
    }
}

@Composable
private fun ElementRow(
    entry: String,
    pens: List<ToolbarPen>,
    onEditPen: (String) -> Unit,
    onHide: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val preset = if (entry.startsWith(ToolbarPen.LAYOUT_PREFIX))
        pens.find { it.id == entry.removePrefix(ToolbarPen.LAYOUT_PREFIX) } else null

    val c = NotableTheme.colors
    // Exactly ROW_HEIGHT tall, hairline included — the drag detector maps y to row index.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(c.paper)
            .then(
                if (preset != null) Modifier.inkClickable { onEditPen(preset.id) } else Modifier
            ),
    ) {
        Row(Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            // Drag handle: the drag itself is a long-press anywhere on the row.
            NIcon(
                FeatherIcons.Menu,
                tint = c.secondary,
                size = 18.dp,
                modifier = Modifier.padding(end = NotableSpace.md),
            )

            if (preset != null) {
                ToolIcon(
                    icon = ToolbarElements.penIcon(preset.pen),
                    penColor = Color(preset.color),
                    onClick = { onEditPen(preset.id) },
                )
                Spacer(Modifier.width(NotableSpace.md))
                Text(
                    text = stringResource(penNameRes(preset.pen)),
                    style = NotableTheme.type.body,
                    color = c.ink,
                    modifier = Modifier.weight(1f)
                )
            } else {
                val id = ToolbarElementId.fromString(entry)
                if (id == ToolbarElementId.DIVIDER) Box(
                    Modifier.size(28.dp), contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(width = NotableSize.strong, height = 20.dp)
                            .background(c.ink)
                    )
                } else ToolIcon(icon = id?.let { ToolbarElements.all[it]?.icon })
                Spacer(Modifier.width(NotableSpace.md))
                Text(
                    text = id?.let { stringResource(elementNameRes(it)) } ?: entry,
                    style = NotableTheme.type.body,
                    color = c.ink,
                    modifier = Modifier.weight(1f)
                )
            }

            if (onDelete != null) NIconButton(
                FeatherIcons.Trash2,
                contentDescription = stringResource(R.string.toolbar_settings_delete),
                onClick = onDelete,
            )
            if (onHide != null) NIconButton(
                FeatherIcons.EyeOff,
                contentDescription = stringResource(R.string.toolbar_settings_hide),
                onClick = onHide,
            )
        }
        NDivider(Modifier.align(Alignment.BottomCenter))
    }
}

// ---------------------------------------------------------------------------
// Preview strip
// ---------------------------------------------------------------------------

/**
 * Static rendition of the layout: real icons in real order, pens tinted with their
 * preset color, but non-interactive and unconditionally visible (visibleWhen predicates
 * like "has clipboard" can't be evaluated meaningfully here). Deliberately compact —
 * roughly half the real toolbar's scale, it's an overview, not a replica.
 */
@Composable
private fun ToolbarPreview(layout: ToolbarLayout, pens: List<ToolbarPen>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = NotableSpace.sm)
            .heightIn(min = NotableSize.control)
            .border(NotableSize.hairline, NotableTheme.colors.rule, NotableShape.control)
            .padding(horizontal = NotableSpace.sm, vertical = NotableSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun previewIcon(icon: IconRef, background: Color? = null) {
            val tint = NotableTheme.colors.ink
            Box(
                modifier = Modifier
                    .padding(1.dp)
                    .size(22.dp)
                    .nColorMark(background, NotableTheme.colors, radius = 3.dp, inset = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                when (icon) {
                    is IconRef.Drawable -> Icon(
                        painterResource(icon.resId), null,
                        tint = tint, modifier = Modifier.size(14.dp)
                    )

                    is IconRef.Vector -> Icon(
                        icon.imageVector, null,
                        tint = tint, modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        @Composable
        fun previewEntry(entry: String) {
            if (entry.startsWith(ToolbarPen.LAYOUT_PREFIX)) {
                pens.find { it.id == entry.removePrefix(ToolbarPen.LAYOUT_PREFIX) }?.let {
                    previewIcon(ToolbarElements.penIcon(it.pen), background = Color(it.color))
                }
                return
            }
            val id = ToolbarElementId.fromString(entry) ?: return
            if (id == ToolbarElementId.DIVIDER) {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(width = NotableSize.hairline, height = 16.dp)
                        .background(NotableTheme.colors.rule)
                )
                return
            }
            when (val icon = ToolbarElements.all[id]?.icon) {
                is IconRef.Drawable, is IconRef.Vector -> previewIcon(icon)
                null -> if (id == ToolbarElementId.PAGE_NAV) Text(
                    text = "1/1",
                    style = NotableTheme.type.meta,
                    color = NotableTheme.colors.ink,
                    modifier = Modifier.padding(horizontal = NotableSpace.xs)
                )
            }
        }

        // Mirror the real toolbar: the scrollable zone takes the free space (and scrolls),
        // so the pinned zone is pushed hard against the right edge.
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            layout.scrollable.forEach { previewEntry(it) }
        }
        Spacer(Modifier.width(NotableSpace.md))
        Row(verticalAlignment = Alignment.CenterVertically) {
            layout.pinned.forEach { previewEntry(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun AddPenDialog(onPick: (Pen) -> Unit, onClose: () -> Unit) {
    NDialog(
        title = stringResource(R.string.toolbar_settings_pick_pen_type),
        onDismiss = onClose,
        maxWidth = 420.dp,
    ) {
        ToolbarPen.BASE_TYPES.forEach { pen ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = NotableSize.menuRow)
                    .inkClickable { onPick(pen) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIcon(icon = ToolbarElements.penIcon(pen))
                Spacer(Modifier.width(NotableSpace.md))
                Text(stringResource(penNameRes(pen)), style = NotableTheme.type.body, color = NotableTheme.colors.ink)
            }
        }
    }
}

/**
 * Per-pen StrokeMenu contents editor: which colors and which sizes this pen offers in
 * its toolbar popup. Multi-select toggles over the candidate sets; the pen's *current*
 * color/size is still picked from the toolbar's StrokeMenu, as always. At least one
 * color and one size must stay included (a single-size pen's StrokeMenu shows the
 * button picker instead of the slider).
 *
 * Toggles edit a local copy; the settings blob is written once, on dismiss — not per
 * tap. If an edit removes the option the pen currently draws with, the active
 * color/size is clamped to a surviving option so it never falls off its own palette.
 */
@Composable
private fun PenEditDialog(
    preset: ToolbarPen,
    onChange: (ToolbarPen) -> Unit,
    onClose: () -> Unit,
) {
    var edited by remember(preset.id) { mutableStateOf(preset) }
    val includedColors = edited.effectiveColorOptions()
    val includedSizes = edited.effectiveSizeOptions()

    fun commitAndClose() {
        if (edited != preset) onChange(
            edited.copy(
                color = if (edited.color in edited.effectiveColorOptions()) edited.color
                else edited.effectiveColorOptions().first(),
                size = if (edited.size in edited.effectiveSizeOptions()) edited.size
                else edited.effectiveSizeOptions().minBy { kotlin.math.abs(it - edited.size) },
            )
        )
        onClose()
    }

    val c = NotableTheme.colors
    NDialog(
        title = stringResource(penNameRes(edited.pen)),
        onDismiss = ::commitAndClose,
        titleAction = {
            ToolIcon(
                icon = ToolbarElements.penIcon(edited.pen),
                penColor = Color(edited.color),
            )
        },
    ) {
        DialogHeading(stringResource(R.string.toolbar_settings_color))
        ToolbarPen.COLOR_CANDIDATES.chunked(7).forEach { rowColors ->
            Row {
                rowColors.forEach { colorInt ->
                    val included = colorInt in includedColors
                    // Swatches are user data (the pen's palette): its color is the fill.
                    Box(
                        modifier = Modifier
                            .padding(NotableSpace.xxs)
                            .size(40.dp)
                            .background(Color(colorInt), NotableShape.control)
                            .border(
                                if (included) NotableSize.strong + 1.dp else NotableSize.hairline,
                                c.ink,
                                NotableShape.control,
                            )
                            .inkClickable {
                                // Re-added colors append at the end: the user's
                                // stored palette order is preserved, not re-sorted
                                // to candidate order.
                                val updated =
                                    if (included) includedColors - colorInt
                                    else includedColors + colorInt
                                if (updated.isNotEmpty())
                                    edited = edited.copy(colorOptions = updated)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (included) NIcon(
                            FeatherIcons.Check,
                            tint = inkOn(Color(colorInt)),
                            size = 18.dp,
                        )
                    }
                }
            }
        }

        DialogHeading(stringResource(R.string.toolbar_settings_size))
        ToolbarPen.SIZE_CANDIDATES.chunked(7).forEach { rowSizes ->
            Row(horizontalArrangement = Arrangement.spacedBy(NotableSpace.xs)) {
                rowSizes.forEach { size ->
                    val included = size in includedSizes
                    NButton(
                        text = ToolbarElements.sizeLabel(size),
                        selected = included,
                        size = NControlSize.Compact,
                        onClick = {
                            val updated =
                                if (included) includedSizes - size
                                else (includedSizes + size).sorted()
                            if (updated.isNotEmpty())
                                edited = edited.copy(sizeOptions = updated)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogHeading(text: String) {
    Text(
        text = text,
        style = NotableTheme.type.label,
        color = NotableTheme.colors.ink,
        modifier = Modifier.padding(top = NotableSpace.lg, bottom = NotableSpace.sm)
    )
}

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

private fun penNameRes(pen: Pen): Int = when (pen) {
    Pen.BALLPEN, Pen.REDBALLPEN, Pen.GREENBALLPEN, Pen.BLUEBALLPEN -> R.string.pen_name_ballpen
    Pen.PENCIL -> R.string.pen_name_pencil // charcoal V1, shown as "Pencil"
    Pen.CHARCOAL -> R.string.pen_name_charcoal
    Pen.BRUSH -> R.string.pen_name_brush
    Pen.FOUNTAIN -> R.string.pen_name_fountain
    Pen.MARKER -> R.string.pen_name_marker
    Pen.CALLIGRAPHY -> R.string.pen_name_calligraphy
    Pen.DASHED -> R.string.pen_name_ballpen // not placeable; unreachable in practice
}

private fun elementNameRes(id: ToolbarElementId): Int = when (id) {
    ToolbarElementId.SHAPE -> R.string.toolbar_element_shape
    ToolbarElementId.ERASER -> R.string.toolbar_element_eraser
    ToolbarElementId.SELECT -> R.string.toolbar_element_select
    ToolbarElementId.IMAGE -> R.string.toolbar_element_image
    ToolbarElementId.PASTE -> R.string.toolbar_element_paste
    ToolbarElementId.RESET_VIEW -> R.string.toolbar_element_reset_view
    ToolbarElementId.UNDO -> R.string.toolbar_element_undo
    ToolbarElementId.REDO -> R.string.toolbar_element_redo
    ToolbarElementId.PAGE_NAV -> R.string.toolbar_element_page_nav
    ToolbarElementId.HOME -> R.string.toolbar_element_home
    ToolbarElementId.MENU -> R.string.toolbar_element_menu
    ToolbarElementId.DIVIDER -> R.string.toolbar_element_divider
    // Not placeable / not listed, but keep the when exhaustive:
    ToolbarElementId.TOGGLE, ToolbarElementId.PEN -> R.string.toolbar_element_menu
}

// ----------------------------------- //
// --------      Previews      ------- //
// ----------------------------------- //

@Preview(showBackground = true)
@Composable
private fun ToolbarSettingsPreview() {
    InkaTheme {
        ToolbarSettings(settings = AppSettings(version = 1), onSettingsChange = {})
    }
}
