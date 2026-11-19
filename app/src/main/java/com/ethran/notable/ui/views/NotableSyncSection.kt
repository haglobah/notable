package com.ethran.notable.ui.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.ethran.notable.R
import com.ethran.notable.sync.automerge.BackgroundHealth
import com.ethran.notable.sync.automerge.LogoutChoice
import com.ethran.notable.sync.automerge.LogoutResult
import com.ethran.notable.sync.automerge.PendingSync
import com.ethran.notable.sync.automerge.MigrationState
import com.ethran.notable.sync.automerge.ProjectionStatus
import com.ethran.notable.sync.automerge.NotableSyncClient
import com.ethran.notable.sync.automerge.SyncStatus
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NField
import com.ethran.notable.ui.design.NNotice
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NSettingRow
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.LogOut
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotableSyncViewModel @Inject constructor(val client: NotableSyncClient) : ViewModel() {
    init {
        client.start()
    }
}

/** The device library, login, connection status, migration and background health for Automerge sync (ADR-001). */
@Composable
fun NotableSyncSection(viewModel: NotableSyncViewModel = hiltViewModel()) {
    val client = viewModel.client
    val status by client.status.collectAsState()
    val migration by client.migration.collectAsState()
    val projection by client.projection.collectAsState()
    val local by client.local.collectAsState()
    val health = remember(status) { client.backgroundHealth() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var server by remember { mutableStateOf("https://notable.hagenlocher.me") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** A logout that stopped because edits or files wait for sync; offers wait or log out anyway. */
    var logoutBlocked by remember { mutableStateOf<LogoutResult.StillPending?>(null) }
    val keptText: (PendingSync) -> String = { k -> context.getString(R.string.nsync_logout_kept, k.writes, k.files) }

    NSection(
        title = stringResource(R.string.nsync_title),
        description = stringResource(R.string.nsync_intro),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
            local.openError?.let { Warning(stringResource(R.string.nsync_local_error, it)) }

            when (val s = status) {
                SyncStatus.SignedOut, is SyncStatus.AccountUnreadable -> {
                    if (s is SyncStatus.AccountUnreadable) Warning(stringResource(R.string.nsync_status_unreadable, s.reason))
                    val owner = local.owner
                    if (owner == null) Body(stringResource(R.string.nsync_local_own))
                    else NNotice(stringResource(R.string.nsync_local_owned, owner.username, owner.serverUrl))
                    MigrationBlock(migration, enabled = local.openError == null, onMigrate = client::migrate)
                    Column(
                        Modifier.widthIn(max = 480.dp).padding(top = NotableSpace.sm),
                        verticalArrangement = Arrangement.spacedBy(NotableSpace.lg),
                    ) {
                        NField(stringResource(R.string.nsync_server)) {
                            NTextField(
                                server, { server = it }, Modifier.fillMaxWidth(),
                                textStyle = NotableTheme.type.mono,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                        }
                        NField(stringResource(R.string.nsync_username)) {
                            NTextField(username, { username = it }, Modifier.fillMaxWidth())
                        }
                        NField(stringResource(R.string.nsync_password)) {
                            NTextField(
                                password, { password = it }, Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        }
                        NButton(
                            stringResource(if (busy) R.string.nsync_logging_in else R.string.nsync_log_in),
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    error = runCatching { client.login(server, username, password) }.exceptionOrNull()?.message
                                    if (error == null) password = ""
                                    busy = false
                                }
                            },
                            style = NButtonStyle.Primary,
                            enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotEmpty(),
                        )
                    }
                }

                else -> {
                    Text(statusText(s), style = NotableTheme.type.body, color = NotableTheme.colors.ink)
                    ProjectionBlock(projection, enabled = s is SyncStatus.Connected, onRebuild = client::rebuildFromServer)
                    MigrationBlock(migration, enabled = s is SyncStatus.Connected, onMigrate = client::migrate)
                    val logOut: (LogoutChoice) -> Unit = { choice ->
                        busy = true
                        error = null
                        scope.launch {
                            when (val r = client.logout(choice)) {
                                is LogoutResult.StillPending -> logoutBlocked = r
                                is LogoutResult.LoggedOut -> {
                                    logoutBlocked = null
                                    error = listOfNotNull(
                                        r.serverWarning,
                                        if (r.kept.isEmpty) null else keptText(r.kept),
                                    ).joinToString("\n").ifEmpty { null }
                                }
                            }
                            busy = false
                        }
                    }
                    logoutBlocked?.let { b ->
                        NNotice(
                            text = stringResource(R.string.nsync_logout_pending, b.pending.writes, b.pending.files) +
                                (b.reason?.let { "\n" + stringResource(R.string.nsync_logout_pending_reason, it) } ?: ""),
                            icon = FeatherIcons.AlertTriangle,
                            actions = {
                                NButton(
                                    stringResource(if (busy) R.string.nsync_logout_waiting else R.string.nsync_logout_wait),
                                    onClick = { logOut(LogoutChoice.WaitForSync) },
                                    style = NButtonStyle.Primary,
                                    enabled = !busy,
                                )
                                NButton(
                                    stringResource(R.string.nsync_logout_anyway),
                                    onClick = { logOut(LogoutChoice.Anyway) },
                                    style = NButtonStyle.Danger,
                                    enabled = !busy,
                                )
                            },
                        )
                    } ?: NButton(
                        stringResource(R.string.nsync_log_out),
                        onClick = { logOut(LogoutChoice.IfNothingPending) },
                        modifier = Modifier.padding(top = NotableSpace.sm),
                        icon = FeatherIcons.LogOut,
                        enabled = !busy && migration !is MigrationState.Running,
                    )
                }
            }

            error?.let { Warning(it) }

            healthText(health)?.let { Warning(it) }
        }
    }
}

/** Server → this device. The rebuild is a debug action: it re-applies every doc (upsert only). */
@Composable
private fun ProjectionBlock(state: ProjectionStatus, enabled: Boolean, onRebuild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        NSettingRow(
            title = stringResource(R.string.nsync_receive_title),
            description = stringResource(R.string.nsync_receive_body),
            divider = false,
        )
        val last = state.lastAppliedAt?.let {
            java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))
        }
        Body(
            when {
                state.running -> stringResource(R.string.nsync_receive_running, state.docs)
                last != null -> stringResource(R.string.nsync_receive_done, state.docs, last)
                else -> stringResource(R.string.nsync_receive_waiting)
            }
        )
        state.errors.forEach { (doc, why) -> Warning(stringResource(R.string.nsync_receive_error, doc, why)) }
        state.warnings.takeLast(3).forEach { Warning(it) }
        state.writeError?.let { Warning(stringResource(R.string.nsync_write_error, it)) }
        if (state.pendingWrites > 0) NNotice(stringResource(R.string.nsync_pending_writes, state.pendingWrites))
        if (state.pendingBlobs > 0) Meta(stringResource(R.string.nsync_pending_blobs, state.pendingBlobs))
        state.blobError?.let { Warning(stringResource(R.string.nsync_blob_error, it)) }
        state.commitLatency?.let {
            Meta(stringResource(R.string.nsync_commit_latency, it.p50Ms, it.maxMs, it.count))
        }
        NButton(
            stringResource(R.string.nsync_rebuild),
            onClick = onRebuild,
            style = NButtonStyle.Secondary,
            enabled = enabled && !state.running,
        )
    }
}

