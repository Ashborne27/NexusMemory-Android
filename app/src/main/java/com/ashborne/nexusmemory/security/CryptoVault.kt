package com.ashborne.nexusmemory.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

object CryptoVault {
    private const val VAULT_FILE = "nexus_keystore_vault_v2"
    private const val KEY_SECRET = "xor_secret_b64"
    private const val KEY_TOKEN = "nexus_bearer_token"

    private fun vaultPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context.applicationContext,
            VAULT_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getOrCreateKey(context: Context): ByteArray {
        val prefs = vaultPrefs(context)
        val existing = prefs.getString(KEY_SECRET, null)
        if (existing != null) return Base64.decode(existing, Base64.NO_WRAP)
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_SECRET, Base64.encodeToString(raw, Base64.NO_WRAP)).apply()
        return raw
    }

    fun encrypt(context: Context, plaintext: String): String {
        val key = getOrCreateKey(context)
        val input = plaintext.toByteArray(Charsets.UTF_8)
        val out = ByteArray(input.size) { i ->
            (input[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(context: Context, cipherTextBase64: String): String {
        return try {
            val key = getOrCreateKey(context)
            val cipherBytes = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            val plainBytes = ByteArray(cipherBytes.size) { i ->
                (cipherBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
            }
            String(plainBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            "[Déchiffrement impossible]"
        }
    }

    fun saveToken(context: Context, token: String) {
        vaultPrefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun getToken(context: Context): String {
        return vaultPrefs(context).getString(KEY_TOKEN, "") ?: ""
    }
}
