package com.epher.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epher.app.EpherApplication
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.RoomsSnapshot
import com.epher.app.data.model.ResolvedAttachment
import com.epher.app.data.model.RetentionPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EpherUiState(
    val isCreatingRoom: Boolean = false,
    val isJoiningRoom: Boolean = false,
    val createRoomError: String? = null,
    val joinRoomError: String? = null,
    val pendingInviteToken: String? = null,
)

class EpherViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = (application as EpherApplication).container.repository
    private val _uiState = MutableStateFlow(EpherUiState())
    val uiState: StateFlow<EpherUiState> = _uiState

    val snapshot: StateFlow<RoomsSnapshot> = repository.snapshot.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = repository.snapshot.value,
    )

    fun createRoom(
        label: String,
        retentionPreset: RetentionPreset,
        inviteExpiryPreset: InviteExpiryPreset,
        onCreated: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isCreatingRoom = true, createRoomError = null)
            try {
                val roomId = repository.createRoom(
                    label = label,
                    retentionPreset = retentionPreset,
                    inviteExpiryPreset = inviteExpiryPreset,
                )
                withContext(Dispatchers.Main) {
                    onCreated(roomId)
                }
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    createRoomError = t.userFacingMessage(defaultMessage = "Couldn't create the room. Please try again."),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isCreatingRoom = false)
            }
        }
    }

    fun joinRoom(
        inviteToken: String,
        onJoined: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isJoiningRoom = true, joinRoomError = null)
            try {
                val roomId = repository.joinRoom(inviteToken)
                withContext(Dispatchers.Main) {
                    onJoined(roomId)
                }
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    joinRoomError = t.userFacingMessage(defaultMessage = "Couldn't join that room. Check the invite and try again."),
                )
            } finally {
                _uiState.value = _uiState.value.copy(isJoiningRoom = false)
            }
        }
    }

    fun sendMessage(roomId: String, message: String) {
        repository.sendMessage(roomId, message)
    }

    fun sendAttachment(
        roomId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.sendAttachment(roomId, fileName, mimeType, bytes)
            }.onFailure { throwable ->
                withContext(Dispatchers.Main) {
                    onError(
                        throwable.userFacingMessage(
                            defaultMessage = "Couldn't attach that file. Please try a smaller or different file.",
                        ),
                    )
                }
            }
        }
    }

    suspend fun resolveAttachment(
        roomId: String,
        messageId: String,
    ): ResolvedAttachment? = withContext(Dispatchers.IO) {
        repository.resolveAttachment(roomId, messageId)
    }

    fun suspendNetworking() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.suspendNetworking()
        }
    }

    fun resumeNetworking() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resumeNetworking()
        }
    }

    fun leaveRoom(roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.leaveRoom(roomId)
        }
    }

    fun removeParticipant(roomId: String, fingerprint: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeParticipant(roomId, fingerprint)
        }
    }

    fun room(roomId: String) = repository.room(roomId)

    fun participants(roomId: String) = repository.participants(roomId)

    fun messages(roomId: String) = repository.messages(roomId)

    fun logs(roomId: String) = repository.logs(roomId)

    fun localDisplayName(): String = repository.localDisplayName()

    fun localFingerprint(): String = repository.localFingerprint()

    fun roomFingerprint(roomId: String): String? = repository.roomFingerprint(roomId)

    fun clearCreateRoomError() {
        _uiState.value = _uiState.value.copy(createRoomError = null)
    }

    fun clearJoinRoomError() {
        _uiState.value = _uiState.value.copy(joinRoomError = null)
    }

    fun stageIncomingInvite(inviteToken: String) {
        if (inviteToken.isBlank()) return
        _uiState.value = _uiState.value.copy(
            pendingInviteToken = inviteToken.trim(),
            joinRoomError = null,
        )
    }

    fun consumePendingInvite() {
        _uiState.value = _uiState.value.copy(pendingInviteToken = null)
    }
}

private fun Throwable.userFacingMessage(defaultMessage: String): String {
    val message = message?.trim().orEmpty()
    return when {
        message.contains("invalid", ignoreCase = true) -> message
        message.contains("missing", ignoreCase = true) -> message
        message.isNotBlank() -> message
        else -> defaultMessage
    }
}
