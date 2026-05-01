package com.epher.app.security

import com.google.crypto.tink.subtle.Hkdf
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val secureRandom = SecureRandom()

internal fun randomBytes(length: Int): ByteArray = ByteArray(length).also(secureRandom::nextBytes)

internal fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

internal fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

internal fun hkdfSha256(
    ikm: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray = Hkdf.computeHkdf("HmacSha256", ikm, salt, info, length)

internal fun base64UrlEncode(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun base64UrlDecode(value: String): ByteArray =
    Base64.getUrlDecoder().decode(value)

internal fun hexEncode(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

internal fun hexDecode(value: String): ByteArray {
    require(value.length % 2 == 0) { "Hex value must have an even length" }
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun concatBytes(vararg values: ByteArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.sumOf { it.size })
    values.forEach(buffer::put)
    return buffer.array()
}

internal fun fingerprintFor(vararg components: ByteArray): String {
    val joined = ByteBuffer.allocate(components.sumOf { it.size }).apply {
        components.forEach(::put)
    }.array()
    val digest = sha256(joined)
    return digest.take(12).joinToString(":") { "%02X".format(it) }
}

internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
    MessageDigest.isEqual(left, right)

internal fun padForFixedPacket(payload: ByteArray, packetSize: Int): ByteArray {
    require(packetSize > payload.size + 4) { "Packet size must exceed payload size" }
    val buffer = ByteBuffer.allocate(packetSize)
    buffer.putInt(payload.size)
    buffer.put(payload)
    if (buffer.hasRemaining()) {
        buffer.put(randomBytes(buffer.remaining()))
    }
    return buffer.array()
}

internal fun padForPrivacyBucket(
    payload: ByteArray,
    minimumPacketSize: Int,
    bucketStep: Int = minimumPacketSize,
): ByteArray {
    require(minimumPacketSize > 4) { "Minimum packet size must exceed header size" }
    require(bucketStep > 0) { "Bucket step must be positive" }
    val requiredSize = payload.size + 4
    val packetSize = generateSequence(minimumPacketSize) { it + bucketStep }
        .first { it > requiredSize }
    return padForFixedPacket(payload, packetSize)
}

internal fun unpadFixedPacket(packet: ByteArray): ByteArray {
    require(packet.size >= 4) { "Packet too small" }
    val buffer = ByteBuffer.wrap(packet)
    val payloadLength = buffer.int
    require(payloadLength in 0..(packet.size - 4)) { "Invalid payload length" }
    return ByteArray(payloadLength).also(buffer::get)
}

internal fun requireCrypto(condition: Boolean, message: String) {
    if (!condition) {
        throw GeneralSecurityException(message)
    }
}
