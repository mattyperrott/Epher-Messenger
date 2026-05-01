package com.epher.app.data

import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.InvitePackage
import com.epher.app.data.model.MessageDeliveryState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RetentionPreset
import com.epher.app.data.model.AttachmentTransferState
import com.epher.app.data.model.RoomAttachment
import com.epher.app.data.model.RoomMessage
import com.epher.app.data.model.RoomRole
import com.epher.app.data.model.RoomSecurityProfile
import com.epher.app.data.model.RoomSummary
import com.epher.app.security.SecureBlobStore
import org.json.JSONArray
import org.json.JSONObject

class LocalRoomCacheStore(
    private val secureBlobStore: SecureBlobStore,
) {
    fun load(): CachedRoomsState? {
        val payload = secureBlobStore.getString(CACHE_KEY) ?: return null
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        return CachedRoomsState(
            snapshot = RoomsSnapshot(
                rooms = root.optJSONArray("rooms").toRoomList(),
                participants = root.optJSONObject("participants").toParticipantsMap(),
                messages = root.optJSONObject("messages").toMessagesMap(),
                logs = root.optJSONObject("logs").toLogsMap(),
            ),
            pendingOutbound = root.optJSONObject("pendingOutbound").toPendingMap(),
        )
    }

    fun save(
        snapshot: RoomsSnapshot,
        pendingOutbound: Map<String, List<PendingOutboundMessage>>,
    ) {
        if (snapshot.rooms.isEmpty() &&
            snapshot.participants.isEmpty() &&
            snapshot.messages.isEmpty() &&
            pendingOutbound.isEmpty()
        ) {
            secureBlobStore.remove(CACHE_KEY)
            return
        }

        val root = JSONObject()
            .put("rooms", JSONArray().apply {
                snapshot.rooms.forEach { room ->
                    put(
                        JSONObject()
                            .put("id", room.id)
                            .put("localLabel", room.localLabel)
                            .put("participantCount", room.participantCount)
                            .put("unreadCount", room.unreadCount)
                            .put("pendingOutgoingCount", room.pendingOutgoingCount)
                            .put("connectionState", room.connectionState.name)
                            .put("isEncryptedSessionEstablished", room.isEncryptedSessionEstablished)
                            .put("retentionPreset", room.retentionPreset.name)
                            .put("isOwner", room.isOwner)
                            .put("lastActivityEpochMillis", room.lastActivityEpochMillis)
                            .put(
                                "invitePackage",
                                JSONObject()
                                    .put("roomId", room.invitePackage.roomId)
                                    .put("inviteToken", room.invitePackage.inviteToken)
                                    .put("shareUrl", room.invitePackage.shareUrl)
                                    .put("qrPayload", room.invitePackage.qrPayload)
                                    .put("expiresLabel", room.invitePackage.expiresLabel)
                                    .put("expiresAtEpochMillis", room.invitePackage.expiresAtEpochMillis)
                                    .put("ownerFingerprint", room.invitePackage.ownerFingerprint)
                                    .put("ownerTransportPublicKeyHex", room.invitePackage.ownerTransportPublicKeyHex)
                                    .put("signatureState", room.invitePackage.signatureState),
                            )
                            .put(
                                "securityProfile",
                                JSONObject()
                                    .put("messageEncryption", room.securityProfile.messageEncryption)
                                    .put("identityScheme", room.securityProfile.identityScheme)
                                    .put("transportEncryption", room.securityProfile.transportEncryption)
                                    .put("keyDerivation", room.securityProfile.keyDerivation)
                                    .put("peerAuthentication", room.securityProfile.peerAuthentication)
                                    .put("replayProtection", room.securityProfile.replayProtection)
                                    .put("forwardSecrecy", room.securityProfile.forwardSecrecy)
                                    .put("trafficObfuscation", room.securityProfile.trafficObfuscation)
                                    .put("metadataRetention", room.securityProfile.metadataRetention)
                                    .put("groupKeyPolicy", room.securityProfile.groupKeyPolicy)
                                    .put("transportMode", room.securityProfile.transportMode)
                                    .put("overlayMode", room.securityProfile.overlayMode)
                                    .put("fileSharing", room.securityProfile.fileSharing),
                            ),
                    )
                }
            })
            .put("participants", JSONObject().apply {
                snapshot.participants.forEach { (roomId, participants) ->
                    put(
                        roomId,
                        JSONArray().apply {
                            participants.forEach { participant ->
                                put(
                                    JSONObject()
                                        .put("id", participant.id)
                                        .put("displayName", participant.displayName)
                                        .put("isOnline", participant.isOnline)
                                        .put("isVerified", participant.isVerified)
                                        .put("role", participant.role.name)
                                        .put("fingerprint", participant.fingerprint)
                                        .put("transportPublicKeyHex", participant.transportPublicKeyHex)
                                        .put("relayEligible", participant.relayEligible)
                                        .put("isRemoved", participant.isRemoved),
                                )
                            }
                        },
                    )
                }
            })
            .put("messages", JSONObject().apply {
                snapshot.messages.forEach { (roomId, messages) ->
                    put(
                        roomId,
                        JSONArray().apply {
                            messages.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("id", message.id)
                                        .put("roomId", message.roomId)
                                        .put("senderName", message.senderName)
                                        .put("body", message.body)
                                        .put("sentAt", message.sentAt)
                                        .put("isLocalUser", message.isLocalUser)
                                        .put("isSystemEvent", message.isSystemEvent)
                                        .put("deliveryState", message.deliveryState.name)
                                        .put(
                                            "attachment",
                                            message.attachment?.let { attachment ->
                                                JSONObject()
                                                    .put("fileName", attachment.fileName)
                                                    .put("mimeType", attachment.mimeType)
                                                    .put("byteSize", attachment.byteSize)
                                                    .put("encryptedNonce", attachment.encryptedNonce)
                                                    .put("encryptedCiphertext", attachment.encryptedCiphertext)
                                                    .put("digestHex", attachment.digestHex)
                                                    .put("storageKey", attachment.storageKey)
                                                    .put("transferState", attachment.transferState.name)
                                                    .put("receivedChunks", attachment.receivedChunks)
                                                    .put("totalChunks", attachment.totalChunks)
                                            } ?: JSONObject.NULL,
                                        ),
                                )
                            }
                        },
                    )
                }
            })
            .put("logs", JSONObject().apply {
                snapshot.logs.forEach { (roomId, lines) ->
                    put(roomId, JSONArray(lines))
                }
            })
            .put("pendingOutbound", JSONObject().apply {
                pendingOutbound.forEach { (roomId, messages) ->
                    put(
                        roomId,
                        JSONArray().apply {
                            messages.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("roomId", message.roomId)
                                        .put("messageId", message.messageId)
                                        .put("logicalMessageId", message.logicalMessageId)
                                        .put("body", message.body)
                                        .put("createdAtEpochMillis", message.createdAtEpochMillis)
                                        .put("retryCount", message.retryCount)
                                        .put("lastDispatchAtEpochMillis", message.lastDispatchAtEpochMillis)
                                        .put("awaitingAckFrom", JSONArray(message.awaitingAckFrom)),
                                )
                            }
                        },
                    )
                }
            })

        secureBlobStore.putString(CACHE_KEY, root.toString())
    }

    fun clear() {
        secureBlobStore.remove(CACHE_KEY)
    }

    companion object {
        private const val CACHE_KEY = "epher.rooms.cache.v1"
    }
}

