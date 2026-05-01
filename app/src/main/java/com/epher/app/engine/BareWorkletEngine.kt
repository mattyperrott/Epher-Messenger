package com.epher.app.engine

import android.content.res.AssetManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.epher.app.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * P2PEngine implementation backed by BareKit + Hyperswarm.
 *
 * The Bare worklet runs [RUNTIME_ASSET] (a bare-pack bundle of p2p-runtime.js).
 * Commands are pushed synchronously via [Worklet.push] and events are drained
 * on a polling loop via the `drain_events` command.
 *
 * This class is reconstructed from the compiled bytecode of the version that
 * was previously deleted from source control.
 */
class BareWorkletEngine(
    private val assets: AssetManager,
    private val bootstrapNodes: List<String> = emptyList(),
    private val bootstrapRelayPublicKeys: List<String> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : P2PEngine {

    private val _events = MutableSharedFlow<P2PEngineEvent>(extraBufferCapacity = 64)
    override val events: Flow<P2PEngineEvent> = _events.asSharedFlow()

    private val startMutex = Mutex()
    private val pushMutex = Mutex()

    @Volatile private var worklet: Worklet? = null
    @Volatile private var ipc: IPC? = null
    @Volatile private var workletThread: HandlerThread? = null
    @Volatile private var workletHandler: Handler? = null
    private var eventPumpJob: Job? = null

    @Volatile private var runtimeSuspended = false
    private var consecutiveFailures = 0
    private var lastFailureTime = 0L

    // -------------------------------------------------------------------------
    // P2PEngine implementation
    // -------------------------------------------------------------------------

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
        pushCommand(
            JSONObject()
                .put("type", "create_room")
                .put("roomId", roomId)
                .put("label", label)
                .put("topicHex", topicHex)
                .put("transportSeedHex", transportSeedHex)
                .put("localPeerCard", localPeerCard)
                .put("bootstrap", JSONArray(bootstrapNodes))
                .put("bootstrapRelays", JSONArray(bootstrapRelayPublicKeys)),
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
        pushCommand(
            JSONObject()
                .put("type", "join_room")
                .put("roomId", roomId)
                .put("label", label)
                .put("topicHex", topicHex)
                .put("transportSeedHex", transportSeedHex)
                .put("localPeerCard", localPeerCard)
                // invite token is NOT forwarded to the transport layer —
                // it is a local app-level credential only
                .put("bootstrap", JSONArray(bootstrapNodes))
                .put("bootstrapRelays", JSONArray(bootstrapRelayPublicKeys)),
        )
    }

    override suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String) {
        pushCommand(
            JSONObject()
                .put("type", "send_room_message")
                .put("roomId", roomId)
                .put("encodedEnvelope", encodedEnvelope),
        )
    }

    override suspend fun sendDeliveryAck(roomId: String, encodedAck: String) {
        pushCommand(
            JSONObject()
                .put("type", "send_delivery_ack")
                .put("roomId", roomId)
                .put("encodedAck", encodedAck),
        )
    }

    override suspend fun updateKnownPeerTransportKeys(
        roomId: String,
        peerTransportPublicKeysHex: List<String>,
    ) {
        pushCommand(
            JSONObject()
                .put("type", "set_known_peer_transport_keys")
                .put("roomId", roomId)
                .put("peerTransportPublicKeys", JSONArray(peerTransportPublicKeysHex)),
        )
    }

    override suspend fun leaveRoom(roomId: String) {
        pushCommand(
            JSONObject()
                .put("type", "leave_room")
                .put("roomId", roomId),
        )
    }

    override suspend fun suspendNetworking() {
        pushCommand(JSONObject().put("type", "suspend_networking"))
        runtimeSuspended = true
        withContext(Dispatchers.IO) {
            // Give the worklet a moment to process the suspend before the
            // Android process yields to the system.
            Thread.sleep(80)
        }
    }

    override suspend fun resumeNetworking() {
        ensureStarted()
        runtimeSuspended = false
        pushCommand(JSONObject().put("type", "resume_networking"))
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun ensureStarted() {
        if (worklet != null) return

        startMutex.withLock {
            if (worklet != null) return

            val bundleBytes = withContext(Dispatchers.IO) {
                assets.open(RUNTIME_ASSET).use { it.readBytes() }
            }

            val thread = ensureWorkletThread()
            val handler = checkNotNull(workletHandler) { "Bare worklet handler missing" }
            val started = suspendCancellableCoroutine<Pair<Worklet, IPC>> { continuation ->
                handler.post {
                    try {
                        val options = Worklet.Options().memoryLimit(WORKLET_MEMORY_LIMIT_BYTES)
                        val w = Worklet(options)
                        w.start("/$RUNTIME_ASSET", ByteBuffer.wrap(bundleBytes), emptyArray())
                        val channel = IPC(w)
                        if (continuation.isActive) continuation.resume(w to channel)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }

            worklet = started.first
            ipc = started.second
            workletThread = thread
            runtimeSuspended = false
            startRuntimeEventPump(started.first)
        }
    }

    private suspend fun pushCommand(command: JSONObject): JSONObject {
        ensureStarted()
        return pushCommandInternal(command)
    }

    private suspend fun pushCommandInternal(command: JSONObject): JSONObject {
        val currentIpc = checkNotNull(ipc) { "Bare IPC missing" }

        return pushMutex.withLock {
            writeFully(currentIpc, command.toString() + "\n")
            val responseText = readLine(currentIpc)
            val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (!response.optBoolean("ok", true)) {
                val error = response.optString("error", "unknown error")
                Log.e(TAG, "Bare runtime command failed: $error")
                throw IllegalStateException(error)
            }
            response
        }
    }

    private suspend fun writeFully(ipc: IPC, payload: String) {
        val buffer = ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8))
        val handler = checkNotNull(workletHandler) { "Bare worklet handler missing" }
        suspendCancellableCoroutine<Unit> { continuation ->
            handler.post {
                ipc.write(buffer) { exception ->
                    if (!continuation.isActive) return@write
                    if (exception != null) continuation.resumeWithException(exception)
                    else continuation.resume(Unit)
                }
            }
        }
    }

    private suspend fun readLine(ipc: IPC): String {
        val lineBuffer = StringBuilder()
        while (true) {
            val chunk = readChunk(ipc)
            if (chunk.isEmpty()) continue
            lineBuffer.append(chunk)
            val newlineIndex = lineBuffer.indexOf("\n")
            if (newlineIndex >= 0) {
                return lineBuffer.substring(0, newlineIndex)
            }
        }
    }

    private suspend fun readChunk(ipc: IPC): String {
        val handler = checkNotNull(workletHandler) { "Bare worklet handler missing" }
        return suspendCancellableCoroutine { continuation ->
            handler.post {
                ipc.read { buffer, exception ->
                    if (!continuation.isActive) return@read
                    if (exception != null) {
                        continuation.resumeWithException(exception)
                        return@read
                    }

                    if (buffer == null) {
                        continuation.resume("")
                        return@read
                    }

                    try {
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        continuation.resume(String(bytes, StandardCharsets.UTF_8))
                    } catch (t: Throwable) {
                        continuation.resumeWithException(t)
                    }
                }
            }
        }
    }

    private fun ensureWorkletThread(): HandlerThread {
        val existingThread = workletThread
        val existingHandler = workletHandler
        if (existingThread != null && existingHandler != null) return existingThread

        val createdThread = HandlerThread("EpherBareWorklet").also { it.start() }
        workletThread = createdThread
        workletHandler = Handler(createdThread.looper)
        return createdThread
    }

    private fun startRuntimeEventPump(startedWorklet: Worklet) {
        if (eventPumpJob?.isActive == true) return

        eventPumpJob = scope.launch {
            consecutiveFailures = 0
            delay(INITIAL_EVENT_DRAIN_DELAY_MILLIS)
            while (true) {
                if (runtimeSuspended) {
                    delay(EVENT_DRAIN_RETRY_MILLIS)
                    continue
                }

                // Stop pumping if the worklet has been replaced (e.g. after a future restart)
                if (worklet !== startedWorklet) return@launch

                try {
                    val response = pushCommandInternal(JSONObject().put("type", "drain_events"))
                    val eventsArray = response.optJSONArray("events")
                    drainRuntimeEvents(eventsArray)
                    // Reset failure counter on successful drain
                    consecutiveFailures = 0
                } catch (e: Throwable) {
                    consecutiveFailures += 1
                    lastFailureTime = System.currentTimeMillis()
                    Log.e(TAG, "Runtime event drain failed (attempt $consecutiveFailures)", e)
                    emitLog(null, "runtime drain error: ${e.message ?: "unknown"}")
                    
                    // Detect persistent failures: if 5+ consecutive failures within 30 seconds,
                    // emit a critical error event instead of silently continuing
                    if (consecutiveFailures >= 5 && System.currentTimeMillis() - lastFailureTime < 30_000L) {
                        Log.e(TAG, "Runtime appears non-recoverable after $consecutiveFailures failures")
                        _events.emit(
                            P2PEngineEvent.LogLine(
                                roomId = null,
                                line = "CRITICAL: Runtime event pump failed 5+ times, runtime may be stuck"
                            )
                        )
                    }
                    
                    delay(EVENT_DRAIN_RETRY_MILLIS)
                    continue
                }

                delay(EVENT_DRAIN_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun drainRuntimeEvents(eventsArray: JSONArray?) {
        if (eventsArray == null) return
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.optJSONObject(i) ?: continue
            handleRuntimeEvent(event)
        }
    }

    private suspend fun handleRuntimeEvent(event: JSONObject) {
        Log.d(TAG, "runtime event: ${event.optString("type")}")
        when (event.getString("type")) {
            "runtime.ready" -> {
                _events.emit(
                    P2PEngineEvent.EngineReady(
                        runtime = event.optString("runtime", "Bare Worklet"),
                        detail = event.optString("detail"),
                        realTransportActive = event.optBoolean("realTransportActive", false),
                    ),
                )
            }

            "log" -> {
                val roomId = event.optString("roomId").takeUnless { it.isBlank() }
                emitLog(roomId, event.getString("line"))
            }

            "room.state" -> {
                Log.d(TAG, "room ${event.getString("roomId")} state=${event.getString("state")} detail=${event.optString("detail")}")
                val connectionState = when (event.getString("state")) {
                    "connected" -> ConnectionState.Connected
                    "backgrounded" -> ConnectionState.Backgrounded
                    "expired" -> ConnectionState.Expired
                    else -> ConnectionState.Reconnecting
                }
                val participantCount = event.optInt("participantCount").takeIf { it > 0 }
                _events.emit(
                    P2PEngineEvent.RoomStateChanged(
                        roomId = event.getString("roomId"),
                        connectionState = connectionState,
                        participantCount = participantCount,
                        detail = event.optString("detail").takeUnless { it.isBlank() },
                    ),
                )
            }

            "room.envelope" -> {
                _events.emit(
                    P2PEngineEvent.IncomingEnvelope(
                        roomId = event.getString("roomId"),
                        encodedEnvelope = event.getString("encodedEnvelope"),
                    ),
                )
            }

            "room.delivery_ack" -> {
                _events.emit(
                    P2PEngineEvent.DeliveryAckReceived(
                        roomId = event.getString("roomId"),
                        encodedAck = event.getString("encodedAck"),
                    ),
                )
            }

            "peer.card" -> {
                Log.d(TAG, "peer card rx room=${event.getString("roomId")} transport=${event.getString("transportPublicKeyHex").take(16)}")
                _events.emit(
                    P2PEngineEvent.PeerCardReceived(
                        roomId = event.getString("roomId"),
                        encodedPeerCard = event.getString("encodedPeerCard"),
                        transportPublicKeyHex = event.getString("transportPublicKeyHex"),
                    ),
                )
            }

            "peer.presence" -> {
                _events.emit(
                    P2PEngineEvent.PeerConnectionChanged(
                        roomId = event.getString("roomId"),
                        transportPublicKeyHex = event.getString("transportPublicKeyHex"),
                        isConnected = event.optString("presence") == "joined",
                    ),
                )
            }
        }
    }

    private suspend fun emitLog(roomId: String?, line: String) {
        Log.d(TAG, "[${roomId ?: "global"}] $line")
        _events.emit(P2PEngineEvent.LogLine(roomId = roomId, line = line))
    }

    private companion object {
        const val TAG = "BareWorkletEngine"
        const val RUNTIME_ASSET = "p2p.runtime.bundle"
        const val WORKLET_MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
        const val INITIAL_EVENT_DRAIN_DELAY_MILLIS = 750L
        const val EVENT_DRAIN_INTERVAL_MILLIS = 250L
        const val EVENT_DRAIN_RETRY_MILLIS = 1_000L
    }
}
