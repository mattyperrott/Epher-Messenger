package com.epher.app.data.model

enum class RetentionPreset(
    val label: String,
    val detail: String,
    val maxAgeMillis: Long?,
) {
    LeaveOnly("Delete on leave", "Local room content is removed when you leave.", null),
    Day("24 hours", "Room cache expires after 24 hours of inactivity.", 24L * 60L * 60L * 1000L),
    Week("7 days", "Room cache expires after 7 days of inactivity.", 7L * 24L * 60L * 60L * 1000L),
}
