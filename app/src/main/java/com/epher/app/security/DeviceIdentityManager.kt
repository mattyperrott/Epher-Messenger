package com.epher.app.security

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import org.json.JSONObject

data class DeviceIdentity(
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val dhPublicKey: ByteArray,
    val dhPrivateKey: ByteArray,
    val fingerprint: String,
) {
    val signingPublicKeyBase64: String get() = base64UrlEncode(signingPublicKey)
    val dhPublicKeyBase64: String get() = base64UrlEncode(dhPublicKey)
}

class DeviceIdentityManager(
    private val secureBlobStore: SecureBlobStore,
) {
    private var cachedIdentity: DeviceIdentity? = null

    fun localIdentity(): DeviceIdentity {
        cachedIdentity?.let { return it }
        val stored = secureBlobStore.getString(IDENTITY_KEY)
        val identity = if (stored != null) {
            parseIdentity(stored)
        } else {
            createAndPersistIdentity()
        }
        cachedIdentity = identity
        return identity
    }

    fun sign(payload: ByteArray): ByteArray =
        Ed25519Sign(localIdentity().signingPrivateKey).sign(payload)

    fun verify(
        publicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        Ed25519Verify(publicKey).verify(signature, payload)
        true
    }.getOrDefault(false)

    private fun createAndPersistIdentity(): DeviceIdentity {
        val signingKeyPair = Ed25519Sign.KeyPair.newKeyPair()
        val dhPrivateKey = X25519.generatePrivateKey()
        val dhPublicKey = X25519.publicFromPrivate(dhPrivateKey)
        val identity = DeviceIdentity(
            signingPublicKey = signingKeyPair.getPublicKey(),
            signingPrivateKey = signingKeyPair.getPrivateKey(),
            dhPublicKey = dhPublicKey,
            dhPrivateKey = dhPrivateKey,
            fingerprint = fingerprintFor(signingKeyPair.getPublicKey(), dhPublicKey),
        )
        val json = JSONObject()
            .put("signingPublicKey", base64UrlEncode(identity.signingPublicKey))
            .put("signingPrivateKey", base64UrlEncode(identity.signingPrivateKey))
            .put("dhPublicKey", base64UrlEncode(identity.dhPublicKey))
            .put("dhPrivateKey", base64UrlEncode(identity.dhPrivateKey))
            .put("fingerprint", identity.fingerprint)
        secureBlobStore.putString(IDENTITY_KEY, json.toString())
        return identity
    }

    private fun parseIdentity(value: String): DeviceIdentity {
        val json = JSONObject(value)
        return DeviceIdentity(
            signingPublicKey = base64UrlDecode(json.getString("signingPublicKey")),
            signingPrivateKey = base64UrlDecode(json.getString("signingPrivateKey")),
            dhPublicKey = base64UrlDecode(json.getString("dhPublicKey")),
            dhPrivateKey = base64UrlDecode(json.getString("dhPrivateKey")),
            fingerprint = json.getString("fingerprint"),
        )
    }

    private companion object {
        const val IDENTITY_KEY = "device_identity_v1"
    }
}
