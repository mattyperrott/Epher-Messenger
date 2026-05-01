package com.epher.app.data

import android.util.Log
import com.epher.app.data.model.AttachmentTransferState
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.InvitePackage
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.MessageDeliveryState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.ResolvedAttachment
import com.epher.app.data.model.RetentionPreset
import com.epher.app.data.model.RoomAttachment
import com.epher.app.data.model.RoomMessage
import com.epher.app.data.model.RoomRole
import com.epher.app.data.model.RoomSummary
import com.epher.app.engine.P2PEngine
import com.epher.app.engine.P2PEngineEvent
import com.epher.app.security.PairwiseProtocolService
import com.epher.app.security.SecureRoomService
import com.epher.app.security.base64UrlDecode
import com.epher.app.security.base64UrlEncode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
class RoomsRepository(
    private val scope: CoroutineScope,
    private val engine: P2PEngine,
    private val localCacheStore: LocalRoomCacheStore,
    private val attachmentFileStore: AttachmentFileStore,
    private val roomSecurity: SecureRoomService,
    private val pairwiseProtocol: PairwiseProtocolService,
    private val localDisplayName: String,
    private val allowOptimisticConnectedState: Boolean = true,
) {
    private val cachedState = localCacheStore.load()
    private val pendingMutex = Mutex()
    private val flushMutexes = linkedMapOf<String, Mutex>()
    private val ackRetryJobs = linkedMapOf<String, Job>()
    private val pendingOutboundByRoom = linkedMapOf<String, MutableList<PendingOutboundMessage>>().apply {
        cachedState?.pendingOutbound?.forEach { (roomId, messages) ->
            put(roomId, messages.toMutableList())
        }
    }
    // Track decryption failures per room per peer to detect attacks
    private data class DecryptionFailure(
        val senderFingerprint: String,
        val failureCount: Int = 1,
        val firstFailureTime: Long = System.currentTimeMillis(),
    )
    private val decryptionFailuresByRoom = linkedMapOf<String, MutableMap<String, DecryptionFailure>>()
    
    // Track room connection state changes for timeout detection
    private data class RoomConnectionTracking(
        val roomId: String,
        val connectionState: ConnectionState,
        val enteredStateAtEpochMillis: Long = System.currentTimeMillis(),
    )
    private val roomConnectionTracking = linkedMapOf<String, RoomConnectionTracking>()
    private val _snapshot = MutableStateFlow(bootstrapSnapshot(cachedState?.snapshot ?: emptySnapshot()))
    val snapshot: StateFlow<RoomsSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            snapshot
                .debounce(CACHE_SAVE_DEBOUNCE_MILLIS)
                .collectLatest { state ->
                localCacheStore.save(state, snapshotPendingOutbound())
            }
        }
        scope.launch {
            engine.events.collect(::handleEngineEvent)
        }
        scope.launch {
            engine.start()
            syncRoomsToEngine(_snapshot.value.rooms)
            flushAllPendingOutbound("repository boot")
        }
        scope.launch {
            while (true) {
                delay(RETENTION_SWEEP_INTERVAL_MILLIS)
                purgeExpiredRoomsNow("scheduled retention sweep")
                cleanupStaleAttachmentTransfers("scheduled attachment cleanup")
            }
        }
        scope.launch {
            // Periodically check for stuck connections (rooms in "Reconnecting" for too long)
            while (true) {
                delay(RECONNECTION_CHECK_INTERVAL_MILLIS)
                detectStuckConnections()
            }
        }
    }

    suspend fun createRoom(
        label: String,
        retentionPreset: RetentionPreset,
        inviteExpiryPreset: InviteExpiryPreset,
    ): String {
        val trimmed = label.ifBlank { "Private Room" }
        val created = roomSecurity.createRoom(trimmed, inviteExpiryPreset)
        val createdAt = System.currentTimeMillis()
        val localPeerCard = pairwiseProtocol.createLocalPeerCard(created.roomId, localDisplayName)
        engine.createRoom(
            roomId = created.roomId,
            label = trimmed,
            topicHex = roomSecurity.transportTopicHex(created.roomId),
            transportSeedHex = roomSecurity.deviceTransportSeedHex(),
            localPeerCard = localPeerCard,
            mixnetRouteHintsJson = created.mixnetRouteHintsJson,
        )

        val room = RoomSummary(
            id = created.roomId,
            localLabel = trimmed,
            participantCount = 1,
            unreadCount = 0,
            connectionState = ConnectionState.Connecting,
            isEncryptedSessionEstablished = isEncryptedReady(created.roomId),
            retentionPreset = retentionPreset,
            isOwner = true,
            invitePackage = created.invitePackage,
            securityProfile = roomSecurity.roomProfile(created.roomId),
            lastActivityEpochMillis = createdAt,
        )

        updateSnapshot { snapshot ->
            snapshot.copy(
                rooms = listOf(room) + snapshot.rooms,
                participants = snapshot.participants + (
                    created.roomId to listOf(localParticipant(created.roomId, role = RoomRole.Owner))
                ),
                messages = snapshot.messages + (
                    created.roomId to listOf(
                        RoomMessage(
                            id = UUID.randomUUID().toString(),
                            roomId = created.roomId,
                            senderName = "System",
                            body = "Room created. Invite is signed and the room secret was derived with Argon2id.",
                            sentAt = "Now",
                            isLocalUser = false,
                            isSystemEvent = true,
                        ),
                    )
                ),
                logs = appendLog(
                    appendLog(snapshot.logs, created.roomId, "ENGINE >> room registration prepared"),
                    created.roomId,
                    "SECURITY >> local peer card signed and pairwise prekey staged",
                ),
            )
        }
        scheduleConnectionReadyFallback(created.roomId, "local room transport prepared")
        return created.roomId
    }

    suspend fun joinRoom(inviteToken: String): String {
        val normalized = inviteToken.trim()
        require(normalized.isNotBlank()) { "Invite token is required" }
        val joined = roomSecurity.joinRoom(normalized)
        val joinedAt = System.currentTimeMillis()
        val localPeerCard = pairwiseProtocol.createLocalPeerCard(joined.roomId, localDisplayName)
        engine.joinRoom(
            roomId = joined.roomId,
            label = joined.roomLabel,
            topicHex = roomSecurity.transportTopicHex(joined.roomId),
            transportSeedHex = roomSecurity.deviceTransportSeedHex(),
            localPeerCard = localPeerCard,
            inviteToken = normalized,
            mixnetRouteHintsJson = joined.mixnetRouteHintsJson,
        )
        joined.ownerTransportPublicKeyHex?.let { ownerTransportPublicKeyHex ->
            engine.updateKnownPeerTransportKeys(joined.roomId, listOf(ownerTransportPublicKeyHex))
        }

        val room = RoomSummary(
            id = joined.roomId,
            localLabel = joined.roomLabel,
            participantCount = 1,
            unreadCount = 0,
            connectionState = ConnectionState.Connecting,
            isEncryptedSessionEstablished = isEncryptedReady(joined.roomId),
            retentionPreset = RetentionPreset.Day,
            isOwner = false,
            invitePackage = InvitePackage(
                roomId = joined.roomId,
                inviteToken = normalized,
                shareUrl = "epher://room/$normalized",
                qrPayload = normalized,
                expiresLabel = joined.inviteExpiresLabel,
                expiresAtEpochMillis = joined.inviteExpiresAtEpochMillis,
                ownerFingerprint = joined.ownerFingerprint,
                ownerTransportPublicKeyHex = joined.ownerTransportPublicKeyHex,
                signatureState = "Owner signature verified locally",
            ),
            securityProfile = roomSecurity.roomProfile(joined.roomId),
            lastActivityEpochMillis = joinedAt,
        )

        updateSnapshot { snapshot ->
            snapshot.copy(
                rooms = listOf(room) + snapshot.rooms,
                participants = snapshot.participants + (
                    joined.roomId to listOf(localParticipant(joined.roomId, role = RoomRole.Member))
                ),
                messages = snapshot.messages + (
                    joined.roomId to listOf(
                        RoomMessage(
                            id = UUID.randomUUID().toString(),
                            roomId = joined.roomId,
                            senderName = "System",
                            body = "Invite accepted. Waiting for signed peer cards and pairwise sessions.",
                            sentAt = "Now",
                            isLocalUser = false,
                            isSystemEvent = true,
                        ),
                    )
                ),
                logs = appendLog(
                    appendLog(snapshot.logs, joined.roomId, "ENGINE >> join requested"),
                    joined.roomId,
                    "SECURITY >> invite verified and local peer card staged",
                ),
            )
        }
        scheduleConnectionReadyFallback(joined.roomId, "invite accepted locally")
        return joined.roomId
    }

    fun sendMessage(roomId: String, message: String) {
        val trimmed = message.trim()
        if (trimmed.isBlank()) return

        val createdAt = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val canAttemptNow = canAttemptImmediateSend(roomId)
        val pendingCountBefore = room(roomId)?.pendingOutgoingCount ?: 0
        updateSnapshot { snapshot ->
            snapshot.copy(
                rooms = snapshot.rooms.updateRoom(roomId) { room ->
                    room.copy(
                        pendingOutgoingCount = room.pendingOutgoingCount + 1,
                        lastActivityEpochMillis = createdAt,
                    )
                },
                messages = snapshot.messages + (
                    roomId to (snapshot.messages[roomId].orEmpty() + RoomMessage(
                        id = messageId,
                        roomId = roomId,
                            senderName = localDisplayName,
                        body = trimmed,
                        sentAt = formatTime(createdAt),
                        isLocalUser = true,
                        deliveryState = if (canAttemptNow) {
                            MessageDeliveryState.Sending
                        } else {
                            MessageDeliveryState.Queued
                        },
                    ))
                ),
                logs = if (canAttemptNow) {
                    snapshot.logs
                } else {
                    appendLog(
                        snapshot.logs,
                        roomId,
                        "TX >> queued locally until the room reconnects and a verified peer is available",
                    )
                },
            )
        }
        scope.launch {
            enqueuePendingOutbound(
                PendingOutboundMessage(
                    roomId = roomId,
                    messageId = messageId,
                    body = trimmed,
                    createdAtEpochMillis = createdAt,
                ),
            )

            val pendingCount = pendingCount(roomId)
            if (pendingCount != pendingCountBefore + 1) {
                updateSnapshot { snapshot ->
                    snapshot.copy(
                        rooms = snapshot.rooms.updateRoom(roomId) { room ->
                            room.copy(pendingOutgoingCount = pendingCount)
                        },
                    )
                }
            }

            if (!canAttemptNow) {
                Log.d(TAG, "Queued message $messageId for room $roomId")
                return@launch
            }

            flushPendingOutbound(roomId, "immediate send")
        }
    }

    fun sendAttachment(
        roomId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        require(bytes.isNotEmpty()) { "Selected file is empty" }
        require(bytes.size <= MAX_ATTACHMENT_BYTES) {
            "Current attachment limit is 1 MB in this build"
        }

        val normalizedName = normalizeAttachmentFileName(fileName)
        val normalizedMimeType = normalizeAttachmentMimeType(mimeType)
        val encryptedAttachment = roomSecurity.encryptAttachment(roomId, bytes)
        val transferId = UUID.randomUUID().toString()
        val encodedCiphertext = base64UrlEncode(encryptedAttachment.ciphertext)
        val totalChunks = encodedCiphertext.chunked(ATTACHMENT_CHUNK_BASE64_CHARS).size
        require(totalChunks in 1..MAX_ATTACHMENT_TOTAL_CHUNKS) {
            "Current attachment limit is 1 MB in this build"
        }
        val attachment = RoomAttachment(
            fileName = normalizedName,
            mimeType = normalizedMimeType,
            byteSize = bytes.size.toLong(),
            encryptedNonce = base64UrlEncode(encryptedAttachment.nonce),
            digestHex = encryptedAttachment.digestHex,
            encryptedCiphertext = "",
            storageKey = attachmentStorageKey(roomId, transferId),
            transferState = AttachmentTransferState.Available,
            receivedChunks = totalChunks,
            totalChunks = totalChunks,
        )
        val createdAt = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val manifestBody = encodeAttachmentManifestPayload(
            AttachmentManifestPayload(
                transferId = transferId,
                displayMessageId = messageId,
                fileName = attachment.fileName,
                mimeType = attachment.mimeType,
                byteSize = attachment.byteSize,
                encryptedNonce = attachment.encryptedNonce,
                digestHex = attachment.digestHex,
                totalChunks = totalChunks,
            ),
        )
        val chunkBodies = encodedCiphertext
            .chunked(ATTACHMENT_CHUNK_BASE64_CHARS)
            .mapIndexed { index, chunk ->
                encodeAttachmentChunkPayload(
                    AttachmentChunkPayload(
                        transferId = transferId,
                        chunkIndex = index,
                        totalChunks = totalChunks,
                        chunkData = chunk,
                    ),
                )
            }
        val canAttemptNow = canAttemptImmediateSend(roomId)
        val pendingCountBefore = room(roomId)?.pendingOutgoingCount ?: 0

        attachmentFileStore.putEncryptedCiphertext(
            roomId = roomId,
            storageKey = attachment.storageKey ?: attachmentStorageKey(roomId, transferId),
            ciphertext = encryptedAttachment.ciphertext,
        )

        updateSnapshot { snapshot ->
            snapshot.copy(
                rooms = snapshot.rooms.updateRoom(roomId) { room ->
                    room.copy(
                        pendingOutgoingCount = room.pendingOutgoingCount + 1,
                        lastActivityEpochMillis = createdAt,
                    )
                },
                messages = snapshot.messages + (
                    roomId to (snapshot.messages[roomId].orEmpty() + RoomMessage(
                        id = messageId,
                        roomId = roomId,
                        senderName = localDisplayName,
                        body = "",
                        sentAt = formatTime(createdAt),
                        isLocalUser = true,
                        deliveryState = if (canAttemptNow) {
                            MessageDeliveryState.Sending
                        } else {
                            MessageDeliveryState.Queued
                        },
                        attachment = attachment,
                    ))
                ),
                logs = appendLog(
                    snapshot.logs,
                    roomId,
                    if (canAttemptNow) {
                        "TX >> encrypted attachment prepared for ${attachment.fileName}"
                    } else {
                        "TX >> encrypted attachment queued locally until the room reconnects and a verified peer is available"
                    },
                ),
            )
        }

        scope.launch {
            enqueuePendingOutbound(
                PendingOutboundMessage(
                    roomId = roomId,
                    messageId = UUID.randomUUID().toString(),
                    logicalMessageId = messageId,
                    body = manifestBody,
                    createdAtEpochMillis = createdAt,
                ),
            )
            chunkBodies.forEach { chunkBody ->
                enqueuePendingOutbound(
                    PendingOutboundMessage(
                        roomId = roomId,
                        messageId = UUID.randomUUID().toString(),
                        logicalMessageId = messageId,
                        body = chunkBody,
                        createdAtEpochMillis = createdAt,
                    ),
                )
            }

            val pendingCount = pendingCount(roomId)
            if (pendingCount != pendingCountBefore + 1) {
                updateSnapshot { snapshot ->
                    snapshot.copy(
                        rooms = snapshot.rooms.updateRoom(roomId) { room ->
                            room.copy(pendingOutgoingCount = pendingCount)
                        },
                    )
                }
            }

            if (!canAttemptNow) return@launch

            flushPendingOutbound(roomId, "attachment send")
        }
    }

    suspend fun leaveRoom(roomId: String) {
        engine.leaveRoom(roomId)
        removeRoomFromLocalState(roomId)
    }

    fun removeParticipant(
        roomId: String,
        fingerprint: String,
    ) {
        scope.launch {
            val room = room(roomId) ?: return@launch
            require(room.isOwner) { "Only the room owner can remove participants" }
            val localFingerprint = roomSecurity.roomFingerprint(roomId)
            require(fingerprint != localFingerprint) { "Use Leave Room to remove your local device" }
            val participant = participants(roomId).firstOrNull { it.fingerprint == fingerprint } ?: return@launch
            if (participant.isRemoved) return@launch

            val remainingPeerFingerprints = pairwiseProtocol.verifiedPeers(roomId)
                .map { it.fingerprint }
                .filterNot { it == fingerprint }
                .filterNot { isRemovedFingerprint(roomId, it) }
                .distinct()
            val rekey = roomSecurity.rotateRoomSecret(roomId)
            pairwiseProtocol.removePeer(roomId, fingerprint)

            updateSnapshot { snapshot ->
                val updatedParticipants = snapshot.participants[roomId]
                    .orEmpty()
                    .map { current ->
                        if (current.fingerprint == fingerprint) {
                            current.copy(
                                isOnline = false,
                                isVerified = false,
                                relayEligible = false,
                                isRemoved = true,
                            )
                        } else {
                            current
                        }
                    }
                snapshot.copy(
                    rooms = snapshot.rooms.updateRoom(roomId) { current ->
                        current.copy(
                            participantCount = activeParticipantCount(updatedParticipants),
                            isEncryptedSessionEstablished = isEncryptedReady(roomId),
                            invitePackage = rekey.invitePackage ?: current.invitePackage,
                            securityProfile = roomSecurity.roomProfile(roomId),
                            lastActivityEpochMillis = System.currentTimeMillis(),
                        )
                    },
                    participants = snapshot.participants + (roomId to updatedParticipants),
                    messages = appendSystemMessages(
                        snapshot.messages,
                        roomId,
                        listOf(
                            "${participant.displayName} was removed from the room.",
                            "Room keys rotated to epoch ${rekey.epoch}. Future messages use the new room secret.",
                        ),
                    ),
                    logs = appendLog(
                        snapshot.logs,
                        roomId,
                        "SECURITY >> removed peer ${fingerprint.take(12)} and rotated room to epoch ${rekey.epoch}",
                    ),
                )
            }

            if (remainingPeerFingerprints.isNotEmpty()) {
                val controlBody = encodeRoomRekeyControl(
                    roomId = roomId,
                    roomPassword = rekey.roomPassword,
                    epoch = rekey.epoch,
                    removedFingerprint = fingerprint,
                )
                enqueuePendingOutbound(
                    PendingOutboundMessage(
                        roomId = roomId,
                        messageId = UUID.randomUUID().toString(),
                        body = controlBody,
                        createdAtEpochMillis = System.currentTimeMillis(),
                        awaitingAckFrom = remainingPeerFingerprints,
                    ),
                )
            }

            refreshRoomTransport(room(roomId) ?: return@launch)
            flushPendingOutbound(roomId, "room rekey")
        }
    }

    suspend fun suspendNetworking() {
        engine.suspendNetworking()
    }

    suspend fun resumeNetworking() {
        purgeExpiredRoomsNow("app resume")
        cleanupStaleAttachmentTransfers("app resume")
        engine.resumeNetworking()
    }

    fun room(roomId: String): RoomSummary? = _snapshot.value.rooms.firstOrNull { it.id == roomId }

    fun participants(roomId: String): List<Participant> = _snapshot.value.participants[roomId].orEmpty()

    fun messages(roomId: String): List<RoomMessage> = _snapshot.value.messages[roomId].orEmpty()

    fun logs(roomId: String): List<String> = _snapshot.value.logs[GLOBAL_LOG_ROOM_ID].orEmpty() +
        _snapshot.value.logs[roomId].orEmpty()

    fun localDisplayName(): String = localDisplayName

    fun localFingerprint(): String = roomSecurity.localFingerprint()

    fun roomFingerprint(roomId: String): String? = room(roomId)?.let { roomSecurity.roomFingerprint(roomId) }

    fun resolveAttachment(
        roomId: String,
        messageId: String,
    ): ResolvedAttachment? = runCatching {
        val attachment = messages(roomId)
            .firstOrNull { it.id == messageId }
            ?.attachment
            ?: return@runCatching null
        if (attachment.byteSize !in 1L..MAX_ATTACHMENT_BYTES.toLong()) return@runCatching null
        if (!DIGEST_HEX_PATTERN.matches(attachment.digestHex)) return@runCatching null
        if (!isValidAttachmentNonce(attachment.encryptedNonce)) return@runCatching null
        val ciphertext = attachment.storageKey
            ?.let { attachmentFileStore.getEncryptedCiphertext(roomId, it) }
            ?: attachment.encryptedCiphertext.takeIf { it.isNotBlank() }?.let(::base64UrlDecode)
            ?: return@runCatching null
        if (ciphertext.size > MAX_ATTACHMENT_CIPHERTEXT_BYTES) return@runCatching null
        val bytes = roomSecurity.decryptAttachment(
            roomId = roomId,
            attachment = com.epher.app.security.EncryptedAttachment(
                nonce = base64UrlDecode(attachment.encryptedNonce),
                ciphertext = ciphertext,
                digestHex = attachment.digestHex,
            ),
        )
        if (bytes.size.toLong() != attachment.byteSize) return@runCatching null
        ResolvedAttachment(
            fileName = normalizeAttachmentFileName(attachment.fileName),
            mimeType = normalizeAttachmentMimeType(attachment.mimeType),
            bytes = bytes,
        )
    }.getOrNull()

    private fun bootstrapSnapshot(snapshot: RoomsSnapshot): RoomsSnapshot {
        val expiredRoomIds = expiredRoomIds(snapshot)
        if (expiredRoomIds.isNotEmpty()) {
            expiredRoomIds.forEach { roomId ->
                pendingOutboundByRoom.remove(roomId)
                attachmentFileStore.removeRoom(roomId)
                pairwiseProtocol.removeRoomState(roomId)
                roomSecurity.removeRoom(roomId)
            }
        }
        val retainedSnapshot = snapshot.removeRooms(expiredRoomIds)
        val pendingByRoom = pendingOutboundByRoom.mapValues { (_, messages) ->
            messages.associateBy { it.messageId }
        }
        return normalizeSnapshot(
            retainedSnapshot.copy(
                rooms = retainedSnapshot.rooms.map { room ->
                    room.copy(
                        connectionState = if (room.connectionState == ConnectionState.Expired) {
                            ConnectionState.Expired
                        } else {
                            ConnectionState.Reconnecting
                        },
                        isEncryptedSessionEstablished = isEncryptedReady(room.id),
                        pendingOutgoingCount = pendingByRoom[room.id]
                            ?.values
                            ?.map { it.logicalMessageId }
                            ?.distinct()
                            ?.size
                            ?: 0,
                    )
                },
                participants = retainedSnapshot.participants.mapValues { (_, participants) ->
                    participants.map { participant ->
                        if (participant.transportPublicKeyHex == null) {
                            participant.copy(
                                displayName = localDisplayName,
                                isOnline = true,
                                isVerified = true,
                            )
                        } else {
                            participant
                        }
                    }
                },
                messages = retainedSnapshot.messages.mapValues { (roomId, roomMessages) ->
                    val pendingMessages = pendingByRoom[roomId].orEmpty().values.toList()
                    roomMessages.map { message ->
                        val pending = pendingMessages.filter { it.logicalMessageId == message.id }
                        if (pending.isEmpty()) {
                            message
                        } else {
                            message.copy(deliveryState = pending.deliveryState())
                        }
                    }
                },
            ),
        )
    }

    private fun emptySnapshot(): RoomsSnapshot = RoomsSnapshot(
        rooms = emptyList(),
        participants = emptyMap(),
        messages = emptyMap(),
        logs = emptyMap(),
    )

    private suspend fun syncRoomsToEngine(rooms: List<RoomSummary>) {
        rooms.forEach { room ->
            refreshRoomTransport(room)
        }
    }

    private suspend fun refreshRoomTransport(room: RoomSummary) {
        val localPeerCard = pairwiseProtocol.createLocalPeerCard(room.id, localDisplayName)
        val topicHex = roomSecurity.transportTopicHex(room.id)
        val transportSeedHex = roomSecurity.deviceTransportSeedHex()
        if (room.isOwner) {
            engine.createRoom(
                room.id,
                room.localLabel,
                topicHex,
                transportSeedHex,
                localPeerCard,
                roomSecurity.mixnetRouteHintsJson(room.id),
            )
        } else {
            engine.joinRoom(
                room.id,
                room.localLabel,
                topicHex,
                transportSeedHex,
                localPeerCard,
                room.invitePackage.inviteToken,
                roomSecurity.mixnetRouteHintsJson(room.id),
            )
        }
        engine.updateKnownPeerTransportKeys(room.id, pairwiseProtocol.knownPeerTransportKeys(room.id))
        room.invitePackage.ownerTransportPublicKeyHex?.takeUnless { room.isOwner }?.let { ownerKey ->
            engine.updateKnownPeerTransportKeys(room.id, listOf(ownerKey))
        }
    }

    suspend fun purgeExpiredRoomsNow(reason: String) {
        val expiredRoomIds = expiredRoomIds(_snapshot.value)
        if (expiredRoomIds.isEmpty()) return

        expiredRoomIds.forEach { roomId ->
            engine.leaveRoom(roomId)
            removeRoomFromLocalState(roomId)
        }

        updateSnapshot { snapshot ->
            snapshot.copy(
                logs = appendLog(
                    snapshot.logs,
                    GLOBAL_LOG_ROOM_ID,
                    "RETENTION >> purged ${expiredRoomIds.size} expired room(s) after $reason",
                ),
            )
        }
    }

    private fun cleanupStaleAttachmentTransfers(reason: String) {
        val removed = attachmentFileStore.cleanupStalePendingTransfers(STALE_ATTACHMENT_TRANSFER_MILLIS)
        if (removed <= 0) return
        updateSnapshot { snapshot ->
            snapshot.copy(
                logs = appendLog(
                    snapshot.logs,
                    GLOBAL_LOG_ROOM_ID,
                    "ATTACHMENTS >> removed $removed stale pending transfer(s) after $reason",
                ),
            )
        }
    }

    private suspend fun handleEngineEvent(event: P2PEngineEvent) {
        when (event) {
            is P2PEngineEvent.EngineReady -> {
                updateSnapshot { snapshot ->
                    snapshot.copy(
                        logs = appendLog(
                            snapshot.logs,
                            GLOBAL_LOG_ROOM_ID,
                            "ENGINE >> ${event.runtime} ready (${event.detail.ifBlank { "no detail" }})",
                        ),
                    )
                }
            }

            is P2PEngineEvent.LogLine -> {
                updateSnapshot { snapshot ->
                    snapshot.copy(logs = appendLog(snapshot.logs, event.roomId ?: GLOBAL_LOG_ROOM_ID, event.line))
                }
            }

            is P2PEngineEvent.RoomStateChanged -> {
                val queuedCount = pendingCount(event.roomId)
                updateSnapshot { snapshot ->
                    val updatedParticipants = when (event.connectionState) {
                        ConnectionState.Connected -> snapshot.participants[event.roomId].orEmpty()
                        else -> snapshot.participants[event.roomId]
                            .orEmpty()
                            .map { participant ->
                                if (participant.transportPublicKeyHex != null) {
                                    participant.copy(isOnline = false)
                                } else {
                                    participant
                                }
                            }
                    }
                    val rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                        room.copy(
                            connectionState = event.connectionState,
                            participantCount = activeParticipantCount(updatedParticipants),
                            pendingOutgoingCount = queuedCount,
                        )
                    }
                    val logs = event.detail
                        ?.let { appendLog(snapshot.logs, event.roomId, "ENGINE >> $it") }
                        ?: snapshot.logs
                    snapshot.copy(
                        rooms = rooms,
                        participants = snapshot.participants + (event.roomId to updatedParticipants),
                        logs = logs,
                    )
                }
                if (event.connectionState == ConnectionState.Connected) {
                    flushPendingOutbound(event.roomId, "room connected")
                }
            }

            is P2PEngineEvent.PeerConnectionChanged -> {
                updateSnapshot { snapshot ->
                    val currentParticipants = snapshot.participants[event.roomId].orEmpty()
                    val existing = currentParticipants.firstOrNull { participant ->
                        participant.transportPublicKeyHex.equals(event.transportPublicKeyHex, ignoreCase = true)
                    } ?: return@updateSnapshot snapshot
                    if (existing.isRemoved) return@updateSnapshot snapshot
                    val updatedParticipants = currentParticipants.map { participant ->
                        if (participant.fingerprint == existing.fingerprint) {
                            participant.copy(isOnline = event.isConnected)
                        } else {
                            participant
                        }
                    }
                    val messages = when {
                        event.isConnected && !existing.isOnline -> appendSystemMessage(
                            snapshot.messages,
                            event.roomId,
                            "${existing.displayName} rejoined the room.",
                        )

                        !event.isConnected && existing.isOnline -> appendSystemMessage(
                            snapshot.messages,
                            event.roomId,
                            "${existing.displayName} disconnected from the room.",
                        )

                        else -> snapshot.messages
                    }
                    snapshot.copy(
                        participants = snapshot.participants + (event.roomId to updatedParticipants),
                        messages = messages,
                    )
                }
                if (event.isConnected) {
                    flushPendingOutbound(event.roomId, "peer connected")
                }
            }

            is P2PEngineEvent.PeerCardReceived -> {
                val peerResult = runCatching {
                    pairwiseProtocol.ingestPeerCard(
                        roomId = event.roomId,
                        encodedPeerCard = event.encodedPeerCard,
                        transportPublicKeyHex = event.transportPublicKeyHex,
                    )
                }
                val peer = peerResult.getOrElse { throwable ->
                    Log.e(TAG, "Peer card verification failed for ${event.roomId}", throwable)
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> peer card rejected: ${throwable.message ?: "unknown error"}",
                            ),
                        )
                    }
                    return
                } ?: return
                if (isRemovedFingerprint(event.roomId, peer.fingerprint)) {
                    pairwiseProtocol.removePeer(event.roomId, peer.fingerprint)
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> removed peer card rejected for ${peer.fingerprint.take(12)}",
                            ),
                        )
                    }
                    return
                }

                val hadSession = pairwiseProtocol.hasSessionWithPeer(event.roomId, peer.fingerprint)
                pairwiseProtocol.establishSession(event.roomId, peer.fingerprint)
                val hasSessionNow = pairwiseProtocol.hasSessionWithPeer(event.roomId, peer.fingerprint)
                engine.updateKnownPeerTransportKeys(event.roomId, pairwiseProtocol.knownPeerTransportKeys(event.roomId))
                Log.d(TAG, "Verified peer ${peer.displayName} for room ${event.roomId}")
                updateSnapshot { snapshot ->
                    val previousPeer = snapshot.participants[event.roomId]
                        .orEmpty()
                        .firstOrNull { participant -> participant.fingerprint == peer.fingerprint }
                    val updatedParticipants = upsertParticipant(
                        current = snapshot.participants[event.roomId].orEmpty(),
                        participant = Participant(
                            id = peer.fingerprint,
                            displayName = peer.displayName,
                            isOnline = true,
                            isVerified = true,
                            role = RoomRole.Member,
                            fingerprint = peer.fingerprint,
                            transportPublicKeyHex = peer.transportPublicKeyHex,
                            relayEligible = peer.relayCapable,
                        ),
                    )
                    val timelineEvents = buildList {
                        if (previousPeer == null) {
                            add("${peer.displayName} joined the room.")
                        }
                        if (previousPeer?.isVerified != true) {
                            add("${peer.displayName} passed verification.")
                        }
                        if (!hadSession && hasSessionNow) {
                            add("Encrypted session established with ${peer.displayName}.")
                        }
                    }
                    snapshot.copy(
                        participants = snapshot.participants + (event.roomId to updatedParticipants),
                        rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                            room.copy(
                                participantCount = activeParticipantCount(updatedParticipants),
                                isEncryptedSessionEstablished = isEncryptedReady(event.roomId),
                            )
                        },
                        messages = appendSystemMessages(
                            snapshot.messages,
                            event.roomId,
                            timelineEvents,
                        ),
                        logs = appendLog(
                            snapshot.logs,
                            event.roomId,
                            "SECURITY >> verified peer card for ${peer.displayName} and refreshed known peer transport keys",
                        ),
                    )
                }
                flushPendingOutbound(event.roomId, "peer verified")
            }

            is P2PEngineEvent.IncomingEnvelope -> {
                val preview = runCatching {
                    pairwiseProtocol.previewEnvelope(event.encodedEnvelope)
                }.getOrElse { throwable ->
                    Log.e(TAG, "Envelope preview failed for ${event.roomId}", throwable)
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> envelope preview rejected: ${throwable.message ?: "unknown error"}",
                            ),
                        )
                    }
                    return
                }

                val duplicateAlreadyStored = messages(event.roomId).any { it.id == preview.messageId }
                if (isRemovedFingerprint(event.roomId, preview.senderFingerprint)) {
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> envelope from removed peer rejected: ${preview.senderFingerprint.take(12)}",
                            ),
                        )
                    }
                    return
                }
                if (duplicateAlreadyStored) {
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "RX >> duplicate envelope ignored after receipt",
                            ),
                        )
                    }
                    runCatching {
                        engine.sendDeliveryAck(
                            event.roomId,
                            pairwiseProtocol.createDeliveryAck(
                                roomId = event.roomId,
                                recipientFingerprint = preview.senderFingerprint,
                                messageId = preview.messageId,
                            ),
                        )
                    }.onFailure { throwable ->
                        updateSnapshot { snapshot ->
                            snapshot.copy(
                                logs = appendLog(
                                    snapshot.logs,
                                    event.roomId,
                                    "TX >> duplicate envelope ack failed: ${throwable.message ?: "unknown error"}",
                                ),
                            )
                        }
                    }
                    return
                }

                val decryptedResult = runCatching {
                    pairwiseProtocol.decryptEnvelope(event.roomId, event.encodedEnvelope)
                }
                val decrypted = decryptedResult.getOrElse { throwable ->
                    val senderFingerprint = preview.senderFingerprint
                    trackDecryptionFailure(event.roomId, senderFingerprint)
                    
                    Log.e(
                        TAG,
                        "Pairwise decrypt failed for ${event.roomId} message=${preview.messageId.take(8)} number=${preview.messageNumber} ratchet=${preview.ratchetPublicKeyPrefix} size=${event.encodedEnvelope.length}",
                        throwable,
                    )
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> envelope rejected: ${throwable.message ?: "unknown error"} [message=${preview.messageId.take(8)} #${preview.messageNumber} ratchet=${preview.ratchetPublicKeyPrefix} size=${event.encodedEnvelope.length}]",
                            ),
                        )
                    }
                    return
                } ?: return

                val controlEffect = handleIncomingControlPayload(event.roomId, decrypted)
                if (controlEffect != null) {
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                                room.copy(
                                    securityProfile = roomSecurity.roomProfile(event.roomId),
                                    lastActivityEpochMillis = controlEffect.lastActivityEpochMillis,
                                    isEncryptedSessionEstablished = isEncryptedReady(event.roomId),
                                )
                            },
                            participants = snapshot.participants + (
                                event.roomId to snapshot.participants[event.roomId]
                                    .orEmpty()
                                    .map { participant ->
                                        if (participant.fingerprint == controlEffect.removedFingerprint) {
                                            participant.copy(
                                                isOnline = false,
                                                isVerified = false,
                                                relayEligible = false,
                                                isRemoved = true,
                                            )
                                        } else {
                                            participant
                                        }
                                    }
                            ),
                            messages = appendSystemMessage(
                                snapshot.messages,
                                event.roomId,
                                controlEffect.systemMessage,
                                controlEffect.lastActivityEpochMillis,
                            ),
                            logs = appendLog(snapshot.logs, event.roomId, controlEffect.logLine),
                        )
                    }
                    refreshRoomTransport(room(event.roomId) ?: return)
                    flushPendingOutbound(event.roomId, "room rekey applied")
                    runCatching {
                        engine.sendDeliveryAck(
                            event.roomId,
                            pairwiseProtocol.createDeliveryAck(
                                roomId = event.roomId,
                                recipientFingerprint = decrypted.senderFingerprint,
                                messageId = decrypted.messageId,
                            ),
                        )
                    }.onFailure { throwable ->
                        updateSnapshot { snapshot ->
                            snapshot.copy(
                                logs = appendLog(
                                    snapshot.logs,
                                    event.roomId,
                                    "TX >> rekey delivery ack deferred: ${throwable.message ?: "unknown error"}",
                                ),
                            )
                        }
                    }
                    return
                }

                val attachmentEffect = handleIncomingAttachmentPayload(event.roomId, decrypted)
                updateSnapshot { snapshot ->
                    val existingMessages = snapshot.messages[event.roomId].orEmpty()
                    when {
                        existingMessages.any { it.id == decrypted.messageId } -> {
                            snapshot.copy(
                                logs = appendLog(
                                    snapshot.logs,
                                    event.roomId,
                                    "RX >> duplicate envelope ignored after reconnect",
                                ),
                            )
                        }

                        attachmentEffect != null -> {
                            snapshot.copy(
                                rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                                    room.copy(
                                        lastActivityEpochMillis = attachmentEffect.lastActivityEpochMillis,
                                        isEncryptedSessionEstablished = true,
                                    )
                                },
                                messages = attachmentEffect.message?.let { completedMessage ->
                                    snapshot.messages + (
                                        event.roomId to upsertRoomMessage(existingMessages, completedMessage)
                                    )
                                } ?: snapshot.messages,
                                logs = appendLog(
                                    snapshot.logs,
                                    event.roomId,
                                    attachmentEffect.logLine,
                                ),
                            )
                        }

                        else -> {
                            snapshot.copy(
                                rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                                    room.copy(
                                        lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                                        isEncryptedSessionEstablished = true,
                                    )
                                },
                                messages = snapshot.messages + (
                                    event.roomId to (existingMessages + RoomMessage(
                                        id = decrypted.messageId,
                                        roomId = event.roomId,
                                        senderName = decrypted.senderName,
                                        body = decrypted.body,
                                        sentAt = formatTime(decrypted.sentAtEpochMillis),
                                        isLocalUser = false,
                                    ))
                                ),
                                logs = appendLog(
                                    snapshot.logs,
                                    event.roomId,
                                    "RX >> verified pairwise envelope accepted",
                                ),
                            )
                        }
                    }
                }
                runCatching {
                    engine.sendDeliveryAck(
                        event.roomId,
                        pairwiseProtocol.createDeliveryAck(
                            roomId = event.roomId,
                            recipientFingerprint = decrypted.senderFingerprint,
                            messageId = decrypted.messageId,
                        ),
                    )
                }.onFailure { throwable ->
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "TX >> delivery ack deferred: ${throwable.message ?: "unknown error"}",
                            ),
                        )
                    }
                }
                Log.d(
                    TAG,
                    "Accepted encrypted envelope for room ${event.roomId} from ${decrypted.senderName} message=${preview.messageId.take(8)} number=${preview.messageNumber} ratchet=${preview.ratchetPublicKeyPrefix}",
                )
            }

            is P2PEngineEvent.DeliveryAckReceived -> {
                val ackResult = runCatching {
                    pairwiseProtocol.verifyDeliveryAck(event.roomId, event.encodedAck)
                }
                val ack = ackResult.getOrElse { throwable ->
                    Log.e(TAG, "Delivery ack verification failed for ${event.roomId}", throwable)
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> delivery ack rejected: ${throwable.message ?: "unknown error"}",
                            ),
                        )
                    }
                    return
                } ?: return
                if (isRemovedFingerprint(event.roomId, ack.senderFingerprint)) {
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "SECURITY >> delivery ack from removed peer ignored",
                            ),
                        )
                    }
                    return
                }

                val ackUpdate = applyDeliveryAck(
                    roomId = event.roomId,
                    messageId = ack.messageId,
                    senderFingerprint = ack.senderFingerprint,
                )
                if (!ackUpdate.matched) {
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                event.roomId,
                                "TX >> duplicate or late delivery ack ignored",
                            ),
                        )
                    }
                    return
                }

                updateSnapshot { snapshot ->
                    val senderName = snapshot.participants[event.roomId]
                        .orEmpty()
                        .firstOrNull { participant -> participant.fingerprint == ack.senderFingerprint }
                        ?.displayName
                        ?: "peer"
                    val updatedMessages = snapshot.messages[event.roomId].orEmpty().map { message ->
                        if (message.id == ackUpdate.logicalMessageId) {
                            message.copy(
                                deliveryState = if (ackUpdate.fullyDelivered) {
                                    MessageDeliveryState.Sent
                                } else {
                                    MessageDeliveryState.Sending
                                },
                            )
                        } else {
                            message
                        }
                    }
                    val logLine = if (ackUpdate.fullyDelivered) {
                        "TX >> delivery confirmed by $senderName"
                    } else {
                        "TX >> delivery ack from $senderName, waiting on ${ackUpdate.remainingRecipients} packet(s)"
                    }
                    snapshot.copy(
                        rooms = snapshot.rooms.updateRoom(event.roomId) { room ->
                            room.copy(pendingOutgoingCount = ackUpdate.pendingCount)
                        },
                        messages = snapshot.messages + (event.roomId to updatedMessages),
                        logs = appendLog(snapshot.logs, event.roomId, logLine),
                    )
                }
            }
        }
    }

    private suspend fun flushAllPendingOutbound(reason: String) {
        val roomIds = pendingMutex.withLock { pendingOutboundByRoom.keys.toList() }
        roomIds.forEach { roomId ->
            flushPendingOutbound(roomId, reason)
        }
    }

    private suspend fun flushPendingOutbound(roomId: String, reason: String) {
        flushMutex(roomId).withLock {
            val room = room(roomId) ?: return@withLock
            val verifiedPeers = pairwiseProtocol.verifiedPeers(roomId)
                .filterNot { isRemovedFingerprint(roomId, it.fingerprint) }
                .associateBy { it.fingerprint }
            if (room.connectionState != ConnectionState.Connected || verifiedPeers.isEmpty()) return@withLock

            val now = System.currentTimeMillis()
            
            // First, remove expired messages (TTL enforcement)
            cleanupExpiredPendingMessages(roomId, now)
            
            val queuedMessages = pendingMessages(roomId)
                .filter { pending ->
                    // Skip if message is too old (TTL exceeded, even if acks still pending)
                    if (now - pending.createdAtEpochMillis >= PENDING_MESSAGE_MAX_AGE_MILLIS) {
                        return@filter false
                    }
                    
                    // Check if it's ready for retry using exponential backoff
                    if (pending.awaitingAckFrom.isNotEmpty() && pending.lastDispatchAtEpochMillis != null) {
                        // Exponential backoff: 1s, 2s, 4s, 8s, ... capped at 5 minutes
                        val backoffDelayMillis = calculateExponentialBackoff(pending.retryCount)
                        now - pending.lastDispatchAtEpochMillis >= backoffDelayMillis
                    } else {
                        // First dispatch attempt or no acks needed
                        true
                    }
                }
            if (queuedMessages.isEmpty()) return@withLock

            updateSnapshot { snapshot ->
                snapshot.copy(
                    messages = snapshot.messages + (
                        roomId to snapshot.messages[roomId].orEmpty().map { message ->
                            if (message.isLocalUser && queuedMessages.any { it.logicalMessageId == message.id }) {
                                message.copy(deliveryState = MessageDeliveryState.Sending)
                            } else {
                                message
                            }
                        }
                    ),
                    logs = appendLog(
                        snapshot.logs,
                        roomId,
                        "TX >> retrying ${queuedMessages.size} queued packet(s) after $reason",
                    ),
                )
            }

            queuedMessages.forEach { pending ->
                val targetFingerprints = if (pending.awaitingAckFrom.isNotEmpty()) {
                    pending.awaitingAckFrom
                } else {
                    verifiedPeers.keys.toList()
                }
                val targetPeers = targetFingerprints.mapNotNull { verifiedPeers[it] }.distinctBy { it.fingerprint }
                if (targetPeers.isEmpty()) return@forEach

                val sendResult = runCatching {
                    targetPeers.forEach { peer ->
                        val envelope = pairwiseProtocol.encryptForPeer(
                            roomId = roomId,
                            recipientFingerprint = peer.fingerprint,
                            senderName = localDisplayName,
                            plaintext = pending.body,
                            messageId = pending.messageId,
                        )
                        val encodedEnvelope = envelope.encode()
                        if (isAttachmentPayload(pending.body)) {
                            Log.d(
                                TAG,
                                "Attachment packet send room=$roomId label=${outboundPayloadLabel(pending.body)} msg=${pending.messageId.take(8)} number=${envelope.messageNumber} ratchet=${base64UrlEncode(envelope.ratchetPublicKey).take(12)} size=${encodedEnvelope.length}",
                            )
                            updateSnapshot { snapshot ->
                                snapshot.copy(
                                    logs = appendLog(
                                        snapshot.logs,
                                        roomId,
                                        "TX >> ${outboundPayloadLabel(pending.body)} packet #${envelope.messageNumber} size=${encodedEnvelope.length}",
                                    ),
                                )
                            }
                        }
                        engine.sendRoomMessage(roomId, encodedEnvelope)
                    }
                }

                if (sendResult.isSuccess) {
                    updatePendingDispatch(
                        roomId = roomId,
                        messageId = pending.messageId,
                        awaitingAckFrom = targetPeers.map { it.fingerprint },
                        dispatchedAtEpochMillis = System.currentTimeMillis(),
                        incrementRetry = pending.awaitingAckFrom.isNotEmpty(),
                    )
                    val remaining = pendingCount(roomId)
                    scheduleAckRetry(roomId)
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            rooms = snapshot.rooms.updateRoom(roomId) { current ->
                                current.copy(
                                    pendingOutgoingCount = remaining,
                                    lastActivityEpochMillis = maxOf(current.lastActivityEpochMillis, pending.createdAtEpochMillis),
                                    isEncryptedSessionEstablished = true,
                                )
                            },
                            messages = snapshot.messages + (
                                roomId to snapshot.messages[roomId].orEmpty().map { message ->
                                    if (message.id == pending.logicalMessageId) {
                                        message.copy(deliveryState = MessageDeliveryState.Sending)
                                    } else {
                                        message
                                    }
                                }
                            ),
                            logs = appendLog(
                                snapshot.logs,
                                roomId,
                                "TX >> pairwise fan-out handed to transport for ${targetPeers.size} verified peer(s); awaiting delivery ack",
                            ),
                        )
                    }
                } else {
                    incrementPendingRetry(roomId, pending.messageId)
                    val remaining = pendingCount(roomId)
                    val detail = sendResult.exceptionOrNull()?.message ?: "unknown send failure"
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            rooms = snapshot.rooms.updateRoom(roomId) { current ->
                                current.copy(pendingOutgoingCount = remaining)
                            },
                            messages = snapshot.messages + (
                                roomId to snapshot.messages[roomId].orEmpty().map { message ->
                                    if (message.id == pending.logicalMessageId) {
                                        message.copy(deliveryState = MessageDeliveryState.Retry)
                                    } else {
                                        message
                                    }
                                }
                            ),
                            logs = appendLog(snapshot.logs, roomId, "TX >> retry deferred: $detail"),
                        )
                    }
                }
            }
        }
    }

    private suspend fun flushMutex(roomId: String): Mutex = pendingMutex.withLock {
        flushMutexes.getOrPut(roomId) { Mutex() }
    }

    private fun updateSnapshot(transform: (RoomsSnapshot) -> RoomsSnapshot) {
        _snapshot.update { current ->
            normalizeSnapshot(transform(current))
        }
    }

    private fun normalizeSnapshot(snapshot: RoomsSnapshot): RoomsSnapshot = snapshot.copy(
        rooms = snapshot.rooms.sortedByDescending { it.lastActivityEpochMillis },
    )

    private fun scheduleConnectionReadyFallback(roomId: String, detail: String) {
        if (!allowOptimisticConnectedState) return
        scope.launch {
            delay(CONNECTION_READY_FALLBACK_MILLIS)
            updateSnapshot { snapshot ->
                val current = snapshot.rooms.firstOrNull { it.id == roomId } ?: return@updateSnapshot snapshot
                if (current.connectionState != ConnectionState.Connecting &&
                    current.connectionState != ConnectionState.Reconnecting
                ) {
                    return@updateSnapshot snapshot
                }

                snapshot.copy(
                    rooms = snapshot.rooms.updateRoom(roomId) { room ->
                        room.copy(connectionState = ConnectionState.Connected)
                    },
                    logs = appendLog(
                        snapshot.logs,
                        roomId,
                        "ENGINE >> $detail",
                    ),
                )
            }
        }
    }

    private suspend fun removeRoomFromLocalState(roomId: String) {
        clearPendingOutbound(roomId)
        attachmentFileStore.removeRoom(roomId)
        pairwiseProtocol.removeRoomState(roomId)
        roomSecurity.removeRoom(roomId)
        updateSnapshot { snapshot ->
            snapshot.removeRooms(setOf(roomId))
        }
    }

    private fun expiredRoomIds(snapshot: RoomsSnapshot, now: Long = System.currentTimeMillis()): Set<String> = snapshot.rooms
        .filter { room ->
            room.retentionPreset.maxAgeMillis?.let { maxAge ->
                now - room.lastActivityEpochMillis >= maxAge
            } ?: false
        }
        .mapTo(linkedSetOf()) { it.id }

    private suspend fun snapshotPendingOutbound(): Map<String, List<PendingOutboundMessage>> = pendingMutex.withLock {
        pendingOutboundByRoom.mapValues { (_, messages) -> messages.toList() }
    }

    private suspend fun enqueuePendingOutbound(message: PendingOutboundMessage) {
        pendingMutex.withLock {
            val queue = pendingOutboundByRoom.getOrPut(message.roomId) { mutableListOf() }
            queue.removeAll { it.messageId == message.messageId }
            
            // Enforce max pending messages per room
            if (queue.size >= MAX_PENDING_MESSAGES_PER_ROOM) {
                Log.w(TAG, "Pending queue for room ${message.roomId} at capacity (${queue.size}); dropping oldest message")
                queue.removeAt(0)  // Remove oldest message (FIFO)
            }
            
            queue.add(message)
        }
    }

    private suspend fun updatePendingDispatch(
        roomId: String,
        messageId: String,
        awaitingAckFrom: List<String>,
        dispatchedAtEpochMillis: Long,
        incrementRetry: Boolean,
    ) {
        pendingMutex.withLock {
            val queue = pendingOutboundByRoom[roomId] ?: return@withLock
            val index = queue.indexOfFirst { it.messageId == messageId }
            if (index < 0) return@withLock
            val current = queue[index]
            queue[index] = current.copy(
                retryCount = if (incrementRetry) current.retryCount + 1 else current.retryCount,
                lastDispatchAtEpochMillis = dispatchedAtEpochMillis,
                awaitingAckFrom = awaitingAckFrom.distinct(),
            )
        }
    }

    private suspend fun removePendingOutbound(roomId: String, messageId: String) {
        pendingMutex.withLock {
            val queue = pendingOutboundByRoom[roomId] ?: return@withLock
            queue.removeAll { it.messageId == messageId }
            if (queue.isEmpty()) {
                pendingOutboundByRoom.remove(roomId)
            }
        }
    }

    /**
     * Detect rooms stuck in "Reconnecting" state for too long.
     * If a room stays in Reconnecting > 30 seconds and has verified peers,
     * it likely needs a fresh connection attempt.
     */
    private suspend fun detectStuckConnections() {
        val now = System.currentTimeMillis()
        val currentRooms = snapshot.value.rooms
        
        for (room in currentRooms) {
            if (room.connectionState != ConnectionState.Reconnecting) {
                // Update tracking
                val current = roomConnectionTracking[room.id]
                if (current?.connectionState != ConnectionState.Reconnecting) {
                    roomConnectionTracking[room.id] = RoomConnectionTracking(room.id, room.connectionState, now)
                }
                continue
            }
            
            val tracking = roomConnectionTracking[room.id]
            if (tracking == null || tracking.connectionState != ConnectionState.Reconnecting) {
                // Just entered Reconnecting state
                roomConnectionTracking[room.id] = RoomConnectionTracking(room.id, room.connectionState, now)
                continue
            }
            
            // Check if stuck for too long
            if (now - tracking.enteredStateAtEpochMillis > CONNECTION_STUCK_TIMEOUT_MILLIS) {
                val verifiedPeers = pairwiseProtocol.verifiedPeers(room.id)
                if (verifiedPeers.isNotEmpty()) {
                    Log.w(TAG, "Room ${room.id} stuck in Reconnecting for >30s with ${verifiedPeers.size} peers; refreshing transport")
                    roomConnectionTracking[room.id] = RoomConnectionTracking(room.id, room.connectionState, now)
                    runCatching { refreshRoomTransport(room) }
                        .onFailure { throwable ->
                            Log.w(TAG, "Failed to refresh room transport for ${room.id}", throwable)
                        }
                    updateSnapshot { snapshot ->
                        snapshot.copy(
                            logs = appendLog(
                                snapshot.logs,
                                room.id,
                                "TIMEOUT >> Reconnection stuck for 30+ seconds; refreshed transport registration",
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * Calculate exponential backoff delay for message retry.
     * Formula: min(1s * 2^retryCount, 5 minutes)
     * Results in: 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 300s, 300s, ...
     */
    private fun calculateExponentialBackoff(retryCount: Int): Long {
        val baseDelayMillis = 1_000L
        val maxDelayMillis = 5 * 60 * 1_000L  // 5 minutes
        val exponentialDelay = baseDelayMillis * (1L shl retryCount.coerceAtMost(8))  // Cap at 2^8
        return exponentialDelay.coerceAtMost(maxDelayMillis)
    }

    /**
     * Clean up pending messages that have exceeded their TTL.
     * Messages are kept for up to 24 hours; older messages are discarded and logged.
     */
    private suspend fun cleanupExpiredPendingMessages(roomId: String, now: Long) {
        pendingMutex.withLock {
            val queue = pendingOutboundByRoom[roomId] ?: return@withLock
            val expired = queue.filter { now - it.createdAtEpochMillis > PENDING_MESSAGE_MAX_AGE_MILLIS }
            if (expired.isNotEmpty()) {
                Log.w(TAG, "Discarding ${expired.size} expired pending messages from room $roomId")
                queue.removeAll(expired)
                if (queue.isEmpty()) {
                    pendingOutboundByRoom.remove(roomId)
                }
                updateSnapshot { snapshot ->
                    snapshot.copy(
                        logs = appendLog(
                            snapshot.logs,
                            roomId,
                            "CLEANUP >> Discarded ${expired.size} pending messages (TTL exceeded)",
                        ),
                    )
                }
            }
        }
    }

    private suspend fun incrementPendingRetry(roomId: String, messageId: String) {
        pendingMutex.withLock {
            val queue = pendingOutboundByRoom[roomId] ?: return@withLock
            val index = queue.indexOfFirst { it.messageId == messageId }
            if (index < 0) return@withLock
            val current = queue[index]
            queue[index] = current.copy(
                retryCount = current.retryCount + 1,
                lastDispatchAtEpochMillis = null,
                awaitingAckFrom = emptyList(),
            )
        }
    }

    private suspend fun applyDeliveryAck(
        roomId: String,
        messageId: String,
        senderFingerprint: String,
    ): DeliveryAckUpdate = pendingMutex.withLock {
        val queue = pendingOutboundByRoom[roomId] ?: return@withLock DeliveryAckUpdate(
            matched = false,
            logicalMessageId = messageId,
            fullyDelivered = false,
            remainingRecipients = 0,
            pendingCount = 0,
        )
        val index = queue.indexOfFirst { it.messageId == messageId }
        if (index < 0) {
            return@withLock DeliveryAckUpdate(
                matched = false,
                logicalMessageId = messageId,
                fullyDelivered = false,
                remainingRecipients = queue.size,
                pendingCount = queue.map { it.logicalMessageId }.distinct().size,
            )
        }
        val current = queue[index]
        if (!current.awaitingAckFrom.contains(senderFingerprint)) {
            return@withLock DeliveryAckUpdate(
                matched = false,
                logicalMessageId = current.logicalMessageId,
                fullyDelivered = false,
                remainingRecipients = current.awaitingAckFrom.size,
                pendingCount = queue.map { it.logicalMessageId }.distinct().size,
            )
        }
        val remainingRecipients = current.awaitingAckFrom.filterNot { it == senderFingerprint }
        if (remainingRecipients.isEmpty()) {
            val logicalMessageId = current.logicalMessageId
            queue.removeAt(index)
            if (queue.isEmpty()) {
                pendingOutboundByRoom.remove(roomId)
            }
            val pendingForLogical = queue.any { it.logicalMessageId == logicalMessageId }
            return@withLock DeliveryAckUpdate(
                matched = true,
                logicalMessageId = logicalMessageId,
                fullyDelivered = !pendingForLogical,
                remainingRecipients = 0,
                pendingCount = queue.map { it.logicalMessageId }.distinct().size,
            )
        }
        queue[index] = current.copy(awaitingAckFrom = remainingRecipients)
        DeliveryAckUpdate(
            matched = true,
            logicalMessageId = current.logicalMessageId,
            fullyDelivered = false,
            remainingRecipients = remainingRecipients.size,
            pendingCount = queue.map { it.logicalMessageId }.distinct().size,
        )
    }

    private suspend fun clearPendingOutbound(roomId: String) {
        pendingMutex.withLock {
            pendingOutboundByRoom.remove(roomId)
        }
    }

    private suspend fun pendingCount(roomId: String): Int = pendingMutex.withLock {
        pendingOutboundByRoom[roomId]
            ?.map { it.logicalMessageId }
            ?.distinct()
            ?.size
            ?: 0
    }

    private suspend fun pendingMessages(roomId: String): List<PendingOutboundMessage> = pendingMutex.withLock {
        pendingOutboundByRoom[roomId]
            ?.sortedBy { it.createdAtEpochMillis }
            ?.map { it.copy() }
            .orEmpty()
    }

    private fun scheduleAckRetry(roomId: String) {
        val existing = ackRetryJobs[roomId]
        if (existing?.isActive == true) return
        ackRetryJobs[roomId] = scope.launch {
            delay(DELIVERY_ACK_TIMEOUT_MILLIS)
            ackRetryJobs.remove(roomId)
            flushPendingOutbound(roomId, "delivery ack timeout")
        }
    }

    private fun canAttemptImmediateSend(roomId: String): Boolean {
        val currentRoom = room(roomId) ?: return false
        return currentRoom.connectionState == ConnectionState.Connected &&
            pairwiseProtocol.verifiedPeers(roomId).isNotEmpty()
    }

    private fun isEncryptedReady(roomId: String): Boolean {
        return pairwiseProtocol.hasEstablishedSession(roomId)
    }

    private fun isAttachmentPayload(body: String): Boolean {
        return decodeAttachmentManifestPayload(body) != null || decodeAttachmentChunkPayload(body) != null
    }

    private fun outboundPayloadLabel(body: String): String {
        decodeAttachmentManifestPayload(body)?.let { manifest ->
            return "attachment manifest ${manifest.transferId.take(8)}"
        }
        decodeAttachmentChunkPayload(body)?.let { chunk ->
            return "attachment chunk ${chunk.transferId.take(8)} ${chunk.chunkIndex + 1}/${chunk.totalChunks}"
        }
        return "message"
    }

    private fun handleIncomingAttachmentPayload(
        roomId: String,
        decrypted: com.epher.app.security.DecryptedPairwiseMessage,
    ): IncomingAttachmentEffect? {
        decodeAttachmentManifestPayload(decrypted.body)?.let { manifest ->
            val validationError = validateIncomingAttachmentManifest(manifest)
            if (validationError != null) {
                attachmentFileStore.removePendingTransfer(roomId, manifest.transferId)
                return incomingAttachmentRejectedEffect(
                    sentAtEpochMillis = decrypted.sentAtEpochMillis,
                    detail = "attachment manifest rejected: $validationError",
                )
            }
            if (attachmentFileStore.loadPendingManifest(roomId, manifest.transferId) == null &&
                attachmentFileStore.loadPendingTransferCount(roomId) >= MAX_PENDING_ATTACHMENT_TRANSFERS_PER_ROOM
            ) {
                return incomingAttachmentRejectedEffect(
                    sentAtEpochMillis = decrypted.sentAtEpochMillis,
                    detail = "attachment manifest rejected: too many pending transfers",
                )
            }
            attachmentFileStore.savePendingManifest(
                PendingAttachmentManifest(
                    roomId = roomId,
                    transferId = manifest.transferId,
                    displayMessageId = manifest.displayMessageId,
                    senderName = decrypted.senderName,
                    senderFingerprint = decrypted.senderFingerprint,
                    fileName = manifest.fileName,
                    mimeType = manifest.mimeType,
                    byteSize = manifest.byteSize,
                    encryptedNonce = manifest.encryptedNonce,
                    digestHex = manifest.digestHex,
                    totalChunks = manifest.totalChunks,
                    sentAtEpochMillis = decrypted.sentAtEpochMillis,
                ),
            )
            return completeIncomingAttachmentIfReady(roomId, manifest.transferId)
        }

        decodeAttachmentChunkPayload(decrypted.body)?.let { chunk ->
            val validationError = validateIncomingAttachmentChunk(chunk)
            if (validationError != null) {
                attachmentFileStore.removePendingTransfer(roomId, chunk.transferId)
                return incomingAttachmentRejectedEffect(
                    sentAtEpochMillis = decrypted.sentAtEpochMillis,
                    detail = "attachment chunk rejected: $validationError",
                )
            }
            if (attachmentFileStore.loadPendingManifest(roomId, chunk.transferId) == null &&
                attachmentFileStore.loadPendingTransferCount(roomId) >= MAX_PENDING_ATTACHMENT_TRANSFERS_PER_ROOM
            ) {
                return incomingAttachmentRejectedEffect(
                    sentAtEpochMillis = decrypted.sentAtEpochMillis,
                    detail = "attachment chunk rejected: too many pending transfers",
                )
            }
            attachmentFileStore.savePendingChunk(
                roomId = roomId,
                transferId = chunk.transferId,
                chunkIndex = chunk.chunkIndex,
                encodedChunk = chunk.chunkData,
            )
            return completeIncomingAttachmentIfReady(roomId, chunk.transferId, chunk.totalChunks)
        }

        decodeAttachmentPayload(decrypted.body)?.let { legacyAttachment ->
            return IncomingAttachmentEffect(
                message = RoomMessage(
                    id = decrypted.messageId,
                    roomId = roomId,
                    senderName = decrypted.senderName,
                    body = "",
                    sentAt = formatTime(decrypted.sentAtEpochMillis),
                    isLocalUser = false,
                    attachment = legacyAttachment,
                ),
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                logLine = "RX >> encrypted attachment accepted",
            )
        }

        return null
    }

    private fun completeIncomingAttachmentIfReady(
        roomId: String,
        transferId: String,
        fallbackTotalChunks: Int? = null,
    ): IncomingAttachmentEffect {
        val manifest = attachmentFileStore.loadPendingManifest(roomId, transferId)
        if (manifest == null) {
            return IncomingAttachmentEffect(
                message = null,
                lastActivityEpochMillis = System.currentTimeMillis(),
                logLine = "RX >> attachment chunk buffered while waiting for manifest",
            )
        }
        val manifestValidationError = validatePendingAttachmentManifest(manifest, transferId)
        if (manifestValidationError != null) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: $manifestValidationError",
            )
        }
        val totalChunks = fallbackTotalChunks ?: manifest.totalChunks
        if (totalChunks != manifest.totalChunks) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: chunk count mismatch",
            )
        }
        val chunkCount = attachmentFileStore.loadPendingChunkCount(roomId, transferId)
        if (chunkCount > totalChunks) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: too many chunks",
            )
        }
        val encodedCiphertext = attachmentFileStore.assemblePendingChunks(roomId, transferId, totalChunks)
        if (encodedCiphertext == null) {
            return IncomingAttachmentEffect(
                message = incomingAttachmentPlaceholderMessage(
                    roomId = roomId,
                    manifest = manifest,
                    sentAt = formatTime(manifest.sentAtEpochMillis),
                    receivedChunks = chunkCount,
                    totalChunks = totalChunks,
                ),
                lastActivityEpochMillis = manifest.sentAtEpochMillis,
                logLine = "RX >> attachment transfer buffered ($chunkCount/$totalChunks chunks)",
            )
        }
        if (encodedCiphertext.length > MAX_ATTACHMENT_CIPHERTEXT_BASE64_CHARS) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: ciphertext exceeds current limit",
            )
        }
        val ciphertext = runCatching { base64UrlDecode(encodedCiphertext) }.getOrElse {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: ciphertext encoding invalid",
            )
        }
        if (ciphertext.size > MAX_ATTACHMENT_CIPHERTEXT_BYTES) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: ciphertext exceeds current limit",
            )
        }
        val verifiedPlaintextSize = runCatching {
            roomSecurity.decryptAttachment(
                roomId = roomId,
                attachment = com.epher.app.security.EncryptedAttachment(
                    nonce = base64UrlDecode(manifest.encryptedNonce),
                    ciphertext = ciphertext,
                    digestHex = manifest.digestHex,
                ),
            ).size.toLong()
        }.getOrElse {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: integrity check failed",
            )
        }
        if (verifiedPlaintextSize != manifest.byteSize) {
            attachmentFileStore.removePendingTransfer(roomId, transferId)
            return incomingAttachmentRejectedEffect(
                sentAtEpochMillis = manifest.sentAtEpochMillis,
                detail = "attachment transfer rejected: declared size mismatch",
            )
        }

        val storageKey = attachmentStorageKey(roomId, manifest.displayMessageId)
        attachmentFileStore.putEncryptedCiphertext(
            roomId = roomId,
            storageKey = storageKey,
            ciphertext = ciphertext,
        )
        attachmentFileStore.removePendingTransfer(roomId, transferId)
        return IncomingAttachmentEffect(
            message = RoomMessage(
                id = manifest.displayMessageId,
                roomId = roomId,
                senderName = manifest.senderName,
                body = "",
                sentAt = formatTime(manifest.sentAtEpochMillis),
                isLocalUser = false,
                attachment = RoomAttachment(
                    fileName = manifest.fileName,
                    mimeType = manifest.mimeType,
                    byteSize = manifest.byteSize,
                    encryptedNonce = manifest.encryptedNonce,
                    encryptedCiphertext = "",
                    digestHex = manifest.digestHex,
                    storageKey = storageKey,
                    transferState = AttachmentTransferState.Available,
                    receivedChunks = totalChunks,
                    totalChunks = totalChunks,
                ),
            ),
            lastActivityEpochMillis = manifest.sentAtEpochMillis,
            logLine = "RX >> encrypted attachment accepted",
        )
    }

    private fun incomingAttachmentRejectedEffect(
        sentAtEpochMillis: Long,
        detail: String,
    ): IncomingAttachmentEffect = IncomingAttachmentEffect(
        message = null,
        lastActivityEpochMillis = sentAtEpochMillis,
        logLine = "SECURITY >> $detail",
    )

    private fun validateIncomingAttachmentManifest(manifest: AttachmentManifestPayload): String? {
        if (!isSafeAttachmentId(manifest.transferId)) return "transfer ID invalid"
        if (!isSafeAttachmentId(manifest.displayMessageId)) return "display message ID invalid"
        if (!isSafeAttachmentFileName(manifest.fileName)) return "file name invalid"
        if (!isSafeAttachmentMimeType(manifest.mimeType)) return "MIME type invalid"
        if (manifest.byteSize !in 1L..MAX_ATTACHMENT_BYTES.toLong()) return "attachment size exceeds current limit"
        if (manifest.totalChunks !in 1..MAX_ATTACHMENT_TOTAL_CHUNKS) return "chunk count exceeds current limit"
        if (!DIGEST_HEX_PATTERN.matches(manifest.digestHex)) return "attachment digest invalid"
        if (!isValidAttachmentNonce(manifest.encryptedNonce)) return "attachment nonce invalid"
        return null
    }

    private fun validateIncomingAttachmentChunk(chunk: AttachmentChunkPayload): String? {
        if (!isSafeAttachmentId(chunk.transferId)) return "transfer ID invalid"
        if (chunk.totalChunks !in 1..MAX_ATTACHMENT_TOTAL_CHUNKS) return "chunk count exceeds current limit"
        if (chunk.chunkIndex !in 0 until chunk.totalChunks) return "chunk index invalid"
        if (chunk.chunkData.isBlank()) return "chunk payload missing"
        if (chunk.chunkData.length > ATTACHMENT_CHUNK_BASE64_CHARS) return "chunk payload exceeds current limit"
        if (!BASE64_URL_PATTERN.matches(chunk.chunkData)) return "chunk payload encoding invalid"
        if (chunk.chunkData.length % 4 == 1) return "chunk payload length invalid"
        return null
    }

    private fun validatePendingAttachmentManifest(
        manifest: PendingAttachmentManifest,
        expectedTransferId: String,
    ): String? {
        if (manifest.transferId != expectedTransferId) return "transfer metadata mismatch"
        if (!isSafeAttachmentId(manifest.transferId)) return "transfer ID invalid"
        if (!isSafeAttachmentId(manifest.displayMessageId)) return "display message ID invalid"
        if (!isSafeAttachmentFileName(manifest.fileName)) return "file name invalid"
        if (!isSafeAttachmentMimeType(manifest.mimeType)) return "MIME type invalid"
        if (manifest.byteSize !in 1L..MAX_ATTACHMENT_BYTES.toLong()) return "attachment size exceeds current limit"
        if (manifest.totalChunks !in 1..MAX_ATTACHMENT_TOTAL_CHUNKS) return "chunk count exceeds current limit"
        if (!DIGEST_HEX_PATTERN.matches(manifest.digestHex)) return "attachment digest invalid"
        if (!isValidAttachmentNonce(manifest.encryptedNonce)) return "attachment nonce invalid"
        return null
    }

    private fun isSafeAttachmentId(value: String): Boolean = ATTACHMENT_ID_PATTERN.matches(value)

    private fun isSafeAttachmentFileName(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_ATTACHMENT_FILE_NAME_CHARS) return false
        if (value.any { it.code < 0x20 || it == '/' || it == '\\' }) return false
        return true
    }

    private fun isSafeAttachmentMimeType(value: String): Boolean =
        value.length <= MAX_ATTACHMENT_MIME_TYPE_CHARS && MIME_TYPE_PATTERN.matches(value)

    private fun normalizeAttachmentFileName(value: String): String {
        val normalized = value
            .ifBlank { "attachment.bin" }
            .map { char ->
                when {
                    char.code < 0x20 || char == '/' || char == '\\' -> '_'
                    else -> char
                }
            }
            .joinToString("")
            .trim()
            .take(MAX_ATTACHMENT_FILE_NAME_CHARS)
            .trim('.', ' ')
        return normalized.ifBlank { "attachment.bin" }
    }

    private fun normalizeAttachmentMimeType(value: String): String {
        val normalized = value.ifBlank { "application/octet-stream" }.trim().lowercase(Locale.US)
        return if (isSafeAttachmentMimeType(normalized)) normalized else "application/octet-stream"
    }

    private fun isValidAttachmentNonce(value: String): Boolean = runCatching {
        base64UrlDecode(value).size == ATTACHMENT_NONCE_BYTES
    }.getOrDefault(false)

    private suspend fun handleIncomingControlPayload(
        roomId: String,
        decrypted: com.epher.app.security.DecryptedPairwiseMessage,
    ): IncomingControlEffect? {
        val control = decodeRoomRekeyControl(decrypted.body) ?: return null
        if (control.roomId != roomId) {
            return IncomingControlEffect(
                removedFingerprint = control.removedFingerprint,
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                systemMessage = "Rejected room rekey with mismatched room ID.",
                logLine = "SECURITY >> room rekey rejected: room mismatch",
            )
        }
        val ownerFingerprint = roomSecurity.ownerFingerprint(roomId)
        if (!ownerFingerprint.equals(decrypted.senderFingerprint, ignoreCase = true)) {
            return IncomingControlEffect(
                removedFingerprint = control.removedFingerprint,
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                systemMessage = "Rejected room rekey because it was not sent by the room owner.",
                logLine = "SECURITY >> room rekey rejected: sender is not owner",
            )
        }
        if (control.removedFingerprint == roomSecurity.roomFingerprint(roomId)) {
            leaveRoom(roomId)
            return IncomingControlEffect(
                removedFingerprint = control.removedFingerprint,
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                systemMessage = "This device was removed from the room. Local room state has been wiped.",
                logLine = "SECURITY >> local device removed by owner",
            )
        }
        return runCatching {
            val rekey = roomSecurity.applyRoomRekey(
                roomId = roomId,
                roomPassword = control.roomPassword,
                epoch = control.epoch,
            )
            pairwiseProtocol.removePeer(roomId, control.removedFingerprint)
            IncomingControlEffect(
                removedFingerprint = control.removedFingerprint,
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                systemMessage = "Room keys rotated to epoch ${rekey.epoch}. Removed peers cannot read future messages.",
                logLine = "SECURITY >> owner rekey applied; room now at epoch ${rekey.epoch}",
            )
        }.getOrElse { throwable ->
            IncomingControlEffect(
                removedFingerprint = control.removedFingerprint,
                lastActivityEpochMillis = decrypted.sentAtEpochMillis,
                systemMessage = "Rejected stale or invalid room rekey.",
                logLine = "SECURITY >> room rekey rejected: ${throwable.message ?: "unknown error"}",
            )
        }
    }

    private fun isRemovedFingerprint(roomId: String, fingerprint: String): Boolean =
        participants(roomId).any { participant ->
            participant.isRemoved && participant.fingerprint.equals(fingerprint, ignoreCase = true)
        }

    private fun activeParticipantCount(participants: List<Participant>): Int =
        participants.count { !it.isRemoved }.coerceAtLeast(1)

    private fun localParticipant(
        roomId: String,
        role: RoomRole,
    ): Participant {
        return Participant(
            id = UUID.randomUUID().toString(),
            displayName = localDisplayName,
            isOnline = true,
            isVerified = true,
            role = role,
            fingerprint = roomSecurity.roomFingerprint(roomId),
            relayEligible = true,
        )
    }

    private fun upsertParticipant(
        current: List<Participant>,
        participant: Participant,
    ): List<Participant> {
        val existingIndex = current.indexOfFirst { it.fingerprint == participant.fingerprint }
        if (existingIndex < 0) return current + participant
        return current.toMutableList().apply { set(existingIndex, participant) }
    }

    private fun appendLog(
        current: Map<String, List<String>>,
        roomId: String,
        line: String,
    ): Map<String, List<String>> {
        val updated = (current[roomId].orEmpty() + timestamped(line)).takeLast(MAX_LOG_LINES)
        return current + (roomId to updated)
    }

    private fun appendSystemMessage(
        current: Map<String, List<RoomMessage>>,
        roomId: String,
        body: String,
        sentAtEpochMillis: Long = System.currentTimeMillis(),
    ): Map<String, List<RoomMessage>> {
        return current + (
            roomId to (current[roomId].orEmpty() + RoomMessage(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                senderName = "System",
                body = body,
                sentAt = formatTime(sentAtEpochMillis),
                isLocalUser = false,
                isSystemEvent = true,
            ))
        )
    }

    private fun appendSystemMessages(
        current: Map<String, List<RoomMessage>>,
        roomId: String,
        bodies: List<String>,
        sentAtEpochMillis: Long = System.currentTimeMillis(),
    ): Map<String, List<RoomMessage>> {
        var updated = current
        bodies.forEach { body ->
            updated = appendSystemMessage(updated, roomId, body, sentAtEpochMillis)
        }
        return updated
    }

    private fun timestamped(line: String): String = "${formatLogTime(System.currentTimeMillis())} >> $line"

    private fun formatTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))
        .lowercase(Locale.US)

    private fun formatLogTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

    /**
     * Track decryption failures per peer to detect DoS attacks or key sync issues.
     * Emits warning if a peer sends >5 failed decryptions within 1 minute.
     */
    private fun trackDecryptionFailure(roomId: String, senderFingerprint: String) {
        val failuresByPeer = decryptionFailuresByRoom.getOrPut(roomId) { linkedMapOf() }
        val existing = failuresByPeer[senderFingerprint]
        val now = System.currentTimeMillis()
        
        val updated = if (existing != null && now - existing.firstFailureTime < 60_000L) {
            // Within 1-minute window, increment failure count
            existing.copy(failureCount = existing.failureCount + 1)
        } else {
            // Outside window or first failure, reset
            DecryptionFailure(senderFingerprint, 1, now)
        }
        
        failuresByPeer[senderFingerprint] = updated
        
        // Alert if peer has 5+ failures in 1 minute (likely attack or key sync issue)
        if (updated.failureCount >= 5 && now - updated.firstFailureTime < 60_000L) {
            Log.w(TAG, "High decryption failure rate from peer $senderFingerprint in room $roomId")
            updateSnapshot { snapshot ->
                snapshot.copy(
                    logs = appendLog(
                        snapshot.logs,
                        roomId,
                        "ALERT >> Peer ${senderFingerprint.take(12)} has ${updated.failureCount} failed decryptions in 1 minute - possible DoS or key sync issue",
                    ),
                )
            }
        }
    }

    private companion object {
        const val TAG = "RoomsRepository"
        const val GLOBAL_LOG_ROOM_ID = "__global__"
        const val MAX_LOG_LINES = 40
        const val MAX_ATTACHMENT_BYTES = 1024 * 1024
        const val ATTACHMENT_PACKET_SIZE = 512
        const val ATTACHMENT_AEAD_TAG_BYTES = 16
        const val ATTACHMENT_NONCE_BYTES = 24
        const val ATTACHMENT_CHUNK_BASE64_CHARS = 2000
        const val RETENTION_SWEEP_INTERVAL_MILLIS = 15L * 60L * 1000L
        const val CACHE_SAVE_DEBOUNCE_MILLIS = 250L
        const val CONNECTION_READY_FALLBACK_MILLIS = 2_500L
        const val DELIVERY_ACK_TIMEOUT_MILLIS = 20_000L
        const val PENDING_MESSAGE_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L  // 24 hours
        const val MAX_PENDING_MESSAGES_PER_ROOM = 500
        const val CONNECTION_STUCK_TIMEOUT_MILLIS = 30_000L  // 30 seconds
        const val RECONNECTION_CHECK_INTERVAL_MILLIS = 10_000L  // Check every 10 seconds
        const val STALE_ATTACHMENT_TRANSFER_MILLIS = 2L * 60L * 60L * 1000L
        const val MAX_ATTACHMENT_FILE_NAME_CHARS = 120
        const val MAX_ATTACHMENT_MIME_TYPE_CHARS = 100
        const val MAX_PENDING_ATTACHMENT_TRANSFERS_PER_ROOM = 64
        val MAX_ATTACHMENT_PADDED_BYTES =
            ((MAX_ATTACHMENT_BYTES + 4) / ATTACHMENT_PACKET_SIZE + 1) * ATTACHMENT_PACKET_SIZE
        val MAX_ATTACHMENT_CIPHERTEXT_BYTES = MAX_ATTACHMENT_PADDED_BYTES + ATTACHMENT_AEAD_TAG_BYTES
        val MAX_ATTACHMENT_CIPHERTEXT_BASE64_CHARS =
            ((MAX_ATTACHMENT_CIPHERTEXT_BYTES + 2) / 3) * 4
        val MAX_ATTACHMENT_TOTAL_CHUNKS =
            (MAX_ATTACHMENT_CIPHERTEXT_BASE64_CHARS + ATTACHMENT_CHUNK_BASE64_CHARS - 1) / ATTACHMENT_CHUNK_BASE64_CHARS
        val ATTACHMENT_ID_PATTERN = Regex("[A-Za-z0-9-]{1,128}")
        val DIGEST_HEX_PATTERN = Regex("[0-9a-fA-F]{64}")
        val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        val MIME_TYPE_PATTERN = Regex("[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,63}")
    }
}

