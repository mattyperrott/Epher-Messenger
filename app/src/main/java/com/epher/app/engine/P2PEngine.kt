package com.epher.app.engine

import kotlinx.coroutines.flow.Flow

interface P2PEngine {
    val events: Flow<P2PEngineEvent>

    suspend fun start()
    suspend fun createRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        mixnetRouteHintsJson: String? = null,
    )
    suspend fun joinRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        inviteToken: String,
        mixnetRouteHintsJson: String? = null,
    )
    suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String)
    suspend fun sendDeliveryAck(roomId: String, encodedAck: String)
    suspend fun updateKnownPeerTransportKeys(roomId: String, peerTransportPublicKeysHex: List<String>)
    suspend fun leaveRoom(roomId: String)
    suspend fun suspendNetworking()
    suspend fun resumeNetworking()
}
