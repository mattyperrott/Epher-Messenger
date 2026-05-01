package com.epher.app.data.model

data class InvitePackage(
    val roomId: String,
    val inviteToken: String,
    val shareUrl: String,
    val qrPayload: String,
    val expiresLabel: String,
    val expiresAtEpochMillis: Long? = null,
    val ownerFingerprint: String = "UNVERIFIED",
    val ownerTransportPublicKeyHex: String? = null,
    val signatureState: String = "Signed invite token",
)
