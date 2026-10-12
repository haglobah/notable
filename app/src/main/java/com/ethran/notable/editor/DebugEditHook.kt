package com.ethran.notable.editor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.compose.ui.unit.IntOffset
import androidx.core.content.ContextCompat
import com.ethran.notable.BuildConfig
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.deletePage
import com.ethran.notable.data.db.StrokePoint
import com.ethran.notable.data.model.SimplePointF
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.editor.state.History
import com.ethran.notable.editor.state.Operation
import com.ethran.notable.editor.utils.Eraser
import com.ethran.notable.editor.utils.handleDraw
import com.ethran.notable.editor.utils.handleErase
import com.ethran.notable.editor.utils.selectImagesAndStrokes
import com.ethran.notable.editor.utils.strokeBounds
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sin

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DebugEditEntryPoint {
    fun appRepository(): AppRepository
}

/**
 * Debug builds only: drives the editor's real [PageView] and [History] paths from adb, because
 * injected touch events don't draw on Onyx (the firmware reads the digitizer). Registered while
 * an editor is open.
 *
 * ```
 * adb shell am broadcast -a com.ethran.notable.dev.DEBUG_EDIT --es op draw --ei x 200 --ei y 300 --ei n 3
 * ```
 *
 * Ops (coordinates in page units):
 * - `draw` `x y w h n`: n zigzag strokes in the box, as one history block (like a pen burst).
 * - `erase` `x y w h`: an eraser swipe along the box's diagonal ([handleErase]).
 * - `move` `x y w h dx dy`: selects what lies in the box and moves it, as a tap outside does.
 * - `undo`, `redo`: [History.undo], [History.redo].
 * - `image` `path`: inserts a file as the image picker does; `place` then commits it.
 * - `dump`: logs the page id and its stroke and image ids.
 * - `delete-page`: deletes the open page as the quick-nav page menu does ([deletePage]); the
 *   editor then moves to the neighbouring page or the library.
 *
 * Every op logs one line under the tag `DebugEdit`.
 */
internal class DebugEditHook(
    private val context: Context,
    private val scope: CoroutineScope,
    private val page: PageView,
    private val history: History,
    private val viewModel: EditorViewModel,
    private val tower: EditorControlTower,
) {
    private val log = ShipBook.getLogger("DebugEdit")
    private val action = "${BuildConfig.APPLICATION_ID}.DEBUG_EDIT"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            scope.launch(Dispatchers.Main) {
                try {
                    handle(intent)
                } catch (e: Exception) {
                    log.e("DebugEdit ${intent.getStringExtra("op")} failed: ${e.message}", e)
                }
            }
        }
    }

    /** Registers the receiver; returns the unregister action. A no-op in release builds. */
    fun register(): () -> Unit {
        if (!BuildConfig.DEBUG) return {}
        // Exported so `adb shell am broadcast` (the shell user) reaches it; debug builds only.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        log.i("DebugEdit ready: $action")
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    private suspend fun handle(intent: Intent) {
        fun int(k: String, d: Int) = intent.getIntExtra(k, d)
        val x = int("x", 200).toFloat()
        val y = int("y", 300).toFloat()
        val w = int("w", 400).toFloat()
        val h = int("h", 200).toFloat()
        when (val op = intent.getStringExtra("op")) {
            "draw" -> {
                val n = int("n", 1)
                val ids = mutableListOf<String>()
                val pen = viewModel.toolbarState.value.pen
                val setting = viewModel.toolbarState.value.activePenSetting
                repeat(n) { i ->
                    val top = y + i * (h / n)
                    handleDraw(page, ids, setting.strokeSize, setting.color, pen, zigzag(x, top, w, h / n))
                }
                // What DrawCanvas.commitToHistory does after a burst of strokes.
                history.addOperationsToHistory(listOf(Operation.DeleteStroke(ids.toList())))
                CanvasEventBus.refreshUi.emit(Unit)
                log.i("DebugEdit draw page=${page.currentPageId} ids=${ids.joinToString(",")}")
            }

            "erase" -> {
                val points = (0..40).map { SimplePointF(x + w * it / 40, y + h * it / 40) }
                val area = handleErase(page, history, points, Eraser.PEN)
                CanvasEventBus.refreshUi.emit(Unit)
                log.i("DebugEdit erase page=${page.currentPageId} area=$area strokes=${page.strokes.size}")
            }

            "move" -> {
                val box = android.graphics.RectF(x, y, x + w, y + h)
                val strokes = page.strokes.filter { box.contains(strokeBounds(it)) }
                val images = page.images.filter {
                    box.contains(android.graphics.RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat()))
                }
                if (strokes.isEmpty() && images.isEmpty()) {
                    log.w("DebugEdit move: nothing in $box")
                    return
                }
                selectImagesAndStrokes(scope, page, viewModel, images, strokes)
                viewModel.selectionState.selectionDisplaceOffset = IntOffset(int("dx", 100), int("dy", 0))
                commitSelection()
                log.i("DebugEdit move page=${page.currentPageId} strokes=${strokes.map { it.id }} images=${images.map { it.id }}")
            }

            "undo" -> {
                history.undo()
                log.i("DebugEdit undo page=${page.currentPageId} strokes=${page.strokes.size} images=${page.images.size}")
            }

            "redo" -> {
                history.redo()
                log.i("DebugEdit redo page=${page.currentPageId} strokes=${page.strokes.size} images=${page.images.size}")
            }

            "image" -> {
                val path = intent.getStringExtra("path") ?: throw IllegalArgumentException("--es path missing")
                val uri = if (path.contains("://")) Uri.parse(path) else Uri.fromFile(File(path))
                viewModel.onToolbarAction(ToolbarAction.ImagePicked(uri))
                log.i("DebugEdit image queued $uri; send op=place to commit it")
            }

            "place" -> {
                val images = viewModel.selectionState.selectedImages?.map { it.id }
                commitSelection()
                log.i("DebugEdit place page=${page.currentPageId} images=$images")
            }

            "dump" -> log.i(
                "DebugEdit dump page=${page.currentPageId} strokes=${page.strokes.map { it.id }} images=${page.images.map { it.id }}"
            )

            "delete-page" -> {
                val id = page.currentPageId
                val repo = EntryPoints.get(context.applicationContext, DebugEditEntryPoint::class.java).appRepository()
                deletePage(repo, id, context.filesDir)
                log.i("DebugEdit delete-page page=$id")
            }

            else -> log.w("DebugEdit: unknown op '$op'")
        }
    }

    /** What a tap outside the selection does (SelectorBitmap). */
    private fun commitSelection() {
        tower.applySelectionDisplace()
        viewModel.selectionState.reset()
        tower.setIsDrawing(true)
    }

    private fun zigzag(x: Float, y: Float, w: Float, h: Float): List<StrokePoint> =
        (0..60).map { i ->
            val t = i / 60f
            StrokePoint(x = x + w * t, y = y + h / 2 + (h / 3) * sin(t * 6 * Math.PI).toFloat(), pressure = 0.6f)
        }
}
