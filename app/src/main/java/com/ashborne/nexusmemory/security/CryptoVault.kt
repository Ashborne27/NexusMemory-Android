package com.ashborne.nexusmemory.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoVault {
    private const val PREFS_NAME = "nexus_secure_prefs"
    private const val KEY_TOKEN = "nexus_token"
    private const val KEY_ALIAS = "NexusMemoryVaultKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) {
            return existing.secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithKeystore(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val iv = cipher.iv
        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        return "v1:$ivB64:$dataB64"
    }

    private fun decryptWithKeystore(payload: String): String {
        return try {
            val parts = payload.split(":")
            if (parts.size == 3 && parts[0] == "v1") {
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipherBytes = Base64.decode(parts[2], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
            } else {
                String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            "[Déchiffrement impossible]"
        }
    }

    fun saveToken(context: Context, token: String) {
        val clean = token.trim()
        val stored = if (clean.isEmpty()) "" else encryptWithKeystore(clean)
        prefs(context).edit().putString(KEY_TOKEN, stored).apply()
    }

    fun getToken(context: Context): String {
        val raw = prefs(context).getString(KEY_TOKEN, "").orEmpty()
        if (raw.isEmpty()) return ""
        val decrypted = decryptWithKeystore(raw)
        return if (decrypted.startsWith("[Déchiffrement impossible]")) "" else decrypted
    }

    fun ensureBootstrappedToken(context: Context, buildToken: String) {
        if (buildToken.isNotBlank() && getToken(context).isEmpty()) {
            saveToken(context, buildToken)
        }
    }

    fun encrypt(context: Context, plainText: String): String {
        val encoded = encryptWithKeystore(plainText)
        val key = "mem_${System.nanoTime()}"
        prefs(context).edit().putString(key, encoded).apply()
        return encoded
    }

    fun decrypt(context: Context, payload: String): String {
        return decryptWithKeystore(payload)
    }
}
