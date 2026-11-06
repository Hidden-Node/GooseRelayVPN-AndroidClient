package com.gooserelay.gooserelayvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.navigation.AppNavigation
import com.gooserelay.gooserelayvpn.ui.theme.GooseRelayVPNTheme
import com.gooserelay.gooserelayvpn.util.GooseRelayProtocol
import com.gooserelay.gooserelayvpn.util.ImportIntentGate
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    @Volatile
    private var lastHandledImportUri: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastHandledImportUri = savedInstanceState?.getString(ImportIntentGate.KEY_LAST_HANDLED_URI)
        if (savedInstanceState == null) handleJsonImportIntent(intent, isFirstCreation = true)
        if (savedInstanceState == null) handleDeepLinkIntent(intent)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        enableEdgeToEdge()
        setContent {
            GooseRelayVPNTheme {
                AppNavigation()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ImportIntentGate.KEY_LAST_HANDLED_URI, lastHandledImportUri)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleJsonImportIntent(intent, isFirstCreation = true)
        handleDeepLinkIntent(intent)
    }

    private fun handleJsonImportIntent(intent: Intent?, isFirstCreation: Boolean) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val uri = when {
            intent.data != null -> intent.data
            intent.clipData != null && intent.clipData!!.itemCount > 0 -> intent.clipData!!.getItemAt(0).uri
            else -> null
        } ?: return

        val uriToken = uri.toString()
        if (!ImportIntentGate.shouldHandle(isFirstCreation, uriToken, lastHandledImportUri)) return
        if (!ImportIntentGate.isJsonLike(intent.type, uri.toString())) return

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        lastHandledImportUri = uriToken
        intent.action = null
        intent.data = null
        setIntent(intent)

        lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                val content = readTextFromUri(uri)
                if (content.isBlank()) return@withContext null
                parseImportedProfile(uri, content)
            }
            if (imported == null) {
                Toast.makeText(
                    this@MainActivity,
                    R.string.profiles_invalid_json_msg,
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val id = withContext(Dispatchers.IO) { profileRepository.insertProfile(imported) }
            profileRepository.setSelectedProfile(id)
            Toast.makeText(
                this@MainActivity,
                R.string.profiles_json_imported_msg,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "goose-relay") return
        val rawUri = data.toString()  // use toString(), NOT host/path — see note below
        // Reuse the existing gate (isFirstCreation + already-handled token, restored
        // from savedInstanceState in onCreate) instead of a bare rawUri equality check.
        if (!ImportIntentGate.shouldHandle(true, rawUri, lastHandledImportUri)) return

        val parsed = GooseRelayProtocol.parse(rawUri)
        // Mark handled + clear synchronously BEFORE any async work, exactly like
        // handleJsonImportIntent (91-94), so rotation / process restore cannot re-import.
        lastHandledImportUri = rawUri
        intent.action = null
        intent.data = null
        setIntent(intent)

        if (parsed == null) {
            // LENGTH_LONG to match the existing import-failure Toast (MainActivity.kt:106).
            Toast.makeText(this@MainActivity, R.string.profiles_import_failed_general, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // insertProfileAndSelectIfFirst intentionally does NOT switch the active
                // VPN (it only auto-selects when the DB is empty, like ProfilesViewModel's
                // addProfile). That differs from the JSON file-intent path, which always
                // calls setSelectedProfile — deep-link import must not hijack the active
                // connection mid-session.
                profileRepository.insertProfileAndSelectIfFirst(parsed)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.profiles_import_deep_link_success, parsed.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun readTextFromUri(uri: Uri): String {
        return contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty()
    }

    private fun parseImportedProfile(uri: Uri, jsonContent: String): ProfileEntity? {
        return ProfileJsonParser.parse(jsonContent, readDisplayName(uri) ?: "Imported Profile")
    }

    private fun readDisplayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0 || !cursor.moveToFirst()) return@use null
                cursor.getString(idx)
            }
        }.getOrNull()
            ?.substringBeforeLast(".")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}