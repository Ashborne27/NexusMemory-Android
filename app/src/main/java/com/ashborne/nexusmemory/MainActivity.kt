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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.data.PendingMemory
import com.ashborne.nexusmemory.security.CryptoVault
import com.ashborne.nexusmemory.worker.CloudMemoryItem
import com.ashborne.nexusmemory.worker.MemorySyncWorker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MemorySyncWorker.enqueue(this)
        MemorySyncWorker.schedulePeriodic(this)
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
    var statusText by remember { mutableStateOf("Prêt — Sync auto & Keystore actifs") }
    var cloudItems by remember { mutableStateOf<List<CloudMemoryItem>>(emptyList()) }
    var loadingCloud by remember { mutableStateOf(false) }
    var previousPending by remember { mutableIntStateOf(-1) }

    val dateFormatter = remember {
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    }

    suspend fun refreshCloudSilently(token: String) {
        if (token.isEmpty()) return
        loadingCloud = true
        val res = MemorySyncWorker.fetchCloudMemories(token, limit = 20)
        loadingCloud = false
        res.onSuccess { list ->
            cloudItems = list
            statusText = "Synchronisé — Cloud à jour (${list.size} entrées)"
        }.onFailure { err ->
            statusText = "Erreur cloud : ${err.message}"
        }
    }

    // Chargement automatique à l'ouverture de l'app
    LaunchedEffect(Unit) {
        val activeToken = CryptoVault.getToken(context)
        if (activeToken.isNotEmpty()) {
            refreshCloudSilently(activeToken)
        }
    }

    // Rafraîchissement automatique de l'historique cloud dès que la file locale se vide
    LaunchedEffect(pendingCount) {
        if (previousPending > 0 && pendingCount == 0) {
            val activeToken = CryptoVault.getToken(context)
            if (activeToken.isNotEmpty()) {
                refreshCloudSilently(activeToken)
            }
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
            Text("NexusMemory v1.2", style = MaterialTheme.typography.headlineMedium)
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
                modifier = Modifier.fillMaxWidth().height(115.dp)
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
                                statusText = "Chiffré (Keystore) — Envoi auto..."
                                MemorySyncWorker.enqueue(context)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Enregistrer", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        MemorySyncWorker.enqueue(context)
                        statusText = "Synchronisation manuelle..."
                    },
                    modifier = Modifier.weight(0.65f)
                ) {
                    Text("Sync", maxLines = 1, softWrap = false)
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
                        statusText = "Jeton enregistré dans Keystore"
                        MemorySyncWorker.enqueue(context)
                        scope.launch {
                            refreshCloudSilently(CryptoVault.getToken(context))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sauver jeton", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = {
                        val activeToken = CryptoVault.getToken(context)
                        scope.launch {
                            refreshCloudSilently(activeToken)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (loadingCloud) "Chargement..." else "Cloud GET",
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (recentSynced.isNotEmpty()) {
            item {
                Text("Historique local déchiffré", style = MaterialTheme.typography.titleMedium)
            }
            items(recentSynced) { item ->
                val clearText = CryptoVault.decrypt(context, item.encryptedPayload)
                val formattedDate = dateFormatter.format(Date(item.syncedAt))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(formattedDate, style = MaterialTheme.typography.labelSmall)
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
