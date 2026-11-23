package com.ashborne.nexusmemory

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.StatFs
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
        val prefs = getSharedPreferences("nexus_wifi_guard", Context.MODE_PRIVATE)
        val defaultPackages = listOf("com.whatsapp", "com.mobizen.mobizen")

        setContent {
            MaterialTheme {
                var wifiActive by remember { mutableStateOf(false) }
                var availRamMb by remember { mutableLongStateOf(wifiGuard.getAvailableRamMb()) }
                var newPkgInput by remember { mutableStateOf("") }

                val savedPackages = remember {
                    mutableStateListOf<String>().apply {
                        val extra = prefs.getStringSet("custom_packages", emptySet()) ?: emptySet()
                        addAll((defaultPackages + extra).distinct())
                    }
                }

                DisposableEffect(Unit) {
                    wifiGuard.startWatchingWifi { ramMb ->
                        wifiActive = true
                        availRamMb = ramMb
                    }
                    onDispose {
                        wifiGuard.stopWatchingWifi()
                    }
                }

                val dataDir = filesDir
                val stat = StatFs(dataDir.absolutePath)
                val freeStorageMb = stat.availableBytes / (1024 * 1024)
                val totalStorageMb = stat.totalBytes / (1024 * 1024)

                val actMgr = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                actMgr.getMemoryInfo(memInfo)
                val totalRamMb = memInfo.totalMem / (1024 * 1024)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("NexusMemory Guard", style = MaterialTheme.typography.headlineSmall)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("État Wi-Fi : ${if (wifiActive) "Actif" else "En attente / Inactif"}")
                            Text("RAM Appareil : $availRamMb Mo libre / $totalRamMb Mo total")
                            Text("Stockage local : $freeStorageMb Mo libre / $totalStorageMb Mo total")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newPkgInput,
                            onValueChange = { newPkgInput = it },
                            label = { Text("Ajouter package (ex: com.exemple.app)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val pkg = newPkgInput.trim()
                                if (pkg.isNotEmpty() && !savedPackages.contains(pkg)) {
                                    savedPackages.add(pkg)
                                    val currentExtra = prefs.getStringSet("custom_packages", emptySet())?.toMutableSet()
                                        ?: mutableSetOf()
                                    currentExtra.add(pkg)
                                    prefs.edit().putStringSet("custom_packages", currentExtra).apply()
                                    newPkgInput = ""
                                }
                            }
                        ) {
                            Text("Ajouter")
                        }
                    }

                    Text("Réglages rapides des applications", style = MaterialTheme.typography.titleMedium)

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(savedPackages) { pkg ->
                            Button(
                                onClick = { wifiGuard.openAppDetails(pkg) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Ouvrir réglages : $pkg")
                            }
                        }
                    }
                }
            }
        }
    }
}
