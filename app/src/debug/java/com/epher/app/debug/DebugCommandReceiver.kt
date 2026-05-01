package com.epher.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.epher.app.EpherApplication
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.RetentionPreset
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val application = context.applicationContext as? EpherApplication
        if (application == null) {
            pendingResult.finish()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(DEBUG_BROADCAST_TIMEOUT_MILLIS) {
                    application.container.repository.resumeNetworking()
                    when (intent.action) {
                        ACTION_CREATE_ROOM -> {
                            val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
                            val roomId = application.container.repository.createRoom(
                                label = label,
                                retentionPreset = RetentionPreset.LeaveOnly,
                                inviteExpiryPreset = InviteExpiryPreset.Day,
                            )
                            val room = application.container.repository.snapshot.value.rooms.firstOrNull { it.id == roomId }
                            Log.d(
                                TAG,
                                "Created debug room roomId=$roomId invite=${room?.invitePackage?.inviteToken.orEmpty()}",
                            )
                        }

                        ACTION_JOIN_ROOM -> {
                            val inviteToken = intent.getStringExtra(EXTRA_INVITE_TOKEN).orEmpty()
                            if (inviteToken.isBlank()) {
                                Log.w(TAG, "Missing inviteToken for debug join")
                            } else {
                                val roomId = application.container.repository.joinRoom(inviteToken)
                                Log.d(TAG, "Joined debug room roomId=$roomId")
                            }
                        }

                        ACTION_SEND_MESSAGE -> {
                            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                            val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
                            if (roomId.isBlank() || message.isBlank()) {
                                Log.w(TAG, "Missing roomId or message for debug send")
                            } else {
                                application.container.repository.sendMessage(roomId, message)
                                Log.d(TAG, "Injected debug message into room $roomId")
                            }
                        }

                        ACTION_SEND_ATTACHMENT -> {
                            val roomId = intent.getStringExtra(EXTRA_ROOM_ID).orEmpty()
                            val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
                            val inlineText = intent.getStringExtra(EXTRA_INLINE_TEXT).orEmpty()
                            val inlineBase64 = intent.getStringExtra(EXTRA_INLINE_BASE64).orEmpty()
                            val requestedFileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
                            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty().ifBlank {
                                "application/octet-stream"
                            }
                            val file = File(filePath)
                            if (roomId.isBlank()) {
                                Log.w(TAG, "Missing roomId for debug attachment send")
                            } else if (inlineBase64.isNotBlank()) {
                                val fileName = requestedFileName.ifBlank { "debug-attachment.bin" }
                                application.container.repository.sendAttachment(
                                    roomId = roomId,
                                    fileName = fileName,
                                    mimeType = mimeType,
                                    bytes = android.util.Base64.decode(
                                        inlineBase64,
                                        android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
                                    ),
                                )
                                Log.d(TAG, "Injected base64 debug attachment $fileName into room $roomId")
                            } else if (inlineText.isNotBlank()) {
                                val fileName = requestedFileName.ifBlank { "debug-attachment.txt" }
                                application.container.repository.sendAttachment(
                                    roomId = roomId,
                                    fileName = fileName,
                                    mimeType = if (mimeType == "application/octet-stream") "text/plain" else mimeType,
                                    bytes = inlineText.toByteArray(),
                                )
                                Log.d(TAG, "Injected inline debug attachment $fileName into room $roomId")
                            } else if (filePath.isBlank()) {
                                Log.w(TAG, "Missing filePath or inlineText for debug attachment send")
                            } else if (!file.exists() || !file.isFile) {
                                Log.w(TAG, "Attachment file does not exist: $filePath")
                            } else {
                                application.container.repository.sendAttachment(
                                    roomId = roomId,
                                    fileName = requestedFileName.ifBlank { file.name },
                                    mimeType = mimeType,
                                    bytes = file.readBytes(),
                                )
                                Log.d(TAG, "Injected debug attachment ${file.name} into room $roomId")
                            }
                        }

                        ACTION_DUMP_SNAPSHOT -> {
                            val snapshot = application.container.repository.snapshot.value
                            Log.d(TAG, "Snapshot rooms=${snapshot.rooms.size}")
                            snapshot.rooms.forEach { room ->
                                val participants = application.container.repository.participants(room.id)
                                val messages = application.container.repository.messages(room.id)
                                val lastMessage = messages.lastOrNull()
                                Log.d(
                                    TAG,
                                    "Room ${room.id} peers=${participants.size} messages=${messages.size} state=${room.connectionState} pending=${room.pendingOutgoingCount} last=${lastMessage?.body.orEmpty().take(48)} lastDelivery=${lastMessage?.deliveryState}",
                                )
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                Log.e(TAG, "Debug command failed for action=${intent.action}", throwable)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DebugCommandReceiver"
        const val ACTION_CREATE_ROOM = "com.epher.app.DEBUG_CREATE_ROOM"
        const val ACTION_JOIN_ROOM = "com.epher.app.DEBUG_JOIN_ROOM"
        const val ACTION_SEND_MESSAGE = "com.epher.app.DEBUG_SEND_MESSAGE"
        const val ACTION_SEND_ATTACHMENT = "com.epher.app.DEBUG_SEND_ATTACHMENT"
        const val ACTION_DUMP_SNAPSHOT = "com.epher.app.DEBUG_DUMP_SNAPSHOT"
        const val EXTRA_LABEL = "label"
        const val EXTRA_INVITE_TOKEN = "inviteToken"
        const val EXTRA_ROOM_ID = "roomId"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_FILE_PATH = "filePath"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_MIME_TYPE = "mimeType"
        const val EXTRA_INLINE_TEXT = "inlineText"
        const val EXTRA_INLINE_BASE64 = "inlineBase64"
        const val DEBUG_BROADCAST_TIMEOUT_MILLIS = 45_000L
    }
}
