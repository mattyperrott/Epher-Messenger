package com.epher.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.DetailLine
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.NoticeCard
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.InkCard
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun RoomSafetyScreen(
    room: RoomSummary,
    participants: List<Participant>,
    fingerprint: String,
    onBack: () -> Unit,
    onLeaveRoom: () -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    val retentionStatus = remember(room.id, room.retentionPreset, room.lastActivityEpochMillis) {
        retentionStatusFor(room)
    }
    val retentionDeadline = remember(room.id, room.retentionPreset, room.lastActivityEpochMillis) {
        retentionDeadlineFor(room)
    }
    val indicators = remember(room, participants) { sessionIndicators(room, participants) }

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                right = {},
            )
            AppBodyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "${displayRoomLabel(room)} safety",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Identity, invite, and retention details for this room session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NoticeCard(
                        title = "RETENTION STATUS",
                        body = retentionStatus,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SafetyActionButton(
                            modifier = Modifier.weight(1f),
                            label = "COPY INVITE",
                            icon = Icons.Rounded.ContentCopy,
                            containerColor = ChromePurple,
                            contentColor = Color.White,
                            onClick = {
                                clipboardManager.setText(AnnotatedString(room.invitePackage.inviteToken))
                                scope.launch {
                                    snackbarHostState.showSnackbar("Invite copied")
                                }
                            },
                        )
                        SafetyActionButton(
                            modifier = Modifier.weight(1f),
                            label = "SHARE",
                            icon = Icons.Rounded.ArrowOutward,
                            containerColor = InkCard,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(
                                                    Intent.EXTRA_TEXT,
                                                    "${displayRoomLabel(room)}\n${room.invitePackage.shareUrl}\n\nInvite token:\n${room.invitePackage.inviteToken}",
                                                )
                                            },
                                            "Share room invite",
                                        ),
                                    )
                                }.onSuccess {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Share sheet opened")
                                    }
                                }.onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Couldn't open the share sheet")
                                    }
                                }
                            },
                        )
                    }
                    SafetyActionButton(
                        modifier = Modifier.fillMaxWidth(),
                        label = "LEAVE + WIPE NOW",
                        icon = Icons.Rounded.Delete,
                        containerColor = AlertRed,
                        contentColor = Color.White,
                        onClick = { showLeaveConfirmation = true },
                    )
                    DetailLine(title = "ROOM ID", value = room.id)
                    DetailLine(title = "ROOM LABEL", value = displayRoomLabel(room))
                    DetailLine(title = "LOCAL FINGERPRINT", value = fingerprint)
                    DetailLine(title = "OWNER FINGERPRINT", value = room.invitePackage.ownerFingerprint)
                    DetailLine(
                        title = "INVITE TOKEN",
                        value = room.invitePackage.inviteToken,
                        valueMaxLines = 2,
                        valueOverflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    DetailLine(
                        title = "SHARE LINK",
                        value = room.invitePackage.shareUrl,
                        valueMaxLines = 2,
                        valueOverflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    DetailLine(title = "INVITE VALIDITY", value = room.invitePackage.expiresLabel)
                    DetailLine(title = "INVITE SIGNATURE", value = room.invitePackage.signatureState)
                    DetailLine(title = "OWNER ACCESS", value = if (room.isOwner) "This device created the room" else "Invite came from a remote owner")
                    DetailLine(title = "ROOM RETENTION", value = "${room.retentionPreset.label} • ${room.retentionPreset.detail}")
                    DetailLine(title = "RETENTION DEADLINE", value = retentionDeadline)
                    DetailLine(title = "LAST ACTIVITY", value = formatRetentionMoment(room.lastActivityEpochMillis))
                    DetailLine(title = "TRANSPORT", value = room.securityProfile.transportMode)
                    DetailLine(title = "TRANSPORT ENCRYPTION", value = room.securityProfile.transportEncryption)
                    DetailLine(title = "TRAFFIC OBFUSCATION", value = room.securityProfile.trafficObfuscation)
                    DetailLine(title = "METADATA RETENTION", value = room.securityProfile.metadataRetention)
                    DetailLine(title = "REPLAY PROTECTION", value = room.securityProfile.replayProtection)
                    DetailLine(title = "FORWARD SECRECY", value = room.securityProfile.forwardSecrecy)
                    DetailLine(title = "MESSAGE ENCRYPTION", value = room.securityProfile.messageEncryption)
                    DetailLine(title = "KEY DERIVATION", value = room.securityProfile.keyDerivation)
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            BottomStatusStrip(indicators = indicators)
        }

        if (showLeaveConfirmation) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmation = false },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            showLeaveConfirmation = false
                            onLeaveRoom()
                        },
                    ) {
                        Text("LEAVE + WIPE")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showLeaveConfirmation = false }) {
                        Text("CANCEL")
                    }
                },
                title = {
                    Text("Wipe this room from this device?")
                },
                text = {
                    Text(
                        "This immediately removes room chats, queued outbound items, peer state, room persona keys, and room secrets from local storage.",
                    )
                },
            )
        }
    }
}

@Composable
private fun SafetyActionButton(
    modifier: Modifier = Modifier,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = containerColor,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

private fun retentionStatusFor(room: RoomSummary): String {
    val maxAge = room.retentionPreset.maxAgeMillis
        ?: return "This room is set to wipe immediately when you leave it."
    val remaining = (room.lastActivityEpochMillis + maxAge) - System.currentTimeMillis()
    return if (remaining <= 0L) {
        "This room has exceeded its retention window and will be wiped on the next cleanup pass."
    } else {
        "This room will be wiped locally in ${formatRemainingDuration(remaining)} unless you leave and wipe it sooner."
    }
}

private fun retentionDeadlineFor(room: RoomSummary): String {
    val maxAge = room.retentionPreset.maxAgeMillis
        ?: return "On explicit leave"
    return formatRetentionMoment(room.lastActivityEpochMillis + maxAge)
}

private fun formatRemainingDuration(millis: Long): String {
    val duration = Duration.ofMillis(millis.coerceAtLeast(0L))
    val days = duration.toDays()
    val hours = duration.minusDays(days).toHours()
    val minutes = duration.minusDays(days).minusHours(hours).toMinutes()
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes.coerceAtLeast(1)}m"
    }
}

private fun formatRetentionMoment(epochMillis: Long): String = DateTimeFormatter
    .ofPattern("MMM d, h:mm a", Locale.US)
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))
