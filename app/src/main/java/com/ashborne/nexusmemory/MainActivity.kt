package com.ashborne.nexusmemory

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val isLauncherApp: Boolean
)

class MainActivity : ComponentActivity() {

    private fun getRamInfoMb(): Pair<Long, Long> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb: Long = memInfo.availMem / (1024 * 1024)
        val totalMb: Long = memInfo.totalMem / (1024 * 1024)
        return Pair(availMb, totalMb)
    }

    private fun isCandidateApp(info: ApplicationInfo): Boolean {
        val isSystem: Boolean = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem: Boolean = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        return !isSystem || isUpdatedSystem
    }

    private fun getAppsList(includeNonLauncher: Boolean): List<InstalledAppItem> {
        val pm: PackageManager = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPackages: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }.mapNotNull { it.activityInfo?.packageName }.toSet()

        val appInfos: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }

        return appInfos
            .mapNotNull { info: ApplicationInfo ->
                if (!isCandidateApp(info)) return@mapNotNull null
                val pkg: String = info.packageName
                if (pkg == packageName) return@mapNotNull null
                val hasLauncher: Boolean = launcherPackages.contains(pkg)
                if (!includeNonLauncher && !hasLauncher) return@mapNotNull null
                val label: String = pm.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                InstalledAppItem(
                    appName = label,
                    packageName = pkg,
                    isLauncherApp = hasLauncher
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    private fun openAppSettings(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("nexus_memory_prefs", Context.MODE_PRIVATE)
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        setContent {
            MaterialTheme {
                val initialRam: Pair<Long, Long> = remember { getRamInfoMb() }
                var wifiConnected: Boolean by remember { mutableStateOf<Boolean>(false) }
                var availRamMb: Long by remember { mutableStateOf<Long>(initialRam.first) }
                val totalRamMb: Long = initialRam.second
                var searchQuery: String by remember { mutableStateOf<String>("") }
                var showOnlyChecked: Boolean by remember { mutableStateOf<Boolean>(false) }
                var includeNonLauncher: Boolean by remember { mutableStateOf<Boolean>(true) }

                val installedApps: List<InstalledAppItem> = remember(includeNonLauncher) {
                    getAppsList(includeNonLauncher)
                }

                val keptPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved: Set<String> =
                            prefs.getStringSet("kept_packages", setOf("com.whatsapp")) ?: setOf("com.whatsapp")
                        addAll(saved)
                    }
                }

                fun toggleKept(pkg: String, checked: Boolean) {
                    if (checked) {
                        if (!keptPackages.contains(pkg)) keptPackages.add(pkg)
                    } else {
                        keptPackages.remove(pkg)
                    }
                    prefs.edit().putStringSet("kept_packages", keptPackages.toSet()).apply()
                }

                DisposableEffect(Unit) {
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build()

                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            super.onAvailable(network)
                            val currentRam: Pair<Long, Long> = getRamInfoMb()
                            wifiConnected = true
                            availRamMb = currentRam.first
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            val currentRam: Pair<Long, Long> = getRamInfoMb()
                            wifiConnected = false
                            availRamMb = currentRam.first
                        }
                    }

                    connectivityManager.registerNetworkCallback(request, callback)
                    onDispose {
                        connectivityManager.unregisterNetworkCallback(callback)
                    }
                }

                val filteredApps: List<InstalledAppItem> = remember(searchQuery, showOnlyChecked, keptPackages.toList(), installedApps) {
                    installedApps.filter { item ->
                        val matchesQuery = searchQuery.isBlank() ||
                            item.appName.contains(searchQuery.trim(), ignoreCase = true) ||
                            item.packageName.contains(searchQuery.trim(), ignoreCase = true)
                        val matchesFilter = !showOnlyChecked || keptPackages.contains(item.packageName)
                        matchesQuery && matchesFilter
                    }
                }

                val appsToStopCount: Int = installedApps.count { !keptPackages.contains(it.packageName) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("NexusMemory", style = MaterialTheme.typography.headlineSmall)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Wi-Fi : ${if (wifiConnected) "Connecté" else "Inactif"}")
                            Text("RAM libre : $availRamMb Mo / $totalRamMb Mo")
                            Text(
                                "Gardées : ${keptPackages.size} | Non cochées : $appsToStopCount",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { value: String -> searchQuery = value },
                        label = { Text("Rechercher une application...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !showOnlyChecked,
                            onClick = { showOnlyChecked = false },
                            label = { Text("Installées (${installedApps.size})") }
                        )
                        FilterChip(
                            selected = showOnlyChecked,
                            onClick = { showOnlyChecked = true },
                            label = { Text("Gardées (${keptPackages.size})") }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = includeNonLauncher,
                            onCheckedChange = { checked: Boolean -> includeNonLauncher = checked }
                        )
                        Text(
                            text = "Inclure les apps sans icône lanceur",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = filteredApps, key = { it.packageName }) { app: InstalledAppItem ->
                            val isKept: Boolean = keptPackages.contains(app.packageName)
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { toggleKept(app.packageName, !isKept) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isKept,
                                        onCheckedChange = { checked: Boolean ->
                                            toggleKept(app.packageName, checked)
                                        }
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        Text(
                                            text = app.appName,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = app.packageName,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (!isKept) {
                                        TextButton(onClick = { openAppSettings(app.packageName) }) {
                                            Text("Réglages")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