private data class AttachmentManifestPayload(
    val transferId: String,
    val displayMessageId: String,
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
    val encryptedNonce: String,
    val digestHex: String,
    val totalChunks: Int,
)

private data class AttachmentChunkPayload(
    val transferId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val chunkData: String,
)

private data class IncomingAttachmentEffect(
    val message: RoomMessage?,
    val lastActivityEpochMillis: Long,
    val logLine: String,
)

private data class RoomRekeyControlPayload(
    val roomId: String,
    val roomPassword: String,
    val epoch: Long,
    val removedFingerprint: String,
)

private data class IncomingControlEffect(
    val removedFingerprint: String,
    val lastActivityEpochMillis: Long,
    val systemMessage: String,
    val logLine: String,
)

private fun incomingAttachmentPlaceholderMessage(
    roomId: String,
    manifest: PendingAttachmentManifest,
    sentAt: String,
    receivedChunks: Int,
    totalChunks: Int,
): RoomMessage = RoomMessage(
    id = manifest.displayMessageId,
    roomId = roomId,
    senderName = manifest.senderName,
    body = "",
    sentAt = sentAt,
    isLocalUser = false,
    attachment = RoomAttachment(
        fileName = manifest.fileName,
        mimeType = manifest.mimeType,
        byteSize = manifest.byteSize,
        encryptedNonce = manifest.encryptedNonce,
        encryptedCiphertext = "",
        digestHex = manifest.digestHex,
        storageKey = null,
        transferState = AttachmentTransferState.Receiving,
        receivedChunks = receivedChunks.coerceAtMost(totalChunks),
        totalChunks = totalChunks,
    ),
)

