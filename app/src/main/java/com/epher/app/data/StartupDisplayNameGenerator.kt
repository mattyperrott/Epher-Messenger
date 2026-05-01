package com.epher.app.data

import kotlin.random.Random

object StartupDisplayNameGenerator {
    private val adjectives = listOf(
        "Silent",
        "North",
        "Night",
        "Signal",
        "Quiet",
        "Harbor",
        "Cinder",
        "Echo",
        "Shadow",
        "Static",
        "Velvet",
        "Copper",
        "Drift",
        "Neon",
        "Granite",
    )

    private val nouns = listOf(
        "Relay",
        "Harbor",
        "Cipher",
        "Atlas",
        "Fox",
        "Comet",
        "Pilot",
        "Beacon",
        "Current",
        "Nomad",
        "Rook",
        "Signal",
        "Runner",
        "Vector",
        "Anchor",
    )

    fun generate(random: Random = Random.Default): String {
        val adjective = adjectives.random(random)
        val noun = nouns.random(random)
        return "$adjective $noun"
    }
}
