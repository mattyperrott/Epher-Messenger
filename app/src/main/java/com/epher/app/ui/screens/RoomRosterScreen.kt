package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomRole
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleSoft
import com.epher.app.ui.theme.EmberCoral
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.InkPanel
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.SignalMint
import com.epher.app.ui.theme.TideTeal
import kotlin.math.absoluteValue

@Composable
fun RoomRosterScreen(
    room: RoomSummary,
    participants: List<Participant>,
    onRemoveParticipant: (String) -> Unit,
    onBack: () -> Unit,
) {
    val indicators = sessionIndicators(room, participants)
    var pendingRemoval by remember { mutableStateOf<Participant?>(null) }
    val localParticipants = participants.filter { it.displayName == "You" }
    val remoteParticipants = participants.filterNot { it.displayName == "You" }
    val activeCount = participants.count { it.isOnline && !it.isRemoved }
    val verifiedCount = participants.count { it.isVerified && !it.isRemoved }

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "SESSION LEDGER",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                },
                right = {
                    Spacer(modifier = Modifier.size(48.dp))
                },
            )
            AppBodyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        RosterHeader(
                            roomLabel = displayRoomLabel(room),
                            activeCount = activeCount,
                            verifiedCount = verifiedCount,
                        )
                    }
                    if (localParticipants.isNotEmpty()) {
                        item { RosterSectionLabel("LOCAL IDENTITY") }
                        items(localParticipants, key = { it.id }) { participant ->
                            ParticipantCard(
                                room = room,
                                participant = participant,
                                canRemove = false,
                                onRemove = { pendingRemoval = participant },
                            )
                        }
                    }
                    item { RosterSectionLabel("REMOTE PEERS") }
                    items(remoteParticipants, key = { it.id }) { participant ->
                        val canRemove = room.isOwner &&
                            participant.transportPublicKeyHex != null &&
                            !participant.isRemoved
                        ParticipantCard(
                            room = room,
                            participant = participant,
                            canRemove = canRemove,
                            onRemove = { pendingRemoval = participant },
                        )
                    }
                    item {
                        RosterFooter()
                    }
                }
            }
            BottomStatusStrip(indicators = indicators)
        }

        pendingRemoval?.let { participant ->
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = {
                    Text(
                        text = "Remove ${participant.displayName}?",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                text = {
                    Text(
                        text = "This rotates the room to a new epoch and stops sending future room keys to this participant. It cannot revoke messages or files they already received.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingRemoval = null
                            onRemoveParticipant(participant.fingerprint)
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = AlertRed),
                    ) {
                        Text("REMOVE AND REKEY")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) {
                        Text("CANCEL")
                    }
                },
                containerColor = InkCard,
            )
        }
    }
}

@Composable
private fun RosterHeader(
    roomLabel: String,
    activeCount: Int,
    verifiedCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = roomLabel,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RosterMetricChip(
                label = "$activeCount ACTIVE",
                dotColor = ChromePurple,
                containerColor = InkCard,
            )
            RosterMetricChip(
                label = "$verifiedCount VERIFIED",
                dotColor = SignalMint,
                containerColor = SignalMint.copy(alpha = 0.10f),
                textColor = SignalMint,
            )
        }
    }
}

@Composable
private fun RosterMetricChip(
    label: String,
    dotColor: Color,
    containerColor: Color,
    textColor: Color = MistBlue,
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = textColor,
            )
        }
    }
}

@Composable
private fun RosterSectionLabel(label: String) {
    Text(
        text = label,
        modifier = Modifier.padding(start = 14.dp, top = 8.dp),
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 10.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = MistBlue.copy(alpha = 0.56f),
    )
}

@Composable
private fun ParticipantCard(
    room: RoomSummary,
    participant: Participant,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val accent = participantAccent(participant)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (participant.isRemoved) InkCard.copy(alpha = 0.58f) else InkCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (participant.displayName == "You") {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(width = 6.dp, height = 58.dp)
                        .background(TideTeal, RoundedCornerShape(topEnd = 999.dp, bottomEnd = 999.dp)),
                )
            }
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 14.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParticipantAvatar(participant = participant, accent = accent)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (participant.displayName == "You") "${participant.displayName} (You)" else participant.displayName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (participant.role == RoomRole.Owner) {
                            Surface(
                                color = ChromePurple.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = "OWNER",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        letterSpacing = 0.7.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = Color.White,
                                )
                            }
                        }
                        if (participant.isVerified) {
                            Icon(
                                Icons.Rounded.Verified,
                                contentDescription = "Verified",
                                tint = SignalMint,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                    Text(
                        text = fingerprintPreview(participant.fingerprint),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                        ),
                        color = if (participant.isVerified) MistBlue.copy(alpha = 0.84f) else EmberCoral.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = participantStatusText(participant),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (canRemove) {
                    Surface(
                        color = AlertRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, AlertRed.copy(alpha = 0.28f)),
                        onClick = onRemove,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = AlertRed,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "REKEY",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = AlertRed,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantAvatar(
    participant: Participant,
    accent: Color,
) {
    Box {
        Surface(
            modifier = Modifier.size(52.dp),
            color = accent.copy(alpha = if (participant.isRemoved) 0.10f else 0.18f),
            shape = CircleShape,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(16.dp),
            color = InkPanel,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            when {
                                participant.isRemoved -> AlertRed
                                participant.isOnline -> SignalMint
                                else -> MistBlue.copy(alpha = 0.42f)
                            },
                            CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun RosterFooter() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 2.dp)
                .background(MistBlue.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
        )
        Text(
            text = "Roster is local trust state. Verify fingerprints out of band before treating peers as trusted.",
            style = MaterialTheme.typography.bodySmall,
            color = MistBlue.copy(alpha = 0.56f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private val rosterPalette = listOf(
    TideTeal,
    Color(0xFF4AB2FF),
    EmberCoral,
    SignalMint,
    ChromePurpleSoft,
)

private fun participantAccent(participant: Participant): Color = when {
    participant.isRemoved -> AlertRed
    participant.displayName == "You" -> TideTeal
    participant.isVerified -> rosterPalette[participant.displayName.hashCode().absoluteValue % rosterPalette.size]
    else -> MistBlue
}

private fun participantStatusText(participant: Participant): String = when {
    participant.isRemoved -> "Removed peers are blocked locally and excluded from future rekeys."
    participant.isOnline -> "Online in this session"
    else -> "Peer not currently reachable"
}

private fun fingerprintPreview(fingerprint: String): String {
    val clean = fingerprint.uppercase()
    if (clean.length < 12) return clean
    return "${clean.take(4)} • ${clean.drop(4).take(4)} • ${clean.takeLast(4)}"
}
