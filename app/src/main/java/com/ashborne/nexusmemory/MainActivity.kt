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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

    private fun openShizukuApp() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (_: Exception) {
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

    private fun forceStopViaUserService(packages: List<String>): Pair<List<String>, Int>? {
        val svc = userService ?: return null
        val stopped = mutableListOf<String>()
        var err = 0
        for (pkg in packages) {
            if (isProtectedPackage(pkg)) continue
            try {
                val res = svc.forceStopPackage(pkg)
                if (res == 0) stopped.add(pkg) else err++
            } catch (_: Throwable) {
                err++
            }
        }
        return Pair(stopped, err)
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

    private fun getAppsList(includeNonLauncher: Boolean, extraRamMap: Map<String, Long> = emptyMap()): Pair<List<InstalledAppItem>, Int> {
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
        val ramMap: MutableMap<String, Long> = getRunningRamByPackage().toMutableMap()
        for ((pkg, mb) in extraRamMap) {
            if (mb > (ramMap[pkg] ?: 0L)) {
                ramMap[pkg] = mb
            }
        }

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
                val lifecycleOwner = LocalLifecycleOwner.current
                val initialRam: Pair<Long, Long> = remember { getRamInfoMb() }
                var wifiConnected: Boolean by remember { mutableStateOf<Boolean>(false) }
                var availRamMb: Long by remember { mutableStateOf<Long>(initialRam.first) }
                val totalRamMb: Long = initialRam.second
                var refreshTick: Int by remember { mutableStateOf<Int>(0) }
                var searchQuery: String by remember { mutableStateOf<String>("") }
                var showOnlyChecked: Boolean by remember { mutableStateOf<Boolean>(false) }
                var includeNonLauncher: Boolean by remember { mutableStateOf<Boolean>(false) }
                var hideZeroRam: Boolean by remember { mutableStateOf<Boolean>(true) }

                var shizukuBinderActive: Boolean by remember { mutableStateOf(isShizukuBinderReady()) }
                var shizukuReady: Boolean by remember { mutableStateOf(hasShizukuPermissionSafe()) }
                var serviceConnected: Boolean by remember { mutableStateOf(userService != null) }

                LaunchedEffect(wifiConnected, shizukuReady, serviceConnected) {
                    if (shizukuReady && !serviceConnected) {
                        try {
                            ensureUserServiceBound()
                        } catch (_: Throwable) {
                        }
                    }
                    if (wifiConnected && shizukuReady) {
                        refreshTick++
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val laterConnected = userService != null || isServiceBound
                                if (laterConnected) {
                                    serviceConnected = true
                                    refreshTick++
                                }
                            }, 1800L)
                    }
                }
                var isStoppingAuto: Boolean by remember { mutableStateOf(false) }
                var wasWifiConnected by remember { mutableStateOf(false) }
                var pendingConfirmItems: List<InstalledAppItem>? by remember { mutableStateOf(null) }
                var pendingConfirmLabel: String by remember { mutableStateOf("") }
                var pendingUncheckStopped: Boolean by remember { mutableStateOf(false) }

                var queuePackages: List<InstalledAppItem> by remember { mutableStateOf<List<InstalledAppItem>>(emptyList()) }
                var queueIndex: Int by remember { mutableStateOf<Int>(0) }
                var statusMessage: String by remember { mutableStateOf<String>("") }

                fun refreshAllStates(manual: Boolean = false) {
                    val ramNow = getRamInfoMb()
                    availRamMb = ramNow.first
                    shizukuBinderActive = isShizukuBinderReady()
                    shizukuReady = hasShizukuPermissionSafe()
                    if (shizukuReady) {
                        ensureUserServiceBound()
                    }
                    serviceConnected = userService != null
                    if (shizukuReady && userService == null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val nowConnected = userService != null
                            if (nowConnected != serviceConnected) {
                                serviceConnected = nowConnected
                                refreshTick++
                            }
                        }, 600L)
                    }
                    refreshTick++
                    if (manual) {
                        statusMessage = "Actualisé • RAM libre : $availRamMb Mo"
                    }
                }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            refreshAllStates(manual = false)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                DisposableEffect(Unit) {
                    val binderReceivedListener = Shizuku.OnBinderReceivedListener {
                        refreshAllStates(manual = false)
                    }
                    val binderDeadListener = Shizuku.OnBinderDeadListener {
                        serviceConnected = false
                        shizukuBinderActive = false
                        shizukuReady = false
                    }
                    try {
                        Shizuku.addBinderReceivedListener(binderReceivedListener)
                        Shizuku.addBinderDeadListener(binderDeadListener)
                    } catch (_: Throwable) {
                    }
                    val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                        if (requestCode == shizukuPermissionRequestCode) {
                            refreshAllStates(manual = false)
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
                    refreshAllStates(manual = false)
                    onDispose {
                        try {
                            Shizuku.removeRequestPermissionResultListener(permListener)
                            Shizuku.removeBinderReceivedListener(binderReceivedListener)
                            Shizuku.removeBinderDeadListener(binderDeadListener)
                        } catch (_: Throwable) {
                        }
                    }
                }

                val shizukuRamMap: Map<String, Long> = remember(availRamMb, refreshTick, serviceConnected, shizukuReady) {
                    val parsed = mutableMapOf<String, Long>()
                    if (shizukuReady && userService != null) {
                        try {
                            val raw = userService?.runningRamSummary.orEmpty()
                            for (entry in raw.split(",")) {
                                val eqIdx = entry.indexOf('=')
                                if (eqIdx > 0) {
                                    val pkg = entry.substring(0, eqIdx).trim()
                                    val mb = entry.substring(eqIdx + 1).trim().toLongOrNull() ?: 0L
                                    if (pkg.isNotEmpty() && mb > (parsed[pkg] ?: 0L)) {
                                        parsed[pkg] = mb
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                    parsed
                }

                val appsPair: Pair<List<InstalledAppItem>, Int> = remember(includeNonLauncher, availRamMb, refreshTick, shizukuRamMap) {
                    getAppsList(includeNonLauncher, shizukuRamMap)
                }
                val installedApps: List<InstalledAppItem> = appsPair.first
                val rawCount: Int = appsPair.second

                val selectedPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved: Set<String> =
                            prefs.getStringSet("selected_to_stop_packages", emptySet()) ?: emptySet()
                        addAll(saved)
                    }
                }

                fun saveSelectedPackages() {
                    prefs.edit().putStringSet("selected_to_stop_packages", selectedPackages.toSet()).apply()
                }

                fun toggleSelected(pkg: String, checked: Boolean) {
                    if (checked) {
                        if (!selectedPackages.contains(pkg)) selectedPackages.add(pkg)
                    } else {
                        selectedPackages.remove(pkg)
                    }
                    saveSelectedPackages()
                }

                fun clearSelectedPackages() {
                    selectedPackages.clear()
                    saveSelectedPackages()
                    statusMessage = "Sélection vidée."
                }

                fun executeStopQueue(
                    itemsToClose: List<InstalledAppItem>,
                    labelMode: String,
                    uncheckStopped: Boolean
                ) {
                    val ordered = itemsToClose
                        .filter { !isProtectedPackage(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                    val pkgs = ordered.map { it.packageName }
                    if (ordered.isEmpty()) {
                        statusMessage = "Aucune application non protégée à fermer."
                        return
                    }

                    refreshAllStates(manual = false)
                    if (shizukuReady && userService != null && !isStoppingAuto) {
                        isStoppingAuto = true
                        statusMessage = "Arrêt forcé de ${pkgs.size} apps via Shizuku..."
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                forceStopViaUserService(pkgs)
                            }
                            isStoppingAuto = false
                            if (result != null) {
                                val (stoppedList, errCount) = result
                                if (uncheckStopped) {
                                    selectedPackages.removeAll(stoppedList.toSet())
                                    saveSelectedPackages()
                                }
                                refreshAllStates(manual = false)
                                statusMessage = "$labelMode : ${stoppedList.size} arrêtées via am force-stop ($errCount échec). RAM libre : $availRamMb Mo."
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

                fun startQueueWithItems(
                    itemsToClose: List<InstalledAppItem>,
                    labelMode: String,
                    uncheckStopped: Boolean
                ) {
                    val safeList = itemsToClose.filter { !isProtectedPackage(it.packageName) }
                    if (safeList.isEmpty()) {
                        statusMessage = "Aucune application non protégée à traiter."
                        return
                    }
                    refreshAllStates(manual = false)
                    if (shizukuReady && userService != null && safeList.size > 5) {
                        pendingConfirmItems = safeList
                        pendingConfirmLabel = labelMode
                        pendingUncheckStopped = uncheckStopped
                    } else {
                        executeStopQueue(safeList, labelMode, uncheckStopped)
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
                        refreshAllStates(manual = false)
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
                            wifiConnected = true
                            refreshAllStates(manual = false)
                        }

                        override fun onLost(network: Network) {
                            super.onLost(network)
                            wifiConnected = false
                            refreshAllStates(manual = false)
                        }
                    }

                    connectivityManager.registerNetworkCallback(request, callback)
                    onDispose {
                        connectivityManager.unregisterNetworkCallback(callback)
                    }
                }

                LaunchedEffect(wifiConnected, shizukuReady) {
                if (shizukuReady && userService == null) {
                    try {
                        ensureUserServiceBound()
                    } catch (_: Throwable) {
                    }
                }
                if (wifiConnected && shizukuReady && userService != null) {
                    refreshTick++
                }
            }
            val filteredApps: List<InstalledAppItem> = remember(searchQuery, showOnlyChecked, hideZeroRam, selectedPackages.toList(), installedApps) {
                    installedApps.filter { item ->
                        val matchesQuery = searchQuery.isBlank() ||
                            item.appName.contains(searchQuery.trim(), ignoreCase = true) ||
                            item.packageName.contains(searchQuery.trim(), ignoreCase = true)
                        val matchesFilter = !showOnlyChecked || selectedPackages.contains(item.packageName)
                        val matchesRam = !hideZeroRam || item.ramMb > 0L || selectedPackages.contains(item.packageName)
                        matchesQuery && matchesFilter && matchesRam
                    }
                }

                val checkedTargets: List<InstalledAppItem> = remember(installedApps, selectedPackages.toList()) {
                    installedApps
                        .filter { selectedPackages.contains(it.packageName) && !isProtectedPackage(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                }

                val uncheckedTargets: List<InstalledAppItem> = remember(installedApps, selectedPackages.toList()) {
                    installedApps
                        .filter { !selectedPackages.contains(it.packageName) && !isProtectedPackage(it.packageName) }
                        .sortedWith(
                            compareByDescending<InstalledAppItem> { it.ramMb }
                                .thenBy { it.appName.lowercase() }
                        )
                }

                if (pendingConfirmItems != null) {
                    val toStop = pendingConfirmItems!!
                    AlertDialog(
                        onDismissRequest = { pendingConfirmItems = null },
                        title = { Text("Confirmer am force-stop") },
                        text = {
                            val sampleNames = toStop.take(5).joinToString(", ") { it.appName }
                            Text(
                                "Arrêter ${toStop.size} applications via Shizuku ?\n" +
                                    "Exemples : $sampleNames"
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    val itemsSnapshot = toStop
                                    val labelSnapshot = pendingConfirmLabel
                                    val uncheckSnapshot = pendingUncheckStopped
                                    pendingConfirmItems = null
                                    executeStopQueue(itemsSnapshot, labelSnapshot, uncheckSnapshot)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("NexusMemory", style = MaterialTheme.typography.headlineSmall)
                            TextButton(onClick = { refreshAllStates(manual = true) }) {
                                Text("Actualiser")
                            }
                        }
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
                                    shizukuReady && serviceConnected -> "Shizuku : Auto force-stop prêt"
                                    shizukuReady -> "Shizuku : Autorisé (liaison service...)"
                                    shizukuBinderActive -> "Shizuku : Service actif (autorisation requise)"
                                    shizukuInstalled -> "Shizuku : Installé (démarrer Shizuku)"
                                    else -> "Shizuku : Non installé"
                                }
                                Text(shizukuLabel, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Cochées : ${checkedTargets.size} | Non cochées : ${uncheckedTargets.size}",
                                    style = MaterialTheme.typography.bodySmall
                                )

                                if (!shizukuReady && shizukuBinderActive) {
                                    OutlinedButton(
                                        onClick = { requestShizukuPermissionSafe() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Autoriser Shizuku")
                                    }
                                } else if (shizukuReady && !serviceConnected) {
                                    OutlinedButton(
                                        onClick = {
                                            ensureUserServiceBound()
                                            serviceConnected = userService != null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Connecter service Shizuku")
                                    }
                                } else if (shizukuInstalled && !shizukuBinderActive) {
                                    OutlinedButton(
                                        onClick = { openShizukuApp() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Ouvrir Shizuku")
                                    }
                                }

                                Button(
                                    onClick = {
                                        startQueueWithItems(
                                            checkedTargets,
                                            "Apps cochées",
                                            uncheckStopped = true
                                        )
                                    },
                                    enabled = checkedTargets.isNotEmpty() && !isStoppingAuto,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (shizukuReady && serviceConnected) "Fermer auto cochées (${checkedTargets.size})"
                                        else "Fermer cochées (${checkedTargets.size})"
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        startQueueWithItems(
                                            uncheckedTargets,
                                            "Apps non cochées",
                                            uncheckStopped = false
                                        )
                                    },

                                    enabled = uncheckedTargets.isNotEmpty() && !isStoppingAuto,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (shizukuReady && serviceConnected) "Fermer auto non cochées (${uncheckedTargets.size})"
                                        else "Fermer non cochées (${uncheckedTargets.size})"
                                    )
                                }

LaunchedEffect(wifiConnected) {
val justConnectedWifi = !wasWifiConnected && wifiConnected
wasWifiConnected = wifiConnected
if (justConnectedWifi && shizukuReady && serviceConnected && userService != null && !isStoppingAuto && uncheckedTargets.isNotEmpty()) {
onServiceConnected(uncheckedTargets)
}
}

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { clearSelectedPackages() },
                                        enabled = selectedPackages.isNotEmpty(),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Vider (${selectedPackages.size})", maxLines = 1, softWrap = false)
                                    }
                                    TextButton(
                                        onClick = {
                                            copyDiagnosticReport(includeNonLauncher)
                                            statusMessage = "Rapport brut ($rawCount paquets) copié dans le presse-papiers."
                                        }
                                    ) {
                                        Text("Log ($rawCount)", maxLines = 1, softWrap = false)
                                    }
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
                                                    toggleSelected(currentTarget.packageName, false)
                                                    advanceQueue(openNextSettings = false)
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("Décocher", maxLines = 1)
                                            }
                                            TextButton(
                                                onClick = {
                                                    queuePackages = emptyList()
                                                    queueIndex = 0
                                                    refreshAllStates(manual = false)
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
                                label = { Text("Cochées (${checkedTargets.size})") }
                            )
                        }
                    }

                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = hideZeroRam,
                                    onCheckedChange = { checked: Boolean -> hideZeroRam = checked }
                                )
                                Text(
                                    text = "Masquer les apps à 0 Mo",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    items(items = filteredApps, key = { it.packageName }) { app: InstalledAppItem ->
                        val isChecked: Boolean = selectedPackages.contains(app.packageName)
                        val isProtected = isProtectedPackage(app.packageName)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { toggleSelected(app.packageName, !isChecked) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked: Boolean ->
                                        toggleSelected(app.packageName, checked)
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
                                if (!isProtected) {
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

private fun applyZeroRamFilter(
    apps: List<InstalledAppItem>,
    hideZeroRam: Boolean,
    selectedPackages: Set<String>
): List<InstalledAppItem> {
    if (!hideZeroRam) return apps
    return apps.filter { item ->
        item.ramMb > 0L || selectedPackages.contains(item.packageName)
    }
}
}
