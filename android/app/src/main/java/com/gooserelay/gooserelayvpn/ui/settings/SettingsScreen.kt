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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar

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
        Scaffold(topBar = { MdvBackTopAppBar(title = "Profile Settings", onBack = onBack) }) { p ->
            Text("No selected profile", modifier = Modifier.padding(p).padding(16.dp))
        }
        return
    }

    var debugTiming by remember(profile.id) { mutableStateOf(profile.debugTiming) }
    var socksHost by remember(profile.id) { mutableStateOf(profile.socksHost) }
    var socksPort by remember(profile.id) { mutableStateOf(profile.socksPort.toString()) }
    var socksUser by remember(profile.id) { mutableStateOf(profile.socksUser) }
    var socksPass by remember(profile.id) { mutableStateOf(profile.socksPass) }
    var googleHost by remember(profile.id) { mutableStateOf(profile.googleHost) }
    var sniText by remember(profile.id) { mutableStateOf(profile.sniJson.removePrefix("[").removeSuffix("]").replace("\"", "")) }
    var scriptKeys by remember(profile.id) { mutableStateOf(profile.scriptKeysText) }
    var tunnelKey by remember(profile.id) { mutableStateOf(profile.tunnelKey) }

    val clipboardManager = LocalClipboardManager.current

    fun applyImportedProfile(json: String) {
        val raw = if (json.trim().startsWith("goose-relay://")) {
            try {
                val base64Content = json.trim().substring("goose-relay://".length)
                val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
                String(decodedBytes)
            } catch (_: Exception) {
                validationMessage = "Import failed: invalid goose-relay:// format."
                return
            }
        } else {
            json
        }

        val updated = viewModel.importJsonToProfile(profile, raw)
        if (updated == null) {
            validationMessage = "Import failed: invalid JSON format."
            return
        }
        if ((updated.socksUser.isBlank()) != (updated.socksPass.isBlank())) {
            validationMessage = "Import rejected: socks_user and socks_pass must both be set or both be empty (SOCKS5 auth requires both values)."
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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        applyImportedProfile(readTextFromUri(context, uri))
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
        writeTextToUri(context, uri, viewModel.exportConfigJson(updated))
    }

    Scaffold(
        topBar = { MdvBackTopAppBar(title = "Profile Settings", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        LaunchedEffect(validationMessage) {
            validationMessage?.let {
                snackbar.showSnackbar(it)
                validationMessage = null
            }
        }

        LaunchedEffect(debugTiming, socksHost, socksPort, socksUser, socksPass, googleHost, sniText, scriptKeys, tunnelKey) {
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

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("debug_timing")
            Switch(checked = debugTiming, onCheckedChange = { debugTiming = it })
            OutlinedTextField(socksHost, { socksHost = it }, label = { Text("socks_host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksPort, { socksPort = it.filter(Char::isDigit) }, label = { Text("socks_port") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksUser, { socksUser = it }, label = { Text("socks_user") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(socksPass, { socksPass = it }, label = { Text("socks_pass") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(googleHost, { googleHost = it }, label = { Text("google_host") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sniText, { sniText = it }, label = { Text("sni (comma separated)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(scriptKeys, { scriptKeys = it }, label = { Text("script_keys (one per line)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tunnelKey, { tunnelKey = it }, label = { Text("tunnel_key") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Import File") }
                Button(onClick = {
                    val text = clipboardManager.getText()?.text
                    if (text.isNullOrBlank()) {
                        validationMessage = "Clipboard is empty"
                    } else {
                        applyImportedProfile(text)
                    }
                }, modifier = Modifier.weight(1f)) { Text("From Clipboard") }
            }
            Button(onClick = { exportLauncher.launch("goose_profile.json") }, modifier = Modifier.fillMaxWidth()) { Text("Export JSON File") }
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

private fun writeTextToUri(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
}
