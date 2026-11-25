package com.ashborne.nexusmemory

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Debug
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
    val isLauncherApp: Boolean,
    val isSystemFlag: Boolean,
    val isUpdatedSystemFlag: Boolean,
    val ramMb: Long = 0L
)

class MainActivity : ComponentActivity() {

    private val blockedKeywords = listOf(
        "accessibilit",
        "talkback",
        "captiveportallogin",
        "webview",
        "inputmethod",
        "keyboard",
        "permissioncontroller",
        "packageinstaller",
        "overlay",
        "carrierconfig",
        "vending",
        "googlequicksearchbox",
        "android.gms",
        "android.gsf",
        "knox",
        "emergencymode",
        "systemui",
        "providers."
    )

    private fun getRamInfoMb(): Pair<Long, Long> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb: Long = memInfo.availMem / (1024 * 1024)
        val totalMb: Long = memInfo.totalMem / (1024 * 1024)
        return Pair(availMb, totalMb)
    }

    private fun getRunningRamByPackage(): Map<String, Long> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val result = mutableMapOf<String, Long>()
        try {
            @Suppress("DEPRECATION")
            val runningProcesses = activityManager.runningAppProcesses ?: emptyList()
            val pids = runningProcesses.map { it.pid }.toIntArray()
            if (pids.isNotEmpty()) {
                val memInfos: Array<Debug.MemoryInfo> = activityManager.getProcessMemoryInfo(pids)
                for (i in runningProcesses.indices) {
                    val proc = runningProcesses[i]
                    val pssKb = if (i < memInfos.size) memInfos[i].totalPss else 0
                    val pssMb = (pssKb / 1024).toLong()
                    for (pkg in proc.pkgList ?: emptyArray()) {
                        val current = result[pkg] ?: 0L
                        if (pssMb > current) {
                            result[pkg] = pssMb
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun isSelectableApp(
        info: ApplicationInfo,
        label: String,
        hasLauncher: Boolean,
        includeNonLauncher: Boolean,
        ownPackage: String
    ): Boolean {
        if (info.packageName == ownPackage) return true

        val pkgLower = info.packageName.lowercase()
        val labelLower = label.lowercase()
        if (blockedKeywords.any { pkgLower.contains(it) || labelLower.contains(it) }) return false

        val isSystem: Boolean = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystem: Boolean = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        if (!isSystem && !isUpdatedSystem) {
            return includeNonLauncher || hasLauncher
        }

        return if (isUpdatedSystem) hasLauncher else false
    }

    private fun queryAllRawPackages(): List<ApplicationInfo> {
        val pm: PackageManager = packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
    }

    private fun getAppsList(includeNonLauncher: Boolean): Pair<List<InstalledAppItem>, Int> {
        val pm: PackageManager = packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val launcherPackages: Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }.mapNotNull { it.activityInfo?.packageName }.toSet()

        val rawInfos: List<ApplicationInfo> = queryAllRawPackages()
        val ramMap: Map<String, Long> = getRunningRamByPackage()

        val filtered = rawInfos
            .mapNotNull { info: ApplicationInfo ->
                val pkg: String = info.packageName
                val hasLauncher: Boolean = launcherPackages.contains(pkg)
                val label: String = pm.getApplicationLabel(info)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                if (!isSelectableApp(info, label, hasLauncher, includeNonLauncher, packageName)) return@mapNotNull null
                InstalledAppItem(
                    appName = label,
                    packageName = pkg,
                    isLauncherApp = hasLauncher,
                    isSystemFlag = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isUpdatedSystemFlag = (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    ramMb = ramMap[pkg] ?: 0L
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareByDescending<InstalledAppItem> { it.ramMb }
                    .thenBy { it.appName.lowercase() }
            )

        return Pair(filtered, rawInfos.distinctBy { it.packageName }.size)
    }

    private fun copyDiagnosticReport(includeNonLauncher: Boolean) {
        val pm: PackageManager = packageManager
        val rawInfos = queryAllRawPackages().distinctBy { it.packageName }.sortedBy { it.packageName.lowercase() }
        val lines = rawInfos.map { info ->
            val sys = if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) "SYS" else "USER"
            val upd = if ((info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) "+UPD" else ""
            val label = pm.getApplicationLabel(info)?.toString()?.trim().orEmpty()
            "${info.packageName} | $label | $sys$upd"
        }
        val text = "Total brut (${rawInfos.size}), includeNonLauncher=$includeNonLauncher\n" + lines.joinToString("\n")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NexusMemory Diagnostic", text))
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

    private fun killBackgroundForTargets(targetPackages: List<String>) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (pkg in targetPackages) {
            try {
                @Suppress("DEPRECATION")
                activityManager.killBackgroundProcesses(pkg)
            } catch (_: Exception) {
            }
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
                var includeNonLauncher: Boolean by remember { mutableStateOf<Boolean>(false) }

                var queuePackages: List<InstalledAppItem> by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
                var queueIndex: Int by remember { mutableStateOf<Int>(0) }
                var statusMessage: String by remember { mutableStateOf<String>("") }

                val appsPair: Pair<List<InstalledAppItem>, Int> = remember(includeNonLauncher, availRamMb) {
                    getAppsList(includeNonLauncher)
                }
                val installedApps: List<InstalledAppItem> = appsPair.first
                val rawCount: Int = appsPair.second

                val keptPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved: Set<String> =
                            prefs.getStringSet("kept_packages", emptySet()) ?: emptySet()
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

                fun startQueueWithItems(itemsToClose: List<InstalledAppItem>, labelMode: String) {
                    // Capturer l'ordre RAM décroissante AVANT killBackgroundProcesses
                    val ordered = itemsToClose.sortedWith(
                        compareByDescending<InstalledAppItem> { it.ramMb }
                            .thenBy { it.appName.lowercase() }
                    )
                    val pkgs = ordered.map { it.packageName }
                    killBackgroundForTargets(pkgs)
                    availRamMb = getRamInfoMb().first
                    queuePackages = ordered
                    queueIndex = 0
                    if (ordered.isNotEmpty()) {
                        statusMessage = "$labelMode (${ordered.size} cibles). File : 1/${ordered.size}"
                        openAppSettings(ordered[0].packageName)
                    } else {
                        statusMessage = "Aucune application à traiter dans ce mode."
                    }
                }

                fun advanceQueue(openNextSettings: Boolean) {
                    val nextIdx = queueIndex + 1
                    if (nextIdx < queuePackages.size) {
                        queueIndex = nextIdx
                        if (openNextSettings) {
                            openAppSettings(queuePackages[nextIdx].packageName)
                        }
                        statusMessage = "File réglages : ${nextIdx + 1}/${queuePackages.size}"
                    } else {
                        queuePackages = emptyList()
                        queueIndex = 0
                        availRamMb = getRamInfoMb().first
                        statusMessage = "File terminée."
                    }
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

                val targetApps: List<InstalledAppItem> = remember(installedApps, keptPackages.toList()) {
                    installedApps
                        .filter { it.packageName != packageName && !keptPackages.contains(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                }

                val activeHeavyTargets: List<InstalledAppItem> = remember(targetApps) {
                    targetApps.filter { it.ramMb > 0L }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text("NexusMemory", style = MaterialTheme.typography.headlineSmall)
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Wi-Fi : ${if (wifiConnected) "Connecté" else "Inactif"}")
                                Text("RAM libre : $availRamMb Mo / $totalRamMb Mo")
                                Text(
                                    "Gardées : ${keptPackages.size} | Non cochées : ${targetApps.size} | Actives (>0 Mo) : ${activeHeavyTargets.size}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                Button(
                                    onClick = { startQueueWithItems(activeHeavyTargets, "Actives > 0 Mo") },
                                    enabled = activeHeavyTargets.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Fermer actives > 0 Mo (${activeHeavyTargets.size})")
                                }

                                OutlinedButton(
                                    onClick = { startQueueWithItems(targetApps, "Toutes non cochées") },
                                    enabled = targetApps.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Fermer toutes non cochées (${targetApps.size})")
                                }

                                OutlinedButton(
                                    onClick = {
                                        copyDiagnosticReport(includeNonLauncher)
                                        statusMessage = "Rapport brut ($rawCount paquets) copié dans le presse-papiers."
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Copier diagnostic ($rawCount bruts)")
                                }

                                if (queuePackages.isNotEmpty() && queueIndex < queuePackages.size) {
                                    val currentTarget = queuePackages[queueIndex]
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "File ${queueIndex + 1}/${queuePackages.size} : ${currentTarget.appName} (${currentTarget.ramMb} Mo)",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = { advanceQueue(openNextSettings = true) },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Suivante", maxLines = 1)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    toggleKept(currentTarget.packageName, true)
                                                    advanceQueue(openNextSettings = false)
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Garder", maxLines = 1)
                                            }
                                            TextButton(
                                                onClick = {
                                                    queuePackages = emptyList()
                                                    queueIndex = 0
                                                    availRamMb = getRamInfoMb().first
                                                    statusMessage = "File arrêtée."
                                                }
                                            ) {
                                                Text("Stop", maxLines = 1)
                                            }
                                        }
                                    }
                                }

                                if (statusMessage.isNotBlank()) {
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { value: String -> searchQuery = value },
                            label = { Text("Rechercher une application...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
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
                    }

                    item {
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
                    }

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
                                        text = "${app.appName} (${app.ramMb} Mo)",
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!isKept && app.packageName != packageName) {
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
