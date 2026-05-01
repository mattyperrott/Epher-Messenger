package com.epher.app.engine

import android.util.Log
import com.epher.app.data.model.ConnectionState
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class RelayWebSocketEngine(
    private val relayUrl: String,
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
        extraBufferCapacity = 128,
    )
    override val events: Flow<P2PEngineEvent> = _events.asSharedFlow()

    private val rooms = linkedMapOf<String, RelayRoomRegistration>()
    private val pendingEnvelopes = ArrayDeque<QueuedEnvelope>()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
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
        registerRoom(
            RelayRoomRegistration(
                roomId = roomId,
                label = label,
                topicHex = topicHex,
                transportPublicKeyHex = deriveTransportPublicKeyHex(transportSeedHex),
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
        registerRoom(
            RelayRoomRegistration(
                roomId = roomId,
                label = label,
                topicHex = topicHex,
                transportPublicKeyHex = deriveTransportPublicKeyHex(transportSeedHex),
                localPeerCard = localPeerCard,
                role = "member",
            ),
        )
    }

    override suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String) {
        ensureStarted()
        val immediateSend = stateMutex.withLock {
            if (webSocket == null) {
                pendingEnvelopes.addLast(QueuedEnvelope(roomId = roomId, encodedEnvelope = encodedEnvelope))
                while (pendingEnvelopes.size > MAX_PENDING_ENVELOPES) {
                    pendingEnvelopes.removeFirst()
                }
                false
            } else {
                true
            }
        }

        if (!immediateSend) {
            Log.d(TAG, "Queued encrypted envelope for room $roomId while relay is offline")
            emitLog(roomId, "relay offline; queued encrypted envelope for retry")
            return
        }

        sendJson(
            JSONObject()
                .put("type", "envelope")
                .put("roomId", roomId)
                .put("encodedEnvelope", encodedEnvelope),
        )
    }

    override suspend fun sendDeliveryAck(roomId: String, encodedAck: String) {
        ensureStarted()
        sendJson(
            JSONObject()
                .put("type", "delivery_ack")
                .put("roomId", roomId)
                .put("encodedAck", encodedAck),
        )
    }

    override suspend fun updateKnownPeerTransportKeys(roomId: String, peerTransportPublicKeysHex: List<String>) {
        if (peerTransportPublicKeysHex.isNotEmpty()) {
            Log.d(TAG, "Known peer transport keys updated for $roomId (${peerTransportPublicKeysHex.size})")
            emitLog(roomId, "known peer transport keys refreshed (${peerTransportPublicKeysHex.size})")
        }
    }

    override suspend fun leaveRoom(roomId: String) {
        val shouldSend = stateMutex.withLock {
            rooms.remove(roomId)
            pendingEnvelopes.removeAll { it.roomId == roomId }
            webSocket != null
        }

        if (shouldSend) {
            sendJson(
                JSONObject()
                    .put("type", "leave_room")
                    .put("roomId", roomId),
            )
        }
    }

    override suspend fun suspendNetworking() {
        suspended = true
        val roomIds = stateMutex.withLock { rooms.keys.toList() }
        closeSocket(1000, "app suspended")
        roomIds.forEach { roomId ->
            _events.emit(
                P2PEngineEvent.RoomStateChanged(
                    roomId = roomId,
                    connectionState = ConnectionState.Backgrounded,
                    detail = "Room relay paused while app is backgrounded",
                ),
            )
        }
    }

    override suspend fun resumeNetworking() {
        suspended = false
        ensureStarted()
        connectIfNeeded("resume")
    }

    private suspend fun registerRoom(room: RelayRoomRegistration) {
        ensureStarted()
        stateMutex.withLock {
            rooms[room.roomId] = room
        }
        _events.emit(
            P2PEngineEvent.RoomStateChanged(
                roomId = room.roomId,
                connectionState = ConnectionState.Reconnecting,
                detail = "Waiting for room relay connection",
            ),
        )
        emitLog(room.roomId, "room relay registration prepared for ${room.role}")
        Log.d(TAG, "Registered room ${room.roomId} for ${room.role} via $relayUrl")
        sendJoinIfConnected(room.roomId)
    }

    private suspend fun ensureStarted() {
        if (started) return
        startMutex.withLock {
            if (started) return
            started = true
            Log.d(TAG, "Starting room relay engine for $relayUrl")
            emitLog(null, "room relay enabled at $relayUrl")
            connectIfNeeded("startup")
        }
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

        Log.d(TAG, "Connecting to room relay ($reason) at $relayUrl")
        emitLog(null, "connecting to room relay ($reason)")
        val request = Request.Builder()
            .url(relayUrl)
            .build()
        withContext(Dispatchers.IO) {
            client.newWebSocket(request, RelaySocketListener())
        }
    }

    private suspend fun sendJoinIfConnected(roomId: String) {
        val room = stateMutex.withLock { rooms[roomId] } ?: return
        if (stateMutex.withLock { webSocket == null }) return
        Log.d(TAG, "Sending join request for room ${room.roomId}")
        sendHello()
        sendJson(room.toJoinMessage())
    }

    private suspend fun sendHello() {
        sendJson(
            JSONObject()
                .put("type", "hello")
                .put("clientId", clientId)
                .put("platform", "android")
                .put("transport", "room-relay"),
        )
    }

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

    private suspend fun closeSocket(code: Int, reason: String) {
        reconnectJob?.cancel()
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
            webSocket = socket
            connecting = false
            rooms.values.toList()
        }

        emitLog(null, "room relay connected")
        Log.d(TAG, "Room relay socket opened")
        sendHello()
        roomsToJoin.forEach { room ->
            Log.d(TAG, "Rejoining room ${room.roomId}")
            sendJson(room.toJoinMessage())
        }
        flushPendingEnvelopes()
    }

    private suspend fun flushPendingEnvelopes() {
        val queued = stateMutex.withLock {
            if (webSocket == null || pendingEnvelopes.isEmpty()) {
                emptyList()
            } else {
                buildList {
                    while (pendingEnvelopes.isNotEmpty()) {
                        add(pendingEnvelopes.removeFirst())
                    }
                }
            }
        }
        queued.forEach { envelope ->
            val sent = sendJson(
                JSONObject()
                    .put("type", "envelope")
                    .put("roomId", envelope.roomId)
                    .put("encodedEnvelope", envelope.encodedEnvelope),
            )
            if (!sent) {
                stateMutex.withLock {
                    pendingEnvelopes.addFirst(envelope)
                }
                return
            }
        }
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

        if (!wasConnected) return

        Log.d(TAG, "Room relay disconnected: $detail")
        emitLog(null, "room relay disconnected: $detail")
        val state = if (suspended) ConnectionState.Backgrounded else ConnectionState.Reconnecting
        val detailLine = if (suspended) {
            "Room relay paused"
        } else {
            "Room relay reconnecting"
        }
        roomIds.forEach { roomId ->
            _events.emit(
                P2PEngineEvent.RoomStateChanged(
                    roomId = roomId,
                    connectionState = state,
                    detail = detailLine,
                ),
            )
        }

        if (shouldReconnect) {
            scheduleReconnect()
        }
    }

    private suspend fun scheduleReconnect() {
        val delayMillis = stateMutex.withLock {
            if (reconnectJob?.isActive == true) return
            reconnectAttempt += 1
            val delayMillis = (750L * (1L shl minOf(reconnectAttempt - 1, 4))).coerceAtMost(12_000L)
            reconnectJob = scope.launch {
                kotlinx.coroutines.delay(delayMillis)
                connectIfNeeded("retry-$reconnectAttempt")
            }
            delayMillis
        }
        emitLog(null, "retrying room relay in ${delayMillis}ms")
        Log.d(TAG, "Retrying room relay in ${delayMillis}ms")
    }

    private suspend fun handleIncomingMessage(text: String) {
        val json = JSONObject(text)
        Log.d(TAG, "Incoming relay message: ${json.optString("type")}")
        when (json.optString("type")) {
            "welcome" -> {
                if (!engineReadyEmitted) {
                    engineReadyEmitted = true
                    _events.emit(
                        P2PEngineEvent.EngineReady(
                            runtime = "Room Relay",
                            detail = json.optString("detail", relayUrl),
                            realTransportActive = true,
                        ),
                    )
                }
                emitLog(null, json.optString("detail", "room relay session ready"))
            }

            "log" -> emitLog(json.optString("roomId").ifBlank { null }, json.optString("line", "relay log"))

            "room_state" -> {
                _events.emit(
                    P2PEngineEvent.RoomStateChanged(
                        roomId = json.getString("roomId"),
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

            "peer_card" -> {
                _events.emit(
                    P2PEngineEvent.PeerCardReceived(
                        roomId = json.getString("roomId"),
                        encodedPeerCard = json.getString("encodedPeerCard"),
                        transportPublicKeyHex = json.getString("transportPublicKeyHex"),
                    ),
                )
            }

            "envelope" -> {
                _events.emit(
                    P2PEngineEvent.IncomingEnvelope(
                        roomId = json.getString("roomId"),
                        encodedEnvelope = json.getString("encodedEnvelope"),
                    ),
                )
            }

            "delivery_ack" -> {
                _events.emit(
                    P2PEngineEvent.DeliveryAckReceived(
                        roomId = json.getString("roomId"),
                        encodedAck = json.getString("encodedAck"),
                    ),
                )
            }

            "error" -> emitLog(json.optString("roomId").ifBlank { null }, "relay error: ${json.optString("detail", "unknown")}")
        }
    }

    private suspend fun emitLog(roomId: String?, line: String) {
        _events.emit(P2PEngineEvent.LogLine(roomId = roomId, line = line))
    }

    private inner class RelaySocketListener : WebSocketListener() {
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

    private data class RelayRoomRegistration(
        val roomId: String,
        val label: String,
        val topicHex: String,
        val transportPublicKeyHex: String,
        val localPeerCard: String,
        val role: String,
    ) {
        fun toJoinMessage(): JSONObject = JSONObject()
            .put("type", "join_room")
            .put("roomId", roomId)
            .put("label", label)
            .put("topicHex", topicHex)
            .put("role", role)
            .put("transportPublicKeyHex", transportPublicKeyHex)
            .put("encodedPeerCard", localPeerCard)
    }

    private data class QueuedEnvelope(
        val roomId: String,
        val encodedEnvelope: String,
    )

    private companion object {
        const val MAX_PENDING_ENVELOPES = 128
        const val TAG = "RelayWebSocketEngine"

        fun deriveTransportPublicKeyHex(transportSeedHex: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(transportSeedHex.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
