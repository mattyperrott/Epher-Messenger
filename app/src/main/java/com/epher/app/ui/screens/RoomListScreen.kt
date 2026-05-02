package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.ArrowOutward
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.InkPanel
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.SignalMint

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
                    Text(
                        text = "ROOM DIRECTORY",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.6.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                },
                right = {
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
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "Local Rooms",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                            )
                            Text(
                                text = "Local and recently joined rooms stay here until you leave or the retention window expires.",
                                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onJoinRoom,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(2.dp, ChromePurple),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = ChromePurple.copy(alpha = 0.30f),
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Icon(Icons.Rounded.GroupAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("JOIN")
                            }
                            Button(
                                onClick = onCreateRoom,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ChromePurple,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("CREATE")
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
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(130.dp)
                    .background(ChromePurple.copy(alpha = 0.04f), CircleShape),
            )
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = displayRoomLabel(room),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.20f),
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                        ) {
                            Text(
                                text = "ID: ${room.id}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MistBlue,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoomStateBadge(state = room.connectionState)
                        Icon(
                            Icons.Rounded.ArrowOutward,
                            contentDescription = null,
                            tint = MistBlue,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
            ConnectionState.Backgrounded -> InkPanel
            ConnectionState.Expired -> ChromePurpleDark
        },
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(
                        if (state == ConnectionState.Connected) SignalMint else MistBlue.copy(alpha = 0.52f),
                        CircleShape,
                    ),
            )
        Text(
            text = state.label.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp,
                ),
            color = if (state == ConnectionState.Connected) {
                    Color.White
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
}
