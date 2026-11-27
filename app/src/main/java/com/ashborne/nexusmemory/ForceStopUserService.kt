package com.ashborne.nexusmemory

import android.os.RemoteException
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

class ForceStopUserService : IForceStopService.Stub() {

    private val protectedPackages = setOf(
        "com.ashborne.nexusmemory",
        "moe.shizuku.privileged.api",
        "com.termux"
    )

    override fun forceStopPackage(packageName: String?): Int {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || protectedPackages.contains(pkg)) {
            return -1
        }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $pkg"))
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
            val code = proc.waitFor()
            if (code == 0 && stderr.isBlank()) 0 else -2
        } catch (e: Exception) {
            Log.e("ForceStopUserService", "Erreur am force-stop $pkg", e)
            -3
        }
    }

    @Throws(RemoteException::class)
    override fun destroy() {
        exitProcess(0)
    }

    override fun getRunningRamSummary(): String {
        val map = mutableMapOf<String, Long>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "ps -A -o RSS,NAME"))
            proc.inputStream.bufferedReader().useLines { lines ->
                for (rawLine in lines) {
                    val trimmed = rawLine.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("RSS")) continue
                    val parts = trimmed.split(" ")
                    val tokens = parts.filter { it.isNotEmpty() }
                    if (tokens.size >= 2) {
                        val rssKb = tokens[0].toLongOrNull() ?: 0L
                        val procName = tokens.last().substringBefore(":")
                        val mb = rssKb / 1024L
                        if (mb > 0L && procName.contains(".")) {
                            val prev = map[procName] ?: 0L
                            if (mb > prev) {
                                map[procName] = mb
                            }
                        }
                    }
                }
            }
            proc.waitFor()
        } catch (_: Throwable) {
        }
        return map.entries.joinToString(",") { "${it.key}=${it.value}" }
    }
}
