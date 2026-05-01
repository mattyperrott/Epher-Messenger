package com.epher.app.data

data class PendingOutboundMessage(
    val roomId: String,
    val messageId: String,
    val logicalMessageId: String = messageId,
    val body: String,
    val createdAtEpochMillis: Long,
    val retryCount: Int = 0,
    val lastDispatchAtEpochMillis: Long? = null,
    val awaitingAckFrom: List<String> = emptyList(),
)
