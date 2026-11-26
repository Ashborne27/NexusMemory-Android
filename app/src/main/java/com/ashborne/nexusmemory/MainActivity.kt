package com.ashborne.nexusmemory

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import android.os.IBinder
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val isLauncherApp: Boolean,
    val isSystemFlag: Boolean,
    val isUpdatedSystemFlag: Boolean,
    val ramMb: Long = 0L
)

class MainActivity : ComponentActivity() {

    private val shizukuPermissionRequestCode = 10042

    private val neverForceStopPackages = setOf(
        "moe.shizuku.privileged.api",
        "com.termux",
        "com.termux.boot"
    )

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

    private var userService: IForceStopService? = null
    private var isServiceBound: Boolean = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(packageName, ForceStopUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("force_stop_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IForceStopService.Stub.asInterface(service)
            isServiceBound = userService != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            isServiceBound = false
        }
    }

    private fun isProtectedPackage(pkg: String): Boolean {
        return pkg == packageName || neverForceStopPackages.contains(pkg)
    }

    private fun getRamInfoMb(): Pair<Long, Long> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb: Long = memInfo.availMem / (1024 * 1024)
        val totalMb: Long = memInfo.totalMem / (1024 * 1024)
        return Pair(availMb, totalMb)
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    "moe.shizuku.privileged.api",
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuBinderReady(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun hasShizukuPermissionSafe(): Boolean {
        if (!isShizukuBinderReady()) return false
        return try {
            if (Shizuku.isPreV11()) {
                true
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun requestShizukuPermissionSafe() {
        try {
            if (isShizukuBinderReady() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(shizukuPermissionRequestCode)
            }
        } catch (_: Throwable) {
        }
    }

    private fun ensureUserServiceBound() {
        if (!hasShizukuPermissionSafe()) return
        if (userService != null) return
        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (_: Throwable) {
        }
    }

    private fun forceStopViaUserService(packages: List<String>): Pair<Int, Int>? {
        val svc = userService ?: return null
        var ok = 0
        var err = 0
        for (pkg in packages) {
            if (isProtectedPackage(pkg)) continue
            try {
                val res = svc.forceStopPackage(pkg)
                if (res == 0) ok++ else err++
            } catch (_: Throwable) {
                err++
            }
        }
        return Pair(ok, err)
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
            if (isProtectedPackage(pkg)) continue
            try {
                @Suppress("DEPRECATION")
                activityManager.killBackgroundProcesses(pkg)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            try {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            } catch (_: Throwable) {
            }
            userService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("nexus_memory_prefs", Context.MODE_PRIVATE)
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shizukuInstalled = isShizukuInstalled()

        setContent {
            MaterialTheme {
                val coroutineScope = rememberCoroutineScope()
                val initialRam: Pair<Long, Long> = remember { getRamInfoMb() }
                var wifiConnected: Boolean by remember { mutableStateOf<Boolean>(false) }
                var availRamMb: Long by remember { mutableStateOf<Long>(initialRam.first) }
                val totalRamMb: Long = initialRam.second
                var searchQuery: String by remember { mutableStateOf<String>("") }
                var showOnlyChecked: Boolean by remember { mutableStateOf<Boolean>(false) }
                var includeNonLauncher: Boolean by remember { mutableStateOf<Boolean>(false) }

                var shizukuReady: Boolean by remember { mutableStateOf(hasShizukuPermissionSafe()) }
                var isStoppingAuto: Boolean by remember { mutableStateOf(false) }
                var pendingConfirmItems: List<InstalledAppItem>? by remember { mutableStateOf(null) }
                var pendingConfirmLabel: String by remember { mutableStateOf("") }

                var queuePackages: List<InstalledAppItem> by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
                var queueIndex: Int by remember { mutableStateOf<Int>(0) }
                var statusMessage: String by remember { mutableStateOf<String>("") }

                fun refreshShizukuState() {
                    shizukuReady = hasShizukuPermissionSafe()
                    if (shizukuReady) {
                        ensureUserServiceBound()
                    }
                }

                DisposableEffect(Unit) {
                    val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                        if (requestCode == shizukuPermissionRequestCode) {
                            refreshShizukuState()
                            statusMessage = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                "Permission Shizuku accordée. Liaison UserService en cours..."
                            } else {
                                "Permission Shizuku refusée."
                            }
                        }
                    }
                    try {
                        Shizuku.addRequestPermissionResultListener(permListener)
                    } catch (_: Throwable) {
                    }
                    refreshShizukuState()
                    onDispose {
                        try {
                            Shizuku.removeRequestPermissionResultListener(permListener)
                        } catch (_: Throwable) {
                        }
                    }
                }

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

                fun saveKeptPackages() {
                    prefs.edit().putStringSet("kept_packages", keptPackages.toSet()).apply()
                }

                fun toggleKept(pkg: String, checked: Boolean) {
                    if (checked) {
                        if (!keptPackages.contains(pkg)) keptPackages.add(pkg)
                    } else {
                        keptPackages.remove(pkg)
                    }
                    saveKeptPackages()
                }

                fun keepAllVisible(apps: List<InstalledAppItem>) {
                    for (app in apps) {
                        if (!keptPackages.contains(app.packageName)) {
                            keptPackages.add(app.packageName)
                        }
                    }
                    saveKeptPackages()
                    statusMessage = "${apps.size} applications ajoutées à Gardées."
                }

                fun clearKeptPackages() {
                    keptPackages.clear()
                    saveKeptPackages()
                    statusMessage = "Liste Gardées réinitialisée (paquets critiques toujours protégés)."
                }

                fun executeStopQueue(itemsToClose: List<InstalledAppItem>, labelMode: String) {
                    val ordered = itemsToClose
                        .filter { !isProtectedPackage(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                    val pkgs = ordered.map { it.packageName }
                    if (ordered.isEmpty()) {
                        statusMessage = "Aucune application non protégée à traiter."
                        return
                    }

                    refreshShizukuState()
                    if (shizukuReady && userService != null && !isStoppingAuto) {
                        isStoppingAuto = true
                        statusMessage = "Arrêt forcé auto via Shizuku (${pkgs.size} cibles)..."
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                forceStopViaUserService(pkgs)
                            }
                            availRamMb = getRamInfoMb().first
                            isStoppingAuto = false
                            if (result != null) {
                                val (okCount, errCount) = result
                                statusMessage = "$labelMode : $okCount arrêtées via am force-stop ($errCount ignorées/échec). RAM libre : $availRamMb Mo."
                            } else {
                                killBackgroundForTargets(pkgs)
                                queuePackages = ordered
                                queueIndex = 0
                                statusMessage = "Service indisponible : repli file réglages (${ordered.size} cibles)."
                                openAppSettings(ordered[0].packageName)
                            }
                        }
                    } else {
                        killBackgroundForTargets(pkgs)
                        availRamMb = getRamInfoMb().first
                        queuePackages = ordered
                        queueIndex = 0
                        val hint = if (shizukuReady) "Liaison service en cours..." else "File réglages"
                        statusMessage = "$labelMode (${ordered.size} cibles) • $hint : 1/${ordered.size}"
                        openAppSettings(ordered[0].packageName)
                    }
                }

                fun startQueueWithItems(itemsToClose: List<InstalledAppItem>, labelMode: String) {
                    val safeList = itemsToClose.filter { !isProtectedPackage(it.packageName) }
                    if (safeList.isEmpty()) {
                        statusMessage = "Aucune application non protégée à traiter."
                        return
                    }
                    refreshShizukuState()
                    if (shizukuReady && userService != null && safeList.size > 5) {
                        pendingConfirmItems = safeList
                        pendingConfirmLabel = labelMode
                    } else {
                        executeStopQueue(safeList, labelMode)
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
                            refreshShizukuState()
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
                        .filter { !isProtectedPackage(it.packageName) && !keptPackages.contains(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                }

                val activeHeavyTargets: List<InstalledAppItem> = remember(targetApps) {
                    targetApps.filter { it.ramMb > 0L }
                }

                if (pendingConfirmItems != null) {
                    val toStop = pendingConfirmItems!!
                    AlertDialog(
                        onDismissRequest = { pendingConfirmItems = null },
                        title = { Text("Confirmer am force-stop") },
                        text = {
                            val sampleNames = toStop.take(5).joinToString(", ") { it.appName }
                            Text(
                                "Arrêter immédiatement ${toStop.size} applications via Shizuku ?\n" +
                                    "Exemples : $sampleNames\n" +
                                    "NexusMemory, Shizuku et Termux sont protégés."
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val itemsSnapshot = toStop
                                    val labelSnapshot = pendingConfirmLabel
                                    pendingConfirmItems = null
                                    executeStopQueue(itemsSnapshot, labelSnapshot)
                                }
                            ) {
                                Text("Arrêter (${toStop.size})")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingConfirmItems = null }) {
                                Text("Annuler")
                            }
                        }
                    )
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
                                val shizukuLabel = when {
                                    shizukuReady && userService != null -> "Shizuku : Auto force-stop prêt"
                                    shizukuReady -> "Shizuku : Autorisé (liaison service...)"
                                    isShizukuBinderReady() -> "Shizuku : Service actif (autorisation requise)"
                                    shizukuInstalled -> "Shizuku : Installé (démarrer Shizuku)"
                                    else -> "Shizuku : Non installé"
                                }
                                Text(shizukuLabel, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Gardées : ${keptPackages.size} | Non cochées : ${targetApps.size} | Actives (>0 Mo) : ${activeHeavyTargets.size}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (!shizukuReady && isShizukuBinderReady()) {
                                    OutlinedButton(
                                        onClick = { requestShizukuPermissionSafe() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Autoriser Shizuku")
                                    }
                                } else if (shizukuReady && userService == null) {
                                    OutlinedButton(
                                        onClick = { ensureUserServiceBound() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Connecter service Shizuku")
                                    }
                                }

                                Button(
                                    onClick = { startQueueWithItems(activeHeavyTargets, "Actives > 0 Mo") },
                                    enabled = activeHeavyTargets.isNotEmpty() && !isStoppingAuto,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Fermer actives > 0 Mo (${activeHeavyTargets.size})")
                                }

                                OutlinedButton(
                                    onClick = { startQueueWithItems(targetApps, "Toutes non cochées") },
                                    enabled = targetApps.isNotEmpty() && !isStoppingAuto,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (shizukuReady && userService != null) "Fermer auto toutes non cochées (${targetApps.size})"
                                        else "Fermer toutes non cochées (${targetApps.size})"
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { keepAllVisible(filteredApps) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Garder tout", maxLines = 1, softWrap = false)
                                    }
                                    TextButton(
                                        onClick = { clearKeptPackages() },
                                        enabled = keptPackages.isNotEmpty()
                                    ) {
                                        Text("Vider", maxLines = 1, softWrap = false)
                                    }
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
                        val isProtected = isProtectedPackage(app.packageName)
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
                                        text = if (isProtected) "${app.packageName} • Protégée" else app.packageName,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!isKept && !isProtected) {
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