data class CachedRoomsState(
    val snapshot: RoomsSnapshot,
    val pendingOutbound: Map<String, List<PendingOutboundMessage>>,
)

private fun JSONArray?.toRoomList(): List<RoomSummary> {
    if (this == null) return emptyList()
    return buildList {
        repeat(length()) { index ->
            val room = optJSONObject(index) ?: return@repeat
            val roomId = room.optString("id").takeIf { it.isNotBlank() } ?: return@repeat
            val invite = room.optJSONObject("invitePackage") ?: JSONObject()
            val profile = room.optJSONObject("securityProfile") ?: JSONObject()
            add(
                RoomSummary(
                    id = roomId,
                    localLabel = room.optString("localLabel").ifBlank { roomId },
                    participantCount = room.optInt("participantCount", 0),
                    unreadCount = room.optInt("unreadCount", 0),
                    pendingOutgoingCount = room.optInt("pendingOutgoingCount", 0),
                    connectionState = room.optString("connectionState")
                        .takeIf { it.isNotBlank() }
                        ?.let(::enumValueOrNull)
                        ?: ConnectionState.Reconnecting,
                    isEncryptedSessionEstablished = room.optBoolean("isEncryptedSessionEstablished", false),
                    retentionPreset = room.optString("retentionPreset")
                        .takeIf { it.isNotBlank() }
                        ?.let(::enumValueOrNull)
                        ?: RetentionPreset.LeaveOnly,
                    isOwner = room.optBoolean("isOwner", false),
                    invitePackage = InvitePackage(
                        roomId = invite.optString("roomId", roomId),
                        inviteToken = invite.optString("inviteToken"),
                        shareUrl = invite.optString("shareUrl"),
                        qrPayload = invite.optString("qrPayload"),
                        expiresLabel = invite.optString("expiresLabel"),
                        expiresAtEpochMillis = invite.opt("expiresAtEpochMillis")
                            ?.takeUnless { it == JSONObject.NULL }
                            ?.toString()
                            ?.toLongOrNull(),
                        ownerFingerprint = invite.optString("ownerFingerprint", "UNVERIFIED"),
                        ownerTransportPublicKeyHex = invite.optString("ownerTransportPublicKeyHex")
                            .takeIf { it.isNotBlank() },
                        signatureState = invite.optString("signatureState", "Signed invite token"),
                    ),
                    securityProfile = RoomSecurityProfile(
                        messageEncryption = profile.optString("messageEncryption", RoomSecurityProfile().messageEncryption),
                        identityScheme = profile.optString("identityScheme", RoomSecurityProfile().identityScheme),
                        transportEncryption = profile.optString("transportEncryption", RoomSecurityProfile().transportEncryption),
                        keyDerivation = profile.optString("keyDerivation", RoomSecurityProfile().keyDerivation),
                        peerAuthentication = profile.optString("peerAuthentication", RoomSecurityProfile().peerAuthentication),
                        replayProtection = profile.optString("replayProtection", RoomSecurityProfile().replayProtection),
                        forwardSecrecy = profile.optString("forwardSecrecy", RoomSecurityProfile().forwardSecrecy),
                        trafficObfuscation = profile.optString("trafficObfuscation", RoomSecurityProfile().trafficObfuscation),
                        metadataRetention = profile.optString("metadataRetention", RoomSecurityProfile().metadataRetention),
                        groupKeyPolicy = profile.optString("groupKeyPolicy", RoomSecurityProfile().groupKeyPolicy),
                        transportMode = profile.optString("transportMode", RoomSecurityProfile().transportMode),
                        overlayMode = profile.optString("overlayMode", RoomSecurityProfile().overlayMode),
                        fileSharing = profile.optString("fileSharing", RoomSecurityProfile().fileSharing),
                    ),
                    lastActivityEpochMillis = room.optLong("lastActivityEpochMillis", System.currentTimeMillis()),
                ),
            )
        }
    }
}

