package com.epher.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecureBlobStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)
    private val secretKey: SecretKey by lazy(::loadOrCreateSecretKey)

    fun putString(key: String, value: String) {
        putBytes(key, utf8(value))
    }

    fun getString(key: String): String? = getBytes(key)?.toString(Charsets.UTF_8)

    fun putBytes(key: String, value: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value)
        prefs.edit()
            .putString("$key.iv", base64UrlEncode(iv))
            .putString("$key.cipher", base64UrlEncode(ciphertext))
            .apply()
    }

    fun getBytes(key: String): ByteArray? {
        val iv = prefs.getString("$key.iv", null)?.let(::base64UrlDecode) ?: return null
        val ciphertext = prefs.getString("$key.cipher", null)?.let(::base64UrlDecode) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    fun remove(key: String) {
        prefs.edit()
            .remove("$key.iv")
            .remove("$key.cipher")
            .apply()
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private companion object {
        const val STORE_NAME = "epher_secure_blob_store_v1"
        const val KEY_ALIAS = "epher.master.blob.key.v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
