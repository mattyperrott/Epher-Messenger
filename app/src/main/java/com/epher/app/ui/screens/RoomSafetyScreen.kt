package com.epher.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.SignalMint
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
                    Text(
                        text = "${displayRoomLabel(room)} AUDIT",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.5.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                right = {
                    Box(modifier = Modifier.size(48.dp))
                },
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
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        text = "Cryptographic parameters and trust metrics for the current session. Review carefully.",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SafetyActionButton(
                            modifier = Modifier.weight(1f),
                            label = "COPY INVITE",
                            icon = Icons.Rounded.ContentCopy,
                            containerColor = InkCard,
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
                        label = "LEAVE & WIPE NOW",
                        icon = Icons.Rounded.Delete,
                        containerColor = AlertRed.copy(alpha = 0.16f),
                        contentColor = AlertRed,
                        onClick = { showLeaveConfirmation = true },
                    )
                    SafetyMetadataCard(
                        title = "IDENTITY & ACCESS",
                        icon = Icons.Rounded.Fingerprint,
                        rows = listOf(
                            SafetyRow("Room ID", room.id),
                            SafetyRow("Room Label", displayRoomLabel(room)),
                            SafetyRow("Local Fingerprint", fingerprint, maxLines = 1),
                            SafetyRow("Owner Fingerprint", room.invitePackage.ownerFingerprint, maxLines = 1, valueColor = SignalMint),
                            SafetyRow("Active Invite Token", room.invitePackage.inviteToken, maxLines = 2, block = true),
                            SafetyRow("Share Link", room.invitePackage.shareUrl, maxLines = 2, block = true),
                            SafetyRow("Invite Validity", room.invitePackage.expiresLabel),
                            SafetyRow("Invite Signature", room.invitePackage.signatureState),
                            SafetyRow("Owner Access", if (room.isOwner) "This device created the room" else "Invite came from a remote owner"),
                        ),
                    )
                    SafetyMetadataCard(
                        title = "RETENTION",
                        icon = Icons.Rounded.Delete,
                        rows = listOf(
                            SafetyRow("Status", retentionStatus, maxLines = 3),
                            SafetyRow("Room Retention", "${room.retentionPreset.label} • ${room.retentionPreset.detail}", maxLines = 2),
                            SafetyRow("Retention Deadline", retentionDeadline),
                            SafetyRow("Last Activity", formatRetentionMoment(room.lastActivityEpochMillis)),
                        ),
                    )
                    SafetyMetadataCard(
                        title = "PROTOCOL PARAMETERS",
                        icon = Icons.Rounded.Lock,
                        rows = listOf(
                            SafetyRow("Transport Layer", room.securityProfile.transportMode),
                            SafetyRow("Transport Encryption", room.securityProfile.transportEncryption, maxLines = 2),
                            SafetyRow("Message Cipher", room.securityProfile.messageEncryption),
                            SafetyRow("Key Derivation", room.securityProfile.keyDerivation),
                            SafetyRow("Forward Secrecy", room.securityProfile.forwardSecrecy, valueColor = SignalMint),
                            SafetyRow("Replay Protection", room.securityProfile.replayProtection, maxLines = 2),
                            SafetyRow("Traffic Obfuscation", room.securityProfile.trafficObfuscation, maxLines = 2),
                            SafetyRow("Metadata Retention", room.securityProfile.metadataRetention, maxLines = 2),
                        ),
                    )
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
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .height(50.dp)
                .padding(horizontal = 14.dp),
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

private data class SafetyRow(
    val label: String,
    val value: String,
    val maxLines: Int = 1,
    val block: Boolean = false,
    val valueColor: Color? = null,
)

@Composable
private fun SafetyMetadataCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    rows: List<SafetyRow>,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = InkCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.20f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MistBlue,
                    modifier = Modifier.size(17.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MistBlue,
                )
            }
            rows.forEachIndexed { index, row ->
                if (row.block) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MistBlue,
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Black.copy(alpha = 0.28f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                text = row.value,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = row.valueColor ?: Color.White.copy(alpha = 0.70f),
                                maxLines = row.maxLines,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.label,
                            modifier = Modifier.weight(0.42f),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MistBlue,
                        )
                        Text(
                            text = row.value,
                            modifier = Modifier.weight(0.58f),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = row.valueColor ?: MaterialTheme.colorScheme.onSurface,
                            maxLines = row.maxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (index != rows.lastIndex) {
                    androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
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
