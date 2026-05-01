package com.epher.app.data.model

data class Participant(
    val id: String,
    val displayName: String,
    val isOnline: Boolean,
    val isVerified: Boolean,
    val role: RoomRole,
    val fingerprint: String,
    val transportPublicKeyHex: String? = null,
    val relayEligible: Boolean = false,
    val isRemoved: Boolean = false,
)

enum class RoomRole(val label: String) {
    Owner("Owner"),
    Member("Member"),
}
