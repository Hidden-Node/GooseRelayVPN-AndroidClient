package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.gooserelay.gooserelayvpn.ui.theme.ConnectedGreen
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.util.ConfigGenerator

data class ScriptKeyEntry(
    val id: String = "",
    val account: String = ""
)

fun parseScriptKeysText(text: String): List<ScriptKeyEntry> {
    if (text.isBlank()) return emptyList()
    Log.d("ProfilesScreen", "Parsing script keys text: '$text'")
    return text.split("\n")
        .filter { it.isNotBlank() }
        .map { line ->
            if (line.contains("|")) {
                val parts = line.split("|")
                val id = parts[0].trim()
                val account = parts.getOrElse(1) { "" }.trim()
                Log.d("ProfilesScreen", "  Parsed pipe: id='$id', account='$account'")
                ScriptKeyEntry(id, account)
            } else {
                val id = line.trim()
                Log.d("ProfilesScreen", "  Parsed single: id='$id'")
                ScriptKeyEntry(id, "")
            }
        }
}

fun scriptKeysToText(entries: List<ScriptKeyEntry>): String {
    val result = entries
        .filter { it.id.isNotBlank() }
        .joinToString("\n") { entry ->
            if (entry.account.isNotBlank()) "${entry.id}|${entry.account}" else entry.id
        }
    Log.d("ProfilesScreen", "scriptKeysToText output: '$result'")
    return result
}

fun parseProfileFromJson(raw: String, defaultName: String? = null): ProfileEntity? {
    return try {
        val root = Gson().fromJson(raw, JsonObject::class.java)
        
        // Check if it has at least one identifying part
        if (!root.has("script_keys") && !root.has("tunnel_key")) return null

        val name = root.get("name")?.asString ?: defaultName ?: "Imported"
        val debugTiming = root.get("debug_timing")?.asBoolean ?: false
        val socksHost = root.get("socks_host")?.asString ?: "127.0.0.1"
        val socksPort = root.get("socks_port")?.asInt ?: 1080
        val socksUser = root.get("socks_user")?.asString ?: ""
        val socksPass = root.get("socks_pass")?.asString ?: ""
        val googleHost = root.get("google_host")?.asString ?: "216.239.38.120"
        val sniJson = when {
            root.get("sni")?.isJsonArray == true -> Gson().toJson(root.getAsJsonArray("sni").mapNotNull { it.asString })
            root.get("sni")?.isJsonPrimitive == true -> Gson().toJson(listOf(root.get("sni").asString))
            else -> "[\"www.google.com\", \"mail.google.com\", \"accounts.google.com\"]"
        }
        val scriptKeysText = when {
            root.get("script_keys")?.isJsonArray == true -> {
                root.getAsJsonArray("script_keys").mapNotNull { element ->
                    when {
                        element.isJsonObject -> {
                            val obj = element.asJsonObject
                            val id = obj.get("id")?.asString?.trim()
                            val account = obj.get("account")?.asString?.trim()
                            if (id.isNullOrBlank()) null
                            else if (account.isNullOrBlank()) id
                            else "$id|$account"
                        }
                        element.isJsonPrimitive -> element.asString.trim()
                        else -> null
                    }
                }.filter { it.isNotBlank() }.joinToString("\n")
            }
            root.get("script_keys")?.isJsonPrimitive == true -> root.get("script_keys").asString.trim()
            else -> ""
        }
        val coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: 0
        val idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 2
        val tunnelKey = root.get("tunnel_key")?.asString ?: ""

        ProfileEntity(
            name = name,
            debugTiming = debugTiming,
            socksHost = socksHost,
            socksPort = socksPort,
            socksUser = socksUser,
            socksPass = socksPass,
            googleHost = googleHost,
            sniJson = sniJson,
            scriptKeysText = scriptKeysText,
            tunnelKey = tunnelKey,
            coalesceStepMs = coalesceStepMs,
            idleSlotsPerBucket = idleSlotsPerBucket,
            remoteUrl = null
        )
    } catch (_: Exception) {
        null
    }
}

