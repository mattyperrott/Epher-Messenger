package com.epher.app.engine

import android.util.Base64
import android.util.Log
import com.epher.app.data.model.ConnectionState
import com.epher.app.mixnet.MixnetInviteRouteHints
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

class MixnetRelayEngine(
    private val relayUrl: String,
    private val providerBaseUrl: String,
    private val scope: CoroutineScope,
    private val clientId: String,
) : P2PEngine {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val stateMutex = Mutex()
    private val sendMutex = Mutex()
    private val startMutex = Mutex()
    private val _events = MutableSharedFlow<P2PEngineEvent>(
        replay = 0,
        extraBufferCapacity = 256,
    )
    override val events: Flow<P2PEngineEvent> = _events.asSharedFlow()

    private val rooms = linkedMapOf<String, MixRoomRegistration>()
    private val pendingPackets = ArrayDeque<QueuedMixPacket>()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectStateJob: Job? = null
    private var trafficLoopJob: Job? = null
    private var reconnectAttempt = 0
    private var roundRobinCursor = 0
    @Volatile
    private var started = false
    @Volatile
    private var suspended = false
    @Volatile
    private var engineReadyEmitted = false
    @Volatile
    private var connecting = false

    override suspend fun start() {
        ensureStarted()
    }

    override suspend fun createRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        mixnetRouteHintsJson: String?,
    ) {
        val routeHints = MixnetInviteRouteHints.fromJsonString(mixnetRouteHintsJson)
            ?: fallbackRouteHints(topicHex, transportSeedHex)
        registerRoom(
            MixRoomRegistration(
                roomId = roomId,
                roomAlias = routeHints.roomAlias,
                roomAccessProof = routeHints.roomAccessProof,
                providerId = routeHints.providerId,
                ingressGatewayId = routeHints.ingressGatewayId,
                routeId = routeHints.routeId,
                mixHopIds = routeHints.mixHopIds,
                transportPublicKeyHex = deriveTransportPublicKeyHex(transportSeedHex),
                mailboxId = MixnetInviteRouteHints.deriveMailboxAlias(
                    routeHints.roomAlias,
                    routeHints.providerId,
                    transportSeedHex,
                ),
                localPeerCard = localPeerCard,
                role = "owner",
            ),
        )
    }

    override suspend fun joinRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        inviteToken: String,
        mixnetRouteHintsJson: String?,
    ) {
        val routeHints = MixnetInviteRouteHints.fromJsonString(mixnetRouteHintsJson)
            ?: fallbackRouteHints(topicHex, transportSeedHex)
        registerRoom(
            MixRoomRegistration(
                roomId = roomId,
                roomAlias = routeHints.roomAlias,
                roomAccessProof = routeHints.roomAccessProof,
                providerId = routeHints.providerId,
                ingressGatewayId = routeHints.ingressGatewayId,
                routeId = routeHints.routeId,
                mixHopIds = routeHints.mixHopIds,
                transportPublicKeyHex = deriveTransportPublicKeyHex(transportSeedHex),
                mailboxId = MixnetInviteRouteHints.deriveMailboxAlias(
                    routeHints.roomAlias,
                    routeHints.providerId,
                    transportSeedHex,
                ),
                localPeerCard = localPeerCard,
                role = "member",
            ),
        )
    }

    override suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String) {
        ensureStarted()
        val packet = encodeFixedPacket(
            JSONObject()
                .put("type", "envelope")
                .put("encodedEnvelope", encodedEnvelope)
                .put("packetId", UUID.randomUUID().toString()),
        )
        stateMutex.withLock {
            pendingPackets.addLast(QueuedMixPacket(roomId = roomId, packet = packet, isCover = false))
            while (pendingPackets.size > MAX_PENDING_PACKETS) {
                pendingPackets.removeFirst()
            }
        }
        emitLog(roomId, "queued encrypted mix packet for next relay cycle")
    }

    override suspend fun sendDeliveryAck(roomId: String, encodedAck: String) {
        ensureStarted()
        val packet = encodeFixedPacket(
            JSONObject()
                .put("type", "delivery_ack")
                .put("encodedAck", encodedAck)
                .put("packetId", UUID.randomUUID().toString()),
        )
        stateMutex.withLock {
            pendingPackets.addLast(QueuedMixPacket(roomId = roomId, packet = packet, isCover = false))
            while (pendingPackets.size > MAX_PENDING_PACKETS) {
                pendingPackets.removeFirst()
            }
        }
        emitLog(roomId, "queued delivery ack for next relay cycle")
    }

    override suspend fun updateKnownPeerTransportKeys(roomId: String, peerTransportPublicKeysHex: List<String>) {
        if (peerTransportPublicKeysHex.isNotEmpty()) {
            emitLog(roomId, "mix relay known peer transport keys refreshed (${peerTransportPublicKeysHex.size})")
        }
    }

    override suspend fun leaveRoom(roomId: String) {
        val room = stateMutex.withLock {
            val removed = rooms.remove(roomId)
            pendingPackets.removeAll { it.roomId == roomId }
            removed
        } ?: return

        if (stateMutex.withLock { webSocket != null }) {
            sendJson(
                JSONObject()
                    .put("type", "leave_room")
                    .put("roomId", room.roomAlias)
                    .put("roomAccessProof", room.roomAccessProof)
                    .put("providerId", room.providerId)
                    .put("mailboxId", room.mailboxId),
            )
        }
    }

    override suspend fun suspendNetworking() {
        suspended = true
        trafficLoopJob?.cancel()
        val roomIds = stateMutex.withLock { rooms.keys.toList() }
        closeSocket(1000, "app suspended")
        roomIds.forEach { roomId ->
            _events.emit(
                P2PEngineEvent.RoomStateChanged(
                    roomId = roomId,
                    connectionState = ConnectionState.Backgrounded,
                    detail = "Mix relay paused while app is backgrounded",
                ),
            )
        }
    }

    override suspend fun resumeNetworking() {
        suspended = false
        ensureStarted()
        connectIfNeeded("resume")
    }

    private suspend fun ensureStarted() {
        if (started) return
        startMutex.withLock {
            if (started) return
            started = true
            emitLog(null, "mix relay enabled at $relayUrl")
            Log.d(TAG, "Starting mix relay engine for $relayUrl")
            connectIfNeeded("startup")
        }
    }

    private suspend fun registerRoom(room: MixRoomRegistration) {
        ensureStarted()
        stateMutex.withLock {
            rooms[room.roomId] = room
        }
        _events.emit(
            P2PEngineEvent.RoomStateChanged(
                roomId = room.roomId,
                connectionState = ConnectionState.Reconnecting,
                detail = "Preparing mix route and mailbox",
            ),
        )
        emitLog(
            room.roomId,
            "mix relay registration prepared via ${room.ingressGatewayId} -> ${room.mixHopIds.joinToString(" -> ")} -> ${room.providerId}",
        )
        sendJoinIfConnected(room.roomId)
    }

    private suspend fun connectIfNeeded(reason: String) {
        if (suspended) return
        val shouldConnect = stateMutex.withLock {
            if (connecting || webSocket != null) {
                false
            } else {
                connecting = true
                true
            }
        }
        if (!shouldConnect) return

        emitLog(null, "connecting to mix relay ($reason)")
        val request = Request.Builder()
            .url(relayUrl)
            .build()
        withContext(Dispatchers.IO) {
            client.newWebSocket(request, MixRelaySocketListener())
        }
    }

    private suspend fun sendJoinIfConnected(roomId: String) {
        val room = stateMutex.withLock { rooms[roomId] } ?: return
        if (stateMutex.withLock { webSocket == null }) return
        sendHello()
        sendJson(room.toJoinMessage())
    }

    private suspend fun sendHello() {
        sendJson(
            JSONObject()
                .put("type", "hello")
                .put("clientId", clientId)
                .put("platform", "android")
                .put("transport", "mixnet-multi-hop-dev"),
        )
    }

    private suspend fun sendNextTransportFrames() {
        val roomList = stateMutex.withLock { rooms.values.toList() }
        if (roomList.isEmpty()) return
        val queued = stateMutex.withLock {
            buildList {
                repeat(MAX_PACKETS_PER_CYCLE) {
                    val next = pendingPackets.removeFirstOrNull() ?: return@repeat
                    add(next)
                }
            }
        }
        if (queued.isNotEmpty()) {
            queued.forEach { outbound ->
                val room = roomList.firstOrNull { it.roomId == outbound.roomId } ?: roomList.first()
                sendJson(
                    JSONObject()
                        .put("type", "push_packet")
                        .put("roomId", room.roomAlias)
                        .put("roomAccessProof", room.roomAccessProof)
                        .put("providerId", room.providerId)
                        .put("routeId", room.routeId)
                        .put("ingressGatewayId", room.ingressGatewayId)
                        .put("mixHopIds", JSONArray(room.mixHopIds))
                        .put("mailboxId", room.mailboxId)
                        .put("packet", outbound.packet),
                )
            }
            return
        }

        val coverRoom = roomList[roundRobinCursor % roomList.size]
        roundRobinCursor = (roundRobinCursor + 1) % roomList.size
        sendJson(
            JSONObject()
                .put("type", "push_packet")
                .put("roomId", coverRoom.roomAlias)
                .put("roomAccessProof", coverRoom.roomAccessProof)
                .put("providerId", coverRoom.providerId)
                .put("routeId", coverRoom.routeId)
                .put("ingressGatewayId", coverRoom.ingressGatewayId)
                .put("mixHopIds", JSONArray(coverRoom.mixHopIds))
                .put("mailboxId", coverRoom.mailboxId)
                .put("packet", coverPacket()),
        )
    }

    private fun startTrafficLoop() {
        if (trafficLoopJob?.isActive == true) return
        trafficLoopJob = scope.launch {
            while (isActive) {
                val roomsToPoll = stateMutex.withLock {
                    if (suspended || webSocket == null) emptyList() else rooms.values.toList()
                }
                if (roomsToPoll.isNotEmpty()) {
                    runCatching {
                        sendNextTransportFrames()
                        roomsToPoll.forEach { room ->
                            pullMailboxFromProvider(room)
                        }
                    }.onFailure { throwable ->
                        Log.e(TAG, "Mix relay cycle failed", throwable)
                        emitLog(null, "mix relay cycle failure: ${throwable.message ?: "unknown"}")
                    }
                }
                delay(nextRelayIntervalMillis())
            }
        }
    }

    private fun nextRelayIntervalMillis(): Long = MIX_LOOP_BASE_INTERVAL_MILLIS +
        Random.nextLong(MIX_LOOP_JITTER_MILLIS + 1)

    private suspend fun sendJson(payload: JSONObject): Boolean {
        val socket = stateMutex.withLock { webSocket } ?: return false
        val encoded = payload.toString()
        val sent = sendMutex.withLock {
            withContext(Dispatchers.IO) { socket.send(encoded) }
        }
        if (!sent) {
            handleSocketEnded(socket, "send failed")
        }
        return sent
    }

    private suspend fun pullMailboxFromProvider(room: MixRoomRegistration) {
        if (providerBaseUrl.isBlank()) {
            emitLog(room.roomId, "provider URL missing; mailbox pull skipped")
            return
        }

        val requestBody = JSONObject()
            .put("roomId", room.roomAlias)
            .put("roomAccessProof", room.roomAccessProof)
            .put("providerId", room.providerId)
            .put("routeId", room.routeId)
            .put("mailboxId", room.mailboxId)
            .put("batchSize", MAILBOX_PULL_BATCH_SIZE)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url("${providerBaseUrl.trimEnd('/')}/pull")
            .post(requestBody)
            .build()

        val responseText = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("provider pull failed (${response.code})")
                }
                response.body?.string().orEmpty()
            }
        }

        if (responseText.isBlank()) return
        val json = JSONObject(responseText)
        Log.d(TAG, "Pulled provider batch for room ${room.roomId} mailbox=${room.mailboxId}")
        drainMailboxBatch(room.roomAlias, json.optJSONArray("frames"))
    }

    private suspend fun closeSocket(code: Int, reason: String) {
        reconnectJob?.cancel()
        reconnectStateJob?.cancel()
        trafficLoopJob?.cancel()
        val socket = stateMutex.withLock {
            connecting = false
            val current = webSocket
            webSocket = null
            current
        } ?: return
        withContext(Dispatchers.IO) {
            socket.close(code, reason)
            socket.cancel()
        }
    }

    private suspend fun handleSocketOpened(socket: WebSocket) {
        val roomsToJoin = stateMutex.withLock {
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectStateJob?.cancel()
            reconnectStateJob = null
            webSocket = socket
            connecting = false
            rooms.values.toList()
        }

        emitLog(null, "mix relay connected")
        sendHello()
        roomsToJoin.forEach { room ->
            sendJson(room.toJoinMessage())
        }
        startTrafficLoop()
    }

    private suspend fun handleSocketEnded(
        socket: WebSocket?,
        detail: String,
    ) {
        val roomIds: List<String>
        val shouldReconnect: Boolean
        val wasConnected: Boolean
        stateMutex.withLock {
            if (socket != null && webSocket != null && webSocket !== socket) {
                return
            }
            wasConnected = webSocket != null || connecting
            webSocket = null
            connecting = false
            roomIds = rooms.keys.toList()
            shouldReconnect = started && !suspended && roomIds.isNotEmpty()
        }

        trafficLoopJob?.cancel()
        if (!wasConnected) return

        emitLog(null, "mix relay disconnected: $detail")
        val detailLine = if (suspended) {
            "Mix relay paused"
        } else {
            "Mix relay reconnecting"
        }
        if (suspended) {
            roomIds.forEach { roomId ->
                _events.emit(
                    P2PEngineEvent.RoomStateChanged(
                        roomId = roomId,
                        connectionState = ConnectionState.Backgrounded,
                        detail = detailLine,
                    ),
                )
            }
        } else if (roomIds.isNotEmpty()) {
            scheduleReconnectStateUpdate(roomIds, detailLine)
        }

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnectStateUpdate(
        roomIds: List<String>,
        detail: String,
    ) {
        reconnectStateJob?.cancel()
        reconnectStateJob = scope.launch {
            delay(RECONNECT_STATE_GRACE_MILLIS)
            val shouldEmit = stateMutex.withLock { webSocket == null && !suspended && rooms.isNotEmpty() }
            if (!shouldEmit) return@launch
            roomIds.forEach { roomId ->
                _events.emit(
                    P2PEngineEvent.RoomStateChanged(
                        roomId = roomId,
                        connectionState = ConnectionState.Reconnecting,
                        detail = detail,
                    ),
                )
            }
        }
    }

    private suspend fun scheduleReconnect() {
        val delayMillis = stateMutex.withLock {
            if (reconnectJob?.isActive == true) return
            reconnectAttempt += 1
            val delayMillis = (850L * (1L shl minOf(reconnectAttempt - 1, 4))).coerceAtMost(12_000L)
            reconnectJob = scope.launch {
                delay(delayMillis)
                connectIfNeeded("retry-$reconnectAttempt")
            }
            delayMillis
        }
        emitLog(null, "retrying mix relay in ${delayMillis}ms")
    }

    private suspend fun handleIncomingMessage(text: String) {
        val json = JSONObject(text)
        when (json.optString("type")) {
            "welcome" -> {
                Log.d(TAG, "Gateway welcome: ${json.optString("detail", relayUrl)}")
                if (!engineReadyEmitted) {
                    engineReadyEmitted = true
                    _events.emit(
                        P2PEngineEvent.EngineReady(
                            runtime = "Mixnet Relay",
                            detail = json.optString("detail", relayUrl),
                            realTransportActive = true,
                        ),
                    )
                }
                emitLog(null, json.optString("detail", "mix relay session ready"))
            }

            "log" -> emitLog(json.optString("roomId").ifBlank { null }, json.optString("line", "mix relay log"))

            "room_state" -> {
                val localRoomId = resolveLocalRoomId(json.getString("roomId")) ?: return
                Log.d(TAG, "Room state for $localRoomId -> ${json.optString("state")}")
                _events.emit(
                    P2PEngineEvent.RoomStateChanged(
                        roomId = localRoomId,
                        connectionState = when (json.optString("state")) {
                            "backgrounded" -> ConnectionState.Backgrounded
                            "expired" -> ConnectionState.Expired
                            "reconnecting" -> ConnectionState.Reconnecting
                            else -> ConnectionState.Connected
                        },
                        participantCount = json.optInt("participantCount", -1).takeIf { it >= 0 },
                        detail = json.optString("detail").ifBlank { null },
                    ),
                )
            }

            "peer_presence" -> {
                val localRoomId = resolveLocalRoomId(json.getString("roomId")) ?: return
                Log.d(TAG, "Peer presence for $localRoomId -> ${json.getString("transportPublicKeyHex")} ${json.optString("presence")}")
                _events.emit(
                    P2PEngineEvent.PeerConnectionChanged(
                        roomId = localRoomId,
                        transportPublicKeyHex = json.getString("transportPublicKeyHex"),
                        isConnected = json.optString("presence") == "joined",
                    ),
                )
            }

            "error" -> emitLog(json.optString("roomId").ifBlank { null }, "mix relay error: ${json.optString("detail", "unknown")}")
        }
    }

    private suspend fun drainMailboxBatch(relayRoomId: String, frames: JSONArray?) {
        if (frames == null) return
        val localRoomId = resolveLocalRoomId(relayRoomId)
        repeat(frames.length()) { index ->
            val frame = frames.optString(index)
            if (frame.isBlank()) return@repeat
            val packet = decodeFixedPacket(frame) ?: return@repeat
            when (packet.optString("type")) {
                "cover" -> Unit
                "peer_card" -> {
                    val packetRoomId = resolveLocalRoomId(packet.getString("roomId")) ?: localRoomId ?: return@repeat
                    Log.d(TAG, "Peer card received for $packetRoomId")
                    _events.emit(
                        P2PEngineEvent.PeerCardReceived(
                            roomId = packetRoomId,
                            encodedPeerCard = packet.getString("encodedPeerCard"),
                            transportPublicKeyHex = packet.getString("transportPublicKeyHex"),
                        ),
                    )
                }

                "peer_presence" -> {
                    val packetRoomId = resolveLocalRoomId(packet.getString("roomId")) ?: localRoomId ?: return@repeat
                    Log.d(TAG, "Peer presence packet for $packetRoomId -> ${packet.getString("transportPublicKeyHex")} ${packet.optString("presence")}")
                    _events.emit(
                        P2PEngineEvent.PeerConnectionChanged(
                            roomId = packetRoomId,
                            transportPublicKeyHex = packet.getString("transportPublicKeyHex"),
                            isConnected = packet.optString("presence") == "joined",
                        ),
                    )
                }

                "envelope" -> {
                    val packetRoomId = resolveLocalRoomId(packet.getString("roomId")) ?: localRoomId ?: return@repeat
                    Log.d(TAG, "Envelope received for $packetRoomId")
                    _events.emit(
                        P2PEngineEvent.IncomingEnvelope(
                            roomId = packetRoomId,
                            encodedEnvelope = packet.getString("encodedEnvelope"),
                        ),
                    )
                }

                "delivery_ack" -> {
                    val packetRoomId = resolveLocalRoomId(packet.getString("roomId")) ?: localRoomId ?: return@repeat
                    Log.d(TAG, "Delivery ack received for $packetRoomId")
                    _events.emit(
                        P2PEngineEvent.DeliveryAckReceived(
                            roomId = packetRoomId,
                            encodedAck = packet.getString("encodedAck"),
                        ),
                    )
                }

                else -> emitLog(localRoomId, "ignored unknown mix packet ${packet.optString("type")}")
            }
        }
    }

    private suspend fun resolveLocalRoomId(roomAlias: String): String? = stateMutex.withLock {
        rooms.values.firstOrNull { it.roomAlias == roomAlias }?.roomId
    }

    private suspend fun emitLog(roomId: String?, line: String) {
        _events.emit(P2PEngineEvent.LogLine(roomId = roomId, line = line))
    }

    private inner class MixRelaySocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                handleSocketOpened(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                handleIncomingMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                handleSocketEnded(webSocket, "closed ($code): $reason")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val detail = response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "unknown failure"
            scope.launch {
                handleSocketEnded(webSocket, detail)
            }
        }
    }

    private data class MixRoomRegistration(
        val roomId: String,
        val roomAlias: String,
        val roomAccessProof: String,
        val providerId: String,
        val ingressGatewayId: String,
        val routeId: String,
        val mixHopIds: List<String>,
        val transportPublicKeyHex: String,
        val mailboxId: String,
        val localPeerCard: String,
        val role: String,
    ) {
        fun toJoinMessage(): JSONObject = JSONObject()
            .put("type", "join_room")
            .put("roomId", roomAlias)
            .put("roomAccessProof", roomAccessProof)
            .put("role", role)
            .put("providerId", providerId)
            .put("ingressGatewayId", ingressGatewayId)
            .put("routeId", routeId)
            .put("mixHopIds", JSONArray(mixHopIds))
            .put("mailboxId", mailboxId)
            .put("transportPublicKeyHex", transportPublicKeyHex)
            .put("encodedPeerCard", localPeerCard)
    }

    private data class QueuedMixPacket(
        val roomId: String,
        val packet: String,
        val isCover: Boolean,
    )

    private companion object {
        const val TAG = "MixnetRelayEngine"
        const val MAX_PENDING_PACKETS = 512
        const val MAX_PACKETS_PER_CYCLE = 12
        const val FIXED_PACKET_CHARS = 6144
        const val MIX_LOOP_BASE_INTERVAL_MILLIS = 1200L
        const val MIX_LOOP_JITTER_MILLIS = 650L
        const val MAILBOX_PULL_BATCH_SIZE = 12
        const val RECONNECT_STATE_GRACE_MILLIS = 1400L
        const val PADDING_CHAR = '~'
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun deriveTransportPublicKeyHex(transportSeedHex: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(transportSeedHex.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun encodeFixedPacket(packet: JSONObject): String {
            val raw = packet.toString().toByteArray(StandardCharsets.UTF_8)
            val encoded = Base64.encodeToString(raw, Base64.NO_WRAP or Base64.URL_SAFE)
            require(encoded.length <= FIXED_PACKET_CHARS) {
                "Mix packet exceeds fixed frame budget (${encoded.length} > $FIXED_PACKET_CHARS)"
            }
            return encoded.padEnd(FIXED_PACKET_CHARS, PADDING_CHAR)
        }

        fun decodeFixedPacket(packet: String): JSONObject? {
            val trimmed = packet.trimEnd(PADDING_CHAR)
            if (trimmed.isBlank()) return null
            val decoded = Base64.decode(trimmed, Base64.NO_WRAP or Base64.URL_SAFE)
            return JSONObject(String(decoded, StandardCharsets.UTF_8))
        }

        fun coverPacket(): String = encodeFixedPacket(
            JSONObject()
                .put("type", "cover")
                .put("packetId", UUID.randomUUID().toString()),
        )

        fun fallbackRouteHints(
            topicHex: String,
            transportSeedHex: String,
        ): MixnetInviteRouteHints = MixnetInviteRouteHints(
            roomAlias = MessageDigest.getInstance("SHA-256")
                .digest("mix.relay.room:$topicHex".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32),
            roomAccessProof = MessageDigest.getInstance("SHA-256")
                .digest("mix.relay.access:$topicHex:$transportSeedHex".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            providerId = "provider-alpha",
            ingressGatewayId = "entry-gateway-a",
            routeId = MessageDigest.getInstance("SHA-256")
                .digest("mix.route:$topicHex:$transportSeedHex".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(20),
            mixHopIds = listOf("mix-a", "mix-b", "mix-c"),
        )
    }
}
