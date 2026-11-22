package com.ethran.notable.ui.views

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.ethran.notable.R
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.flow.VoiceState
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import com.ethran.notable.ui.design.nSurface
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val DotSize = 56.dp
/** From the right edge, where it sits at first. */
private val DotMargin = 16.dp
/** Where it sits at first: just under the library's top bar (64 dp) and a note's toolbar (41 dp). */
val FlowDotTop = 76.dp

/**
 * Talking to Claude through Flow without leaving the screen: a microphone dot floating over the
 * library and over a note while its toolbar shows. Tap to talk (it turns black), tap again to send;
 * drag it anywhere; a long press opens the conversation ([onOpen]). The library bar's microphone
 * shows and hides it. At first it sits at the right, [top] from the top.
 */
@Composable
fun FlowMicDot(onOpen: () -> Unit, top: Dp = FlowDotTop, viewModel: FlowViewModel = hiltViewModel()) {
    val client = viewModel.client
    val dot by client.dot.collectAsState()
    if (!dot.shown) return
    val account by client.account.collectAsState()
    val voice by client.voice.collectAsState()
    val c = NotableTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) client.toggleRecording()
    }
    val tap = {
        when {
            account == null -> onOpen()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> client.toggleRecording()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val recording = voice == VoiceState.Recording
    val busy = voice == VoiceState.Transcribing || voice == VoiceState.Sending

    // Gone (the toolbar closed, another screen): the pen draws where it was again.
    DisposableEffect(Unit) { onDispose { CanvasEventBus.setFloatingControl(null) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = with(density) { DotSize.toPx() }
        val maxX = (constraints.maxWidth - size).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - size).coerceAtLeast(0f)
        fun clamp(p: Offset) = Offset(p.x.coerceIn(0f, maxX), p.y.coerceIn(0f, maxY))
        val placed = with(density) {
            clamp(Offset(dot.x?.dp?.toPx() ?: (maxX - DotMargin.toPx()), dot.y?.dp?.toPx() ?: top.toPx()))
        }
        // While a finger drags it: where it is now, in px; saved when it lets go.
        var dragged by remember { mutableStateOf<Offset?>(null) }
        val at = dragged ?: placed
        val current by rememberUpdatedState(at)
        // The pen leaves it to the finger: its place is kept out of raw drawing once it settles.
        var bounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
        LaunchedEffect(bounds, dragged == null) { if (dragged == null) CanvasEventBus.setFloatingControl(bounds) }

        val description = stringResource(if (recording) R.string.flow_listening else R.string.flow_tap_to_talk)
        Box(
            Modifier
                .offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }
                .size(DotSize)
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    bounds = android.graphics.Rect(floor(b.left).toInt(), floor(b.top).toInt(), ceil(b.right).toInt(), ceil(b.bottom).toInt())
                }
                .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { dragged = current },
                        onDragEnd = {
                            dragged?.let { p -> with(density) { client.moveDot(p.x.toDp().value, p.y.toDp().value) } }
                            dragged = null
                        },
                        onDragCancel = { dragged = null },
                    ) { change, amount ->
                        change.consume()
                        dragged = clamp((dragged ?: current) + amount)
                    }
                }
                .background(if (recording) c.ink else c.paper, CircleShape)
                .border(if (recording) 0.dp else 2.dp, if (busy) c.rule else c.ink, CircleShape)
                .inkClickable(enabled = !busy, onClickLabel = description, onLongClick = onOpen, onClick = tap),
            contentAlignment = Alignment.Center,
        ) {
            NIcon(FeatherIcons.Mic, description, size = 26.dp, tint = when {
                recording -> c.paper
                busy -> c.disabled
                else -> c.ink
            })
        }

        // What it is doing, beside it on the side with room: listening, transcribing, a failure.
        val label = when (val v = voice) {
            VoiceState.Idle -> null
            VoiceState.Recording -> stringResource(R.string.flow_listening)
            VoiceState.Transcribing -> stringResource(R.string.flow_transcribing)
            VoiceState.Sending -> stringResource(R.string.flow_sending)
            is VoiceState.Failed -> v.reason
        }
        if (label != null) {
            var width by remember { mutableIntStateOf(0) }
            var height by remember { mutableIntStateOf(0) }
            val gap = with(density) { NotableSpace.sm.toPx() }
            val left = at.x + size / 2 > constraints.maxWidth / 2f
            Text(
                label, style = NotableTheme.type.meta, color = c.ink, maxLines = 2,
                modifier = Modifier
                    .offset {
                        val x = if (left) at.x - gap - width else at.x + size + gap
                        IntOffset(x.roundToInt(), (at.y + (size - height) / 2).roundToInt())
                    }
                    .onSizeChanged { width = it.width; height = it.height }
                    .widthIn(max = 280.dp)
                    .nSurface(c)
                    .then(if (voice is VoiceState.Failed) Modifier.inkClickable(onClick = client::dismissVoiceError) else Modifier)
                    .padding(horizontal = NotableSpace.md, vertical = NotableSpace.xs),
            )
        }
    }
}
