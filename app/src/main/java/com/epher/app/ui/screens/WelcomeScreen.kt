package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GroupWork
import androidx.compose.material.icons.rounded.Groups
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.data.model.ConnectionState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.GlitchLogo
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue

@Composable
fun WelcomeScreen(
    latestRoom: RoomSummary?,
    latestRoomParticipants: List<Participant>,
    roomCount: Int,
    localDisplayName: String,
    onCreateRoom: () -> Unit,
    onJoinRoom: () -> Unit,
    onBrowseRooms: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLatestInvite: (String) -> Unit,
    onResumeLatestRoom: (String) -> Unit,
) {
    val indicators = sessionIndicators(latestRoom, latestRoomParticipants)
    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    GlitchLogo(modifier = Modifier.size(60.dp), size = 46)
                },
                right = {
                    IconButton(onClick = onBrowseRooms) {
                        Icon(Icons.Rounded.GroupWork, contentDescription = "Local rooms")
                    }
                    IconButton(onClick = onCreateRoom) {
                        Icon(Icons.Rounded.Add, contentDescription = "Create room")
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    GlitchLogo(size = 98)
                    Text(
                        text = buildAnnotatedString {
                            append("Your username: ")
                            withStyle(SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)) {
                                append(localDisplayName)
                            }
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = latestRoom != null) {
                                latestRoom?.let { onResumeLatestRoom(it.id) }
                            },
                        color = InkCard,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (latestRoom == null) "READY" else "LATEST ROOM",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MistBlue,
                                )
                                Text(
                                    text = latestRoom?.let(::displayRoomLabel) ?: "No local room yet",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 21.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (latestRoom != null) {
                                    Text(
                                        text = latestRoom.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = latestRoom.connectionState.label,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (latestRoom.connectionState == ConnectionState.Connected) {
                                            ChromePurple
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                } else {
                                    Text(
                                        text = "Create a room or join one from an invite to get started.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    latestRoom?.let { onResumeLatestRoom(it.id) } ?: onCreateRoom()
                                },
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowOutward,
                                    contentDescription = if (latestRoom == null) "Create room" else "Open latest room",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OutlinedButton(
                            onClick = onJoinRoom,
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.5.dp, ChromePurple),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Text("JOIN ROOM")
                        }
                        Button(
                            onClick = onCreateRoom,
                            modifier = Modifier
                                .weight(1f)
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ChromePurple,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text("CREATE ROOM")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = InkCard.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Groups,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(28.dp),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "ROOM DIRECTORY",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Resume cached rooms, inspect invite details, and keep chats moving.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            BottomStatusStrip(indicators = indicators)
        }
    }
}
