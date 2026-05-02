package com.epher.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.EmberCoral
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.SignalMint
import com.epher.app.ui.theme.TideTeal
import com.epher.app.ui.theme.VerifiedGreen
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
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "${displayRoomLabel(room)} roster",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Participants currently attached to this room session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(participants, key = { it.id }) { participant ->
                            val canRemove = room.isOwner &&
                                participant.transportPublicKeyHex != null &&
                                !participant.isRemoved
                            Surface(
                                color = if (participant.isRemoved) InkCard.copy(alpha = 0.62f) else InkCard,
                                shape = RoundedCornerShape(24.dp),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(44.dp),
                                                color = participantColor(participant.displayName).copy(alpha = 0.82f),
                                                shape = CircleShape,
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxSize(),
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Person,
                                                        contentDescription = null,
                                                        tint = Color.White,
                                                        modifier = Modifier.size(22.dp),
                                                    )
                                                }
                                            }
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = participant.displayName,
                                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = participantColor(participant.displayName),
                                                )
                                                Text(
                                                    text = if (participant.isRemoved) "Removed" else participant.role.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = if (participant.isRemoved) AlertRed else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Surface(
                                            color = when {
                                                participant.isRemoved -> AlertRed.copy(alpha = 0.78f)
                                                participant.isVerified -> VerifiedGreen
                                                else -> ChromePurple.copy(alpha = 0.24f)
                                            },
                                            shape = RoundedCornerShape(999.dp),
                                        ) {
                                            Text(
                                                text = when {
                                                    participant.isRemoved -> "REMOVED"
                                                    participant.isVerified -> "VERIFIED"
                                                    else -> "CHECK"
                                                },
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                                color = Color.White,
                                            )
                                        }
                                    }
                                    HorizontalDivider(color = ChromePurple.copy(alpha = 0.16f))
                                    Text(
                                        text = "Fingerprint: ${participant.fingerprint.take(12)}...${participant.fingerprint.takeLast(10)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = when {
                                            participant.isRemoved -> "Removed peers are blocked locally and excluded from future rekeys."
                                            participant.isOnline -> "Online in this session"
                                            else -> "Peer not currently reachable"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (canRemove) {
                                        TextButton(
                                            onClick = { pendingRemoval = participant },
                                            modifier = Modifier.align(Alignment.End),
                                        ) {
                                            Text(
                                                text = "REMOVE AND REKEY",
                                                color = AlertRed,
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            )
                                        }
                                    }
                                }
                            }
                        }
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

private val rosterPalette = listOf(
    TideTeal,
    Color(0xFF4AB2FF),
    EmberCoral,
    SignalMint,
    AlertRed,
)

private fun participantColor(name: String): Color = when (name) {
    "You" -> TideTeal
    else -> rosterPalette[name.hashCode().absoluteValue % rosterPalette.size]
}
