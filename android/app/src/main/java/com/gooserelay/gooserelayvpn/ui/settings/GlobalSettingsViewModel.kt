package com.gooserelay.gooserelayvpn.ui.settings

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gooserelay.gooserelayvpn.util.GlobalSettings
import com.gooserelay.gooserelayvpn.util.GlobalSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GlobalSettingsViewModel(app: Application) : AndroidViewModel(app) {
    data class AppEntry(
        val packageName: String,
        val label: String,
        val firstInstallTime: Long
    )

    val settings: StateFlow<GlobalSettings> = GlobalSettingsStore.observe(app.applicationContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GlobalSettings())

    private val _installedApps = MutableStateFlow<List<AppEntry>>(emptyList())
    val installedApps: StateFlow<List<AppEntry>> = _installedApps

    private var installedAppsJob: Job? = null
    private var installedAppsLoaded = false

    fun save(settings: GlobalSettings) {
        GlobalSettingsStore.saveAsync(getApplication(), settings)
    }

    fun loadInstalledAppsIfNeeded() {
        if (installedAppsLoaded || installedAppsJob?.isActive == true) return
        installedAppsJob = viewModelScope.launch {
            val appList = withContext(Dispatchers.IO) {
                val packageManager = getApplication<Application>().packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launcherPackages = packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.MATCH_ALL
                )
                    .asSequence()
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()

                packageManager.getInstalledApplications(PackageManager.MATCH_ALL)
                    .asSequence()
                    .filter { app -> launcherPackages.contains(app.packageName) }
                    .filter { app -> app.packageName != getApplication<Application>().packageName }
                    .map { app: ApplicationInfo ->
                        val label = runCatching {
                            packageManager.getApplicationLabel(app).toString()
                        }.getOrDefault(app.packageName)
                        val installTime = runCatching {
                            packageManager.getPackageInfo(app.packageName, PackageManager.GET_META_DATA).firstInstallTime
                        }.getOrDefault(0L)
                        AppEntry(app.packageName, label, installTime)
                    }
                    .sortedWith(compareBy<AppEntry> { it.label.lowercase() }.thenBy { it.packageName })
                    .toList()
            }
            _installedApps.value = appList
            installedAppsLoaded = true
        }
    }
}
