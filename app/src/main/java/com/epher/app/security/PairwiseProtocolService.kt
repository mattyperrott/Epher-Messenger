package com.epher.app.security

import com.google.crypto.tink.aead.internal.InsecureNonceXChaCha20Poly1305
import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class VerifiedPeer(
    val roomId: String,
    val displayName: String,
    val fingerprint: String,
    val signingPublicKey: ByteArray,
    val dhPublicKey: ByteArray,
    val sessionPreKeyPublic: ByteArray,
    val transportPublicKeyHex: String,
    val relayCapable: Boolean,
)

data class PairwiseEnvelope(
    val roomId: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val senderName: String,
    val messageId: String,
    val previousChainLength: Int,
    val messageNumber: Int,
    val ratchetPublicKey: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val signature: ByteArray,
) {
    fun encode(): String = JSONObject()
        .put("roomId", roomId)
        .put("senderFingerprint", senderFingerprint)
        .put("recipientFingerprint", recipientFingerprint)
        .put("senderName", senderName)
        .put("messageId", messageId)
        .put("previousChainLength", previousChainLength)
        .put("messageNumber", messageNumber)
        .put("ratchetPublicKey", base64UrlEncode(ratchetPublicKey))
        .put("nonce", base64UrlEncode(nonce))
        .put("ciphertext", base64UrlEncode(ciphertext))
        .put("signature", base64UrlEncode(signature))
        .toString()
}

data class DecryptedPairwiseMessage(
    val senderFingerprint: String,
    val senderName: String,
    val body: String,
    val sentAtEpochMillis: Long,
    val messageId: String,
)

data class PairwiseDeliveryAck(
    val roomId: String,
    val messageId: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val ackedAtEpochMillis: Long,
    val signature: ByteArray,
) {
    fun encode(): String = JSONObject()
        .put("roomId", roomId)
        .put("messageId", messageId)
        .put("senderFingerprint", senderFingerprint)
        .put("recipientFingerprint", recipientFingerprint)
        .put("ackedAtEpochMillis", ackedAtEpochMillis)
        .put("signature", base64UrlEncode(signature))
        .toString()
}

data class PairwiseEnvelopePreview(
    val roomId: String,
    val senderFingerprint: String,
    val recipientFingerprint: String,
    val messageId: String,
    val messageNumber: Int,
    val ratchetPublicKeyPrefix: String,
)

private data class RoomPreKeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

private data class SkippedMessageKey(
    val key: ByteArray,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
)

private data class PairwiseSession(
    val roomId: String,
    val remoteFingerprint: String,
    var rootKey: ByteArray,
    var sendChainKey: ByteArray,
    var receiveChainKey: ByteArray,
    var localRatchetPrivateKey: ByteArray,
    var localRatchetPublicKey: ByteArray,
    var remoteRatchetPublicKey: ByteArray,
    var sendCount: Int,
    var receiveCount: Int,
    var previousChainLength: Int,
    var needsSendRatchet: Boolean,
    val skippedKeys: LinkedHashMap<String, SkippedMessageKey>,
)

