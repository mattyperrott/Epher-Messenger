package com.epher.app.engine

import com.epher.app.data.model.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class UnavailableTransportEngine(
    private val runtimeLabel: String,
    private val detail: String,
) : P2PEngine {
    private val _events = MutableSharedFlow<P2PEngineEvent>(extraBufferCapacity = 32)
    override val events: Flow<P2PEngineEvent> = _events.asSharedFlow()

    override suspend fun start() {
        _events.emit(
            P2PEngineEvent.EngineReady(
                runtime = runtimeLabel,
                detail = detail,
                realTransportActive = false,
            ),
        )
    }

    override suspend fun createRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        mixnetRouteHintsJson: String?,
    ) {
        emitUnavailableRoomState(roomId)
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
        emitUnavailableRoomState(roomId)
    }

    override suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String) {
        _events.emit(
            P2PEngineEvent.LogLine(
                roomId = roomId,
                line = "ENGINE >> transport unavailable; outgoing message kept queued locally",
            ),
        )
    }

    override suspend fun sendDeliveryAck(roomId: String, encodedAck: String) {
        _events.emit(
            P2PEngineEvent.LogLine(
                roomId = roomId,
                line = "ENGINE >> transport unavailable; delivery ack could not be sent",
            ),
        )
    }

    override suspend fun updateKnownPeerTransportKeys(roomId: String, peerTransportPublicKeysHex: List<String>) = Unit

    override suspend fun leaveRoom(roomId: String) = Unit

    override suspend fun suspendNetworking() = Unit

    override suspend fun resumeNetworking() = Unit

    private suspend fun emitUnavailableRoomState(roomId: String) {
        _events.emit(
            P2PEngineEvent.RoomStateChanged(
                roomId = roomId,
                connectionState = ConnectionState.Reconnecting,
                participantCount = 1,
                detail = detail,
            ),
        )
        _events.emit(
            P2PEngineEvent.LogLine(
                roomId = roomId,
                line = "ENGINE >> transport unavailable until a relay URL is configured",
            ),
        )
    }
}