private fun JSONObject?.toParticipantsMap(): Map<String, List<Participant>> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { roomId ->
        optJSONArray(roomId)?.let { array ->
            buildList {
                repeat(array.length()) { index ->
                    val participant = array.optJSONObject(index) ?: return@repeat
                    add(
                        Participant(
                            id = participant.optString("id"),
                            displayName = participant.optString("displayName"),
                            isOnline = participant.optBoolean("isOnline", false),
                            isVerified = participant.optBoolean("isVerified", false),
                            role = participant.optString("role")
                                .takeIf { it.isNotBlank() }
                                ?.let(::enumValueOrNull)
                                ?: RoomRole.Member,
                            fingerprint = participant.optString("fingerprint"),
                            transportPublicKeyHex = participant.optString("transportPublicKeyHex").ifBlank { null },
                            relayEligible = participant.optBoolean("relayEligible", false),
                            isRemoved = participant.optBoolean("isRemoved", false),
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}

private fun JSONObject?.toMessagesMap(): Map<String, List<RoomMessage>> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { roomId ->
        optJSONArray(roomId)?.let { array ->
            buildList {
                repeat(array.length()) { index ->
                    val message = array.optJSONObject(index) ?: return@repeat
                    add(
                        RoomMessage(
                            id = message.optString("id"),
                            roomId = message.optString("roomId", roomId),
                            senderName = message.optString("senderName"),
                            body = message.optString("body"),
                            sentAt = message.optString("sentAt"),
                            isLocalUser = message.optBoolean("isLocalUser", false),
                            isSystemEvent = message.optBoolean("isSystemEvent", false),
                            deliveryState = message.optString("deliveryState")
                                .takeIf { it.isNotBlank() }
                                ?.let(::enumValueOrNull)
                                ?: MessageDeliveryState.Sent,
                            attachment = message.optJSONObject("attachment")?.let { attachment ->
                                RoomAttachment(
                                    fileName = attachment.optString("fileName"),
                                    mimeType = attachment.optString("mimeType", "application/octet-stream"),
                                    byteSize = attachment.optLong("byteSize", 0L),
                                    encryptedNonce = attachment.optString("encryptedNonce"),
                                    encryptedCiphertext = attachment.optString("encryptedCiphertext"),
                                    digestHex = attachment.optString("digestHex"),
                                    storageKey = attachment.optString("storageKey").ifBlank { null },
                                    transferState = attachment.optString("transferState")
                                        .takeIf { it.isNotBlank() }
                                        ?.let(::enumValueOrNull)
                                        ?: AttachmentTransferState.Available,
                                    receivedChunks = attachment.optInt("receivedChunks", 0),
                                    totalChunks = attachment.optInt("totalChunks", 0),
                                )
                            },
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}

private fun JSONObject?.toLogsMap(): Map<String, List<String>> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { roomId ->
        optJSONArray(roomId)?.let { array ->
            buildList {
                repeat(array.length()) { index ->
                    add(array.optString(index))
                }
            }
        }.orEmpty()
    }
}

private fun JSONObject?.toPendingMap(): Map<String, List<PendingOutboundMessage>> {
    if (this == null) return emptyMap()
    return keys().asSequence().associateWith { roomId ->
        optJSONArray(roomId)?.let { array ->
            buildList {
                repeat(array.length()) { index ->
                    val pending = array.optJSONObject(index) ?: return@repeat
                    add(
                        PendingOutboundMessage(
                            roomId = pending.optString("roomId", roomId),
                            messageId = pending.optString("messageId"),
                            logicalMessageId = pending.optString("logicalMessageId").ifBlank {
                                pending.optString("messageId")
                            },
                            body = pending.optString("body"),
                            createdAtEpochMillis = pending.optLong("createdAtEpochMillis", System.currentTimeMillis()),
                            retryCount = pending.optInt("retryCount", 0),
                            lastDispatchAtEpochMillis = pending.opt("lastDispatchAtEpochMillis")
                                ?.takeUnless { it == JSONObject.NULL }
                                ?.toString()
                                ?.toLongOrNull(),
                            awaitingAckFrom = buildList {
                                val array = pending.optJSONArray("awaitingAckFrom") ?: JSONArray()
                                repeat(array.length()) { ackIndex ->
                                    val fingerprint = array.optString(ackIndex).trim()
                                    if (fingerprint.isNotBlank()) add(fingerprint)
                                }
                            },
                        ),
                    )
                }
            }
        }.orEmpty()
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
