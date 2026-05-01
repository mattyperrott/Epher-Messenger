package com.epher.app.security

import java.security.GeneralSecurityException
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

data class DerivedRoomSecrets(
    val masterKey: ByteArray,
    val chainKey: ByteArray,
    val authKey: ByteArray,
    val attachmentKey: ByteArray,
)

class RoomSecretDeriver {
    fun derive(
        roomId: String,
        roomPassword: String,
    ): DerivedRoomSecrets {
        val salt = sha256(utf8(roomId))
        val ikm = deriveWithArgon2id(roomPassword, salt)
        val masterKey = hkdfSha256(ikm, salt, utf8("epher.room.master"), 32)
        return DerivedRoomSecrets(
            masterKey = masterKey,
            chainKey = hkdfSha256(masterKey, salt, utf8("epher.room.chain"), 32),
            authKey = hkdfSha256(masterKey, salt, utf8("epher.room.auth"), 32),
            attachmentKey = hkdfSha256(masterKey, salt, utf8("epher.room.attachment"), 32),
        )
    }

    private fun deriveWithArgon2id(
        roomPassword: String,
        salt: ByteArray
    ): ByteArray {
        val generator = Argon2BytesGenerator()
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(1)
            .withIterations(3)
            .withMemoryAsKB(32 * 1024)
            .build()
        generator.init(parameters)
        return try {
            ByteArray(32).also { derived ->
                generator.generateBytes(utf8(roomPassword), derived, 0, derived.size)
            }
        } catch (throwable: Throwable) {
            throw GeneralSecurityException("Argon2id room secret derivation failed", throwable)
        }
    }
}
