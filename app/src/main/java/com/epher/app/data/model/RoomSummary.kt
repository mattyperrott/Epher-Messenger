package com.epher.app.data.model

data class RoomSummary(
    val id: String,
    val localLabel: String,
    val participantCount: Int,
    val unreadCount: Int,
    val pendingOutgoingCount: Int = 0,
    val connectionState: ConnectionState,
    val isEncryptedSessionEstablished: Boolean = false,
    val retentionPreset: RetentionPreset,
    val isOwner: Boolean,
    val invitePackage: InvitePackage,
    val securityProfile: RoomSecurityProfile = RoomSecurityProfile(),
    val lastActivityEpochMillis: Long = System.currentTimeMillis(),
)
