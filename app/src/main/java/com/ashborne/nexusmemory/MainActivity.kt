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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.data.PendingMemory
import com.ashborne.nexusmemory.security.CryptoVault
import com.ashborne.nexusmemory.worker.CloudMemoryItem
import com.ashborne.nexusmemory.worker.MemorySyncWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MemorySyncWorker.enqueue(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NexusScreen()
                }
            }
        }
    }
}

@Composable
fun NexusScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { NexusDatabase.get(context).dao() }
    val pendingCount by dao.observePendingCount().collectAsState(initial = 0)
    val recentSynced by dao.observeRecentSynced().collectAsState(initial = emptyList())

    var memoryInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(CryptoVault.getToken(context)) }
    var statusText by remember { mutableStateOf("Prêt — Keystore & synchronisation actifs") }
    var cloudItems by remember { mutableStateOf<List<CloudMemoryItem>>(emptyList()) }
    var loadingCloud by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("NexusMemory", style = MaterialTheme.typography.headlineMedium)
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
                label = { Text("Nouvelle mémoire") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val clean = memoryInput.trim()
                        if (clean.isNotEmpty()) {
                            scope.launch {
                                val cipher = CryptoVault.encrypt(context, clean)
                                dao.insert(PendingMemory(encryptedPayload = cipher))
                                memoryInput = ""
                                statusText = "Chiffré (Keystore) et mis en file"
                                MemorySyncWorker.enqueue(context)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enregistrer")
                }

                OutlinedButton(
                    onClick = {
                        MemorySyncWorker.enqueue(context)
                        statusText = "Synchronisation déclenchée..."
                    }
                ) {
                    Text("Sync")
                }
            }
        }

        item {
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                label = { Text("NEXUS_TOKEN (Render)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        CryptoVault.saveToken(context, tokenInput)
                        statusText = "Jeton sécurisé dans Keystore"
                        MemorySyncWorker.enqueue(context)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sauvegarder jeton")
                }

                OutlinedButton(
                    onClick = {
                        val activeToken = CryptoVault.getToken(context)
                        loadingCloud = true
                        scope.launch {
                            val res = MemorySyncWorker.fetchCloudMemories(activeToken, limit = 15)
                            loadingCloud = false
                            res.onSuccess { list ->
                                cloudItems = list
                                statusText = "Historique cloud chargé (${list.size} entrées)"
                            }.onFailure { err ->
                                statusText = "Erreur cloud : ${err.message}"
                            }
                        }
                    }
                ) {
                    Text(if (loadingCloud) "..." else "Cloud GET")
                }
            }
        }

        if (recentSynced.isNotEmpty()) {
            item {
                Text("Historique local déchiffré", style = MaterialTheme.typography.titleMedium)
            }
            items(recentSynced) { item ->
                val clearText = CryptoVault.decrypt(context, item.encryptedPayload)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                val display = if (decoded.startsWith("[Déchiffrement impossible]")) item.data else "$decoded (chiffré: ${item.data.take(16)}...)"
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
