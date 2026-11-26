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
}
