package com.epher.app.data.model

enum class InviteExpiryPreset(
    val label: String,
    val detail: String,
    val durationMillis: Long?,
) {
    Hour("1 hour", "Invite link expires 1 hour after room creation.", 60L * 60L * 1000L),
    Day("24 hours", "Invite link expires 24 hours after room creation.", 24L * 60L * 60L * 1000L),
    Week("7 days", "Invite link expires 7 days after room creation.", 7L * 24L * 60L * 60L * 1000L),
}