@Composable
private fun MigrationBlock(state: MigrationState, enabled: Boolean, onMigrate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.sm)) {
        NSettingRow(
            title = stringResource(R.string.nsync_migrate_title),
            description = stringResource(R.string.nsync_migrate_body),
            divider = false,
        ) {
            NButton(
                stringResource(R.string.nsync_migrate),
                onClick = onMigrate,
                enabled = enabled && state !is MigrationState.Running,
            )
        }
        when (state) {
            is MigrationState.Running -> Body(
                stringResource(R.string.nsync_migrating, state.progress.done + 1, state.progress.total, state.progress.current ?: "")
            )

            is MigrationState.Done -> {
                val r = state.report
                Body(stringResource(R.string.nsync_migrate_done, r.migrated.size, r.alreadyDone.size, r.failed.size))
                if (r.merged.isNotEmpty()) Meta(stringResource(R.string.nsync_migrate_merged, r.merged.size))
                r.failed.forEach { Warning(stringResource(R.string.nsync_migrate_failed_item, it.title, it.reason)) }
                r.warnings.forEach { Warning(stringResource(R.string.nsync_migrate_missing_pages, it.id, it.missingPages.size)) }
            }

            is MigrationState.Failed -> Warning(stringResource(R.string.nsync_migrate_error, state.message))
            MigrationState.Idle -> Unit
        }
    }
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

/** Something that needs attention: a notice with the warning mark, so it reads without color. */
@Composable
private fun Warning(text: String) = NNotice(text, icon = FeatherIcons.AlertTriangle)

@Composable
private fun Body(text: String) = Text(text, style = NotableTheme.type.body, color = NotableTheme.colors.ink)

@Composable
private fun Meta(text: String) = Text(text, style = NotableTheme.type.meta, color = NotableTheme.colors.secondary)