fun parseGooseRelayProtocol(raw: String): ProfileEntity? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("goose-relay://")) return null
    return try {
        val base64Content = trimmed.substring("goose-relay://".length)
        val decodedBytes = Base64.decode(base64Content, Base64.DEFAULT)
        val decodedString = String(decodedBytes)
        parseProfileFromJson(decodedString)
    } catch (_: Exception) {
        null
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst()) {
            val full = cursor.getString(nameIndex)
            full.substringBeforeLast(".")
        } else null
    }
}

@Composable
fun ProfilesScreen(
    onBack: () -> Unit
) {
    val viewModel: ProfilesViewModel = hiltViewModel()
    val profiles by viewModel.profiles.collectAsState()
    val isUpdating by viewModel.isUpdating.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var profilePendingDelete by remember { mutableStateOf<ProfileEntity?>(null) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var profileToExport by remember { mutableStateOf<ProfileEntity?>(null) }

    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(updateMessage) {
        updateMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearUpdateMessage()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null || profileToExport == null) return@rememberLauncherForActivityResult
        writeTextToUri(context, uri, ConfigGenerator.exportProfileJson(profileToExport!!))
        profileToExport = null
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = readTextFromUri(context, uri)
        val fileName = getFileNameFromUri(context, uri)
        val profile = viewModel.parseProfileFromJson(raw, fileName)
        if (profile != null) {
            viewModel.addProfile(profile)
        } else {
            showErrorDialog = "Import failed: invalid JSON format."
        }
    }

    Scaffold(
        topBar = {
            MdvBackTopAppBar(
                title = "Profiles",
                onBack = onBack,
                actions = {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(4.dp),
                            strokeWidth = 2.dp,
                            color = MdvColor.Primary
                        )
                    } else {
                        IconButton(onClick = { viewModel.updateRemoteProfile(context) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Update Remote")
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "Add")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("From Clipboard") },
                                onClick = {
                                    menuExpanded = false
                                    val text = clipboardManager.getText()?.text?.trim()
                                    if (!text.isNullOrBlank()) {
                                        when {
                                            text.startsWith("http://") || text.startsWith("https://") -> {
                                                viewModel.importProfileFromUrl(text, context)
                                            }
                                            else -> {
                                                val profile = parseGooseRelayProtocol(text) ?: parseProfileFromJson(text)
                                                if (profile != null) {
                                                    viewModel.addProfile(profile)
                                                } else {
                                                    showErrorDialog = "Import failed: invalid JSON, URL, or goose-relay:// format."
                                                }
                                            }
                                        }
                                    } else {
                                        showErrorDialog = "Clipboard is empty"
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("From JSON File") },
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add Manual") },
                                onClick = {
                                    menuExpanded = false
                                    editing = null
                                    showEditor = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = MdvColor.OnSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(MdvSpace.S3))
                    Text("No profiles yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(MdvSpace.S1))
                    Text(
                        "Tap the + button above to add a profile\nor import from clipboard/file",
                        style = MaterialTheme.typography.bodySmall,
                        color = MdvColor.OnSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(profiles) { profile ->
                    Card(
                        onClick = { viewModel.selectProfile(profile.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.isSelected)
                                MdvColor.PrimaryContainer.copy(alpha = 0.16f)
                            else
                                MdvColor.SurfaceHigh
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (profile.isSelected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = ConnectedGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile.name)
                                Text("${profile.socksHost}:${profile.socksPort}")
                            }

                            IconButton(onClick = { editing = profile; showEditor = true }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }

                            var shareMenuExpanded by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { shareMenuExpanded = true }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share")
                                }
                                DropdownMenu(
                                    expanded = shareMenuExpanded,
                                    onDismissRequest = { shareMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("to Clipboard") },
                                        onClick = {
                                            shareMenuExpanded = false
                                            val exportObj = JsonObject()
                                            exportObj.addProperty("name", profile.name)
                                            exportObj.addProperty("script_keys", profile.scriptKeysText)
                                            exportObj.addProperty("tunnel_key", profile.tunnelKey)
                                            val json = Gson().toJson(exportObj)
                                            val base64 = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
                                            val protocol = "goose-relay://$base64"
                                            clipboardManager.setText(AnnotatedString(protocol))
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("to JSON") },
                                        onClick = {
                                            shareMenuExpanded = false
                                            profileToExport = profile
                                            exportLauncher.launch("goose_profile_${profile.name}.json")
                                        }
                                    )
                                }
                            }

                            IconButton(onClick = { profilePendingDelete = profile }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    val dialogError = showErrorDialog
    if (dialogError != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("OK")
                }
            },
            title = { Text("Notification") },
            text = { Text(dialogError) }
        )
    }

    if (showEditor) {
        ProfileEditorDialog(
            profile = editing,
            onSave = {
                if (editing == null) viewModel.addProfile(it) else viewModel.updateProfile(it)
                showEditor = false
                editing = null
            },
            onDismiss = { showEditor = false; editing = null }
        )
    }

    profilePendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { profilePendingDelete = null },
            title = { Text(stringResource(com.gooserelay.gooserelayvpn.R.string.profiles_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        com.gooserelay.gooserelayvpn.R.string.profiles_delete_confirm_message,
                        profile.name.ifBlank { stringResource(com.gooserelay.gooserelayvpn.R.string.profiles_dialog_new_title) }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        profilePendingDelete = null
                    }
                ) {
                    Text(stringResource(com.gooserelay.gooserelayvpn.R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { profilePendingDelete = null }) {
                    Text(stringResource(com.gooserelay.gooserelayvpn.R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorDialog(
    profile: ProfileEntity?,
    onSave: (ProfileEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "Default") }
    var debugTiming by remember { mutableStateOf(profile?.debugTiming ?: false) }
    var socksHost by remember { mutableStateOf(profile?.socksHost ?: "127.0.0.1") }
    var socksPort by remember { mutableStateOf((profile?.socksPort ?: 1080).toString()) }
    var socksUser by remember { mutableStateOf(profile?.socksUser ?: "") }
    var socksPass by remember { mutableStateOf(profile?.socksPass ?: "") }
    var googleHost by remember { mutableStateOf(profile?.googleHost ?: "216.239.38.120") }
    var sniCsv by remember { mutableStateOf(profile?.sniJson?.replace("[", "")?.replace("]", "")?.replace("\"", "") ?: "www.google.com, mail.google.com, accounts.google.com") }
    var scriptKeyEntries by remember { mutableStateOf(parseScriptKeysText(profile?.scriptKeysText ?: "").ifEmpty { listOf(ScriptKeyEntry()) }) }
    var tunnelKey by remember { mutableStateOf(profile?.tunnelKey ?: "") }
    var coalesceStepMs by remember { mutableStateOf((profile?.coalesceStepMs ?: 0).toString()) }
    var idleSlotsPerBucket by remember { mutableStateOf((profile?.idleSlotsPerBucket ?: 2).toString()) }
    var remoteUrl by remember { mutableStateOf(profile?.remoteUrl ?: "") }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val error = when {
                    scriptKeyEntries.none { it.id.isNotBlank() } -> "At least one script key is required"
                    tunnelKey.isBlank() -> "Tunnel key is required"
                    (socksUser.isBlank()) != (socksPass.isBlank()) -> "socks_user and socks_pass must both be set or both be empty"
                    else -> null
                }
                if (error != null) {
                    showErrorDialog = error
                    return@Button
                }
                val sniJson = sniCsv.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    .joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")
                
                val scriptKeysForSave = scriptKeysToText(scriptKeyEntries)
                onSave(
                    ProfileEntity(
                        id = profile?.id ?: 0,
                        name = name,
                        debugTiming = debugTiming,
                        socksHost = socksHost,
                        socksPort = socksPort.toIntOrNull()?.coerceIn(1, 65535) ?: 1080,
                        socksUser = socksUser,
                        socksPass = socksPass,
                        googleHost = googleHost,
                        sniJson = sniJson,
                        scriptKeysText = scriptKeysForSave,
                        tunnelKey = tunnelKey,
                        coalesceStepMs = coalesceStepMs.toIntOrNull() ?: 0,
                        idleSlotsPerBucket = idleSlotsPerBucket.toIntOrNull()?.coerceIn(1, 3) ?: 2,
                        remoteUrl = remoteUrl.ifBlank { null },
                        isSelected = profile?.isSelected ?: false,
                        createdAt = profile?.createdAt ?: System.currentTimeMillis()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (profile == null) "New Profile" else "Edit Profile") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label = { Text("Remote URL (Subscription)") },
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("If set, this profile will update from this URL") }
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Profile Name") })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Debug Timing")
                    Switch(checked = debugTiming, onCheckedChange = { debugTiming = it })
                }
                var socksHostExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = socksHostExpanded,
                    onExpandedChange = { socksHostExpanded = it }
                ) {
                    OutlinedTextField(
                        value = socksHost,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("socks_host") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = socksHostExpanded) },
                        modifier = Modifier.menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = socksHostExpanded,
                        onDismissRequest = { socksHostExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("127.0.0.1 (localhost only)") },
                            onClick = { socksHost = "127.0.0.1"; socksHostExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("0.0.0.0 (all interfaces)") },
                            onClick = { socksHost = "0.0.0.0"; socksHostExpanded = false }
                        )
                    }
                }
                OutlinedTextField(value = socksPort, onValueChange = { socksPort = it.filter(Char::isDigit) }, label = { Text("socks_port") })
                OutlinedTextField(value = socksUser, onValueChange = { socksUser = it }, label = { Text("socks_user") })
                OutlinedTextField(value = socksPass, onValueChange = { socksPass = it }, label = { Text("socks_pass") })
                OutlinedTextField(value = googleHost, onValueChange = { googleHost = it }, label = { Text("google_host") })
                OutlinedTextField(value = sniCsv, onValueChange = { sniCsv = it }, label = { Text("sni (comma separated)") })
                ScriptKeysEditor(
                    entries = scriptKeyEntries,
                    onEntriesChanged = { scriptKeyEntries = it }
                )
                OutlinedTextField(value = tunnelKey, onValueChange = { tunnelKey = it }, label = { Text("tunnel_key") })
                OutlinedTextField(value = coalesceStepMs, onValueChange = { coalesceStepMs = it.filter(Char::isDigit) }, label = { Text("coalesce_step_ms") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = idleSlotsPerBucket, onValueChange = { idleSlotsPerBucket = it.filter(Char::isDigit) }, label = { Text("idle_slots (1-3)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(2.dp))
            }
        }
    )

    val dialogError = showErrorDialog
    if (dialogError != null) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text("OK")
                }
            },
            title = { Text("Error") },
            text = { Text(dialogError) }
        )
    }
}

