package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.local.SubscriptionEntity
import com.gooserelay.gooserelayvpn.ui.components.mdv.cards.MdvCardLow
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvBackTopAppBar
import com.gooserelay.gooserelayvpn.ui.components.mdv.controls.MdvFilterChip
import com.gooserelay.gooserelayvpn.ui.theme.ConnectedGreen
import com.gooserelay.gooserelayvpn.ui.theme.MdvColor
import com.gooserelay.gooserelayvpn.ui.theme.MdvRadius
import com.gooserelay.gooserelayvpn.ui.theme.MdvSpace
import com.gooserelay.gooserelayvpn.ui.scanner.ScannerActivity
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.GooseRelayProtocol
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import com.gooserelay.gooserelayvpn.util.QrCodeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScriptKeyEntry(
    val id: String = "",
    val account: String = ""
)

fun parseScriptKeysText(text: String): List<ScriptKeyEntry> {
    if (text.isBlank()) return emptyList()
    return text.split("\n")
        .filter { it.isNotBlank() }
        .map { line ->
            if (line.contains("|")) {
                val parts = line.split("|")
                val id = parts[0].trim()
                val account = parts.getOrElse(1) { "" }.trim()
                ScriptKeyEntry(id, account)
            } else {
                val id = line.trim()
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
    return result
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) {
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
    val profilesOrNull by viewModel.profiles.collectAsState()
    val profiles = profilesOrNull.orEmpty()
    val isUpdating by viewModel.isUpdating.collectAsState()
    val updateMessage by viewModel.updateMessage.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editing = profiles.find { it.id == editingId }
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val profilePendingDelete = profiles.find { it.id == pendingDeleteId }
    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var exportTargetId by rememberSaveable { mutableStateOf<Long?>(null) }
    var shareQrProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val subscriptionsOrNull by viewModel.subscriptions.collectAsState()
    val subscriptions = subscriptionsOrNull.orEmpty()
    val addSubscriptionState by viewModel.addSubscription.collectAsState()
    var selectedSubscriptionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showManageSubscriptions by rememberSaveable { mutableStateOf(false) }
    var pendingSubscriptionDelete by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingImportUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val displayedProfiles = remember(profiles, selectedSubscriptionId) {
        selectedSubscriptionId?.let { id -> profiles.filter { it.subscriptionId == id } }
            ?: profiles
    }
    LaunchedEffect(profilesOrNull, selectedSubscriptionId) {
        val loaded = profilesOrNull ?: return@LaunchedEffect
        if (loaded.isEmpty()) selectedSubscriptionId = null
    }
    LaunchedEffect(subscriptionsOrNull, selectedSubscriptionId) {
        val loaded = subscriptionsOrNull ?: return@LaunchedEffect
        if (selectedSubscriptionId != null && loaded.none { it.id == selectedSubscriptionId }) {
            selectedSubscriptionId = null
        }
    }

    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(updateMessage) {
        val msg = updateMessage ?: return@LaunchedEffect
        viewModel.clearUpdateMessage()
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            exportTargetId = null
            return@rememberLauncherForActivityResult
        }
        val target = profiles.find { it.id == exportTargetId }
        if (target == null) {
            showErrorDialog = context.getString(R.string.profiles_export_target_missing)
            exportTargetId = null
            return@rememberLauncherForActivityResult
        }
        viewModel.exportProfileToFile(target, uri, context.contentResolver) { exportTargetId = null }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        selectedSubscriptionId = null
        val fileName = getFileNameFromUri(context, uri)
        viewModel.importProfileFromUri(uri, context.contentResolver, fileName) {
            showErrorDialog = context.getString(R.string.profiles_import_failed_general)
        }
    }

    val qrScanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val text = result.data?.getStringExtra(ScannerActivity.EXTRA_SCAN_RESULT)?.trim()
            val detectedUrl = ProfilesViewModel.extractWebUrl(text)
            if (!detectedUrl.isNullOrEmpty()) {
                pendingImportUrl = detectedUrl
            } else if (text.isNullOrBlank()) {
                showErrorDialog = context.getString(R.string.profiles_import_failed_general)
            } else {
                val profile = GooseRelayProtocol.parse(text) ?: ProfileJsonParser.parse(text)
                if (profile != null) {
                    selectedSubscriptionId = null
                    viewModel.addProfile(profile)
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.profiles_import_deep_link_success, profile.name)
                        )
                    }
                } else {
                    showErrorDialog = context.getString(R.string.profiles_import_failed_general)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            MdvBackTopAppBar(
                title = stringResource(R.string.title_profiles),
                onBack = onBack,
                actions = {
                    if (subscriptions.isNotEmpty()) {
                        IconButton(onClick = { showManageSubscriptions = true }) {
                            Icon(
                                Icons.Filled.FolderSpecial,
                                contentDescription = stringResource(R.string.subscriptions_manage)
                            )
                        }
                    }
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(4.dp),
                            strokeWidth = 2.dp,
                            color = MdvColor.Primary
                        )
                    } else if (subscriptions.isNotEmpty()) {
                        IconButton(onClick = { viewModel.refreshAllSubscriptions(context) }) {
                            Icon(
                                Icons.Filled.Sync,
                                contentDescription = stringResource(R.string.subscriptions_sync_all)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add))
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.subscriptions_add)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.CloudDownload, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.openAddSubscriptionDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_from_clipboard)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    val text = clipboardManager.getText()?.text?.trim()
                                    val detectedUrl = ProfilesViewModel.extractWebUrl(text)
                                    if (!detectedUrl.isNullOrEmpty()) {
                                        pendingImportUrl = detectedUrl
                                    } else if (!text.isNullOrBlank()) {
                                        val profile = GooseRelayProtocol.parse(text) ?: ProfileJsonParser.parse(text)
                                        if (profile != null) {
                                            selectedSubscriptionId = null
                                            viewModel.addProfile(profile)
                                        } else {
                                            showErrorDialog = context.getString(R.string.profiles_import_failed_general)
                                        }
                                    } else {
                                        showErrorDialog = context.getString(R.string.global_clipboard_empty_msg)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_qr)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    qrScanLauncher.launch(Intent(context, ScannerActivity::class.java))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_from_json_file)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.profiles_add_manual)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Edit, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    editingId = null
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
                    Text(stringResource(R.string.profiles_empty_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(MdvSpace.S1))
                    Text(
                        stringResource(R.string.profiles_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MdvColor.OnSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (subscriptions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = MdvSpace.S3, vertical = MdvSpace.S2),
                        horizontalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                    ) {
                        item(key = "all") {
                            MdvFilterChip(
                                selected = selectedSubscriptionId == null,
                                label = stringResource(R.string.subscriptions_all_filter),
                                onClick = { selectedSubscriptionId = null }
                            )
                        }
                        items(subscriptions, key = { it.id }) { subscription ->
                            val count = profiles.count { it.subscriptionId == subscription.id }
                            MdvFilterChip(
                                selected = selectedSubscriptionId == subscription.id,
                                label = "${subscription.name} ($count)",
                                onClick = {
                                    selectedSubscriptionId = if (selectedSubscriptionId == subscription.id) null else subscription.id
                                }
                            )
                        }
                    }
                }
                if (displayedProfiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.subscriptions_empty_filtered),
                            color = MdvColor.OnSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = MdvSpace.S3),
                        verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                    ) {
                        items(displayedProfiles, key = { it.id }) { profile ->
                            val cardContainer = if (profile.isSelected) {
                                MdvColor.PrimaryContainer.copy(alpha = 0.16f)
                            } else {
                                MdvColor.SurfaceHigh
                            }
                            Card(
                                onClick = { viewModel.selectProfile(profile.id) },
                                enabled = !isUpdating,
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = cardContainer,
                                    contentColor = MdvColor.OnSurface,
                                    disabledContainerColor = cardContainer,
                                    disabledContentColor = MdvColor.OnSurface
                                )
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (profile.isSelected) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = stringResource(R.string.profiles_selected),
                                            tint = ConnectedGreen,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(profile.name)
                                        Text("${profile.socksHost}:${profile.socksPort}")
                                    }

                                    IconButton(
                                        onClick = { editingId = profile.id; showEditor = true },
                                        enabled = !isUpdating
                                    ) {
                                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                                    }

                                    var shareMenuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(
                                            onClick = { shareMenuExpanded = true },
                                            enabled = !isUpdating
                                        ) {
                                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                                        }
                                        DropdownMenu(
                                            expanded = shareMenuExpanded,
                                            onDismissRequest = { shareMenuExpanded = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profiles_to_clipboard)) },
                                                onClick = {
                                                    shareMenuExpanded = false
                                                    val json = ConfigGenerator.exportProfileJson(profile)
                                                    val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                                    val protocol = "goose-relay://$base64"
                                                    clipboardManager.setText(AnnotatedString(protocol))
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profiles_to_json)) },
                                                onClick = {
                                                    shareMenuExpanded = false
                                                    exportTargetId = profile.id
                                                    exportLauncher.launch("goose_profile_${profile.name}.json")
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profiles_share_qr)) },
                                                onClick = {
                                                    shareMenuExpanded = false
                                                    shareQrProfileId = profile.id
                                                }
                                            )
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.profiles_share_sheet)) },
                                                onClick = {
                                                    shareMenuExpanded = false
                                                    val json = ConfigGenerator.exportProfileJson(profile)
                                                    val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                                    val url = "goose-relay://$base64"
                                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                        type = "text/plain"
                                                        putExtra(Intent.EXTRA_SUBJECT, profile.name)
                                                        putExtra(Intent.EXTRA_TEXT, url)
                                                    }
                                                    val chooser = Intent.createChooser(sendIntent, context.getString(R.string.profiles_share_title))
                                                    try {
                                                        context.startActivity(chooser)
                                                    } catch (_: ActivityNotFoundException) {
                                                        showErrorDialog = context.getString(R.string.profiles_import_failed_general)
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    IconButton(
                                        onClick = { pendingDeleteId = profile.id },
                                        enabled = !isUpdating
                                    ) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.profiles_delete))
                                    }
                                }
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
                    Text(stringResource(R.string.action_ok))
                }
            },
            title = { Text(stringResource(R.string.common_notification)) },
            text = { Text(dialogError) }
        )
    }

    if (showEditor) {
        ProfileEditorDialog(
            profile = editing,
            onSave = {
                if (editing == null) {
                    viewModel.addProfile(it)
                    selectedSubscriptionId = null
                } else {
                    viewModel.updateProfile(it)
                }
                showEditor = false
                editingId = null
            },
            onDismiss = { showEditor = false; editingId = null }
        )
    }

    val shareQrProfile = profiles.find { it.id == shareQrProfileId }
    if (shareQrProfile != null) {
        val shareUrl = remember(shareQrProfile) {
            val json = ConfigGenerator.exportProfileJson(shareQrProfile)
            val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "goose-relay://$base64"
        }
        val qrBitmap by produceState<Bitmap?>(initialValue = null, shareUrl) {
            value = withContext(Dispatchers.Default) {
                runCatching { QrCodeUtil.encodeAsBitmap(shareUrl, sizePx = 800) }.getOrNull()
            }
        }
        AlertDialog(
            onDismissRequest = { shareQrProfileId = null },
            title = { Text(stringResource(R.string.profiles_share_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                    Text(
                        text = shareQrProfile.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(MdvRadius.Lg))
                                .background(MdvColor.QrBackground)
                                .padding(MdvSpace.S2),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = stringResource(R.string.profiles_share_qr_cd),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        OutlinedTextField(
                            value = shareUrl,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            maxLines = 4,
                            textStyle = LocalTextStyle.current.copy(
                                textDirection = TextDirection.Ltr,
                                textAlign = TextAlign.Start
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { shareQrProfileId = null }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { shareQrProfileId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (addSubscriptionState.visible) {
        AddSubscriptionDialog(
            state = addSubscriptionState,
            onNameChange = viewModel::onSubscriptionNameChange,
            onUrlChange = viewModel::onSubscriptionUrlChange,
            onSubmit = { viewModel.submitSubscription(context) },
            onDismiss = viewModel::dismissAddSubscriptionDialog
        )
    }

    pendingImportUrl?.let { url ->
        UrlChoiceDialog(
            url = url,
            onAddSubscription = {
                pendingImportUrl = null
                viewModel.openAddSubscriptionDialog(initialUrl = url)
            },
            onImportSingleProfile = {
                pendingImportUrl = null
                selectedSubscriptionId = null
                viewModel.importProfileFromUrl(url, context)
            },
            onDismiss = { pendingImportUrl = null }
        )
    }

    if (showManageSubscriptions) {
        ManageSubscriptionsDialog(
            subscriptions = subscriptions,
            profileCounts = subscriptions.associate { sub ->
                sub.id to profiles.count { it.subscriptionId == sub.id }
            },
            isUpdating = isUpdating,
            onRefresh = { viewModel.refreshSubscription(context, it) },
            onDelete = {
                showManageSubscriptions = false
                pendingSubscriptionDelete = it
            },
            onDismiss = { showManageSubscriptions = false }
        )
    }

    pendingSubscriptionDelete?.let { id ->
        val subscription = subscriptions.find { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingSubscriptionDelete = null },
            title = { Text(stringResource(R.string.subscriptions_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.subscriptions_delete_confirm_message,
                        subscription?.name ?: stringResource(R.string.subscriptions_default_name)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSubscription(id)
                    pendingSubscriptionDelete = null
                }) { Text(stringResource(R.string.subscriptions_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSubscriptionDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    profilePendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.profiles_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.profiles_delete_confirm_message,
                        profile.name.ifBlank { stringResource(R.string.profiles_dialog_new_title) }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile)
                        pendingDeleteId = null
                    }
                ) {
                    Text(stringResource(R.string.profiles_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.action_cancel))
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
    val context = LocalContext.current
    val contentTextStyle = LocalTextStyle.current.copy(
        textDirection = TextDirection.Ltr,
        textAlign = TextAlign.Left
    )
    var name by rememberSaveable { mutableStateOf(profile?.name ?: "Default") }
    var debugTiming by rememberSaveable { mutableStateOf(profile?.debugTiming ?: false) }
    var socksHost by rememberSaveable { mutableStateOf(profile?.socksHost ?: "127.0.0.1") }
    var socksPort by rememberSaveable { mutableStateOf((profile?.socksPort ?: 1080).toString()) }
    var socksUser by rememberSaveable { mutableStateOf(profile?.socksUser ?: "") }
    var socksPass by rememberSaveable { mutableStateOf(profile?.socksPass ?: "") }
    var googleHost by rememberSaveable { mutableStateOf(profile?.googleHost ?: "216.239.38.120") }
    var sniCsv by rememberSaveable { mutableStateOf(profile?.sniJson?.replace("[", "")?.replace("]", "")?.replace("\"", "") ?: "www.google.com, mail.google.com, accounts.google.com") }
    var scriptKeyEntries by rememberSaveable(
        saver = Saver<MutableState<List<ScriptKeyEntry>>, Any>(
            save = { scriptKeysToText(it.value) },
            restore = {
                val restored = (it as? String)?.let(::parseScriptKeysText).orEmpty()
                mutableStateOf(restored.ifEmpty { listOf(ScriptKeyEntry()) })
            }
        )
    ) { mutableStateOf(parseScriptKeysText(profile?.scriptKeysText ?: "").ifEmpty { listOf(ScriptKeyEntry()) }) }
    var tunnelKey by rememberSaveable { mutableStateOf(profile?.tunnelKey ?: "") }
    var coalesceStepMs by rememberSaveable { mutableStateOf((profile?.coalesceStepMs ?: 0).toString()) }
    var idleSlotsPerBucket by rememberSaveable { mutableStateOf((profile?.idleSlotsPerBucket ?: 2).toString()) }
    var showErrorDialog by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                val error = when {
                    scriptKeyEntries.none { it.id.isNotBlank() } -> context.getString(R.string.profiles_validation_script_keys_required)
                    tunnelKey.isBlank() -> context.getString(R.string.profiles_validation_tunnel_key_required)
                    (socksUser.isBlank()) != (socksPass.isBlank()) -> context.getString(R.string.profiles_validation_auth_mismatch)
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
                        subscriptionId = profile?.subscriptionId,
                        isSelected = profile?.isSelected ?: false,
                        createdAt = profile?.createdAt ?: System.currentTimeMillis()
                    )
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        title = { Text(stringResource(if (profile == null) R.string.profiles_dialog_new_title else R.string.profiles_dialog_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.profiles_name)) })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.settings_debug_timing))
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
                        label = { Text(stringResource(R.string.settings_socks_host)) },
                        textStyle = contentTextStyle,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = socksHostExpanded) },
                        modifier = Modifier.menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = socksHostExpanded,
                        onDismissRequest = { socksHostExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_host_localhost)) },
                            onClick = { socksHost = "127.0.0.1"; socksHostExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.profiles_host_all_interfaces)) },
                            onClick = { socksHost = "0.0.0.0"; socksHostExpanded = false }
                        )
                    }
                }
                OutlinedTextField(value = socksPort, onValueChange = { socksPort = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.settings_socks_port)) }, textStyle = contentTextStyle)
                OutlinedTextField(value = socksUser, onValueChange = { socksUser = it }, label = { Text(stringResource(R.string.settings_socks_user)) }, textStyle = contentTextStyle)
                OutlinedTextField(value = socksPass, onValueChange = { socksPass = it }, label = { Text(stringResource(R.string.settings_socks_pass)) }, textStyle = contentTextStyle)
                OutlinedTextField(value = googleHost, onValueChange = { googleHost = it }, label = { Text(stringResource(R.string.settings_google_host)) }, textStyle = contentTextStyle)
                OutlinedTextField(value = sniCsv, onValueChange = { sniCsv = it }, label = { Text(stringResource(R.string.settings_sni)) }, textStyle = contentTextStyle)
                ScriptKeysEditor(
                    entries = scriptKeyEntries,
                    onEntriesChanged = { scriptKeyEntries = it },
                    contentTextStyle = contentTextStyle
                )
                OutlinedTextField(value = tunnelKey, onValueChange = { tunnelKey = it }, label = { Text(stringResource(R.string.settings_tunnel_key)) }, textStyle = contentTextStyle)
                OutlinedTextField(value = coalesceStepMs, onValueChange = { coalesceStepMs = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.profiles_coalesce_step)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = idleSlotsPerBucket, onValueChange = { idleSlotsPerBucket = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.profiles_idle_slots)) }, textStyle = contentTextStyle, modifier = Modifier.fillMaxWidth(), singleLine = true)
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
                    Text(stringResource(R.string.action_ok))
                }
            },
            title = { Text(stringResource(R.string.common_error)) },
            text = { Text(dialogError) }
        )
    }
}

