package com.ashborne.nexusmemory.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

object CryptoVault {
    private const val PREFS = "nexus_vault_prefs"
    private const val KEY_SECRET = "xor_secret_b64"
    private const val KEY_TOKEN = "nexus_bearer_token"

    fun getOrCreateKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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

    fun saveToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun getToken(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, "") ?: ""
    }
}
