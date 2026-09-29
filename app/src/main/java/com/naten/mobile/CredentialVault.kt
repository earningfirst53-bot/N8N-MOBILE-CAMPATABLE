package com.naten.mobile

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CredentialVault {
    private const val STORE = "naten_credentials"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "NATEN_CREDENTIALS_V1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(256)
        return generator.generateKey()
    }

    fun put(context: Context, name: String, value: String) {
        val cleanName = name.trim()
        require(cleanName.isNotBlank()) { "Credential name cannot be empty." }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val combined = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()

        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit()
            .putString(cleanName, Base64.encodeToString(combined, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context, name: String): String? {
        val encoded = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .getString(name.trim(), null) ?: return null

        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val buffer = ByteBuffer.wrap(bytes)
            val ivLength = buffer.int
            val iv = ByteArray(ivLength)
            buffer.get(iv)
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete(context: Context, name: String) {
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .edit().remove(name.trim()).apply()
    }

    fun list(context: Context): List<String> =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
            .all.keys.sorted()
}
