package com.ashborne.nexusmemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.data.PendingMemory
import com.ashborne.nexusmemory.security.CryptoVault
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

    var memoryInput by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(CryptoVault.getToken(context)) }
    var statusText by remember { mutableStateOf("Prêt — Synchronisation autonome active") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("NexusMemory", style = MaterialTheme.typography.headlineMedium)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("File locale en attente : $pendingCount", style = MaterialTheme.typography.titleMedium)
                Text(statusText, style = MaterialTheme.typography.bodySmall)
            }
        }

        OutlinedTextField(
            value = memoryInput,
            onValueChange = { memoryInput = it },
            label = { Text("Nouvelle mémoire") },
            modifier = Modifier.fillMaxWidth().height(130.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val clean = memoryInput.trim()
                    if (clean.isNotEmpty()) {
                        scope.launch {
                            val cipher = CryptoVault.encrypt(context, clean)
                            dao.insert(PendingMemory(encryptedPayload = cipher))
                            memoryInput = ""
                            statusText = "Chiffré et mis en file — Envoi cloud déclenché"
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
                    statusText = "Synchronisation forcée déclenchée..."
                }
            ) {
                Text("Sync")
            }
        }

        HorizontalDivider()

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("NEXUS_TOKEN (Render)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                CryptoVault.saveToken(context, tokenInput)
                statusText = "Jeton enregistré localement"
                MemorySyncWorker.enqueue(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sauvegarder le jeton")
        }
    }
}
