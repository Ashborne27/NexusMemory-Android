package com.ashborne.nexusmemory.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ashborne.nexusmemory.BuildConfig
import com.ashborne.nexusmemory.security.CryptoVault
import com.ashborne.nexusmemory.worker.MemorySyncWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            CryptoVault.ensureBootstrappedToken(context, BuildConfig.NEXUS_TOKEN)
            MemorySyncWorker.enqueue(context)
            MemorySyncWorker.schedulePeriodic(context)
        }
    }
}