private fun upsertRoomMessage(
    messages: List<RoomMessage>,
    incoming: RoomMessage,
): List<RoomMessage> {
    val existingIndex = messages.indexOfFirst { it.id == incoming.id }
    if (existingIndex < 0) return messages + incoming
    return messages.toMutableList().apply { set(existingIndex, incoming) }
}

private fun attachmentStorageKey(
    roomId: String,
    messageId: String,
): String = "room.$roomId.attachment.$messageId"

private fun encodeRoomRekeyControl(
    roomId: String,
    roomPassword: String,
    epoch: Long,
    removedFingerprint: String,
): String = JSONObject()
    .put("messageType", "room_rekey_v1")
    .put("roomId", roomId)
    .put("roomPassword", roomPassword)
    .put("epoch", epoch)
    .put("removedFingerprint", removedFingerprint)
    .put("issuedAtEpochMillis", System.currentTimeMillis())
    .toString()

private fun decodeRoomRekeyControl(payload: String): RoomRekeyControlPayload? = runCatching {
    val json = JSONObject(payload)
    if (json.optString("messageType") != "room_rekey_v1") return null
    RoomRekeyControlPayload(
        roomId = json.getString("roomId"),
        roomPassword = json.getString("roomPassword"),
        epoch = json.getLong("epoch"),
        removedFingerprint = json.getString("removedFingerprint"),
    )
}.getOrNull()

