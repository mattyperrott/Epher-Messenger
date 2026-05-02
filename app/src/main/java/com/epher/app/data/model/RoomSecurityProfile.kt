package com.epher.app.data.model

data class RoomSecurityProfile(
    val messageEncryption: String = "Pairwise XChaCha20-Poly1305 envelopes",
    val identityScheme: String = "Ed25519 room-scoped identity",
    val transportEncryption: String = "Transport-dependent relay or direct link with client-side end-to-end encryption",
    val keyDerivation: String = "Argon2id",
    val peerAuthentication: String = "Signed peer cards + invite-derived membership proof",
    val replayProtection: String = "Per-peer ratchet counters + recent message IDs",
    val forwardSecrecy: String = "Pairwise ratchet per verified peer",
    val trafficObfuscation: String = "Depends on configured transport",
    val metadataRetention: String = "No central plaintext message storage; encrypted local session state",
    val groupKeyPolicy: String = "Owner removal rotates future room traffic to a new room epoch",
    val transportMode: String = "Transport depends on configured build",
    val overlayMode: String = "No overlay tunnel in current build",
    val fileSharing: String = "Experimental encrypted attachments (not part of canonical v1)",
)
