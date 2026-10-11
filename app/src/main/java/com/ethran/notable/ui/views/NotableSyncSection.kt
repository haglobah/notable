package com.ethran.notable.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.R
import com.ethran.notable.sync.automerge.BackgroundHealth
import com.ethran.notable.sync.automerge.MigrationState
import com.ethran.notable.sync.automerge.ProjectionStatus
import com.ethran.notable.sync.automerge.NotableSyncClient
import com.ethran.notable.sync.automerge.SyncStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotableSyncViewModel @Inject constructor(val client: NotableSyncClient) : ViewModel() {
    init {
        client.start()
    }
}

/** Login, connection status, migration and background health for Automerge sync (ADR-001). */
@Composable
fun NotableSyncSection(viewModel: NotableSyncViewModel = hiltViewModel()) {
    val client = viewModel.client
    val status by client.status.collectAsState()
    val migration by client.migration.collectAsState()
    val projection by client.projection.collectAsState()
    val health = remember(status) { client.backgroundHealth() }
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf("https://notable.hagenlocher.me") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    EInkSection(title = stringResource(R.string.nsync_title), icon = Icons.Default.CloudSync) {
        Text(stringResource(R.string.nsync_intro), style = MaterialTheme.typography.body2)
        Spacer(Modifier.height(12.dp))

        when (val s = status) {
            SyncStatus.SignedOut, is SyncStatus.AccountUnreadable -> {
                if (s is SyncStatus.AccountUnreadable) Notice(stringResource(R.string.nsync_status_unreadable, s.reason))
                EInkTextField(stringResource(R.string.nsync_server), server, { server = it })
                Spacer(Modifier.height(8.dp))
                EInkTextField(stringResource(R.string.nsync_username), username, { username = it })
                Spacer(Modifier.height(8.dp))
                PasswordField(stringResource(R.string.nsync_password), password) { password = it }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            error = runCatching { client.login(server, username, password) }.exceptionOrNull()?.message
                            if (error == null) password = ""
                            busy = false
                        }
                    },
                    enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                    colors = eInkButtonColors(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (busy) R.string.nsync_logging_in else R.string.nsync_log_in)) }
            }

            else -> {
                Text(statusText(s), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                ProjectionBlock(projection, enabled = s is SyncStatus.Connected, onRebuild = client::rebuildFromServer)
                Spacer(Modifier.height(12.dp))
                MigrationBlock(migration, enabled = s is SyncStatus.Connected, onMigrate = client::migrate)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            error = client.logout()
                            busy = false
                        }
                    },
                    enabled = !busy && migration !is MigrationState.Running,
                    colors = eInkButtonColors(isSecondary = true),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.nsync_log_out)) }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Notice(it)
        }

        healthText(health)?.let {
            Spacer(Modifier.height(12.dp))
            Notice(it)
        }
    }
}

/** Server → this device. The rebuild is a debug action: it re-applies every doc (upsert only). */
@Composable
private fun ProjectionBlock(state: ProjectionStatus, enabled: Boolean, onRebuild: () -> Unit) {
    Text(stringResource(R.string.nsync_receive_title), fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.nsync_receive_body), style = MaterialTheme.typography.body2)
    Spacer(Modifier.height(4.dp))
    val last = state.lastAppliedAt?.let {
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
    }
    Text(
        when {
            state.running -> stringResource(R.string.nsync_receive_running, state.docs)
            last != null -> stringResource(R.string.nsync_receive_done, state.docs, last)
            else -> stringResource(R.string.nsync_receive_waiting)
        }
    )
    state.errors.forEach { (doc, why) -> Notice(stringResource(R.string.nsync_receive_error, doc, why)) }
    state.warnings.takeLast(3).forEach { Notice(it) }
    state.writeError?.let { Notice(stringResource(R.string.nsync_write_error, it)) }
    if (state.pendingWrites > 0) Notice(stringResource(R.string.nsync_pending_writes, state.pendingWrites))
    if (state.pendingBlobs > 0) Text(stringResource(R.string.nsync_pending_blobs, state.pendingBlobs), style = MaterialTheme.typography.body2)
    state.blobError?.let { Notice(stringResource(R.string.nsync_blob_error, it)) }
    state.commitLatency?.let {
        Text(stringResource(R.string.nsync_commit_latency, it.p50Ms, it.maxMs, it.count), style = MaterialTheme.typography.body2)
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onRebuild,
        enabled = enabled && !state.running,
        colors = eInkButtonColors(isSecondary = true),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.nsync_rebuild)) }
}

@Composable
private fun MigrationBlock(state: MigrationState, enabled: Boolean, onMigrate: () -> Unit) {
    Text(stringResource(R.string.nsync_migrate_title), fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.nsync_migrate_body), style = MaterialTheme.typography.body2)
    Spacer(Modifier.height(8.dp))
    when (state) {
        is MigrationState.Running -> Text(
            stringResource(R.string.nsync_migrating, state.progress.done + 1, state.progress.total, state.progress.current ?: "")
        )

        is MigrationState.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val r = state.report
            Text(stringResource(R.string.nsync_migrate_done, r.migrated.size, r.alreadyDone.size, r.failed.size))
            r.failed.forEach { Notice(stringResource(R.string.nsync_migrate_failed_item, it.title, it.reason)) }
            r.warnings.forEach { Notice(stringResource(R.string.nsync_migrate_missing_pages, it.id, it.missingPages.size)) }
        }

        is MigrationState.Failed -> Notice(stringResource(R.string.nsync_migrate_error, state.message))
        MigrationState.Idle -> Unit
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = onMigrate,
        enabled = enabled && state !is MigrationState.Running,
        colors = eInkButtonColors(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.nsync_migrate)) }
}

@Composable
private fun statusText(s: SyncStatus): String = when (s) {
    is SyncStatus.Connected -> stringResource(R.string.nsync_status_connected, s.server, s.username)
    is SyncStatus.Connecting -> stringResource(R.string.nsync_status_connecting, s.server)
    is SyncStatus.Offline -> stringResource(R.string.nsync_status_offline, s.reason)
    is SyncStatus.AuthFailed -> stringResource(R.string.nsync_status_auth_failed)
    is SyncStatus.AccountUnreadable -> stringResource(R.string.nsync_status_unreadable, s.reason)
    SyncStatus.SignedOut -> ""
}

@Composable
private fun healthText(h: BackgroundHealth): String? = when (h) {
    BackgroundHealth.Ok -> null
    BackgroundHealth.FrozenByOnyx -> stringResource(R.string.nsync_bg_frozen)
    BackgroundHealth.KilledByPackageChange -> stringResource(R.string.nsync_bg_killed)
    BackgroundHealth.Restricted -> stringResource(R.string.nsync_bg_restricted)
    BackgroundHealth.BatteryOptimized -> stringResource(R.string.nsync_bg_battery)
}

/** A bordered message, so warnings stand out on e-ink without color. */
@Composable
private fun Notice(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colors.onSurface, RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) { Text(text, style = MaterialTheme.typography.body2) }
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.caption, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onSurface)
        Box(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colors.surface, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colors.onSurface, RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = TextStyle(fontSize = 14.sp, color = MaterialTheme.colors.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colors.onSurface),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
