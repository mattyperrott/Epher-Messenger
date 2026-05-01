package com.epher.app.security

import com.epher.app.data.model.InvitePackage
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.RoomSecurityProfile
import com.epher.app.mixnet.MixnetInviteRouteHints
import com.google.crypto.tink.aead.internal.InsecureNonceXChaCha20Poly1305
import java.text.SimpleDateFormat
import java.security.GeneralSecurityException
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject

data class SecureRoomSession(
    val roomId: String,
    val roomLabel: String,
    val ownerFingerprint: String,
    var roomPassword: String,
    var epoch: Long,
    val packetSize: Int,
    val messageEncryption: String,
    val keyDerivation: String,
    val transportMode: String,
    val overlayMode: String,
    var masterKey: ByteArray,
    var authKey: ByteArray,
    var attachmentKey: ByteArray,
    var chainKey: ByteArray,
    var nextSequence: Long,
    val recentMessageIds: MutableList<String>,
)

data class SecureEnvelope(
    val roomId: String,
    val senderFingerprint: String,
    val senderPublicKey: String,
    val senderName: String,
    val messageId: String,
    val sequence: Long,
    val packetSize: Int,
    val jitterDelayMillis: Long,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val signature: ByteArray,
) {
    fun encode(): String = JSONObject()
        .put("roomId", roomId)
        .put("senderFingerprint", senderFingerprint)
        .put("senderPublicKey", senderPublicKey)
        .put("senderName", senderName)
        .put("messageId", messageId)
        .put("sequence", sequence)
        .put("packetSize", packetSize)
        .put("jitterDelayMillis", jitterDelayMillis)
        .put("nonce", base64UrlEncode(nonce))
        .put("ciphertext", base64UrlEncode(ciphertext))
        .put("signature", base64UrlEncode(signature))
        .toString()
}

data class DecryptedRoomMessage(
    val senderName: String,
    val body: String,
    val sentAtEpochMillis: Long,
)

data class CreatedSecureRoom(
    val roomId: String,
    val roomPassword: String,
    val invitePackage: InvitePackage,
    val mixnetRouteHintsJson: String,
    val ownerTransportPublicKeyHex: String,
)

data class JoinedSecureRoom(
    val roomId: String,
    val roomLabel: String,
    val ownerFingerprint: String,
    val inviteExpiresAtEpochMillis: Long?,
    val inviteExpiresLabel: String,
    val mixnetRouteHintsJson: String,
    val ownerTransportPublicKeyHex: String?,
)

data class RoomRekeyMaterial(
    val roomId: String,
    val roomPassword: String,
    val epoch: Long,
    val mixnetRouteHintsJson: String,
    val invitePackage: InvitePackage? = null,
)

data class EncryptedAttachment(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val digestHex: String,
)

data class RoomPairwiseKeyMaterial(
    val masterKey: ByteArray,
    val authKey: ByteArray,
)

