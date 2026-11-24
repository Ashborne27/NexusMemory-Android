package com.ashborne.nexusmemory

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private fun getRamInfoMb(): Pair<Long, Long> {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        val availMb: Long = memInfo.availMem / (1024 * 1024)
        val totalMb: Long = memInfo.totalMem / (1024 * 1024)
        return Pair(availMb, totalMb)
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

        val defaultTargets: List<String> = listOf("com.mobizen.mobizen")
        val defaultProtected: List<String> = listOf("com.whatsapp")

        setContent {
            MaterialTheme {
                val initialRam: Pair<Long, Long> = remember { getRamInfoMb() }
                var wifiConnected: Boolean by remember { mutableStateOf<Boolean>(false) }
                var availRamMb: Long by remember { mutableStateOf<Long>(initialRam.first) }
                val totalRamMb: Long = initialRam.second
                var pkgInput: String by remember { mutableStateOf<String>("") }
                var keepSelected: Boolean by remember { mutableStateOf<Boolean>(false) }

                val protectedPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved: Set<String> =
                            prefs.getStringSet("protected_pkgs", emptySet()) ?: emptySet()
                        addAll((defaultProtected + saved).distinct())
                    }
                }

                val targetPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved: Set<String> =
                            prefs.getStringSet("target_pkgs", emptySet()) ?: emptySet()
                        addAll((defaultTargets + saved).distinct())
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

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("NexusMemory", style = MaterialTheme.typography.headlineSmall)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Wi-Fi : ${if (wifiConnected) "Connecté" else "Inactif"}")
                            Text("RAM libre : $availRamMb Mo / $totalRamMb Mo")
                        }
                    }

                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { value: String -> pkgInput = value },
                        label = { Text("Nom de package (ex: com.exemple.app)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !keepSelected,
                            onClick = { keepSelected = false },
                            label = { Text("À cibler") }
                        )
                        FilterChip(
                            selected = keepSelected,
                            onClick = { keepSelected = true },
                            label = { Text("À garder") }
                        )
                        Button(
                            onClick = {
                                val pkg: String = pkgInput.trim()
                                if (pkg.isNotEmpty()) {
                                    if (keepSelected) {
                                        if (!protectedPackages.contains(pkg)) {
                                            protectedPackages.add(pkg)
                                            val set: MutableSet<String> =
                                                (prefs.getStringSet("protected_pkgs", emptySet())
                                                    ?: emptySet()).toMutableSet().apply { add(pkg) }
                                            prefs.edit().putStringSet("protected_pkgs", set).apply()
                                        }
                                    } else {
                                        if (!targetPackages.contains(pkg)) {
                                            targetPackages.add(pkg)
                                            val set: MutableSet<String> =
                                                (prefs.getStringSet("target_pkgs", emptySet())
                                                    ?: emptySet()).toMutableSet().apply { add(pkg) }
                                            prefs.edit().putStringSet("target_pkgs", set).apply()
                                        }
                                    }
                                    pkgInput = ""
                                }
                            }
                        ) {
                            Text("Enregistrer")
                        }
                    }

                    Text("Applications ciblées", style = MaterialTheme.typography.titleMedium)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = targetPackages.toList()) { pkg: String ->
                            Button(
                                onClick = { openAppSettings(pkg) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Réglages : $pkg")
                            }
                        }
                    }

                    Text(
                        "À garder : ${protectedPackages.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
