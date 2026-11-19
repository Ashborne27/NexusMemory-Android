package com.ashborne.nexusmemory.worker

import android.content.Context
import androidx.work.*
import com.ashborne.nexusmemory.data.NexusDatabase
import com.ashborne.nexusmemory.security.CryptoVault
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    }
}
