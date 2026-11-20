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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ethran.notable.R
import com.ethran.notable.ui.design.NButton
import com.ethran.notable.ui.design.NButtonStyle
import com.ethran.notable.ui.design.NField
import com.ethran.notable.ui.design.NNotice
import com.ethran.notable.ui.design.NSection
import com.ethran.notable.ui.design.NTextField
import com.ethran.notable.ui.design.NotableSpace
import com.ethran.notable.ui.design.NotableTheme
import compose.icons.FeatherIcons
import compose.icons.feathericons.LogOut
import kotlinx.coroutines.launch

/** Signing in to Flow (flow.humane.tools) with a humane tools account, for the voice screen. */
@Composable
fun FlowSection(viewModel: FlowViewModel = hiltViewModel()) {
    val client = viewModel.client
    val account by client.account.collectAsState()
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("https://flow.humane.tools") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    NSection(title = stringResource(R.string.flow_title), description = stringResource(R.string.flow_settings_intro)) {
        Column(verticalArrangement = Arrangement.spacedBy(NotableSpace.md)) {
            val a = account
            if (a != null) {
                Text(stringResource(R.string.flow_signed_in_as, a.email, a.url), style = NotableTheme.type.body, color = NotableTheme.colors.ink)
                NButton(stringResource(R.string.flow_sign_out), { client.signOut() }, icon = FeatherIcons.LogOut)
            } else Column(
                Modifier.widthIn(max = 480.dp).padding(top = NotableSpace.sm),
                verticalArrangement = Arrangement.spacedBy(NotableSpace.lg),
            ) {
                NField(stringResource(R.string.flow_server)) {
                    NTextField(url, { url = it }, Modifier.fillMaxWidth(), textStyle = NotableTheme.type.mono,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                }
                NField(stringResource(R.string.flow_email)) {
                    NTextField(email, { email = it }, Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                }
                NField(stringResource(R.string.nsync_password)) {
                    NTextField(password, { password = it }, Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                NButton(
                    stringResource(if (busy) R.string.flow_signing_in else R.string.flow_sign_in),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            error = runCatching { client.signIn(url, email, password) }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                            if (error == null) password = ""
                            busy = false
                        }
                    },
                    style = NButtonStyle.Primary,
                    enabled = !busy && url.isNotBlank() && email.isNotBlank() && password.isNotEmpty(),
                )
            }
            error?.let { NNotice(it) }
        }
    }
}
