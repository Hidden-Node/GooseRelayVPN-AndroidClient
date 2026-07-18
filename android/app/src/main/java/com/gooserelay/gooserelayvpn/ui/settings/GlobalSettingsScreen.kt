package com.gooserelay.gooserelayvpn.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvFilterChip
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvTopAppBar
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.util.GlobalSettings
import com.gooserelay.gooserelayvpn.util.SplitTunnelMode
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.height
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(vm: GlobalSettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val current by vm.settings.collectAsState()
    val installedApps by vm.installedApps.collectAsState()
    var draft by remember(current) { mutableStateOf(current) }
    var sharingSocksPortText by remember(current.internetSharingSocksPort) {
        mutableStateOf(current.internetSharingSocksPort.toString())
    }
    var sharingHttpPortText by remember(current.internetSharingHttpPort) {
        mutableStateOf(current.internetSharingHttpPort.toString())
    }
    var modeExpanded by remember(current) { mutableStateOf(false) }
    var showAppPicker by remember(current) { mutableStateOf(false) }
    var availableQuery by remember(current) { mutableStateOf("") }
    var selectedQuery by remember(current) { mutableStateOf("") }
    var activeTab by remember(current) { mutableStateOf("AVAILABLE") }
    var draftAppSelection by remember(current) { mutableStateOf(parseCsv(current.splitPackagesCsv).toMutableSet()) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val socksPortValue = sharingSocksPortText.toIntOrNull()
    val httpPortValue = sharingHttpPortText.toIntOrNull()
    val socksPortMissing = sharingSocksPortText.isBlank()
    val httpPortMissing = sharingHttpPortText.isBlank()
    val socksPortRequiresRoot = socksPortValue != null && socksPortValue in 1..1024
    val httpPortRequiresRoot = httpPortValue != null && httpPortValue in 1..1024
    val splitPackagesCount by remember(draft.splitPackagesCsv) {
        derivedStateOf { parseCsv(draft.splitPackagesCsv).size }
    }
    val clipboardManager = LocalClipboardManager.current
    val gson = remember { com.google.gson.Gson() }
    var menuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(draft) {
        val normalized = normalize(draft)
        if (normalized != current) {
            vm.save(normalized)
        }
    }

    Scaffold(
        containerColor = MdvColor.Background,
        topBar = {
            MdvTopAppBar(
                title = stringResource(R.string.settings_title),
                actions = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export to Clipboard") },
                                onClick = {
                                    menuExpanded = false
                                    val json = gson.toJson(draft)
                                    clipboardManager.setText(AnnotatedString(json))
                                    scope.launch { snackbarHostState.showSnackbar("Settings copied to clipboard") }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import from Clipboard") },
                                onClick = {
                                    menuExpanded = false
                                    val text = clipboardManager.getText()?.text
                                    if (!text.isNullOrBlank()) {
                                        try {
                                            val imported = gson.fromJson(text, GlobalSettings::class.java)
                                            draft = imported
                                            scope.launch { snackbarHostState.showSnackbar("Settings imported from clipboard") }
                                        } catch (e: Exception) {
                                            scope.launch { snackbarHostState.showSnackbar("Failed to parse settings JSON") }
                                        }
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar("Clipboard is empty") }
                                    }
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val maxContentWidth = when {
                maxWidth >= 1200.dp -> 980.dp
                maxWidth >= 840.dp -> 840.dp
                else -> Dp.Unspecified
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = maxContentWidth),
                    contentPadding = PaddingValues(MdvSpace.S4),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)) {
                            Column(modifier = Modifier.padding(MdvSpace.S3), verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)) {
                        ExposedDropdownMenuBox(
                            expanded = modeExpanded,
                            onExpandedChange = { modeExpanded = !modeExpanded }
                        ) {
                            OutlinedTextField(
                                value = draft.connectionMode,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.global_connection_mode)) },
                                supportingText = { Text(stringResource(R.string.global_connection_mode_help)) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ArrowDropDown,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                                listOf("VPN", "PROXY").forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode) },
                                        onClick = {
                                            draft = draft.copy(connectionMode = mode)
                                            modeExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        RowSwitch(
                            title = stringResource(R.string.global_split_tunneling),
                            checked = draft.splitTunnelingEnabled,
                            onChecked = { draft = draft.copy(splitTunnelingEnabled = it) }
                        )

                        if (draft.splitTunnelingEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = MdvSpace.S3),
                                horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                            ) {
                                MdvFilterChip(
                                    selected = draft.splitTunnelMode == SplitTunnelMode.INCLUDE,
                                    onClick = { draft = draft.copy(splitTunnelMode = SplitTunnelMode.INCLUDE) },
                                    label = "Proxy Only",
                                    modifier = Modifier.weight(1f)
                                )
                                MdvFilterChip(
                                    selected = draft.splitTunnelMode == SplitTunnelMode.EXCLUDE,
                                    onClick = { draft = draft.copy(splitTunnelMode = SplitTunnelMode.EXCLUDE) },
                                    label = "Bypass Only",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(MdvSpace.S2))
                            Card(
                                onClick = {
                                    draftAppSelection = parseCsv(draft.splitPackagesCsv).toMutableSet()
                                    availableQuery = ""
                                    selectedQuery = ""
                                    activeTab = "AVAILABLE"
                                    vm.loadInstalledAppsIfNeeded()
                                    showAppPicker = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(MdvSpace.S3)) {
                                    Text(stringResource(R.string.split_tunnel_apps_title))
                                    Text(
                                        stringResource(R.string.split_tunnel_apps_selected_count, splitPackagesCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MdvColor.OnSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Custom DNS servers field
                        OutlinedTextField(
                            value = draft.customDnsServers,
                            onValueChange = { draft = draft.copy(customDnsServers = it) },
                            label = { Text("Custom DNS Servers") },
                            placeholder = { Text("e.g., 1.1.1.1, 8.8.8.8") },
                            supportingText = {
                                Text(
                                    "Comma-separated DNS servers for VPN mode. Leave empty for defaults. " +
                                    "For remote DNS in Iran: use your VPS IP (if running DNS server) or use Proxy mode instead.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text
                            )
                        )

                        // Fake DNS toggle
                        RowSwitch(
                            title = "Fake DNS (Remote Resolution)",
                            checked = draft.fakeDnsEnabled,
                            onChecked = { draft = draft.copy(fakeDnsEnabled = it) }
                        )
                        if (draft.fakeDnsEnabled) {
                            Text(
                                "Intercepts DNS packets and resolves hostnames at the server. " +
                                "Best for bypassing DNS filtering in Iran. VPN mode only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant,
                                modifier = Modifier.padding(start = MdvSpace.S3)
                            )
                        }

                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MdvColor.SurfaceHigh)) {
                            Column(
                                modifier = Modifier.padding(MdvSpace.S3),
                                verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                            ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.global_sharing_internet), style = MaterialTheme.typography.titleMedium)
                            Switch(
                                checked = draft.internetSharingEnabled,
                                onCheckedChange = { draft = draft.copy(internetSharingEnabled = it) }
                            )
                        }

                        if (draft.internetSharingEnabled) {
                            val localIp = remember { getSystemLocalIp() }

                            if (localIp != null) {
                                Text(
                                    stringResource(R.string.global_local_ip, localIp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MdvColor.PrimaryContainer
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = sharingSocksPortText,
                                    onValueChange = {
                                        if (it.any { ch -> !ch.isDigit() } || it.length > 5) {
                                            return@OutlinedTextField
                                        }
                                        sharingSocksPortText = it
                                        val port = it.toIntOrNull()
                                        if (port != null && port in 1025..65535) {
                                            draft = draft.copy(internetSharingSocksPort = port)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.global_socks5_port)) },
                                    isError = socksPortMissing || socksPortRequiresRoot,
                                    supportingText = {
                                        when {
                                            socksPortMissing -> Text(stringResource(R.string.global_socks5_port_required))
                                            socksPortRequiresRoot -> Text(stringResource(R.string.global_port_root_warning))
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = sharingHttpPortText,
                                    onValueChange = {
                                        if (it.any { ch -> !ch.isDigit() } || it.length > 5) {
                                            return@OutlinedTextField
                                        }
                                        sharingHttpPortText = it
                                        val port = it.toIntOrNull()
                                        if (port != null && port in 1025..65535) {
                                            draft = draft.copy(internetSharingHttpPort = port)
                                        }
                                    },
                                    label = { Text(stringResource(R.string.global_http_port)) },
                                    isError = httpPortMissing || httpPortRequiresRoot,
                                    supportingText = {
                                        when {
                                            httpPortMissing -> Text(stringResource(R.string.global_http_port_required))
                                            httpPortRequiresRoot -> Text(stringResource(R.string.global_port_root_warning))
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            OutlinedTextField(
                                value = draft.internetSharingUser,
                                onValueChange = { draft = draft.copy(internetSharingUser = it) },
                                label = { Text(stringResource(R.string.global_username)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = draft.internetSharingPass,
                                onValueChange = { draft = draft.copy(internetSharingPass = it) },
                                label = { Text(stringResource(R.string.global_password)) },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                stringResource(R.string.global_sharing_help),
                                style = MaterialTheme.typography.bodySmall,
                                color = MdvColor.OnSurfaceVariant
                            )
                        }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAppPicker) {
        val selectedApps by remember(installedApps, draftAppSelection) {
            derivedStateOf { installedApps.filter { draftAppSelection.contains(it.packageName) } }
        }
        val availableApps by remember(installedApps, draftAppSelection) {
            derivedStateOf { installedApps.filterNot { draftAppSelection.contains(it.packageName) } }
        }

        val selectedFiltered by remember(selectedApps, selectedQuery) {
            derivedStateOf {
                val q = selectedQuery.trim().lowercase()
                selectedApps.filter {
                    q.isEmpty() ||
                        it.label.lowercase().contains(q) ||
                        it.packageName.lowercase().contains(q)
                }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            }
        }

        val availableFiltered by remember(availableApps, availableQuery) {
            derivedStateOf {
                val q = availableQuery.trim().lowercase()
                availableApps.filter {
                    q.isEmpty() ||
                        it.label.lowercase().contains(q) ||
                        it.packageName.lowercase().contains(q)
                }.sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
            }
        }

        Dialog(onDismissRequest = { showAppPicker = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp),
                shape = RoundedCornerShape(16.dp),
                color = MdvColor.Surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .padding(MdvSpace.S3),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S3)
                    ) {
                        Text(stringResource(R.string.split_tunnel_dialog_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.split_tunnel_dialog_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MdvColor.OnSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                            MdvFilterChip(
                                selected = activeTab == "SELECTED",
                                onClick = { activeTab = "SELECTED" },
                                label = stringResource(R.string.split_tunnel_selected_count, selectedApps.size)
                            )
                            MdvFilterChip(
                                selected = activeTab == "AVAILABLE",
                                onClick = { activeTab = "AVAILABLE" },
                                label = stringResource(R.string.split_tunnel_available_count, availableApps.size)
                            )
                        }

                        if (activeTab == "SELECTED") {
                            OutlinedTextField(
                                value = selectedQuery,
                                onValueChange = { selectedQuery = it },
                                label = { Text(stringResource(R.string.split_tunnel_search_selected)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            OutlinedTextField(
                                value = availableQuery,
                                onValueChange = { availableQuery = it },
                                label = { Text(stringResource(R.string.split_tunnel_search_available)) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                            OutlinedButton(
                                onClick = {
                                    draftAppSelection = draftAppSelection.toMutableSet().apply {
                                        addAll(availableFiltered.map { it.packageName })
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.split_tunnel_select_visible))
                            }
                            OutlinedButton(
                                onClick = { draftAppSelection = mutableSetOf() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.split_tunnel_select_none))
                            }
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MdvColor.SurfaceHigh
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp)
                            ) {
                                val appsToShow = if (activeTab == "SELECTED") selectedFiltered else availableFiltered
                                val emptyText = if (activeTab == "SELECTED") {
                                    stringResource(R.string.split_tunnel_empty_selected)
                                } else {
                                    stringResource(R.string.split_tunnel_empty_available)
                                }

                                Text(
                                    if (activeTab == "SELECTED") {
                                        stringResource(R.string.split_tunnel_selected_apps)
                                    } else {
                                        stringResource(R.string.split_tunnel_available_apps)
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MdvColor.PrimaryContainer
                                )

                                if (appsToShow.isEmpty()) {
                                    Text(
                                        emptyText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MdvColor.OnSurfaceVariant,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                } else {
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 220.dp, max = 340.dp)
                                    ) {
                                        items(appsToShow, key = { it.packageName }) { app ->
                                            AppRow(
                                                app = app,
                                                checked = draftAppSelection.contains(app.packageName),
                                                onToggle = {
                                                    draftAppSelection = draftAppSelection.toMutableSet().apply {
                                                        if (!add(app.packageName)) remove(app.packageName)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { showAppPicker = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Button(
                            onClick = {
                                draft = draft.copy(splitPackagesCsv = draftAppSelection.sorted().joinToString(","))
                                showAppPicker = false
                            }
                        ) {
                            Text(stringResource(R.string.action_apply))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: GlobalSettingsViewModel.AppEntry,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    var appIconBitmap by remember(app.packageName) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(app.packageName) {
        withContext(Dispatchers.IO) {
            appIconBitmap = runCatching {
                context.packageManager.getApplicationIcon(app.packageName).toBitmap(32, 32)
            }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val bitmap = appIconBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.size(8.dp))
            Column {
                Text(text = app.label)
                Text(text = app.packageName)
            }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() }
        )
    }
}

@Composable
private fun RowSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

private fun parseCsv(value: String): Set<String> {
    return value.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

private fun normalize(settings: GlobalSettings): GlobalSettings {
    return settings.copy(
        connectionMode = settings.connectionMode.uppercase(),
        splitPackagesCsv = settings.splitPackagesCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    )
}

private fun getSystemLocalIp(): String? {
    return try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}
