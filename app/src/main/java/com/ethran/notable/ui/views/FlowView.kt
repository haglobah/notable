package com.ethran.notable.ui.views

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.R
import com.ethran.notable.flow.FlowClient
import com.ethran.notable.flow.FlowConversation
import com.ethran.notable.flow.FlowEntry
import com.ethran.notable.flow.FlowLink
import com.ethran.notable.flow.ToolState
import com.ethran.notable.flow.VoiceState
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NCodeBlock
import com.ethran.notable.ui.design.NEmptyState
import com.ethran.notable.ui.design.NIcon
import com.ethran.notable.ui.design.NIconButton
import com.ethran.notable.ui.design.NMenu
import com.ethran.notable.ui.design.NMenuDivider
import com.ethran.notable.ui.design.NMenuItem
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NTopBar
import com.ethran.notable.ui.design.NotableShape
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import com.ethran.notable.ui.design.inkClickable
import compose.icons.FeatherIcons
import compose.icons.feathericons.List
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Send
import compose.icons.feathericons.Square
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.sample
import javax.inject.Inject

object FlowDestination : NavigationDestination {
    override val route = "flow"
}

@HiltViewModel
class FlowViewModel @Inject constructor(val client: FlowClient) : ViewModel()

/**
 * Talking to Claude through Flow (flow.humane.tools): a big microphone circle is the main control.
 * Tap it, speak, tap again: Flow transcribes the recording and the text goes to the shown
 * conversation, or starts a new one. Replies stream in from Flow; every conversation is also in
 * Flow's own chat, where its jobs can be inspected.
 */
@Composable
fun FlowView(onBack: () -> Unit, goToSettings: () -> Unit, viewModel: FlowViewModel = hiltViewModel()) {
    val client = viewModel.client
    DisposableEffect(client) {
        client.attach()
        onDispose { client.detach() }
    }
    val account by client.account.collectAsState()
    // A reply arrives in many small events: e-ink redraws at most about once a second.
    val chat by remember(client) { client.chat.sample(900) }.collectAsState(client.chat.value)
    val selectedId by client.selected.collectAsState()
    val link by client.link.collectAsState()
    val voice by client.voice.collectAsState()
    val conversation = selectedId?.let { chat.conversations[it] }
    var listOpen by remember { mutableStateOf(false) }
    val c = NotableTheme.colors

    Column(Modifier.fillMaxSize().background(c.paper)) {
        NTopBar(
            title = conversation?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.flow_title),
            onBack = onBack,
            meta = linkText(link),
        ) {
            Box {
                NIconButton(FeatherIcons.List, stringResource(R.string.flow_conversations), { listOpen = true }, enabled = chat.conversations.isNotEmpty())
                if (listOpen) NMenu(onDismiss = { listOpen = false }, minWidth = 320.dp, maxWidth = 520.dp) {
                    NMenuItem(stringResource(R.string.flow_new), { client.select(null); listOpen = false }, icon = FeatherIcons.Plus)
                    NMenuDivider()
                    chat.ordered.forEach { conv ->
                        NMenuItem(
                            conv.title.ifBlank { stringResource(R.string.flow_untitled) },
                            { client.select(conv.id); listOpen = false },
                            checked = conv.id == selectedId,
                            meta = if (conv.running) stringResource(R.string.flow_working_short) else null,
                        )
                    }
                }
            }
            NIconButton(FeatherIcons.Plus, stringResource(R.string.flow_new), { client.select(null) })
        }

        if (account == null) {
            NEmptyState(
                stringResource(R.string.flow_signed_out_title),
                message = stringResource(R.string.flow_signed_out_message),
                action = { NButton(stringResource(R.string.flow_open_settings), goToSettings, style = NButtonStyle.Primary) },
            )
            return@Column
        }

        Transcript(conversation, Modifier.weight(1f).fillMaxWidth())
        Controls(
            voice = voice,
            running = conversation?.running == true,
            onMic = client::toggleRecording,
            onCancel = client::cancelRecording,
            onStop = client::stopTurn,
            onSend = client::sendText,
            onDismissError = client::dismissVoiceError,
        )
    }
}

@Composable
private fun linkText(link: FlowLink): String? = when (link) {
    FlowLink.Off -> null
    FlowLink.Connecting -> stringResource(R.string.flow_link_connecting)
    FlowLink.Live -> null
    is FlowLink.Down -> stringResource(R.string.flow_link_down)
}

