package com.epher.app.ui.components

import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary

enum class SessionIndicatorState {
    Inactive,
    Loading,
    Active,
}

data class SessionIndicator(
    val activeLabel: String,
    val loadingLabel: String,
    val state: SessionIndicatorState,
) {
    val label: String
        get() = when (state) {
            SessionIndicatorState.Loading -> loadingLabel
            else -> activeLabel
        }
}

data class SessionIndicators(
    val connected: SessionIndicator,
    val verified: SessionIndicator,
    val encrypted: SessionIndicator,
)

fun sessionIndicators(
    room: RoomSummary?,
    participants: List<Participant>,
): SessionIndicators {
    val localParticipantVerified = participants.any { participant ->
        participant.transportPublicKeyHex == null && participant.isVerified
    }
    val anyVerifiedPeer = participants.any { participant -> participant.isVerified }

    val connectedState = when (room?.connectionState) {
        ConnectionState.Connected -> SessionIndicatorState.Active
        ConnectionState.Connecting,
        ConnectionState.Reconnecting -> SessionIndicatorState.Loading
        ConnectionState.Backgrounded,
        ConnectionState.Expired,
        null -> SessionIndicatorState.Inactive
    }

    val verifiedState = when {
        room == null -> SessionIndicatorState.Inactive
        localParticipantVerified || anyVerifiedPeer -> SessionIndicatorState.Active
        else -> SessionIndicatorState.Loading
    }

    val encryptedState = when {
        room == null -> SessionIndicatorState.Inactive
        room.isEncryptedSessionEstablished -> SessionIndicatorState.Active
        room.connectionState == ConnectionState.Connecting ||
            room.connectionState == ConnectionState.Reconnecting -> {
            SessionIndicatorState.Loading
        }
        else -> SessionIndicatorState.Inactive
    }

    return SessionIndicators(
        connected = SessionIndicator(
            activeLabel = "Connected",
            loadingLabel = "Connecting",
            state = connectedState,
        ),
        verified = SessionIndicator(
            activeLabel = "Verified",
            loadingLabel = "Verifying",
            state = verifiedState,
        ),
        encrypted = SessionIndicator(
            activeLabel = "Encrypted",
            loadingLabel = "Encrypting",
            state = encryptedState,
        ),
    )
}

fun aggregateSessionIndicators(indicators: List<SessionIndicators>): SessionIndicators {
    fun mergedState(selector: (SessionIndicators) -> SessionIndicator): SessionIndicatorState {
        val states = indicators.map(selector).map { it.state }
        return when {
            states.any { it == SessionIndicatorState.Active } -> SessionIndicatorState.Active
            states.any { it == SessionIndicatorState.Loading } -> SessionIndicatorState.Loading
            else -> SessionIndicatorState.Inactive
        }
    }

    return SessionIndicators(
        connected = SessionIndicator(
            activeLabel = "Connected",
            loadingLabel = "Connecting",
            state = mergedState(SessionIndicators::connected),
        ),
        verified = SessionIndicator(
            activeLabel = "Verified",
            loadingLabel = "Verifying",
            state = mergedState(SessionIndicators::verified),
        ),
        encrypted = SessionIndicator(
            activeLabel = "Encrypted",
            loadingLabel = "Encrypting",
            state = mergedState(SessionIndicators::encrypted),
        ),
    )
}