private fun encodeAttachmentManifestPayload(payload: AttachmentManifestPayload): String = JSONObject()
    .put("messageType", "attachment_manifest")
    .put("transferId", payload.transferId)
    .put("displayMessageId", payload.displayMessageId)
    .put("fileName", payload.fileName)
    .put("mimeType", payload.mimeType)
    .put("byteSize", payload.byteSize)
    .put("encryptedNonce", payload.encryptedNonce)
    .put("digestHex", payload.digestHex)
    .put("totalChunks", payload.totalChunks)
    .toString()

private fun decodeAttachmentManifestPayload(payload: String): AttachmentManifestPayload? = runCatching {
    val json = JSONObject(payload)
    if (json.optString("messageType") != "attachment_manifest") return null
    AttachmentManifestPayload(
        transferId = json.getString("transferId"),
        displayMessageId = json.getString("displayMessageId"),
        fileName = json.optString("fileName").ifBlank { "attachment.bin" },
        mimeType = json.optString("mimeType", "application/octet-stream"),
        byteSize = json.optLong("byteSize", 0L),
        encryptedNonce = json.getString("encryptedNonce"),
        digestHex = json.getString("digestHex"),
        totalChunks = json.getInt("totalChunks"),
    )
}.getOrNull()

private fun encodeAttachmentChunkPayload(payload: AttachmentChunkPayload): String = JSONObject()
    .put("messageType", "attachment_chunk")
    .put("transferId", payload.transferId)
    .put("chunkIndex", payload.chunkIndex)
    .put("totalChunks", payload.totalChunks)
    .put("chunkData", payload.chunkData)
    .toString()

