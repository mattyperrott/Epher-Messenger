package com.epher.app.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class FakeP2PEngine : P2PEngine {
    override val events: Flow<P2PEngineEvent> = emptyFlow()

    override suspend fun start() {
        delay(40)
    }

    override suspend fun createRoom(
        roomId: String,
        label: String,
        topicHex: String,
        transportSeedHex: String,
        localPeerCard: String,
        mixnetRouteHintsJson: String?,
    ) {
        delay(120)
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
        delay(120)
    }

    override suspend fun sendRoomMessage(roomId: String, encodedEnvelope: String) {
        delay(60)
    }

    override suspend fun sendDeliveryAck(roomId: String, encodedAck: String) {
        delay(20)
    }

    override suspend fun updateKnownPeerTransportKeys(roomId: String, peerTransportPublicKeysHex: List<String>) {
        delay(20)
    }

    override suspend fun leaveRoom(roomId: String) {
        delay(80)
    }

    override suspend fun suspendNetworking() {
        delay(20)
    }

    override suspend fun resumeNetworking() {
        delay(20)
    }
}