@Composable
private fun Transcript(conversation: FlowConversation?, modifier: Modifier) {
    val c = NotableTheme.colors
    if (conversation == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.flow_new_hint), style = NotableTheme.type.body, color = c.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp).padding(NotableSpace.xl))
        }
        return
    }
    // Newest at the bottom, and the list starts there: a reverse layout over the entries reversed.
    val items = remember(conversation.entries) { conversation.entries.withIndex().reversed() }
    val state = rememberLazyListState()
    LaunchedEffect(conversation.id, conversation.entries.size) { state.scrollToItem(0) }
    LazyColumn(
        modifier.padding(horizontal = NotableSpace.gutter),
        state = state,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(NotableSpace.md),
    ) {
        item(key = "end") {
            Column(Modifier.fillMaxWidth().padding(bottom = NotableSpace.lg)) {
                if (conversation.running) Text(
                    conversation.status ?: stringResource(R.string.flow_working),
                    style = NotableTheme.type.meta, color = c.secondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        items(items, key = { it.index }) { (_, entry) -> Entry(entry) }
        item(key = "start") { Spacer(Modifier.height(NotableSpace.lg)) }
    }
}

@Composable
private fun Entry(entry: FlowEntry) {
    val c = NotableTheme.colors
    when (entry) {
        is FlowEntry.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                entry.text, style = NotableTheme.type.body, color = if (entry.pending) c.secondary else c.ink,
                modifier = Modifier.widthIn(max = 560.dp).background(c.inset, NotableShape.control)
                    .padding(horizontal = NotableSpace.md, vertical = NotableSpace.sm),
            )
        }

        is FlowEntry.Assistant -> Text(entry.text.trim(), style = NotableTheme.type.body, color = c.ink, modifier = Modifier.fillMaxWidth())

        is FlowEntry.Tool -> {
            var open by rememberSaveable(entry.id) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().inkClickable { open = !open }.padding(vertical = NotableSpace.xxs)) {
                    Text(
                        entry.name, style = NotableTheme.type.label,
                        color = if (entry.state == ToolState.Failed) c.ink else c.secondary,
                    )
                    Spacer(Modifier.width(NotableSpace.sm))
                    Text(
                        entry.text.lineSequence().firstOrNull().orEmpty() + when (entry.state) {
                            ToolState.Running -> " …"
                            ToolState.Failed -> " · " + stringResource(R.string.flow_tool_failed)
                            ToolState.Ok -> ""
                        },
                        style = NotableTheme.type.meta, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (open) NCodeBlock(listOf(entry.text, entry.result).filter { it.isNotBlank() }.joinToString("\n\n"), maxHeight = 360.dp)
            }
        }

        is FlowEntry.Note -> Text(entry.text, style = NotableTheme.type.meta, color = c.secondary)

        is FlowEntry.Failure -> Text(entry.text, style = NotableTheme.type.body, color = c.ink,
            modifier = Modifier.fillMaxWidth().border(1.dp, c.ink, NotableShape.control).padding(NotableSpace.md))
    }
}

/** The microphone circle and what sits around it: cancel, typing, stop. */
@Composable
private fun Controls(
    voice: VoiceState,
    running: Boolean,
    onMic: () -> Unit,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    val c = NotableTheme.colors
    val context = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onMic()
    }
    val mic = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onMic()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val recording = voice == VoiceState.Recording
    val busy = voice == VoiceState.Transcribing || voice == VoiceState.Sending

    Column(
        Modifier.fillMaxWidth().background(c.paper).padding(horizontal = NotableSpace.gutter, vertical = NotableSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (typing) {
            Row(Modifier.fillMaxWidth().padding(bottom = NotableSpace.lg), verticalAlignment = Alignment.CenterVertically) {
                NTextField(
                    text, { text = it }, Modifier.weight(1f),
                    placeholder = stringResource(R.string.flow_type_placeholder),
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend(text); text = "" }),
                )
                NIconButton(FeatherIcons.Send, stringResource(R.string.flow_send), { onSend(text); text = "" }, enabled = text.isNotBlank() && !busy)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (recording) NButton(stringResource(R.string.flow_cancel), onCancel, style = NButtonStyle.Quiet, icon = FeatherIcons.X)
                else NIconButton(FeatherIcons.Type, stringResource(R.string.flow_type), { typing = !typing }, selected = typing)
            }
            MicCircle(recording = recording, busy = busy, onClick = mic)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (running) NButton(stringResource(R.string.flow_stop), onStop, icon = FeatherIcons.Square)
            }
        }
        Spacer(Modifier.height(NotableSpace.md))
        val label = when (voice) {
            VoiceState.Idle -> stringResource(R.string.flow_tap_to_talk)
            VoiceState.Recording -> stringResource(R.string.flow_listening)
            VoiceState.Transcribing -> stringResource(R.string.flow_transcribing)
            VoiceState.Sending -> stringResource(R.string.flow_sending)
            is VoiceState.Failed -> voice.reason
        }
        Text(
            label, style = NotableTheme.type.body, color = if (voice is VoiceState.Failed) c.ink else c.secondary,
            textAlign = TextAlign.Center, maxLines = 3,
            modifier = Modifier.widthIn(max = 520.dp).heightIn(min = 24.dp)
                .then(if (voice is VoiceState.Failed) Modifier.inkClickable(onClick = onDismissError) else Modifier),
        )
    }
}

/** The main control: an ink ring at rest, filled while listening, grey while Flow works on it. */
@Composable
private fun MicCircle(recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val c = NotableTheme.colors
    val description = stringResource(if (recording) R.string.flow_listening else R.string.flow_tap_to_talk)
    Box(
        Modifier.size(132.dp)
            .background(if (recording) c.ink else c.paper, CircleShape)
            .border(if (recording) 0.dp else 3.dp, if (busy) c.rule else c.ink, CircleShape)
            .inkClickable(enabled = !busy, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NIcon(FeatherIcons.Mic, description, size = 52.dp, tint = when {
            recording -> c.paper
            busy -> c.disabled
            else -> c.ink
        })
    }
}
