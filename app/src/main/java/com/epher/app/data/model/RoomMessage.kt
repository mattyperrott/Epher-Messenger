package com.epher.app.data.model

data class RoomAttachment(
    val fileName: String,
    val mimeType: String,
    val byteSize: Long,
    val encryptedNonce: String,
    val encryptedCiphertext: String,
    val digestHex: String,
    val storageKey: String? = null,
    val transferState: AttachmentTransferState = AttachmentTransferState.Available,
    val receivedChunks: Int = 0,
    val totalChunks: Int = 0,
)

enum class AttachmentTransferState {
    Receiving,
    Available,
}

data class ResolvedAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class RoomMessage(
    val id: String,
    val roomId: String,
    val senderName: String,
    val body: String,
    val sentAt: String,
    val isLocalUser: Boolean,
    val isSystemEvent: Boolean = false,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.Sent,
    val attachment: RoomAttachment? = null,
)

enum class MessageDeliveryState(val label: String) {
    Queued("Queued"),
    Sending("Sending"),
    Sent("Sent"),
    Retry("Retrying"),
}
