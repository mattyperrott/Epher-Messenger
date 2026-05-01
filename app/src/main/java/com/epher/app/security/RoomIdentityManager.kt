package com.epher.app.security

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import org.json.JSONArray
import org.json.JSONObject

data class RoomIdentity(
    val roomId: String,
    val signingPublicKey: ByteArray,
    val signingPrivateKey: ByteArray,
    val dhPublicKey: ByteArray,
    val dhPrivateKey: ByteArray,
    val fingerprint: String,
)

class RoomIdentityManager(
    private val secureBlobStore: SecureBlobStore,
) {
    private val identitiesByRoom = linkedMapOf<String, RoomIdentity>()

    init {
        loadState()
    }

    fun identityForRoom(roomId: String): RoomIdentity = identitiesByRoom[roomId] ?: createAndPersist(roomId)

    fun sign(
        roomId: String,
        payload: ByteArray,
    ): ByteArray = Ed25519Sign(identityForRoom(roomId).signingPrivateKey).sign(payload)

    fun verify(
        publicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = runCatching {
        Ed25519Verify(publicKey).verify(signature, payload)
        true
    }.getOrDefault(false)

    fun removeRoom(roomId: String) {
        if (identitiesByRoom.remove(roomId) != null) {
            persistState()
        }
    }

    private fun createAndPersist(roomId: String): RoomIdentity {
        val signingKeyPair = Ed25519Sign.KeyPair.newKeyPair()
        val dhPrivateKey = X25519.generatePrivateKey()
        val dhPublicKey = X25519.publicFromPrivate(dhPrivateKey)
        val identity = RoomIdentity(
            roomId = roomId,
            signingPublicKey = signingKeyPair.getPublicKey(),
            signingPrivateKey = signingKeyPair.getPrivateKey(),
            dhPublicKey = dhPublicKey,
            dhPrivateKey = dhPrivateKey,
            fingerprint = fingerprintFor(signingKeyPair.getPublicKey(), dhPublicKey),
        )
        identitiesByRoom[roomId] = identity
        persistState()
        return identity
    }

    private fun persistState() {
        if (identitiesByRoom.isEmpty()) {
            secureBlobStore.remove(IDENTITIES_KEY)
            return
        }

        val payload = JSONArray()
        identitiesByRoom.values.forEach { identity ->
            payload.put(
                JSONObject()
                    .put("roomId", identity.roomId)
                    .put("signingPublicKey", base64UrlEncode(identity.signingPublicKey))
                    .put("signingPrivateKey", base64UrlEncode(identity.signingPrivateKey))
                    .put("dhPublicKey", base64UrlEncode(identity.dhPublicKey))
                    .put("dhPrivateKey", base64UrlEncode(identity.dhPrivateKey))
                    .put("fingerprint", identity.fingerprint),
            )
        }
        secureBlobStore.putString(IDENTITIES_KEY, payload.toString())
    }

    private fun loadState() {
        val encoded = secureBlobStore.getString(IDENTITIES_KEY) ?: return
        val payload = JSONArray(encoded)
        repeat(payload.length()) { index ->
            val json = payload.getJSONObject(index)
            val identity = RoomIdentity(
                roomId = json.getString("roomId"),
                signingPublicKey = base64UrlDecode(json.getString("signingPublicKey")),
                signingPrivateKey = base64UrlDecode(json.getString("signingPrivateKey")),
                dhPublicKey = base64UrlDecode(json.getString("dhPublicKey")),
                dhPrivateKey = base64UrlDecode(json.getString("dhPrivateKey")),
                fingerprint = json.getString("fingerprint"),
            )
            identitiesByRoom[identity.roomId] = identity
        }
    }

    private companion object {
        const val IDENTITIES_KEY = "room.identities.v1"
    }
}
