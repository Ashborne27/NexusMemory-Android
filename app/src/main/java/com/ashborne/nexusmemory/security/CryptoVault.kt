package com.ashborne.nexusmemory.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.util.Base64

object CryptoVault {
    private const val PREFS_NAME = "nexus_secure_prefs"
    private const val KEY_TOKEN = "nexus_token"

    private fun prefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_GCM,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun getToken(context: Context): String {
        return prefs(context).getString(KEY_TOKEN, "").orEmpty()
    }

    fun ensureBootstrappedToken(context: Context, buildToken: String) {
        if (buildToken.isNotBlank() && getToken(context).isEmpty()) {
            saveToken(context, buildToken)
        }
    }

    fun encrypt(context: Context, plainText: String): String {
        val encoded = Base64.getEncoder().encodeToString(plainText.toByteArray(StandardCharsets.UTF_8))
        val key = "mem_${System.nanoTime()}"
        prefs(context).edit().putString(key, encoded).apply()
        return "$key:$encoded"
    }

    fun decrypt(context: Context, payload: String): String {
        return try {
            val parts = payload.split(":", limit = 2)
            val b64 = if (parts.size == 2) parts[1] else payload
            String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            "[Déchiffrement impossible]"
        }
    }
}