@Composable
private fun ScriptKeysEditor(
    entries: List<ScriptKeyEntry>,
    onEntriesChanged: (List<ScriptKeyEntry>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "script_keys: For per-account parallelism, add Deployment ID and account name (e.g., id|account)",
            style = MaterialTheme.typography.bodySmall,
            color = MdvColor.OnSurfaceVariant
        )

        entries.forEachIndexed { index, entry ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceLow)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        OutlinedTextField(
                            value = entry.id,
                            onValueChange = { newId ->
                                val newEntries = entries.toMutableList()
                                newEntries[index] = entry.copy(id = newId)
                                onEntriesChanged(newEntries)
                            },
                            label = { Text("Deployment ID") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = entry.account,
                            onValueChange = { newAccount ->
                                val newEntries = entries.toMutableList()
                                newEntries[index] = entry.copy(account = newAccount)
                                onEntriesChanged(newEntries)
                            },
                            label = { Text("Account") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    IconButton(
                        onClick = {
                            val newEntries = entries.toMutableList()
                            newEntries.removeAt(index)
                            onEntriesChanged(newEntries)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MdvColor.Error
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = {
                val newEntries = entries.toMutableList()
                newEntries.add(ScriptKeyEntry())
                onEntriesChanged(newEntries)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add script key")
        }
    }
}

private fun readTextFromUri(context: Context, uri: Uri): String {
    return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
}

private fun writeTextToUri(context: Context, uri: Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
}
