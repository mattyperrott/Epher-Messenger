package com.epher.app.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

data class PendingAttachmentManifest(
    val roomId: String,
    val transferId: String,
    val displayMessageId: String,
    val senderName: String,
    val senderFingerprint: String,
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
    val encryptedNonce: String,
    val digestHex: String,
    val totalChunks: Int,
    val sentAtEpochMillis: Long,
)

class AttachmentFileStore(
    context: Context,
) {
    private val rootDir = File(context.applicationContext.noBackupFilesDir, "room_attachments").apply { mkdirs() }
    private val rootCanonicalFile = rootDir.canonicalFile
    private val rootCanonicalPath = "${rootCanonicalFile.path}${File.separator}"

    fun putEncryptedCiphertext(
        roomId: String,
        storageKey: String,
        ciphertext: ByteArray,
    ) {
        finalAttachmentFile(roomId, storageKey).apply {
            parentFile?.mkdirs()
            writeBytes(ciphertext)
        }
    }

    fun getEncryptedCiphertext(
        roomId: String,
        storageKey: String,
    ): ByteArray? {
        val file = finalAttachmentFile(roomId, storageKey)
        return if (file.isFile) file.readBytes() else null
    }

    fun savePendingManifest(manifest: PendingAttachmentManifest) {
        val file = pendingManifestFile(manifest.roomId, manifest.transferId)
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject()
                .put("roomId", manifest.roomId)
                .put("transferId", manifest.transferId)
                .put("displayMessageId", manifest.displayMessageId)
                .put("senderName", manifest.senderName)
                .put("senderFingerprint", manifest.senderFingerprint)
                .put("fileName", manifest.fileName)
                .put("mimeType", manifest.mimeType)
                .put("byteSize", manifest.byteSize)
                .put("encryptedNonce", manifest.encryptedNonce)
                .put("digestHex", manifest.digestHex)
                .put("totalChunks", manifest.totalChunks)
                .put("sentAtEpochMillis", manifest.sentAtEpochMillis)
                .toString(),
        )
        touchPendingTransfer(manifest.roomId, manifest.transferId)
    }

    fun loadPendingManifest(
        roomId: String,
        transferId: String,
    ): PendingAttachmentManifest? {
        val file = pendingManifestFile(roomId, transferId)
        if (!file.isFile) return null
        val json = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return null
        return runCatching {
            PendingAttachmentManifest(
                roomId = json.getString("roomId"),
                transferId = json.getString("transferId"),
                displayMessageId = json.getString("displayMessageId"),
                senderName = json.getString("senderName"),
                senderFingerprint = json.getString("senderFingerprint"),
                fileName = json.getString("fileName"),
                mimeType = json.optString("mimeType", "application/octet-stream"),
                byteSize = json.optLong("byteSize", 0L),
                encryptedNonce = json.getString("encryptedNonce"),
                digestHex = json.getString("digestHex"),
                totalChunks = json.getInt("totalChunks"),
                sentAtEpochMillis = json.optLong("sentAtEpochMillis"),
            )
        }.getOrNull()
    }

    fun savePendingChunk(
        roomId: String,
        transferId: String,
        chunkIndex: Int,
        encodedChunk: String,
    ) {
        val file = pendingChunkFile(roomId, transferId, chunkIndex)
        file.parentFile?.mkdirs()
        file.writeText(encodedChunk)
        touchPendingTransfer(roomId, transferId)
    }

    fun loadPendingChunkCount(
        roomId: String,
        transferId: String,
    ): Int {
        val dir = pendingChunkDir(roomId, transferId)
        return dir.listFiles()?.count { it.isFile && it.name.endsWith(".part") } ?: 0
    }

    fun loadPendingTransferCount(roomId: String): Int {
        val dir = File(roomDir(roomId), "pending")
        return dir.listFiles()?.count { it.isDirectory } ?: 0
    }

    fun assemblePendingChunks(
        roomId: String,
        transferId: String,
        totalChunks: Int,
    ): String? {
        val builder = StringBuilder()
        for (index in 0 until totalChunks) {
            val file = pendingChunkFile(roomId, transferId, index)
            if (!file.isFile) return null
            builder.append(file.readText())
        }
        return builder.toString()
    }

    fun removePendingTransfer(
        roomId: String,
        transferId: String,
    ) {
        runCatching { pendingTransferDir(roomId, transferId) }
            .getOrNull()
            ?.deleteRecursively()
    }

    fun removeRoom(roomId: String) {
        runCatching { roomDir(roomId) }
            .getOrNull()
            ?.deleteRecursively()
    }

    fun cleanupStalePendingTransfers(maxAgeMillis: Long): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var removed = 0
        rootDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .forEach { room ->
                val pendingDir = File(room, "pending")
                pendingDir.listFiles()
                    .orEmpty()
                    .filter { it.isDirectory && it.lastModified() < cutoff }
                    .forEach { transfer ->
                        if (transfer.deleteRecursively()) removed += 1
                    }
            }
        return removed
    }

    private fun finalAttachmentFile(roomId: String, storageKey: String): File =
        File(File(roomDir(roomId), "final"), "${hashedName(storageKey)}.bin")

    private fun pendingTransferDir(roomId: String, transferId: String): File =
        File(File(roomDir(roomId), "pending"), hashedName(transferId))

    private fun pendingManifestFile(roomId: String, transferId: String): File =
        File(pendingTransferDir(roomId, transferId), "manifest.json")

    private fun pendingChunkDir(roomId: String, transferId: String): File =
        File(pendingTransferDir(roomId, transferId), "chunks")

    private fun pendingChunkFile(roomId: String, transferId: String, chunkIndex: Int): File =
        File(pendingChunkDir(roomId, transferId), "%05d.part".format(chunkIndex))

    private fun touchPendingTransfer(roomId: String, transferId: String) {
        runCatching {
            pendingTransferDir(roomId, transferId).setLastModified(System.currentTimeMillis())
        }
    }

    private fun hashedName(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun roomDir(roomId: String): File {
        require(roomId.isNotBlank()) { "Missing room ID" }
        val candidate = File(rootCanonicalFile, roomId).canonicalFile
        require(candidate.path.startsWith(rootCanonicalPath)) { "Unsafe attachment room path" }
        return candidate
    }
}