class PairwiseProtocolService(
    private val secureRoomService: SecureRoomService,
    private val secureBlobStore: SecureBlobStore,
    private val roomIdentityManager: RoomIdentityManager,
) {
    private val peersByRoom = linkedMapOf<String, MutableMap<String, VerifiedPeer>>()
    private val sessionsByRoom = linkedMapOf<String, MutableMap<String, PairwiseSession>>()
    private val preKeysByRoom = linkedMapOf<String, RoomPreKeyPair>()

    init {
        loadState()
    }

    fun createLocalPeerCard(
        roomId: String,
        displayName: String,
    ): String {
        val identity = roomIdentityManager.identityForRoom(roomId)
        val preKey = ensureRoomPreKey(roomId)
        val keyMaterial = secureRoomService.pairwiseKeyMaterial(roomId)
        val payload = JSONObject()
            .put("roomId", roomId)
            .put("displayName", displayName)
            .put("fingerprint", identity.fingerprint)
            .put("signingPublicKey", base64UrlEncode(identity.signingPublicKey))
            .put("dhPublicKey", base64UrlEncode(identity.dhPublicKey))
            .put("sessionPreKeyPublic", base64UrlEncode(preKey.publicKey))
            .put("relayCapable", true)
            .put("issuedAtEpochMillis", System.currentTimeMillis())
        val membershipProof = hmacSha256(keyMaterial.authKey, utf8(payload.toString()))
        val payloadWithProof = JSONObject(payload.toString())
            .put("membershipProof", base64UrlEncode(membershipProof))
        val signature = roomIdentityManager.sign(roomId, utf8(payloadWithProof.toString()))
        payloadWithProof.put("signature", base64UrlEncode(signature))
        persistState()
        return payloadWithProof.toString()
    }

    fun ingestPeerCard(
        roomId: String,
        encodedPeerCard: String,
        transportPublicKeyHex: String,
    ): VerifiedPeer? {
        val json = JSONObject(encodedPeerCard)
        val signature = base64UrlDecode(json.getString("signature"))
        json.remove("signature")
        val payloadWithProof = json.toString()
        val membershipProof = base64UrlDecode(json.getString("membershipProof"))
        json.remove("membershipProof")

        requireCrypto(json.getString("roomId") == roomId, "Peer card room mismatch")

        val keyMaterial = secureRoomService.pairwiseKeyMaterial(roomId)
        requireCrypto(
            constantTimeEquals(membershipProof, hmacSha256(keyMaterial.authKey, utf8(json.toString()))),
            "Peer membership proof invalid",
        )

        val signingPublicKey = base64UrlDecode(json.getString("signingPublicKey"))
        val dhPublicKey = base64UrlDecode(json.getString("dhPublicKey"))
        val sessionPreKeyPublic = base64UrlDecode(json.getString("sessionPreKeyPublic"))
        val derivedFingerprint = fingerprintFor(signingPublicKey, dhPublicKey)
        requireCrypto(
            json.getString("fingerprint").equals(derivedFingerprint, ignoreCase = true),
            "Peer fingerprint mismatch",
        )
        requireCrypto(
            roomIdentityManager.verify(signingPublicKey, utf8(payloadWithProof), signature),
            "Peer card signature invalid",
        )

        val peer = VerifiedPeer(
            roomId = roomId,
            displayName = json.getString("displayName"),
            fingerprint = derivedFingerprint,
            signingPublicKey = signingPublicKey,
            dhPublicKey = dhPublicKey,
            sessionPreKeyPublic = sessionPreKeyPublic,
            transportPublicKeyHex = transportPublicKeyHex,
            relayCapable = json.optBoolean("relayCapable", false),
        )

        if (peer.fingerprint == roomIdentityManager.identityForRoom(roomId).fingerprint) {
            return null
        }

        peersByRoom.getOrPut(roomId) { linkedMapOf() }[peer.fingerprint] = peer
        persistState()
        return peer
    }

    fun verifiedPeers(roomId: String): List<VerifiedPeer> = peersByRoom[roomId]?.values?.toList().orEmpty()

    fun hasEstablishedSession(roomId: String): Boolean = sessionsByRoom[roomId]?.isNotEmpty() == true

    fun hasSessionWithPeer(
        roomId: String,
        remoteFingerprint: String,
    ): Boolean = sessionsByRoom[roomId]?.containsKey(remoteFingerprint) == true

    fun establishSession(
        roomId: String,
        remoteFingerprint: String,
    ): Boolean = runCatching {
        ensureSession(roomId, remoteFingerprint)
        true
    }.getOrDefault(false)

    fun knownPeerTransportKeys(roomId: String): List<String> = verifiedPeers(roomId)
        .filter { it.transportPublicKeyHex.isNotBlank() }
        .map { it.transportPublicKeyHex }

    fun removeRoomState(roomId: String) {
        val removed = peersByRoom.remove(roomId) != null ||
            sessionsByRoom.remove(roomId) != null ||
            preKeysByRoom.remove(roomId) != null
        if (removed) {
            persistState()
        }
    }

    fun removePeer(
        roomId: String,
        fingerprint: String,
    ) {
        val removedPeer = peersByRoom[roomId]?.remove(fingerprint) != null
        val removedSession = sessionsByRoom[roomId]?.remove(fingerprint) != null
        if (removedPeer || removedSession) {
            persistState()
        }
    }

     fun encryptForPeer(
        roomId: String,
        recipientFingerprint: String,
        senderName: String,
        plaintext: String,
        messageId: String = UUID.randomUUID().toString(),
    ): PairwiseEnvelope {
        val session = ensureSession(roomId, recipientFingerprint)
        val identity = roomIdentityManager.identityForRoom(roomId)
        maybeAdvanceSendingRatchet(session)

        // Snapshot current state before any mutations
        val currentSendCount = session.sendCount
        val currentPreviousChainLength = session.previousChainLength
        val currentRatchetPublicKey = session.localRatchetPublicKey.copyOf()

        val messageKey = hkdfSha256(
            ikm = session.sendChainKey,
            salt = session.rootKey,
            info = utf8("epher.pairwise.message"),
            length = 32,
        )
        val nextChainKey = hkdfSha256(
            ikm = session.sendChainKey,
            salt = session.rootKey,
            info = utf8("epher.pairwise.chain.next"),
            length = 32,
        )

        val header = JSONObject()
            .put("roomId", roomId)
            .put("senderFingerprint", identity.fingerprint)
            .put("recipientFingerprint", recipientFingerprint)
            .put("senderName", senderName)
            .put("messageId", messageId)
            .put("previousChainLength", currentPreviousChainLength)
            .put("messageNumber", currentSendCount)
            .put("ratchetPublicKey", base64UrlEncode(currentRatchetPublicKey))
            .toString()
        val payload = JSONObject()
            .put("body", plaintext)
            .put("sentAtEpochMillis", System.currentTimeMillis())
            .toString()
        val nonce = randomBytes(24)
        val ciphertext = InsecureNonceXChaCha20Poly1305(messageKey)
            .encrypt(nonce, padForPrivacyBucket(utf8(payload), 512), utf8(header))
        val signature = roomIdentityManager.sign(roomId, utf8(header) + nonce + ciphertext)
        
        // Only mutate session state AFTER successful encryption (before any unhandled exception)
        session.sendChainKey = nextChainKey
        session.sendCount += 1
        persistState()

        return PairwiseEnvelope(
            roomId = roomId,
            senderFingerprint = identity.fingerprint,
            recipientFingerprint = recipientFingerprint,
            senderName = senderName,
            messageId = messageId,
            previousChainLength = currentPreviousChainLength,
            messageNumber = currentSendCount,
            ratchetPublicKey = currentRatchetPublicKey,
            nonce = nonce,
            ciphertext = ciphertext,
            signature = signature,
        )
    }

    fun createDeliveryAck(
        roomId: String,
        recipientFingerprint: String,
        messageId: String,
    ): String {
        val identity = roomIdentityManager.identityForRoom(roomId)
        val payload = JSONObject()
            .put("roomId", roomId)
            .put("messageId", messageId)
            .put("senderFingerprint", identity.fingerprint)
            .put("recipientFingerprint", recipientFingerprint)
            .put("ackedAtEpochMillis", System.currentTimeMillis())
        val signature = roomIdentityManager.sign(roomId, utf8(payload.toString()))
        return PairwiseDeliveryAck(
            roomId = roomId,
            messageId = messageId,
            senderFingerprint = identity.fingerprint,
            recipientFingerprint = recipientFingerprint,
            ackedAtEpochMillis = payload.getLong("ackedAtEpochMillis"),
            signature = signature,
        ).encode()
    }

    fun verifyDeliveryAck(
        roomId: String,
        encodedAck: String,
    ): PairwiseDeliveryAck? {
        val json = JSONObject(encodedAck)
        val signature = base64UrlDecode(json.getString("signature"))
        json.remove("signature")
        val payload = json.toString()
        val ack = PairwiseDeliveryAck(
            roomId = json.getString("roomId"),
            messageId = json.getString("messageId"),
            senderFingerprint = json.getString("senderFingerprint"),
            recipientFingerprint = json.getString("recipientFingerprint"),
            ackedAtEpochMillis = json.getLong("ackedAtEpochMillis"),
            signature = signature,
        )
        if (ack.roomId != roomId) return null
        if (ack.recipientFingerprint != roomIdentityManager.identityForRoom(roomId).fingerprint) {
            return null
        }
        val peer = peersByRoom[roomId]?.get(ack.senderFingerprint) ?: return null
        requireCrypto(
            roomIdentityManager.verify(peer.signingPublicKey, utf8(payload), signature),
            "Delivery ack signature invalid",
        )
        return ack
    }

    fun previewEnvelope(encodedEnvelope: String): PairwiseEnvelopePreview {
        val envelope = decodeEnvelope(encodedEnvelope)
        return PairwiseEnvelopePreview(
            roomId = envelope.roomId,
            senderFingerprint = envelope.senderFingerprint,
            recipientFingerprint = envelope.recipientFingerprint,
            messageId = envelope.messageId,
            messageNumber = envelope.messageNumber,
            ratchetPublicKeyPrefix = base64UrlEncode(envelope.ratchetPublicKey).take(12),
        )
    }

    fun decryptEnvelope(
        roomId: String,
        encodedEnvelope: String,
    ): DecryptedPairwiseMessage? {
        val envelope = decodeEnvelope(encodedEnvelope)
        if (envelope.recipientFingerprint != roomIdentityManager.identityForRoom(roomId).fingerprint) {
            return null
        }

        val peer = peersByRoom[roomId]?.get(envelope.senderFingerprint) ?: return null
        val storedSession = ensureSession(roomId, envelope.senderFingerprint)
        val session = storedSession.deepCopy()

        val header = JSONObject()
            .put("roomId", roomId)
            .put("senderFingerprint", envelope.senderFingerprint)
            .put("recipientFingerprint", envelope.recipientFingerprint)
            .put("senderName", envelope.senderName)
            .put("messageId", envelope.messageId)
            .put("previousChainLength", envelope.previousChainLength)
            .put("messageNumber", envelope.messageNumber)
            .put("ratchetPublicKey", base64UrlEncode(envelope.ratchetPublicKey))
            .toString()

        requireCrypto(
            roomIdentityManager.verify(peer.signingPublicKey, utf8(header) + envelope.nonce + envelope.ciphertext, envelope.signature),
            "Pairwise envelope signature invalid",
        )

        val skippedKeyId = skippedKeyId(envelope.ratchetPublicKey, envelope.messageNumber)
        val skipped = session.skippedKeys[skippedKeyId]
        val messageKey = if (skipped != null) {
            skipped.key
        } else {
            maybeAdvanceReceivingRatchet(session, envelope.ratchetPublicKey, envelope.previousChainLength)
            while (session.receiveCount < envelope.messageNumber) {
                val skippedKey = hkdfSha256(
                    ikm = session.receiveChainKey,
                    salt = session.rootKey,
                    info = utf8("epher.pairwise.message"),
                    length = 32,
                )
                session.receiveChainKey = hkdfSha256(
                    ikm = session.receiveChainKey,
                    salt = session.rootKey,
                    info = utf8("epher.pairwise.chain.next"),
                    length = 32,
                )
                session.skippedKeys[skippedKeyId(session.remoteRatchetPublicKey, session.receiveCount)] = 
                    SkippedMessageKey(skippedKey)
                trimSkippedKeys(session)
                session.receiveCount += 1
            }

            val key = hkdfSha256(
                ikm = session.receiveChainKey,
                salt = session.rootKey,
                info = utf8("epher.pairwise.message"),
                length = 32,
            )
            session.receiveChainKey = hkdfSha256(
                ikm = session.receiveChainKey,
                salt = session.rootKey,
                info = utf8("epher.pairwise.chain.next"),
                length = 32,
            )
            session.receiveCount += 1
            key
        }

        val plaintext = InsecureNonceXChaCha20Poly1305(messageKey)
            .decrypt(envelope.nonce, envelope.ciphertext, utf8(header))
        if (skipped != null) {
            session.skippedKeys.remove(skippedKeyId)
        }
        commitSession(storedSession, session)
        persistState()
        
        val payload = try {
            JSONObject(unpadFixedPacket(plaintext).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw GeneralSecurityException("Malformed message payload: invalid JSON or missing fields: ${e.message}", e)
        }
        
        try {
            return DecryptedPairwiseMessage(
                senderFingerprint = envelope.senderFingerprint,
                senderName = envelope.senderName,
                body = payload.getString("body"),
                sentAtEpochMillis = payload.getLong("sentAtEpochMillis"),
                messageId = envelope.messageId,
            )
        } catch (e: Exception) {
            throw GeneralSecurityException("Message payload missing required fields: ${e.message}", e)
        }
    }

    private fun ensureSession(roomId: String, remoteFingerprint: String): PairwiseSession {
        sessionsByRoom.getOrPut(roomId) { linkedMapOf() }[remoteFingerprint]?.let { return it }

        val remote = peersByRoom[roomId]?.get(remoteFingerprint)
            ?: throw GeneralSecurityException("Unknown remote peer")
        val localIdentity = roomIdentityManager.identityForRoom(roomId)
        val localPreKey = ensureRoomPreKey(roomId)
        val keyMaterial = secureRoomService.pairwiseKeyMaterial(roomId)
        val sharedSecrets = listOf(
            X25519.computeSharedSecret(localIdentity.dhPrivateKey, remote.dhPublicKey),
            X25519.computeSharedSecret(localIdentity.dhPrivateKey, remote.sessionPreKeyPublic),
            X25519.computeSharedSecret(localPreKey.privateKey, remote.dhPublicKey),
        ).sortedWith(byteArrayLexicographicComparator())
        val initialSecret = hkdfSha256(
            ikm = concatBytes(*sharedSecrets.map { it.copyOf() }.toTypedArray()),
            salt = keyMaterial.authKey,
            info = utf8("epher.pairwise.root.$roomId"),
            length = 32,
        )
        val forwardOrder = localIdentity.fingerprint < remote.fingerprint
        val chainA = hkdfSha256(initialSecret, keyMaterial.masterKey, utf8("epher.pairwise.chain.a"), 32)
        val chainB = hkdfSha256(initialSecret, keyMaterial.masterKey, utf8("epher.pairwise.chain.b"), 32)

        val session = PairwiseSession(
            roomId = roomId,
            remoteFingerprint = remoteFingerprint,
            rootKey = initialSecret,
            sendChainKey = if (forwardOrder) chainA else chainB,
            receiveChainKey = if (forwardOrder) chainB else chainA,
            localRatchetPrivateKey = localPreKey.privateKey,
            localRatchetPublicKey = localPreKey.publicKey,
            remoteRatchetPublicKey = remote.sessionPreKeyPublic,
            sendCount = 0,
            receiveCount = 0,
            previousChainLength = 0,
            needsSendRatchet = !forwardOrder,
            skippedKeys = linkedMapOf(),
        )
        sessionsByRoom.getOrPut(roomId) { linkedMapOf() }[remoteFingerprint] = session
        persistState()
        return session
    }

    private fun maybeAdvanceSendingRatchet(session: PairwiseSession) {
        if (!session.needsSendRatchet) return
        val nextPrivate = X25519.generatePrivateKey()
        val nextPublic = X25519.publicFromPrivate(nextPrivate)
        val nextRoot = hkdfSha256(
            ikm = X25519.computeSharedSecret(nextPrivate, session.remoteRatchetPublicKey),
            salt = session.rootKey,
            info = utf8("epher.pairwise.dh.root"),
            length = 32,
        )
        session.previousChainLength = session.sendCount
        session.rootKey = nextRoot
        session.sendChainKey = hkdfSha256(nextRoot, nextRoot, utf8("epher.pairwise.dh.chain"), 32)
        session.sendCount = 0
        session.localRatchetPrivateKey = nextPrivate
        session.localRatchetPublicKey = nextPublic
        session.needsSendRatchet = false
    }

    private fun maybeAdvanceReceivingRatchet(
        session: PairwiseSession,
        remoteRatchetPublicKey: ByteArray,
        previousChainLength: Int,
    ) {
        if (constantTimeEquals(session.remoteRatchetPublicKey, remoteRatchetPublicKey)) return
        val nextRoot = hkdfSha256(
            ikm = X25519.computeSharedSecret(session.localRatchetPrivateKey, remoteRatchetPublicKey),
            salt = session.rootKey,
            info = utf8("epher.pairwise.dh.root"),
            length = 32,
        )
        session.rootKey = nextRoot
        session.receiveChainKey = hkdfSha256(nextRoot, nextRoot, utf8("epher.pairwise.dh.chain"), 32)
        session.remoteRatchetPublicKey = remoteRatchetPublicKey
        session.receiveCount = 0
        session.previousChainLength = previousChainLength
        session.needsSendRatchet = true
    }

    private fun ensureRoomPreKey(roomId: String): RoomPreKeyPair = preKeysByRoom.getOrPut(roomId) {
        val privateKey = X25519.generatePrivateKey()
        RoomPreKeyPair(
            privateKey = privateKey,
            publicKey = X25519.publicFromPrivate(privateKey),
        )
    }

    private fun decodeEnvelope(encodedEnvelope: String): PairwiseEnvelope {
        val json = JSONObject(encodedEnvelope)
        return PairwiseEnvelope(
            roomId = json.getString("roomId"),
            senderFingerprint = json.getString("senderFingerprint"),
            recipientFingerprint = json.getString("recipientFingerprint"),
            senderName = json.getString("senderName"),
            messageId = json.getString("messageId"),
            previousChainLength = json.getInt("previousChainLength"),
            messageNumber = json.getInt("messageNumber"),
            ratchetPublicKey = base64UrlDecode(json.getString("ratchetPublicKey")),
            nonce = base64UrlDecode(json.getString("nonce")),
            ciphertext = base64UrlDecode(json.getString("ciphertext")),
            signature = base64UrlDecode(json.getString("signature")),
        )
    }

    private fun skippedKeyId(
        ratchetPublicKey: ByteArray,
        messageNumber: Int,
    ): String = "${base64UrlEncode(ratchetPublicKey)}:$messageNumber"

    private fun PairwiseSession.deepCopy(): PairwiseSession = PairwiseSession(
        roomId = roomId,
        remoteFingerprint = remoteFingerprint,
        rootKey = rootKey.copyOf(),
        sendChainKey = sendChainKey.copyOf(),
        receiveChainKey = receiveChainKey.copyOf(),
        localRatchetPrivateKey = localRatchetPrivateKey.copyOf(),
        localRatchetPublicKey = localRatchetPublicKey.copyOf(),
        remoteRatchetPublicKey = remoteRatchetPublicKey.copyOf(),
        sendCount = sendCount,
        receiveCount = receiveCount,
        previousChainLength = previousChainLength,
        needsSendRatchet = needsSendRatchet,
        skippedKeys = LinkedHashMap<String, SkippedMessageKey>().apply {
            this@deepCopy.skippedKeys.forEach { (id, skipped) -> 
                put(id, skipped.copy(key = skipped.key.copyOf())) 
            }
        },
    )

    private fun commitSession(
        target: PairwiseSession,
        source: PairwiseSession,
    ) {
        target.rootKey = source.rootKey
        target.sendChainKey = source.sendChainKey
        target.receiveChainKey = source.receiveChainKey
        target.localRatchetPrivateKey = source.localRatchetPrivateKey
        target.localRatchetPublicKey = source.localRatchetPublicKey
        target.remoteRatchetPublicKey = source.remoteRatchetPublicKey
        target.sendCount = source.sendCount
        target.receiveCount = source.receiveCount
        target.previousChainLength = source.previousChainLength
        target.needsSendRatchet = source.needsSendRatchet
        target.skippedKeys.clear()
        target.skippedKeys.putAll(source.skippedKeys)
    }

    private fun trimSkippedKeys(session: PairwiseSession) {
        val now = System.currentTimeMillis()
        // First, remove keys older than 15 minutes
        session.skippedKeys.entries
            .filter { now - it.value.createdAtEpochMillis > 15 * 60 * 1000L }
            .map { it.key }
            .forEach { session.skippedKeys.remove(it) }
        
        // Then, if still over capacity, remove oldest keys by count
        while (session.skippedKeys.size > MAX_SKIPPED_MESSAGE_KEYS) {
            val firstKey = session.skippedKeys.entries.firstOrNull()?.key ?: return
            session.skippedKeys.remove(firstKey)
        }
    }

    private fun persistState() {
        val peersJson = JSONArray()
        peersByRoom.values.forEach { peers ->
            peers.values.forEach { peer ->
                peersJson.put(
                    JSONObject()
                        .put("roomId", peer.roomId)
                        .put("displayName", peer.displayName)
                        .put("fingerprint", peer.fingerprint)
                        .put("signingPublicKey", base64UrlEncode(peer.signingPublicKey))
                        .put("dhPublicKey", base64UrlEncode(peer.dhPublicKey))
                        .put("sessionPreKeyPublic", base64UrlEncode(peer.sessionPreKeyPublic))
                        .put("transportPublicKeyHex", peer.transportPublicKeyHex)
                        .put("relayCapable", peer.relayCapable),
                )
            }
        }

        val preKeysJson = JSONArray()
        preKeysByRoom.forEach { (roomId, preKey) ->
            preKeysJson.put(
                JSONObject()
                    .put("roomId", roomId)
                    .put("privateKey", base64UrlEncode(preKey.privateKey))
                    .put("publicKey", base64UrlEncode(preKey.publicKey)),
            )
        }

        val sessionsJson = JSONArray()
        sessionsByRoom.values.forEach { sessions ->
            sessions.values.forEach { session ->
                sessionsJson.put(
                    JSONObject()
                        .put("roomId", session.roomId)
                        .put("remoteFingerprint", session.remoteFingerprint)
                        .put("rootKey", base64UrlEncode(session.rootKey))
                        .put("sendChainKey", base64UrlEncode(session.sendChainKey))
                        .put("receiveChainKey", base64UrlEncode(session.receiveChainKey))
                        .put("localRatchetPrivateKey", base64UrlEncode(session.localRatchetPrivateKey))
                        .put("localRatchetPublicKey", base64UrlEncode(session.localRatchetPublicKey))
                        .put("remoteRatchetPublicKey", base64UrlEncode(session.remoteRatchetPublicKey))
                        .put("sendCount", session.sendCount)
                        .put("receiveCount", session.receiveCount)
                        .put("previousChainLength", session.previousChainLength)
                        .put("needsSendRatchet", session.needsSendRatchet)
                        .put(
                            "skippedKeys",
                            JSONArray().apply {
                                session.skippedKeys.forEach { (id, skipped) ->
                                    put(JSONObject()
                                        .put("id", id)
                                        .put("key", base64UrlEncode(skipped.key))
                                        .put("createdAtEpochMillis", skipped.createdAtEpochMillis))
                                }
                            },
                        ),
                )
            }
        }

        if (peersJson.length() > 0) {
            secureBlobStore.putString(PEERS_KEY, peersJson.toString())
        } else {
            secureBlobStore.remove(PEERS_KEY)
        }
        if (preKeysJson.length() > 0) {
            secureBlobStore.putString(PRE_KEYS_KEY, preKeysJson.toString())
        } else {
            secureBlobStore.remove(PRE_KEYS_KEY)
        }
        if (sessionsJson.length() > 0) {
            secureBlobStore.putString(SESSIONS_KEY, sessionsJson.toString())
        } else {
            secureBlobStore.remove(SESSIONS_KEY)
        }
    }

    private fun loadState() {
        secureBlobStore.getString(PEERS_KEY)?.let { encoded ->
            val array = JSONArray(encoded)
            repeat(array.length()) { index ->
                val json = array.getJSONObject(index)
                val signingPublicKey = base64UrlDecode(json.getString("signingPublicKey"))
                val dhPublicKey = base64UrlDecode(json.getString("dhPublicKey"))
                val derivedFingerprint = fingerprintFor(signingPublicKey, dhPublicKey)
                val storedFingerprint = json.optString("fingerprint")
                if (storedFingerprint.isNotBlank() && !storedFingerprint.equals(derivedFingerprint, ignoreCase = true)) {
                    return@repeat
                }
                val peer = VerifiedPeer(
                    roomId = json.getString("roomId"),
                    displayName = json.getString("displayName"),
                    fingerprint = derivedFingerprint,
                    signingPublicKey = signingPublicKey,
                    dhPublicKey = dhPublicKey,
                    sessionPreKeyPublic = base64UrlDecode(json.getString("sessionPreKeyPublic")),
                    transportPublicKeyHex = json.getString("transportPublicKeyHex"),
                    relayCapable = json.optBoolean("relayCapable", false),
                )
                peersByRoom.getOrPut(peer.roomId) { linkedMapOf() }[peer.fingerprint] = peer
            }
        }

        secureBlobStore.getString(PRE_KEYS_KEY)?.let { encoded ->
            val array = JSONArray(encoded)
            repeat(array.length()) { index ->
                val json = array.getJSONObject(index)
                preKeysByRoom[json.getString("roomId")] = RoomPreKeyPair(
                    privateKey = base64UrlDecode(json.getString("privateKey")),
                    publicKey = base64UrlDecode(json.getString("publicKey")),
                )
            }
        }

        secureBlobStore.getString(SESSIONS_KEY)?.let { encoded ->
            val array = JSONArray(encoded)
            repeat(array.length()) { index ->
                val json = array.getJSONObject(index)
                val session = PairwiseSession(
                    roomId = json.getString("roomId"),
                    remoteFingerprint = json.getString("remoteFingerprint"),
                    rootKey = base64UrlDecode(json.getString("rootKey")),
                    sendChainKey = base64UrlDecode(json.getString("sendChainKey")),
                    receiveChainKey = base64UrlDecode(json.getString("receiveChainKey")),
                    localRatchetPrivateKey = base64UrlDecode(json.getString("localRatchetPrivateKey")),
                    localRatchetPublicKey = base64UrlDecode(json.getString("localRatchetPublicKey")),
                    remoteRatchetPublicKey = base64UrlDecode(json.getString("remoteRatchetPublicKey")),
                    sendCount = json.getInt("sendCount"),
                    receiveCount = json.getInt("receiveCount"),
                    previousChainLength = json.getInt("previousChainLength"),
                    needsSendRatchet = json.optBoolean("needsSendRatchet", false),
                    skippedKeys = linkedMapOf<String, SkippedMessageKey>().apply {
                        val skipped = json.getJSONArray("skippedKeys")
                        repeat(skipped.length()) { skippedIndex ->
                            val skippedJson = skipped.getJSONObject(skippedIndex)
                            val keyId = skippedJson.getString("id")
                            val keyBytes = base64UrlDecode(skippedJson.getString("key"))
                            val createdAt = skippedJson.optLong("createdAtEpochMillis", System.currentTimeMillis())
                            put(keyId, SkippedMessageKey(keyBytes, createdAt))
                        }
                    },
                )
                sessionsByRoom.getOrPut(session.roomId) { linkedMapOf() }[session.remoteFingerprint] = session
            }
        }
    }

    private companion object {
        const val PEERS_KEY = "pairwise.peers.v4"
        const val PRE_KEYS_KEY = "pairwise.prekeys.v4"
        const val SESSIONS_KEY = "pairwise.sessions.v5"
        const val MAX_SKIPPED_MESSAGE_KEYS = 4096

        fun byteArrayLexicographicComparator(): Comparator<ByteArray> = Comparator { left, right ->
            val size = minOf(left.size, right.size)
            for (index in 0 until size) {
                val comparison = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
                if (comparison != 0) return@Comparator comparison
            }
            left.size - right.size
        }
    }
}