@Composable
private fun ScriptKeysEditor(
    entries: List<ScriptKeyEntry>,
    onEntriesChanged: (List<ScriptKeyEntry>) -> Unit,
    modifier: Modifier = Modifier,
    contentTextStyle: androidx.compose.ui.text.TextStyle = LocalTextStyle.current
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.profiles_script_keys_hint),
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
                            label = { Text(stringResource(R.string.profiles_deployment_id)) },
                            textStyle = contentTextStyle,
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
                            label = { Text(stringResource(R.string.profiles_account)) },
                            textStyle = contentTextStyle,
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
                            contentDescription = stringResource(R.string.profiles_delete),
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
            Text(stringResource(R.string.profiles_add_script_key))
        }
    }
}

@Composable
private fun AddSubscriptionDialog(
    state: ProfilesViewModel.AddSubscriptionState,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscriptions_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.subscriptions_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.subscriptions_url_hint)) },
                    textStyle = LocalTextStyle.current.copy(
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Left
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                state.errorRes?.let { Text(stringResource(it), color = MdvColor.Error) }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = !state.submitting) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(MdvSpace.S4),
                        strokeWidth = 2.dp,
                        color = MdvColor.OnPrimary
                    )
                } else {
                    Text(stringResource(R.string.subscriptions_add))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ManageSubscriptionsDialog(
    subscriptions: List<SubscriptionEntity>,
    profileCounts: Map<Long, Int>,
    isUpdating: Boolean,
    onRefresh: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subscriptions_manage)) },
        text = {
            if (subscriptions.isEmpty()) {
                Text(stringResource(R.string.subscriptions_empty))
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
                ) {
                    subscriptions.forEach { subscription ->
                        MdvCardLow(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(MdvSpace.S2),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(subscription.name)
                                    Text(
                                        stringResource(
                                            R.string.subscriptions_profile_count,
                                            profileCounts[subscription.id] ?: 0
                                        ),
                                        color = MdvColor.OnSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRefresh(subscription.id) },
                                    enabled = !isUpdating
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = stringResource(R.string.subscriptions_refresh)
                                    )
                                }
                                IconButton(
                                    onClick = { onDelete(subscription.id) },
                                    enabled = !isUpdating
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.subscriptions_delete)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_ok))
            }
        }
    )
}

@Composable
private fun UrlChoiceDialog(
    url: String,
    onAddSubscription: () -> Unit,
    onImportSingleProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profiles_url_choice_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
            ) {
                Text(stringResource(R.string.profiles_url_choice_dialog_desc))
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = TextDirection.Ltr,
                        textAlign = TextAlign.Start
                    ),
                    color = MdvColor.Primary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MdvSpace.S2)
            ) {
                Button(
                    onClick = onAddSubscription,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(MdvSpace.S4)
                    )
                    Spacer(modifier = Modifier.width(MdvSpace.S2))
                    Text(stringResource(R.string.profiles_import_as_subscription_btn))
                }
                OutlinedButton(
                    onClick = onImportSingleProfile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.profiles_import_as_single_profile_btn))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
        dismissButton = null
    )
}
