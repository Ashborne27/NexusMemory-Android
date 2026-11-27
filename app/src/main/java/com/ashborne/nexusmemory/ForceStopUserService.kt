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

    override fun getRunningRamMapMb(): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys meminfo"))
            proc.inputStream.bufferedReader().useLines { lines ->
                var currentPkg: String? = null
                for (rawLine in lines) {
                    val line = rawLine.trim()
                    val markerIdx = line.indexOf("MEMINFO in pid ")
                    if (markerIdx != -1) {
                        val openBracket = line.indexOf('[', markerIdx)
                        val closeBracket = line.indexOf(']', openBracket + 1)
                        if (openBracket != -1 && closeBracket != -1) {
                            currentPkg = line.substring(openBracket + 1, closeBracket).substringBefore(":")
                        }
                        continue
                    }
                    if (currentPkg != null && line.startsWith("TOTAL ")) {
                        val parts = line.split(Regex("\\s+"))
                        val kb = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                        val mb = kb / 1024L
                        val prev = map[currentPkg] ?: 0L
                        if (mb > prev) {
                            map[currentPkg] = mb
                        }
                        currentPkg = null
                    }
                }
            }
            proc.waitFor()
        } catch (_: Throwable) {
        }
        return map
    }
}
