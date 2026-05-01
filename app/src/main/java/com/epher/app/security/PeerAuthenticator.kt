package com.epher.app.security

import java.security.GeneralSecurityException

data class PeerChallenge(
    val nonce: ByteArray,
    val issuedAtEpochMillis: Long,
)

class PeerAuthenticator {
    fun issueChallenge(): PeerChallenge = PeerChallenge(
        nonce = randomBytes(32),
        issuedAtEpochMillis = System.currentTimeMillis(),
    )

    fun respond(
        authKey: ByteArray,
        roomId: String,
        fingerprint: String,
        challenge: PeerChallenge,
    ): ByteArray = hmacSha256(
        authKey,
        utf8(
            buildString {
                append(roomId)
                append('|')
                append(fingerprint)
                append('|')
                append(base64UrlEncode(challenge.nonce))
                append('|')
                append(challenge.issuedAtEpochMillis)
            },
        ),
    )

    fun verify(
        authKey: ByteArray,
        roomId: String,
        fingerprint: String,
        challenge: PeerChallenge,
        response: ByteArray,
    ) {
        val expected = respond(authKey, roomId, fingerprint, challenge)
        requireCrypto(constantTimeEquals(expected, response), "Peer authentication failed")
    }
}
