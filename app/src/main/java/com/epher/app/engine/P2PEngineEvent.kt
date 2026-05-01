package com.epher.app.engine

import com.epher.app.data.model.ConnectionState

sealed interface P2PEngineEvent {
    data class EngineReady(
        val runtime: String,
        val detail: String,
        val realTransportActive: Boolean,
    ) : P2PEngineEvent

    data class RoomStateChanged(
        val roomId: String,
        val connectionState: ConnectionState,
        val participantCount: Int? = null,
        val detail: String? = null,
    ) : P2PEngineEvent

    data class LogLine(
        val roomId: String?,
        val line: String,
    ) : P2PEngineEvent

    data class IncomingEnvelope(
        val roomId: String,
        val encodedEnvelope: String,
    ) : P2PEngineEvent

    data class DeliveryAckReceived(
        val roomId: String,
        val encodedAck: String,
    ) : P2PEngineEvent

    data class PeerCardReceived(
        val roomId: String,
        val encodedPeerCard: String,
        val transportPublicKeyHex: String,
    ) : P2PEngineEvent

    data class PeerConnectionChanged(
        val roomId: String,
        val transportPublicKeyHex: String,
        val isConnected: Boolean,
    ) : P2PEngineEvent
}