class SecureRoomService(
    private val secureBlobStore: SecureBlobStore,
    private val identityManager: DeviceIdentityManager,
    private val roomIdentityManager: RoomIdentityManager,
    private val roomSecretDeriver: RoomSecretDeriver = RoomSecretDeriver(),
    private val peerAuthenticator: PeerAuthenticator = PeerAuthenticator(),
    private val transportEncryptionLabel: String = "Transport-dependent relay or direct link with client-side end-to-end encryption",
    private val trafficObfuscationLabel: String = "Depends on configured transport",
    private val metadataRetentionLabel: String = "No central plaintext message storage; encrypted local session state",
    private val transportModeLabel: String = "Transport depends on configured build",
    private val overlayModeLabel: String = "No overlay tunnel in current build",
) {
    private val sessions = linkedMapOf<String, SecureRoomSession>()

    init {
        loadPersistedSessions()
    }

    fun localFingerprint(): String = identityManager.localIdentity().fingerprint

    fun roomFingerprint(roomId: String): String = roomIdentityManager.identityForRoom(roomId).fingerprint

    fun hasRoomSession(roomId: String): Boolean = sessions.containsKey(roomId)

    fun roomEpoch(roomId: String): Long = sessions[roomId]?.epoch ?: 0L

    fun ownerFingerprint(roomId: String): String? = sessions[roomId]?.ownerFingerprint

    fun securityProfile(): RoomSecurityProfile = RoomSecurityProfile(
        transportEncryption = transportEncryptionLabel,
        trafficObfuscation = trafficObfuscationLabel,
        metadataRetention = metadataRetentionLabel,
        transportMode = transportModeLabel,
        overlayMode = overlayModeLabel,
    )

    fun createRoom(
        label: String,
        inviteExpiryPreset: InviteExpiryPreset = InviteExpiryPreset.Day,
    ): CreatedSecureRoom {
        val roomId = UUID.randomUUID().toString()
        val roomPassword = base64UrlEncode(randomBytes(18))
        return createRoom(label, roomId, roomPassword, inviteExpiryPreset)
    }

    fun createRoom(
        label: String,
        roomId: String,
        roomPassword: String,
        inviteExpiryPreset: InviteExpiryPreset = InviteExpiryPreset.Day,
    ): CreatedSecureRoom {
        val inviteDurationMillis = requireNotNull(inviteExpiryPreset.durationMillis) {
            "Invite expiry is required for release builds"
        }
        val normalizedRoomId = normalizeRoomId(roomId)
        val roomIdentity = roomIdentityManager.identityForRoom(normalizedRoomId)
        val derived = roomSecretDeriver.derive(normalizedRoomId, roomPassword)
        val mixnetHints = MixnetInviteRouteHints.derive(normalizedRoomId, roomPassword)
        val ownerTransportPublicKeyHex = deviceTransportPublicKeyHex()
        val issuedAtEpochMillis = System.currentTimeMillis()
        val expiresAtEpochMillis = issuedAtEpochMillis + inviteDurationMillis
        val session = SecureRoomSession(
            roomId = normalizedRoomId,
            roomLabel = label.ifBlank { "Private Room" },
            ownerFingerprint = roomIdentity.fingerprint,
            roomPassword = roomPassword,
            epoch = 0,
            packetSize = 512,
            messageEncryption = "XChaCha20-Poly1305 envelope",
            keyDerivation = "Argon2id",
            transportMode = transportModeLabel,
            overlayMode = overlayModeLabel,
            masterKey = derived.masterKey,
            authKey = derived.authKey,
            attachmentKey = derived.attachmentKey,
            chainKey = derived.chainKey,
            nextSequence = 0,
            recentMessageIds = mutableListOf(),
        )
        sessions[roomId] = session
        persistSessions()

        val invitePayload = JSONObject()
            .put("roomId", normalizedRoomId)
            .put("roomLabel", session.roomLabel)
            .put("roomPassword", roomPassword)
            .put("epoch", session.epoch)
            .put("ownerFingerprint", roomIdentity.fingerprint)
            .put("ownerTransportPublicKeyHex", ownerTransportPublicKeyHex)
            .put("ownerSigningPublicKey", base64UrlEncode(roomIdentity.signingPublicKey))
            .put("ownerDhPublicKey", base64UrlEncode(roomIdentity.dhPublicKey))
            .put("issuedAtEpochMillis", issuedAtEpochMillis)
            .put("expiresAtEpochMillis", expiresAtEpochMillis)
            .put("messageEncryption", session.messageEncryption)
            .put("keyDerivation", session.keyDerivation)
            .put("packetSize", session.packetSize)
            .put("mixnet", mixnetHints.toJson())

        val inviteSignature = roomIdentityManager.sign(normalizedRoomId, utf8(invitePayload.toString()))
        val token = base64UrlEncode(
            utf8(
                invitePayload
                    .put("signature", base64UrlEncode(inviteSignature))
                    .toString(),
            ),
        )

        return CreatedSecureRoom(
            roomId = normalizedRoomId,
            roomPassword = roomPassword,
            invitePackage = InvitePackage(
                roomId = normalizedRoomId,
                inviteToken = token,
                shareUrl = "epher://room/$token",
                qrPayload = token,
                expiresLabel = inviteExpiryLabel(expiresAtEpochMillis),
                expiresAtEpochMillis = expiresAtEpochMillis,
                ownerFingerprint = roomIdentity.fingerprint,
                ownerTransportPublicKeyHex = ownerTransportPublicKeyHex,
                signatureState = "Invite signed by owner key",
            ),
            mixnetRouteHintsJson = mixnetHints.encode(),
            ownerTransportPublicKeyHex = ownerTransportPublicKeyHex,
        )
    }

    fun joinRoom(
        inviteToken: String,
        defaultLabel: String = "Joined Room",
    ): JoinedSecureRoom {
        return runCatching {
            val normalizedInviteToken = normalizeInviteToken(inviteToken)
            val json = JSONObject(base64UrlDecode(normalizedInviteToken).toString(Charsets.UTF_8))
            val signature = base64UrlDecode(json.getString("signature"))
            json.remove("signature")

            val ownerSigningPublicKey = base64UrlDecode(json.getString("ownerSigningPublicKey"))
            val ownerDhPublicKey = base64UrlDecode(json.getString("ownerDhPublicKey"))
            val derivedOwnerFingerprint = fingerprintFor(ownerSigningPublicKey, ownerDhPublicKey)
            json.optString("ownerFingerprint")
                .takeIf { it.isNotBlank() }
                ?.let { claimedFingerprint ->
                    requireCrypto(
                        claimedFingerprint.equals(derivedOwnerFingerprint, ignoreCase = true),
                        "Invite owner fingerprint mismatch",
                    )
                }
            requireCrypto(
                identityManager.verify(ownerSigningPublicKey, utf8(json.toString()), signature),
                "Invite signature invalid",
            )

            val roomId = normalizeRoomId(json.getString("roomId"))
            val roomLabel = json.optString("roomLabel", defaultLabel)
            val roomPassword = json.getString("roomPassword")
            val ownerTransportPublicKeyHex = json.optString("ownerTransportPublicKeyHex")
                .takeIf { it.matches(Regex("^[0-9a-fA-F]{64}$")) }
                ?.lowercase(Locale.US)
            val mixnetHints = MixnetInviteRouteHints.fromJsonObject(json.optJSONObject("mixnet"))
                ?: MixnetInviteRouteHints.derive(roomId, roomPassword)
             val expiresAtEpochMillis = json.opt("expiresAtEpochMillis")
                 ?.takeUnless { it == JSONObject.NULL }
                 ?.toString()
                 ?.toLongOrNull()
             val issuedAtEpochMillis = json.opt("issuedAtEpochMillis")
                 ?.takeUnless { it == JSONObject.NULL }
                 ?.toString()
                 ?.toLongOrNull()
             
             // Verify expiry independently: check both claimed expiry and max duration from issue
             val now = System.currentTimeMillis()
             if (expiresAtEpochMillis == null) {
                 throw IllegalArgumentException("Invite link is missing a required expiry")
             }
             if (now > expiresAtEpochMillis) {
                 throw IllegalArgumentException("Invite link has expired (claimed expiry reached)")
             }
             // Secondary check: ensure expiry doesn't exceed 30 days from issue (prevents tampering)
             if (issuedAtEpochMillis != null) {
                 val maxAllowedExpiry = issuedAtEpochMillis + 30 * 24 * 60 * 60 * 1000L
                 if (expiresAtEpochMillis > maxAllowedExpiry) {
                     throw IllegalArgumentException("Invite expiry time tampered (exceeds 30-day limit)")
                 }
             }
            val derived = roomSecretDeriver.derive(roomId, roomPassword)

            sessions[roomId] = SecureRoomSession(
                roomId = roomId,
                roomLabel = roomLabel,
                ownerFingerprint = derivedOwnerFingerprint,
                roomPassword = roomPassword,
                epoch = json.optLong("epoch", 0L),
                packetSize = json.optInt("packetSize", 512),
                messageEncryption = json.optString("messageEncryption", "XChaCha20-Poly1305 envelope"),
                keyDerivation = json.optString("keyDerivation", "Argon2id"),
                transportMode = transportModeLabel,
                overlayMode = overlayModeLabel,
                masterKey = derived.masterKey,
                authKey = derived.authKey,
                attachmentKey = derived.attachmentKey,
                chainKey = derived.chainKey,
                nextSequence = 0,
                recentMessageIds = mutableListOf(),
            )
            roomIdentityManager.identityForRoom(roomId)
            persistSessions()
            JoinedSecureRoom(
                roomId = roomId,
                roomLabel = roomLabel,
                ownerFingerprint = derivedOwnerFingerprint,
                inviteExpiresAtEpochMillis = expiresAtEpochMillis,
                inviteExpiresLabel = inviteExpiryLabel(expiresAtEpochMillis),
                mixnetRouteHintsJson = mixnetHints.encode(),
                ownerTransportPublicKeyHex = ownerTransportPublicKeyHex,
            )
        }.getOrElse { throwable ->
            when (throwable) {
                is IllegalArgumentException,
                is GeneralSecurityException -> throw throwable
                else -> throw IllegalArgumentException("Invalid invite link or token")
            }
        }
    }

    fun removeRoom(roomId: String) {
        val removedSession = sessions.remove(roomId) != null
        roomIdentityManager.removeRoom(roomId)
        if (removedSession) {
            persistSessions()
        }
    }

    fun roomProfile(roomId: String): RoomSecurityProfile = securityProfile().copy(
        transportMode = sessions[roomId]?.transportMode ?: securityProfile().transportMode,
        overlayMode = sessions[roomId]?.overlayMode ?: securityProfile().overlayMode,
    )

    fun mixnetRouteHintsJson(roomId: String): String {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        return MixnetInviteRouteHints.derive(session.roomId, session.roomPassword).encode()
    }

    fun transportTopicHex(roomId: String): String {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        val topic = hkdfSha256(
            ikm = session.masterKey,
            salt = session.authKey,
            info = utf8("epher.transport.topic"),
            length = 32,
        )
        return topic.joinToString("") { "%02x".format(it) }
    }

    fun deviceTransportSeedHex(): String {
        val identity = identityManager.localIdentity()
        val seed = hkdfSha256(
            ikm = identity.dhPrivateKey,
            salt = identity.signingPublicKey,
            info = utf8("epher.device.transport.seed"),
            length = 32,
        )
        return seed.joinToString("") { "%02x".format(it) }
    }

    fun deviceTransportPublicKeyHex(): String {
        val seed = hexDecode(deviceTransportSeedHex())
        val publicKey = Ed25519PrivateKeyParameters(seed, 0)
            .generatePublicKey()
            .encoded
        return hexEncode(publicKey)
    }

    fun roomTransportSeedHex(roomId: String): String = transportSeedHex(roomId, roomFingerprint(roomId))

    fun transportSeedHex(
        roomId: String,
        localFingerprint: String,
    ): String {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        val seed = hkdfSha256(
            ikm = session.masterKey,
            salt = session.authKey,
            info = utf8("epher.transport.seed.$localFingerprint"),
            length = 32,
        )
        return seed.joinToString("") { "%02x".format(it) }
    }

    fun pairwiseKeyMaterial(roomId: String): RoomPairwiseKeyMaterial {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        return RoomPairwiseKeyMaterial(
            masterKey = session.masterKey.copyOf(),
            authKey = session.authKey.copyOf(),
        )
    }

    fun rotateRoomSecret(roomId: String): RoomRekeyMaterial {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        val nextPassword = base64UrlEncode(randomBytes(18))
        val nextEpoch = session.epoch + 1
        applyRoomKeyMaterial(session, nextPassword, nextEpoch)
        persistSessions()
        val mixnetHints = MixnetInviteRouteHints.derive(session.roomId, nextPassword)
        val expiresAtEpochMillis = System.currentTimeMillis() + InviteExpiryPreset.Day.durationMillis!!
        val roomIdentity = roomIdentityManager.identityForRoom(session.roomId)
        val ownerTransportPublicKeyHex = deviceTransportPublicKeyHex()
        val invitePayload = JSONObject()
            .put("roomId", session.roomId)
            .put("roomLabel", session.roomLabel)
            .put("roomPassword", nextPassword)
            .put("epoch", nextEpoch)
            .put("ownerFingerprint", roomIdentity.fingerprint)
            .put("ownerTransportPublicKeyHex", ownerTransportPublicKeyHex)
            .put("ownerSigningPublicKey", base64UrlEncode(roomIdentity.signingPublicKey))
            .put("ownerDhPublicKey", base64UrlEncode(roomIdentity.dhPublicKey))
            .put("issuedAtEpochMillis", System.currentTimeMillis())
            .put("expiresAtEpochMillis", expiresAtEpochMillis)
            .put("messageEncryption", session.messageEncryption)
            .put("keyDerivation", session.keyDerivation)
            .put("packetSize", session.packetSize)
            .put("mixnet", mixnetHints.toJson())
        val signature = roomIdentityManager.sign(session.roomId, utf8(invitePayload.toString()))
        val token = base64UrlEncode(
            utf8(invitePayload.put("signature", base64UrlEncode(signature)).toString()),
        )
        return RoomRekeyMaterial(
            roomId = session.roomId,
            roomPassword = nextPassword,
            epoch = nextEpoch,
            mixnetRouteHintsJson = mixnetHints.encode(),
            invitePackage = InvitePackage(
                roomId = session.roomId,
                inviteToken = token,
                shareUrl = "epher://room/$token",
                qrPayload = token,
                expiresLabel = inviteExpiryLabel(expiresAtEpochMillis),
                expiresAtEpochMillis = expiresAtEpochMillis,
                ownerFingerprint = roomIdentity.fingerprint,
                ownerTransportPublicKeyHex = ownerTransportPublicKeyHex,
                signatureState = "Invite signed by owner key after rekey",
            ),
        )
    }

    fun applyRoomRekey(
        roomId: String,
        roomPassword: String,
        epoch: Long,
    ): RoomRekeyMaterial {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        requireCrypto(epoch > session.epoch, "Ignoring stale room rekey")
        applyRoomKeyMaterial(session, roomPassword, epoch)
        persistSessions()
        return RoomRekeyMaterial(
            roomId = session.roomId,
            roomPassword = roomPassword,
            epoch = epoch,
            mixnetRouteHintsJson = MixnetInviteRouteHints.derive(session.roomId, roomPassword).encode(),
        )
    }

    private fun applyRoomKeyMaterial(
        session: SecureRoomSession,
        roomPassword: String,
        epoch: Long,
    ) {
        val derived = roomSecretDeriver.derive(session.roomId, roomPassword)
        session.roomPassword = roomPassword
        session.epoch = epoch
        session.masterKey = derived.masterKey
        session.authKey = derived.authKey
        session.attachmentKey = derived.attachmentKey
        session.chainKey = derived.chainKey
        session.nextSequence = 0
        session.recentMessageIds.clear()
    }

    fun createEncryptedEnvelope(
        roomId: String,
        senderName: String,
        plaintext: String,
    ): SecureEnvelope {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        val identity = roomIdentityManager.identityForRoom(roomId)
        // Burn a sequence before encryption so a crash cannot reuse the same
        // sequence-derived key. A lost sequence is safe; a reused one is not.
        val sequence = session.nextSequence
        session.nextSequence += 1
        persistSessions()
        val messageId = UUID.randomUUID().toString()
        val sentAt = System.currentTimeMillis()
        val chainSeed = session.chainKey
        val messageKey = hkdfSha256(chainSeed, session.masterKey, utf8("epher.msg.$sequence"), 32)
        val nextChainKey = hkdfSha256(chainSeed, session.masterKey, utf8("epher.chain.${sequence + 1}"), 32)

        val payload = JSONObject()
            .put("senderName", senderName)
            .put("body", plaintext)
            .put("sentAtEpochMillis", sentAt)
            .put("messageId", messageId)
            .toString()
        val paddedPayload = padForFixedPacket(utf8(payload), session.packetSize)
        val nonce = randomBytes(24)
        val associatedData = utf8("${roomId}|${identity.fingerprint}|${messageId}|$sequence")
        val ciphertext = InsecureNonceXChaCha20Poly1305(messageKey)
            .encrypt(nonce, paddedPayload, associatedData)
        val signature = roomIdentityManager.sign(roomId, associatedData + nonce + ciphertext)

        session.chainKey = nextChainKey
        session.recentMessageIds.add(messageId)
        trimReplayWindow(session)
        persistSessions()

        return SecureEnvelope(
            roomId = roomId,
            senderFingerprint = identity.fingerprint,
            senderPublicKey = base64UrlEncode(identity.signingPublicKey),
            senderName = senderName,
            messageId = messageId,
            sequence = sequence,
            packetSize = session.packetSize,
            jitterDelayMillis = 35L + (sequence % 5L) * 11L,
            nonce = nonce,
            ciphertext = ciphertext,
            signature = signature,
        )
    }

    fun decryptEnvelope(
        roomId: String,
        envelope: SecureEnvelope,
    ): DecryptedRoomMessage {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Missing room session")
        requireCrypto(!session.recentMessageIds.contains(envelope.messageId), "Replay detected")

        val associatedData = utf8("${roomId}|${envelope.senderFingerprint}|${envelope.messageId}|${envelope.sequence}")
        val publicKey = base64UrlDecode(envelope.senderPublicKey)
        requireCrypto(
            roomIdentityManager.verify(publicKey, associatedData + envelope.nonce + envelope.ciphertext, envelope.signature),
            "Envelope signature invalid",
        )

        val messageKey = hkdfSha256(session.chainKey, session.masterKey, utf8("epher.msg.${envelope.sequence}"), 32)
        val plaintext = InsecureNonceXChaCha20Poly1305(messageKey)
            .decrypt(envelope.nonce, envelope.ciphertext, associatedData)
        session.recentMessageIds.add(envelope.messageId)
        trimReplayWindow(session)
        persistSessions()

        val payload = JSONObject(unpadFixedPacket(plaintext).toString(Charsets.UTF_8))
        return DecryptedRoomMessage(
            senderName = payload.getString("senderName"),
            body = payload.getString("body"),
            sentAtEpochMillis = payload.getLong("sentAtEpochMillis"),
        )
    }

    fun issuePeerChallenge(roomId: String): PeerChallenge {
        requireCrypto(sessions.containsKey(roomId), "Unknown room")
        return peerAuthenticator.issueChallenge()
    }

    fun respondToPeerChallenge(roomId: String, challenge: PeerChallenge): ByteArray {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Unknown room")
        return peerAuthenticator.respond(
            authKey = session.authKey,
            roomId = roomId,
            fingerprint = roomFingerprint(roomId),
            challenge = challenge,
        )
    }

    fun verifyPeerChallenge(
        roomId: String,
        fingerprint: String,
        challenge: PeerChallenge,
        response: ByteArray,
    ) {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Unknown room")
        peerAuthenticator.verify(
            authKey = session.authKey,
            roomId = roomId,
            fingerprint = fingerprint,
            challenge = challenge,
            response = response,
        )
    }

    fun encryptAttachment(roomId: String, bytes: ByteArray): EncryptedAttachment {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Unknown room")
        val nonce = randomBytes(24)
        val digest = sha256(bytes)
        val aad = utf8("epher.attachment|$roomId|${base64UrlEncode(digest)}")
        val ciphertext = InsecureNonceXChaCha20Poly1305(session.attachmentKey)
            .encrypt(nonce, padForPrivacyBucket(bytes, session.packetSize), aad)
        return EncryptedAttachment(
            nonce = nonce,
            ciphertext = ciphertext,
            digestHex = digest.joinToString("") { "%02x".format(it) },
        )
    }

    fun decryptAttachment(
        roomId: String,
        attachment: EncryptedAttachment,
    ): ByteArray {
        val session = sessions[roomId] ?: throw GeneralSecurityException("Unknown room")
        val digestBytes = attachment.digestHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
        val aad = utf8("epher.attachment|$roomId|${base64UrlEncode(digestBytes)}")
        val plaintext = InsecureNonceXChaCha20Poly1305(session.attachmentKey)
            .decrypt(attachment.nonce, attachment.ciphertext, aad)
        val unpadded = unpadFixedPacket(plaintext)
        requireCrypto(
            attachment.digestHex.equals(
                sha256(unpadded).joinToString("") { "%02x".format(it) },
                ignoreCase = true,
            ),
            "Attachment integrity check failed",
        )
        return unpadded
    }

    private fun trimReplayWindow(session: SecureRoomSession) {
        while (session.recentMessageIds.size > 32) {
            session.recentMessageIds.removeAt(0)
        }
    }

    private fun persistSessions() {
        if (sessions.isEmpty()) {
            secureBlobStore.remove(SESSIONS_KEY)
            return
        }
        val payload = JSONArray()
        sessions.values.forEach { session ->
            payload.put(
                JSONObject()
                    .put("roomId", session.roomId)
                    .put("roomLabel", session.roomLabel)
                    .put("ownerFingerprint", session.ownerFingerprint)
                    .put("roomPassword", session.roomPassword)
                    .put("epoch", session.epoch)
                    .put("packetSize", session.packetSize)
                    .put("messageEncryption", session.messageEncryption)
                    .put("keyDerivation", session.keyDerivation)
                    .put("transportMode", session.transportMode)
                    .put("overlayMode", session.overlayMode)
                    .put("masterKey", base64UrlEncode(session.masterKey))
                    .put("authKey", base64UrlEncode(session.authKey))
                    .put("attachmentKey", base64UrlEncode(session.attachmentKey))
                    .put("chainKey", base64UrlEncode(session.chainKey))
                    .put("nextSequence", session.nextSequence)
                    .put("recentMessageIds", JSONArray(session.recentMessageIds)),
            )
        }
        secureBlobStore.putString(SESSIONS_KEY, payload.toString())
    }

    private fun normalizeInviteToken(rawInvite: String): String {
        val trimmed = rawInvite.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Missing invite token")
        }

        val deepLinkMatch = Regex("""epher://room/([A-Za-z0-9_-]+)""")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
        return deepLinkMatch ?: trimmed
    }

    private fun loadPersistedSessions() {
        val persisted = secureBlobStore.getString(SESSIONS_KEY) ?: return
        val array = JSONArray(persisted)
        repeat(array.length()) { index ->
            val json = array.getJSONObject(index)
            val roomId = runCatching { normalizeRoomId(json.getString("roomId")) }.getOrNull() ?: return@repeat
            sessions[roomId] = SecureRoomSession(
                roomId = roomId,
                roomLabel = json.getString("roomLabel"),
                ownerFingerprint = json.getString("ownerFingerprint"),
                roomPassword = json.getString("roomPassword"),
                epoch = json.optLong("epoch", 0L),
                packetSize = json.getInt("packetSize"),
                messageEncryption = json.getString("messageEncryption"),
                keyDerivation = json.getString("keyDerivation"),
                transportMode = json.getString("transportMode"),
                overlayMode = json.getString("overlayMode"),
                masterKey = base64UrlDecode(json.getString("masterKey")),
                authKey = base64UrlDecode(json.getString("authKey")),
                attachmentKey = base64UrlDecode(json.getString("attachmentKey")),
                chainKey = base64UrlDecode(json.getString("chainKey")),
                nextSequence = json.getLong("nextSequence"),
                recentMessageIds = MutableList(json.getJSONArray("recentMessageIds").length()) { listIndex ->
                    json.getJSONArray("recentMessageIds").getString(listIndex)
                },
            )
        }
    }

    private fun inviteExpiryLabel(expiresAtEpochMillis: Long?): String {
        return if (expiresAtEpochMillis == null) {
            "Invite does not expire automatically"
        } else {
            "Invite valid until ${expiryFormatter.format(Date(expiresAtEpochMillis))}"
        }
    }

    private companion object {
        const val SESSIONS_KEY = "secure_room_sessions_v1"
        const val MAX_ROOM_ID_LENGTH = 128
        val ROOM_ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        val expiryFormatter = SimpleDateFormat("MMM d, h:mm a", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
    }

    private fun normalizeRoomId(rawRoomId: String): String {
        val normalized = rawRoomId.trim()
        require(normalized.isNotBlank()) { "Missing room ID" }
        require(normalized.length <= MAX_ROOM_ID_LENGTH) { "Room ID too long" }
        require(ROOM_ID_PATTERN.matches(normalized)) { "Room ID contains unsupported characters" }
        return normalized
    }
}
