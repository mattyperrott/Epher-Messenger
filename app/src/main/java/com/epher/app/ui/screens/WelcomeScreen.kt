package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.GroupWork
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
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
import androidx.compose.ui.draw.clip
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
import com.epher.app.ui.components.GlitchLogo
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.InkPanel
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.SignalMint
import com.epher.app.ui.theme.TideTeal

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlitchLogo(modifier = Modifier.size(50.dp), size = 42)
                        Text(
                            text = "EPHER",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                letterSpacing = (-0.6).sp,
                            ),
                            color = Color.White,
                        )
                    }
                },
                right = {
                    IconButton(onClick = onBrowseRooms) {
                        Icon(Icons.Rounded.GroupWork, contentDescription = "Local rooms")
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
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    HomeIdentityHeader(localDisplayName = localDisplayName)
                    LatestRoomHeroCard(
                        latestRoom = latestRoom,
                        onCreateRoom = onCreateRoom,
                        onResumeLatestRoom = onResumeLatestRoom,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = onCreateRoom,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ChromePurple,
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("CREATE ROOM")
                        }
                        OutlinedButton(
                            onClick = onJoinRoom,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(2.dp, InkCard),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = InkPanel,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Icon(
                                Icons.Rounded.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("JOIN ROOM")
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .clickable(onClick = onBrowseRooms),
                        color = InkPanel,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Groups,
                                contentDescription = null,
                                tint = MistBlue,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "$roomCount ${if (roomCount == 1) "ROOM" else "ROOMS"} IN MEMORY",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontSize = 12.sp,
                                    letterSpacing = 0.9.sp,
                                ),
                                color = MistBlue,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
            BottomStatusStrip(indicators = indicators)
        }
    }
}

@Composable
private fun HomeIdentityHeader(localDisplayName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(66.dp),
            shape = CircleShape,
            color = InkCard,
            border = BorderStroke(2.dp, InkPanel),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TideTeal.copy(alpha = 0.12f)),
                )
                Icon(
                    imageVector = Icons.Rounded.Key,
                    contentDescription = null,
                    tint = TideTeal.copy(alpha = 0.88f),
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "LOCAL IDENTITY",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 10.sp,
                    letterSpacing = 1.8.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MistBlue,
            )
            Text(
                text = localDisplayName,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LatestRoomHeroCard(
    latestRoom: RoomSummary?,
    onCreateRoom: () -> Unit,
    onResumeLatestRoom: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable {
                latestRoom?.let { onResumeLatestRoom(it.id) } ?: onCreateRoom()
            },
        color = InkCard,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 52.dp, y = (-52).dp)
                    .size(172.dp)
                    .clip(CircleShape)
                    .background(ChromePurple.copy(alpha = 0.07f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (latestRoom == null) "READY" else "LATEST ROOM",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 10.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MistBlue,
                    )
                    Icon(
                        Icons.Rounded.ArrowOutward,
                        contentDescription = if (latestRoom == null) "Create room" else "Open latest room",
                        tint = MistBlue,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = latestRoom?.let(::displayRoomLabel) ?: "No local room yet",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = if (latestRoom == null) 25.sp else 30.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (latestRoom != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(7.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    ) {
                        Text(
                            text = "ID: ${latestRoom.id}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MistBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (latestRoom.connectionState == ConnectionState.Connected) {
                                        SignalMint
                                    } else {
                                        ChromePurpleDark
                                    },
                                ),
                        )
                        Text(
                            text = latestRoom.connectionState.label.uppercase(),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.sp,
                                letterSpacing = 1.1.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = if (latestRoom.connectionState == ConnectionState.Connected) {
                                Color.White
                            } else {
                                MistBlue
                            },
                        )
                    }
                } else {
                    Text(
                        text = "Create a room or join one from an invite to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
