package com.ashborne.nexusmemory.worker

import android.content.Context
import android.app.ActivityManager
import android.os.Environment
import android.os.StatFs
import androidx.work.*
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.data.SyncedMemory
import com.ashborne.nexusmemory.security.CryptoVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.Result as KotlinResult

data class CloudMemoryItem(
    val seq: Int,
    val clientId: String,
    val data: String,
    val receivedAt: String
)

class MemorySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val dao = NexusDatabase.get(applicationContext).dao()
        val pending = dao.getAllPending()
        if (pending.isEmpty()) return Result.success()

        val token = CryptoVault.getToken(applicationContext)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (item in pending) {
            val json = JSONObject().apply {
                put("id", item.id)
                put("data", item.encryptedPayload)
            }.toString()

            val reqBuilder = Request.Builder()
                .url("https://nexus-memory-server.onrender.com/api/memory")
                .post(json.toRequestBody(mediaType))
                .addHeader("Content-Type", "application/json")

            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            try {
                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        dao.insertSynced(SyncedMemory(encryptedPayload = item.encryptedPayload))
                        dao.deleteById(item.id)
                    } else if (resp.code == 401) {
                        return Result.failure()
                    } else {
                        return Result.retry()
                    }
                }
            } catch (_: Exception) {
                return Result.retry()
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "nexus_memory_sync"
        private const val PERIODIC_WORK_NAME = "nexus_memory_periodic_sync"

        fun getLocalStoragePair(context: Context): Pair<Long, Long> {
            return try {
                val stat = StatFs(Environment.getDataDirectory().path)
                Pair(stat.availableBytes, stat.totalBytes)
            } catch (_: Exception) {
                Pair(0L, 0L)
            }
        }

        fun getLocalRamPair(context: Context): Pair<Long, Long> {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                Pair(memInfo.availMem, memInfo.totalMem)
            } catch (_: Exception) {
                Pair(0L, 0L)
            }
        }

        suspend fun fetchRenderRamAvailableBytes(token: String): Long? = withContext(Dispatchers.IO) {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder()
                .url("https://nexus-memory-server.onrender.com/api/storage")
                .get()

            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            return@withContext try {
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    if (root.has("ram_available_bytes")) {
                        root.optLong("ram_available_bytes", -1L).takeIf { it >= 0L }
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        fun getLocalAvailableBytes(context: Context): Long = getLocalStoragePair(context).first


        suspend fun fetchRenderAvailableBytes(token: String): Long? = withContext(Dispatchers.IO) {
            val http = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            val reqBuilder = Request.Builder()
                .url("https://nexus-memory-server.onrender.com/api/storage")
                .get()

            if (token.isNotEmpty()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            return@withContext try {
                http.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    if (root.has("available_bytes")) {
                        root.optLong("available_bytes", -1L).takeIf { it >= 0L }
                    } else if (root.has("free_bytes")) {
                        root.optLong("free_bytes", -1L).takeIf { it >= 0L }
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }


        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<MemorySyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<MemorySyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )
        }

        suspend fun fetchCloudMemories(token: String, limit: Int = 20): KotlinResult<List<CloudMemoryItem>> =
            withContext(Dispatchers.IO) {
                val http = OkHttpClient.Builder()
                    .connectTimeout(25, TimeUnit.SECONDS)
                    .readTimeout(35, TimeUnit.SECONDS)
                    .build()

                val reqBuilder = Request.Builder()
                    .url("https://nexus-memory-server.onrender.com/api/memory?limit=$limit")
                    .get()

                if (token.isNotEmpty()) {
                    reqBuilder.addHeader("Authorization", "Bearer $token")
                }

                try {
                    http.newCall(reqBuilder.build()).execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext KotlinResult.failure(Exception("HTTP ${resp.code}"))
                        }
                        val root = JSONObject(bodyStr)
                        val arr = root.optJSONArray("items")
                        val items = mutableListOf<CloudMemoryItem>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                items.add(
                                    CloudMemoryItem(
                                        seq = obj.optInt("seq", 0),
                                        clientId = obj.optString("client_id", ""),
                                        data = obj.optString("data", ""),
                                        receivedAt = obj.optString("received_at", "")
                                    )
                                )
                            }
                        }
                        KotlinResult.success(items)
                    }
                } catch (e: Exception) {
                    KotlinResult.failure(e)
                }
            }
    }
}
