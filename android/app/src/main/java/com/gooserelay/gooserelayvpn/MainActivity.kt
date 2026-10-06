package com.gooserelay.gooserelayvpn

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.lifecycleScope
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.navigation.AppNavigation
import com.gooserelay.gooserelayvpn.ui.theme.GooseRelayVPNTheme
import com.gooserelay.gooserelayvpn.util.ImportIntentGate
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var profileRepository: ProfileRepository

    @Volatile
    private var lastHandledImportUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastHandledImportUri = savedInstanceState?.getString(ImportIntentGate.KEY_LAST_HANDLED_URI)
        if (savedInstanceState == null) handleJsonImportIntent(intent, isFirstCreation = true)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                GooseRelayVPNTheme {
                    AppNavigation()
                }
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