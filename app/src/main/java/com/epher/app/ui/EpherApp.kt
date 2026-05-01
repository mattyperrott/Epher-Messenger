package com.epher.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.epher.app.ui.navigation.EpherDestination
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.NoticeCard
import com.epher.app.ui.screens.CreateRoomScreen
import com.epher.app.ui.screens.JoinRoomScreen
import com.epher.app.ui.screens.RoomChatScreen
import com.epher.app.ui.screens.RoomListScreen
import com.epher.app.ui.screens.RoomRosterScreen
import com.epher.app.ui.screens.RoomSafetyScreen
import com.epher.app.ui.screens.SettingsScreen
import com.epher.app.ui.screens.WelcomeScreen

@Composable
fun EpherApp(
    viewModel: EpherViewModel,
) {
    val navController = rememberNavController()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.pendingInviteToken) {
        val inviteToken = uiState.pendingInviteToken
        if (!inviteToken.isNullOrBlank()) {
            navController.navigate(EpherDestination.JoinRoom.createRoute(inviteToken)) {
                launchSingleTop = true
            }
            viewModel.consumePendingInvite()
        }
    }

    NavHost(
        navController = navController,
        startDestination = EpherDestination.Welcome.route,
    ) {
        composable(EpherDestination.Welcome.route) {
            WelcomeScreen(
                latestRoom = snapshot.rooms.firstOrNull(),
                latestRoomParticipants = snapshot.rooms.firstOrNull()?.let { viewModel.participants(it.id) }.orEmpty(),
                roomCount = snapshot.rooms.size,
                localDisplayName = viewModel.localDisplayName(),
                onCreateRoom = { navController.navigate(EpherDestination.CreateRoom.route) },
                onJoinRoom = { navController.navigate(EpherDestination.JoinRoom.createRoute()) },
                onBrowseRooms = { navController.navigate(EpherDestination.Rooms.route) },
                onOpenSettings = { navController.navigate(EpherDestination.Settings.route) },
                onOpenLatestInvite = { roomId ->
                    navController.navigate(EpherDestination.RoomSafety.createRoute(roomId))
                },
                onResumeLatestRoom = { roomId ->
                    navController.navigate(EpherDestination.RoomChat.createRoute(roomId))
                },
            )
        }
        composable(EpherDestination.Rooms.route) {
            RoomListScreen(
                rooms = snapshot.rooms,
                participantsByRoom = snapshot.participants,
                onBack = { navController.popBackStack() },
                onCreateRoom = { navController.navigate(EpherDestination.CreateRoom.route) },
                onJoinRoom = { navController.navigate(EpherDestination.JoinRoom.createRoute()) },
                onOpenSettings = { navController.navigate(EpherDestination.Settings.route) },
                onOpenRoom = { roomId ->
                    navController.navigate(EpherDestination.RoomChat.createRoute(roomId))
                },
            )
        }
        composable(EpherDestination.CreateRoom.route) {
            CreateRoomScreen(
                isSubmitting = uiState.isCreatingRoom,
                errorMessage = uiState.createRoomError,
                onBack = { navController.popBackStack() },
                onDismissError = viewModel::clearCreateRoomError,
                onCreateRoom = { label, retention, inviteExpiry ->
                    viewModel.createRoom(label, retention, inviteExpiry) { roomId ->
                        navController.navigate(EpherDestination.RoomChat.createRoute(roomId)) {
                            launchSingleTop = true
                            popUpTo(EpherDestination.Welcome.route)
                        }
                    }
                },
            )
        }
        composable(
            route = EpherDestination.JoinRoom.route,
            arguments = listOf(
                navArgument("inviteToken") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val prefilledInviteToken = backStackEntry.arguments?.getString("inviteToken")
            JoinRoomScreen(
                prefilledInviteToken = prefilledInviteToken,
                isSubmitting = uiState.isJoiningRoom,
                errorMessage = uiState.joinRoomError,
                onBack = { navController.popBackStack() },
                onDismissError = viewModel::clearJoinRoomError,
                onJoinRoom = { invite ->
                    viewModel.joinRoom(invite) { roomId ->
                        navController.navigate(EpherDestination.RoomChat.createRoute(roomId)) {
                            launchSingleTop = true
                            popUpTo(EpherDestination.Welcome.route)
                        }
                    }
                },
            )
        }
        composable(
            route = EpherDestination.RoomChat.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId").orEmpty()
            val room = snapshot.rooms.firstOrNull { it.id == roomId }
            val participants = snapshot.participants[roomId].orEmpty()
            val messages = snapshot.messages[roomId].orEmpty()
            val logLines = snapshot.logs[GLOBAL_LOG_ROOM_ID].orEmpty() + snapshot.logs[roomId].orEmpty()
            if (room != null) {
                RoomChatScreen(
                    room = room,
                    messages = messages,
                    participants = participants,
                    logLines = logLines,
                    onBack = { navController.popBackStack() },
                    onOpenRoster = { navController.navigate(EpherDestination.RoomRoster.createRoute(roomId)) },
                    onOpenSafety = { navController.navigate(EpherDestination.RoomSafety.createRoute(roomId)) },
                    onLeaveRoom = {
                        viewModel.leaveRoom(roomId)
                        navController.navigate(EpherDestination.Rooms.route) {
                            popUpTo(EpherDestination.Rooms.route) { inclusive = true }
                        }
                    },
                    onSendMessage = { text -> viewModel.sendMessage(roomId, text) },
                    onSendAttachment = { fileName, mimeType, bytes, onError ->
                        viewModel.sendAttachment(roomId, fileName, mimeType, bytes, onError)
                    },
                    onResolveAttachment = { messageId ->
                        viewModel.resolveAttachment(roomId, messageId)
                    },
                )
            } else {
                MissingRoomScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = EpherDestination.RoomRoster.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId").orEmpty()
            val room = snapshot.rooms.firstOrNull { it.id == roomId }
            if (room != null) {
                RoomRosterScreen(
                    room = room,
                    participants = snapshot.participants[roomId].orEmpty(),
                    onRemoveParticipant = { fingerprint ->
                        viewModel.removeParticipant(roomId, fingerprint)
                    },
                    onBack = { navController.popBackStack() },
                )
            } else {
                MissingRoomScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(
            route = EpherDestination.RoomSafety.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId").orEmpty()
            val room = snapshot.rooms.firstOrNull { it.id == roomId }
            if (room != null) {
                RoomSafetyScreen(
                    room = room,
                    participants = snapshot.participants[roomId].orEmpty(),
                    fingerprint = viewModel.roomFingerprint(roomId) ?: "UNKNOWN",
                    onBack = { navController.popBackStack() },
                    onLeaveRoom = {
                        viewModel.leaveRoom(roomId)
                        navController.navigate(EpherDestination.Rooms.route) {
                            popUpTo(EpherDestination.Rooms.route) { inclusive = true }
                        }
                    },
                )
            } else {
                MissingRoomScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(EpherDestination.Settings.route) {
            SettingsScreen(
                localDisplayName = viewModel.localDisplayName(),
                localFingerprint = viewModel.localFingerprint(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

private const val GLOBAL_LOG_ROOM_ID = "__global__"

@Composable
private fun MissingRoomScreen(
    onBack: () -> Unit,
) {
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
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NoticeCard(
                        title = "ROOM NOT AVAILABLE",
                        body = "This room may have been wiped, expired, or not finished loading. Return to the room list and try again.",
                    )
                    Text(
                        text = "Use the back gesture or top navigation to return.",
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
