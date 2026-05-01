package com.epher.app.debug

import android.content.res.AssetManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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

class BareTransportProbeSession(
    private val assets: AssetManager,
    private val bootstrapNodes: List<String> = emptyList(),
    private val bootstrapRelayPublicKeys: List<String> = emptyList(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: Flow<String> = _logs.asSharedFlow()

    private val startMutex = Mutex()
    private val pushMutex = Mutex()

    @Volatile private var worklet: Worklet? = null
    @Volatile private var ipc: IPC? = null
    @Volatile private var workletThread: HandlerThread? = null
    @Volatile private var workletHandler: Handler? = null
    private var eventPumpJob: Job? = null

    suspend fun startProbe(topicHex: String, transportSeedHex: String) {
        ensureStarted()
        pushCommand(
            JSONObject()
                .put("type", "start_probe")
                .put("topicHex", topicHex)
                .put("transportSeedHex", transportSeedHex)
                .put("bootstrap", JSONArray(bootstrapNodes))
                .put("bootstrapRelays", JSONArray(bootstrapRelayPublicKeys)),
        )
    }

    suspend fun startDirectProbe(
        role: String,
        transportSeedHex: String,
        directSeedHex: String,
        remoteSeedHex: String,
        localConnection: Boolean,
    ) {
        ensureStarted()
        pushCommand(
            JSONObject()
                .put("type", "start_direct_probe")
                .put("role", role)
                .put("transportSeedHex", transportSeedHex)
                .put("directSeedHex", directSeedHex)
                .put("remoteSeedHex", remoteSeedHex)
                .put("localConnection", localConnection)
                .put("bootstrap", JSONArray(bootstrapNodes))
                .put("bootstrapRelays", JSONArray(bootstrapRelayPublicKeys)),
        )
    }

    suspend fun startLoopbackProbe(
        transportSeedHex: String,
        loopbackServerSeedHex: String,
        loopbackClientSeedHex: String,
    ) {
        ensureStarted()
        pushCommand(
            JSONObject()
                .put("type", "start_loopback_probe")
                .put("transportSeedHex", transportSeedHex)
                .put("loopbackServerSeedHex", loopbackServerSeedHex)
                .put("loopbackClientSeedHex", loopbackClientSeedHex)
                .put("bootstrap", JSONArray(bootstrapNodes))
                .put("bootstrapRelays", JSONArray(bootstrapRelayPublicKeys)),
        )
    }

    suspend fun suspendNetworking() {
        if (worklet == null) return
        pushCommand(JSONObject().put("type", "suspend_networking"))
    }

    suspend fun resumeNetworking() {
        ensureStarted()
        pushCommand(JSONObject().put("type", "resume_networking"))
    }

    fun cleanup() {
        eventPumpJob?.cancel()
        eventPumpJob = null

        val currentWorklet = worklet
        worklet = null
        ipc = null

        runCatching {
            currentWorklet?.terminate()
        }.onFailure {
            Log.w(TAG, "Probe worklet terminate failed", it)
        }

        workletThread?.quitSafely()
        workletThread = null
        workletHandler = null
    }

    private suspend fun ensureStarted() {
        if (worklet != null) return

        startMutex.withLock {
            if (worklet != null) return

            val bundleBytes = withContext(Dispatchers.IO) {
                assets.open(RUNTIME_ASSET).use { it.readBytes() }
            }

            val thread = ensureWorkletThread()
            val handler = checkNotNull(workletHandler) { "Probe worklet handler missing" }
            val started = suspendCancellableCoroutine<Pair<Worklet, IPC>> { continuation ->
                handler.post {
                    try {
                        val options = Worklet.Options().memoryLimit(WORKLET_MEMORY_LIMIT_BYTES)
                        val createdWorklet = Worklet(options)
                        createdWorklet.start("/$RUNTIME_ASSET", ByteBuffer.wrap(bundleBytes), emptyArray())
                        val channel = IPC(createdWorklet)
                        if (continuation.isActive) continuation.resume(createdWorklet to channel)
                    } catch (t: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(t)
                    }
                }
            }

            worklet = started.first
            ipc = started.second
            workletThread = thread
            startRuntimeEventPump(started.first)
        }
    }

    private suspend fun pushCommand(command: JSONObject): JSONObject {
        ensureStarted()
        val currentIpc = checkNotNull(ipc) { "Probe IPC missing" }

        return pushMutex.withLock {
            writeFully(currentIpc, command.toString() + "\n")
            val responseText = readLine(currentIpc)
            val response = if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            if (!response.optBoolean("ok", true)) {
                throw IllegalStateException(response.optString("error", "unknown error"))
            }
            response
        }
    }

    private suspend fun writeFully(ipc: IPC, payload: String) {
        val buffer = ByteBuffer.wrap(payload.toByteArray(StandardCharsets.UTF_8))
        val handler = checkNotNull(workletHandler) { "Probe worklet handler missing" }
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
            if (newlineIndex >= 0) return lineBuffer.substring(0, newlineIndex)
        }
    }

    private suspend fun readChunk(ipc: IPC): String {
        val handler = checkNotNull(workletHandler) { "Probe worklet handler missing" }
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

        val createdThread = HandlerThread("EpherBareProbeWorklet").also { it.start() }
        workletThread = createdThread
        workletHandler = Handler(createdThread.looper)
        return createdThread
    }

    private fun startRuntimeEventPump(startedWorklet: Worklet) {
        if (eventPumpJob?.isActive == true) return

        eventPumpJob = scope.launch {
            delay(INITIAL_EVENT_DRAIN_DELAY_MILLIS)
            while (true) {
                if (worklet !== startedWorklet) return@launch

                try {
                    val response = pushCommand(JSONObject().put("type", "drain_events"))
                    val eventsArray = response.optJSONArray("events") ?: continue
                    for (i in 0 until eventsArray.length()) {
                        val event = eventsArray.optJSONObject(i) ?: continue
                        when (event.optString("type")) {
                            "runtime.ready" -> emitProbeLog("runtime.ready ${event.optString("detail")}")
                            "log" -> emitProbeLog(event.optString("line"))
                        }
                    }
                } catch (t: Throwable) {
                    emitProbeLog("probe event pump error: ${t.message ?: "unknown"}")
                    delay(EVENT_DRAIN_RETRY_MILLIS)
                    continue
                }

                delay(EVENT_DRAIN_INTERVAL_MILLIS)
            }
        }
    }

    private suspend fun emitProbeLog(line: String) {
        Log.d(TAG, line)
        _logs.emit(line)
    }

    private companion object {
        const val TAG = "BareTransportProbe"
        const val RUNTIME_ASSET = "p2p.probe.runtime.bundle"
        const val WORKLET_MEMORY_LIMIT_BYTES = 64 * 1024 * 1024
        const val INITIAL_EVENT_DRAIN_DELAY_MILLIS = 750L
        const val EVENT_DRAIN_INTERVAL_MILLIS = 250L
        const val EVENT_DRAIN_RETRY_MILLIS = 1_000L
    }
}