private fun decodeAttachmentChunkPayload(payload: String): AttachmentChunkPayload? = runCatching {
    val json = JSONObject(payload)
    if (json.optString("messageType") != "attachment_chunk") return null
    AttachmentChunkPayload(
        transferId = json.getString("transferId"),
        chunkIndex = json.getInt("chunkIndex"),
        totalChunks = json.getInt("totalChunks"),
        chunkData = json.getString("chunkData"),
    )
}.getOrNull()

private fun decodeAttachmentPayload(payload: String): RoomAttachment? = runCatching {
    val json = JSONObject(payload)
    if (json.optString("messageType") != "attachment") return null
    RoomAttachment(
        fileName = json.optString("fileName").ifBlank { "attachment.bin" },
        mimeType = json.optString("mimeType", "application/octet-stream"),
        byteSize = json.optLong("byteSize", 0L),
        encryptedNonce = json.getString("encryptedNonce"),
        encryptedCiphertext = json.optString("encryptedCiphertext"),
        digestHex = json.getString("digestHex"),
        storageKey = json.optString("storageKey").ifBlank { null },
    )
}.getOrNull()

private fun List<RoomSummary>.updateRoom(
    roomId: String,
    transform: (RoomSummary) -> RoomSummary,
): List<RoomSummary> = map { room ->
    if (room.id == roomId) transform(room) else room
}

private fun RoomsSnapshot.removeRooms(roomIds: Set<String>): RoomsSnapshot {
    if (roomIds.isEmpty()) return this
    return copy(
        rooms = rooms.filterNot { it.id in roomIds },
        participants = participants.filterKeys { it !in roomIds },
        messages = messages.filterKeys { it !in roomIds },
        logs = logs.filterKeys { it !in roomIds },
    )
}

data class RoomsSnapshot(
    val rooms: List<RoomSummary>,
    val participants: Map<String, List<Participant>>,
    val messages: Map<String, List<RoomMessage>>,
    val logs: Map<String, List<String>>,
)

private data class DeliveryAckUpdate(
    val matched: Boolean,
    val logicalMessageId: String,
    val fullyDelivered: Boolean,
    val remainingRecipients: Int,
    val pendingCount: Int,
)

private fun List<PendingOutboundMessage>.deliveryState(): MessageDeliveryState = when {
    any { it.awaitingAckFrom.isNotEmpty() } -> MessageDeliveryState.Sending
    any { it.retryCount > 0 } -> MessageDeliveryState.Retry
    isNotEmpty() -> MessageDeliveryState.Queued
    else -> MessageDeliveryState.Sent
}
