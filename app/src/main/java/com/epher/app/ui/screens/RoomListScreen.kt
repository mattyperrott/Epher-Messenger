package com.epher.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.NoticeCard
import com.epher.app.ui.components.StatusChip
import com.epher.app.ui.components.aggregateSessionIndicators
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue

@Composable
fun RoomListScreen(
    rooms: List<RoomSummary>,
    participantsByRoom: Map<String, List<Participant>>,
    onBack: () -> Unit,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRoom: (String) -> Unit,
) {
    val indicators = aggregateSessionIndicators(
        rooms.map { room -> sessionIndicators(room, participantsByRoom[room.id].orEmpty()) },
    )
    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Home")
                    }
                },
                right = {
                    IconButton(onClick = onCreateRoom) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create room")
                    }
                    IconButton(onClick = onJoinRoom) {
                        Icon(Icons.Rounded.GroupAdd, contentDescription = "Join room")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                    }
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
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "ROOMS",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Local and recently joined rooms stay here until you leave or the retention window expires.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Button(
                                onClick = onCreateRoom,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ChromePurple),
                            ) {
                                Text("CREATE")
                            }
                            OutlinedButton(
                                onClick = onJoinRoom,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text("JOIN")
                            }
                        }
                    }
                    item {
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (rooms.isEmpty()) {
                        item {
                            NoticeCard(
                                title = "NO CACHED ROOMS",
                                body = "Create a new room to start fresh, or paste an invite to bring an existing room onto this device.",
                            )
                        }
                    }
                    items(rooms, key = { it.id }) { room ->
                        RoomCard(
                            room = room,
                            onClick = { onOpenRoom(room.id) },
                        )
                    }
                }
            }
            BottomStatusStrip(indicators = indicators)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoomCard(
    room: RoomSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = InkCard,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayRoomLabel(room),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = room.id,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MistBlue,
                        maxLines = 1,
                    )
                }
                RoomStateBadge(state = room.connectionState)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(label = "${room.participantCount} peers")
                StatusChip(label = room.retentionPreset.label)
                if (room.unreadCount > 0) {
                    StatusChip(label = "${room.unreadCount} new")
                }
                if (room.pendingOutgoingCount > 0) {
                    StatusChip(label = "${room.pendingOutgoingCount} queued")
                }
            }
        }
    }
}

@Composable
private fun RoomStateBadge(
    state: ConnectionState,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when (state) {
            ConnectionState.Connecting -> ChromePurple.copy(alpha = 0.18f)
            ConnectionState.Connected -> ChromePurple
            ConnectionState.Reconnecting -> ChromePurple.copy(alpha = 0.18f)
            ConnectionState.Backgrounded -> InkCard
            ConnectionState.Expired -> InkCard
        },
    ) {
        Text(
            text = state.label.uppercase(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (state == ConnectionState.Connected) {
                androidx.compose.ui.graphics.Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
