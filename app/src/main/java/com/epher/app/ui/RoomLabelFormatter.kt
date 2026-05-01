package com.epher.app.ui

import com.epher.app.data.model.RoomSummary
import kotlin.math.absoluteValue

private val fallbackAdjectives = listOf(
    "Night",
    "Harbor",
    "Signal",
    "Quiet",
    "North",
    "Safe",
    "Dockside",
    "Hidden",
)

private val fallbackNouns = listOf(
    "Shift",
    "Watch",
    "Room",
    "Channel",
    "Relay",
    "Pod",
    "Circle",
    "Safehouse",
)

fun displayRoomLabel(room: RoomSummary): String = displayRoomLabel(room.localLabel, room.id)

fun displayRoomLabel(
    localLabel: String,
    roomId: String,
): String {
    val trimmed = localLabel.trim()
    if (trimmed.isNotBlank() && !trimmed.equals("Private Room", ignoreCase = true)) {
        return trimmed
    }

    val hash = roomId.hashCode().absoluteValue
    val adjective = fallbackAdjectives[hash % fallbackAdjectives.size]
    val noun = fallbackNouns[(hash / fallbackAdjectives.size) % fallbackNouns.size]
    return "$adjective $noun"
}
