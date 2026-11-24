package com.ashborne.nexusmemory

import android.content.Context
import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val wifiGuard = WifiGuardHelper(this)
        val prefs = getSharedPreferences("nexus_memory_prefs", Context.MODE_PRIVATE)
        val defaultTargets = listOf("com.mobizen.mobizen")
        val defaultProtected = listOf("com.whatsapp")

        setContent {
            MaterialTheme {
                var wifiActive by remember { mutableStateOf(false) }
                var availRamMb by remember { mutableStateOf(wifiGuard.getAvailableRamMb()) }
                val totalRamMb = remember { wifiGuard.getTotalRamMb() }
                var pkgInput by remember { mutableStateOf("") }
                var keepSelected by remember { mutableStateOf(false) }

                val protectedPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved = prefs.getStringSet("protected_pkgs", emptySet()) ?: emptySet()
                        addAll((defaultProtected + saved).distinct())
                    }
                }

                val targetPackages = remember {
                    mutableStateListOf<String>().apply {
                        val saved = prefs.getStringSet("target_pkgs", emptySet()) ?: emptySet()
                        addAll((defaultTargets + saved).distinct())
                    }
                }

                DisposableEffect(Unit) {
                    wifiGuard.startWatchingWifi { isConnected, ramMb ->
                        wifiActive = isConnected
                        availRamMb = ramMb
                    }
                    onDispose {
                        wifiGuard.stopWatchingWifi()
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("NexusMemory Standalone", style = MaterialTheme.typography.headlineSmall)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Wi-Fi : ${if (wifiActive) "Connecté" else "Inactif"}")
                            Text("RAM libre : $availRamMb Mo / $totalRamMb Mo")
                        }
                    }

                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
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
                                val pkg = pkgInput.trim()
                                if (pkg.isNotEmpty()) {
                                    if (keepSelected) {
                                        if (!protectedPackages.contains(pkg)) {
                                            protectedPackages.add(pkg)
                                            val set = (prefs.getStringSet("protected_pkgs", emptySet())
                                                ?: emptySet()).toMutableSet().apply { add(pkg) }
                                            prefs.edit().putStringSet("protected_pkgs", set).apply()
                                        }
                                    } else {
                                        if (!targetPackages.contains(pkg)) {
                                            targetPackages.add(pkg)
                                            val set = (prefs.getStringSet("target_pkgs", emptySet())
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

                    Text("Applications ciblées (accès direct réglages)", style = MaterialTheme.typography.titleMedium)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(items = targetPackages.toList()) { pkg: String ->
                            Button(
                                onClick = { wifiGuard.openAppDetails(pkg) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Réglages : $pkg")
                            }
                        }
                    }

                    Text(
                        "Protégées (à garder) : ${protectedPackages.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
