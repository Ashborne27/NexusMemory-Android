package com.ashborne.nexusmemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.data.PendingMemory
import com.ashborne.nexusmemory.security.CryptoVault
import com.ashborne.nexusmemory.worker.CloudMemoryItem
import com.ashborne.nexusmemory.worker.MemorySyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CryptoVault.ensureBootstrappedToken(this, BuildConfig.NEXUS_TOKEN)
        MemorySyncWorker.enqueue(this)
        MemorySyncWorker.schedulePeriodic(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NexusAutoScreen()
                }
            }
        }
    }
}

@Composable
fun NexusAutoScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { NexusDatabase.get(context).dao() }
    val pendingCount by dao.observePendingCount().collectAsState(initial = 0)
    val recentSynced by dao.observeRecentSynced().collectAsState(initial = emptyList())

    var memoryInput by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Mode 100% Automatique actif") }
    var cloudItems by remember { mutableStateOf<List<CloudMemoryItem>>(emptyList()) }
    var previousPending by remember { mutableIntStateOf(-1) }

    val dateFormatter = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }

    suspend fun syncAndFetch() {
        MemorySyncWorker.enqueue(context)
        val activeToken = CryptoVault.getToken(context)
        if (activeToken.isNotEmpty()) {
            MemorySyncWorker.fetchCloudMemories(activeToken, limit = 20)
                .onSuccess { list ->
                    cloudItems = list
                    statusText = "Auto-sync OK • ${list.size} entrées cloud"
                }
                .onFailure { err ->
                    statusText = "En attente réseau (${err.message})"
                }
        } else {
            statusText = "En attente de NEXUS_TOKEN (build/Keystore)"
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            syncAndFetch()
            delay(30_000L)
        }
    }

    LaunchedEffect(pendingCount) {
        if (previousPending > 0 && pendingCount == 0) {
            syncAndFetch()
        }
        previousPending = pendingCount
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("NexusMemory Auto", style = MaterialTheme.typography.headlineMedium)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("File locale en attente : $pendingCount", style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            OutlinedTextField(
                value = memoryInput,
                onValueChange = { memoryInput = it },
                label = { Text("Mémoire (envoi auto)") },
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val clean = memoryInput.trim()
                    if (clean.isNotEmpty()) {
                        scope.launch {
                            val cipher = CryptoVault.encrypt(context, clean)
                            dao.insert(PendingMemory(encryptedPayload = cipher))
                            memoryInput = ""
                            statusText = "Chiffré — Envoi automatique..."
                            syncAndFetch()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capturer (Auto-Sync)")
            }
        }

        if (recentSynced.isNotEmpty()) {
            item {
                Text("Historique local déchiffré", style = MaterialTheme.typography.titleMedium)
            }
            items(recentSynced) { item ->
                val clearText = CryptoVault.decrypt(context, item.encryptedPayload)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(dateFormatter.format(Date(item.syncedAt)), style = MaterialTheme.typography.labelSmall)
                        Text(clearText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        if (cloudItems.isNotEmpty()) {
            item {
                Text("Historique serveur Render", style = MaterialTheme.typography.titleMedium)
            }
            items(cloudItems) { item ->
                val decoded = CryptoVault.decrypt(context, item.data)
                val display = if (decoded.startsWith("[Déchiffrement impossible]")) {
                    item.data
                } else {
                    "$decoded\n[Cipher: ${item.data.take(18)}...]"
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("#${item.seq} • ${item.receivedAt}", style = MaterialTheme.typography.labelSmall)
                        Text(display, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
