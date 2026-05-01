package com.epher.app.ui.navigation

import android.net.Uri

sealed interface EpherDestination {
    val route: String

    data object Welcome : EpherDestination {
        override val route = "welcome"
    }

    data object Rooms : EpherDestination {
        override val route = "rooms"
    }

    data object CreateRoom : EpherDestination {
        override val route = "create-room"
    }

    data object JoinRoom : EpherDestination {
        override val route = "join-room?invite={inviteToken}"
        fun createRoute(inviteToken: String? = null): String = if (inviteToken.isNullOrBlank()) {
            "join-room"
        } else {
            "join-room?invite=${Uri.encode(inviteToken)}"
        }
    }

    data object Settings : EpherDestination {
        override val route = "settings"
    }

    data object RoomChat : EpherDestination {
        override val route = "room/{roomId}"
        fun createRoute(roomId: String) = "room/$roomId"
    }

    data object RoomRoster : EpherDestination {
        override val route = "room/{roomId}/roster"
        fun createRoute(roomId: String) = "room/$roomId/roster"
    }

    data object RoomSafety : EpherDestination {
        override val route = "room/{roomId}/safety"
        fun createRoute(roomId: String) = "room/$roomId/safety"
    }
}
