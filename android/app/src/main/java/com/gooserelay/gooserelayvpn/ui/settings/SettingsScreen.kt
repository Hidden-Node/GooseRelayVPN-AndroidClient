package com.gooserelay.gooserelayvpn.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val profile = viewModel.selectedProfile.collectAsState().value
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    if (profile == null) {
        Scaffold(topBar = { MdvBackTopAppBar(title = stringResource(R.string.profile_settings_title), onBack = onBack) }) { p ->
            Text(stringResource(R.string.settings_no_profile_title), modifier = Modifier.padding(p).padding(16.dp))
        }
        return
    }

    var debugTiming by rememberSaveable(profile.id) { mutableStateOf(profile.debugTiming) }
    var socksHost by rememberSaveable(profile.id) { mutableStateOf(profile.socksHost) }
    var socksPort by rememberSaveable(profile.id) { mutableStateOf(profile.socksPort.toString()) }
    var socksUser by rememberSaveable(profile.id) { mutableStateOf(profile.socksUser) }
    var socksPass by rememberSaveable(profile.id) { mutableStateOf(profile.socksPass) }
    var googleHost by rememberSaveable(profile.id) { mutableStateOf(profile.googleHost) }
    var sniText by rememberSaveable(profile.id) { mutableStateOf(profile.sniJson.removePrefix("[").removeSuffix("]").replace("\"", "")) }
    var scriptKeys by rememberSaveable(profile.id) { mutableStateOf(profile.scriptKeysText) }
    var tunnelKey by rememberSaveable(profile.id) { mutableStateOf(profile.tunnelKey) }

    val clipboardManager = LocalClipboardManager.current

    fun applyImportedProfile(json: String) {
        val raw = if (json.trim().startsWith("goose-relay://")) {
            try {
                val base64Content = json.trim().substring("goose-relay://".length)
                val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                String(decodedBytes)
            } catch (_: Exception) {
                validationMessage = context.getString(R.string.settings_import_failed_invalid_format)
                return
            }
        } else {
            json
        }

        val updated = viewModel.importJsonToProfile(profile, raw)
        if (updated == null) {
            validationMessage = context.getString(R.string.settings_import_failed_invalid_json)
            return
        }
        if ((updated.socksUser.isBlank()) != (updated.socksPass.isBlank())) {
            validationMessage = context.getString(R.string.settings_import_auth_mismatch)
            return
        }
        debugTiming = updated.debugTiming
        socksHost = updated.socksHost
        socksPort = updated.socksPort.toString()
        socksUser = updated.socksUser
        socksPass = updated.socksPass
        googleHost = updated.googleHost
        sniText = updated.sniJson.removePrefix("[").removeSuffix("]").replace("\"", "")
        scriptKeys = updated.scriptKeysText
        tunnelKey = updated.tunnelKey
    }

    val ioScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ioScope.launch {
            val raw = withContext(Dispatchers.IO) { readTextFromUri(context, uri) }
            applyImportedProfile(raw)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val updated = profile.copy(
            debugTiming = debugTiming,
            socksHost = socksHost,
            socksPort = socksPort.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
            socksUser = socksUser,
            socksPass = socksPass,
            googleHost = googleHost,
            sniJson = "[\"" + sniText.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("\",\"") + "\"]",
            scriptKeysText = scriptKeys,
            tunnelKey = tunnelKey
        )
        ioScope.launch {
            withContext(Dispatchers.IO) {
                writeTextToUri(context, uri, viewModel.exportConfigJson(updated))
            }
        }
    }

    Scaffold(
        topBar = { MdvBackTopAppBar(title = stringResource(R.string.profile_settings_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LaunchedEffect(validationMessage) {
            validationMessage?.let {
                snackbar.showSnackbar(it)
                validationMessage = null
            }
        }

        // Flush the pending autosave immediately (used by the debounced
        // effect below and by the dispose flush). Reads the latest field
        // states, so trailing edits are never lost.
        fun flushAutosave() {
            if ((socksUser.isBlank()) != (socksPass.isBlank())) return
            val portInt = socksPort.toIntOrNull()?.coerceIn(1, 65535)
            val updated = profile.copy(
                debugTiming = debugTiming,
                socksHost = socksHost,
                socksPort = portInt ?: profile.socksPort,
                socksUser = socksUser,
                socksPass = socksPass,
                googleHost = googleHost,
                sniJson = if (sniText.isBlank()) "[]" else "[\"" + sniText.split(",").map { it.trim() }.filter { it.isNotBlank() }.joinToString("\",\"") + "\"]",
                scriptKeysText = scriptKeys,
                tunnelKey = tunnelKey
            )
            if (updated != profile) {
                viewModel.saveProfile(updated)
            }
        }

        LaunchedEffect(debugTiming, socksHost, socksPort, socksUser, socksPass, googleHost, sniText, scriptKeys, tunnelKey) {
            if ((socksUser.isBlank()) != (socksPass.isBlank())) return@LaunchedEffect
            delay(500) // debounce: one Room write per typing pause, not per keystroke
            flushAutosave()
        }

        // If the user leaves before the debounce fires, persist the
        // trailing edits exactly once. (If the debounce already fired,
        // updated == profile and this is a no-op.)
        DisposableEffect(Unit) {
            onDispose { flushAutosave() }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val contentTextStyle = LocalTextStyle.current.copy(
                textDirection = TextDirection.Ltr,
                textAlign = TextAlign.Left
            )

            Text(stringResource(R.string.settings_debug_timing))
            Switch(checked = debugTiming, onCheckedChange = { debugTiming = it })
            OutlinedTextField(socksHost, { socksHost = it }, label = { Text(stringResource(R.string.settings_socks_host)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksPort, { socksPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.settings_socks_port)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksUser, { socksUser = it }, label = { Text(stringResource(R.string.settings_socks_user)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksPass, { socksPass = it }, label = { Text(stringResource(R.string.settings_socks_pass)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(googleHost, { googleHost = it }, label = { Text(stringResource(R.string.settings_google_host)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sniText, { sniText = it }, label = { Text(stringResource(R.string.settings_sni)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(scriptKeys, { scriptKeys = it }, label = { Text(stringResource(R.string.settings_script_keys)) }, textStyle = contentTextStyle, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tunnelKey, { tunnelKey = it }, label = { Text(stringResource(R.string.settings_tunnel_key)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_import_file)) }
                Button(onClick = {
                    val text = clipboardManager.getText()?.text
                    if (text.isNullOrBlank()) {
                        validationMessage = context.getString(R.string.global_clipboard_empty_msg)
                    } else {
                        applyImportedProfile(text)
                    }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.settings_from_clipboard)) }
            }
            Button(onClick = { exportLauncher.launch("goose_profile.json") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.settings_export_json_file)) }
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

private fun writeTextToUri(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
}
