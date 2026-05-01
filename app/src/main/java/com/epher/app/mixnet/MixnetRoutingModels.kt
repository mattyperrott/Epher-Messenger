package com.epher.app.mixnet

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class MixnetInviteRouteHints(
    val version: Int = CURRENT_VERSION,
    val roomAlias: String,
    val roomAccessProof: String,
    val providerId: String,
    val ingressGatewayId: String,
    val routeId: String,
    val mixHopIds: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("roomAlias", roomAlias)
        .put("roomAccessProof", roomAccessProof)
        .put("providerId", providerId)
        .put("ingressGatewayId", ingressGatewayId)
        .put("routeId", routeId)
        .put("mixHopIds", JSONArray(mixHopIds))

    fun encode(): String = toJson().toString()

    companion object {
        const val CURRENT_VERSION = 2

        fun derive(
            roomId: String,
            roomPassword: String,
        ): MixnetInviteRouteHints {
            val seed = digestHex("mixnet.seed:$roomId:$roomPassword")
            val roomAlias = digestHex("mixnet.room-alias:$roomId:$roomPassword").take(32)
            val roomAccessProof = digestHex("mixnet.room-access:$roomId:$roomPassword")
            val gatewayIndex = seed.substring(0, 2).toInt(16) % ENTRY_GATEWAYS.size
            val providerIndex = seed.substring(2, 4).toInt(16) % PROVIDERS.size
            val templateIndex = seed.substring(4, 6).toInt(16) % MIX_ROUTE_TEMPLATES.size
            return MixnetInviteRouteHints(
                roomAlias = roomAlias,
                roomAccessProof = roomAccessProof,
                providerId = PROVIDERS[providerIndex],
                ingressGatewayId = ENTRY_GATEWAYS[gatewayIndex],
                routeId = digestHex("mixnet.route:$seed").take(20),
                mixHopIds = MIX_ROUTE_TEMPLATES[templateIndex],
            )
        }

        fun fromJsonObject(json: JSONObject?): MixnetInviteRouteHints? {
            if (json == null) return null
            val roomAlias = json.optString("roomAlias").trim()
            val roomAccessProof = json.optString("roomAccessProof").trim()
            val providerId = json.optString("providerId").trim()
            val ingressGatewayId = json.optString("ingressGatewayId").trim()
            val routeId = json.optString("routeId").trim()
            val mixHopIds = json.optJSONArray("mixHopIds")
                ?.let { array ->
                    buildList {
                        repeat(array.length()) { index ->
                            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
                .orEmpty()
            if (
                roomAlias.isBlank() ||
                roomAccessProof.isBlank() ||
                providerId.isBlank() ||
                ingressGatewayId.isBlank() ||
                routeId.isBlank() ||
                mixHopIds.isEmpty()
            ) {
                return null
            }
            return MixnetInviteRouteHints(
                version = json.optInt("version", CURRENT_VERSION),
                roomAlias = roomAlias,
                roomAccessProof = roomAccessProof,
                providerId = providerId,
                ingressGatewayId = ingressGatewayId,
                routeId = routeId,
                mixHopIds = mixHopIds,
            )
        }

        fun fromJsonString(json: String?): MixnetInviteRouteHints? = runCatching {
            json?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.let(::fromJsonObject)
        }.getOrNull()

        fun deriveMailboxAlias(
            roomAlias: String,
            providerId: String,
            transportSeedHex: String,
        ): String = digestHex("mixnet.mailbox:$roomAlias:$providerId:$transportSeedHex").take(40)

        private fun digestHex(source: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private val ENTRY_GATEWAYS = listOf(
            "entry-gateway-a",
            "entry-gateway-b",
            "entry-gateway-c",
        )

        private val PROVIDERS = listOf(
            "provider-alpha",
            "provider-bravo",
            "provider-charlie",
        )

        private val MIX_ROUTE_TEMPLATES = listOf(
            listOf("mix-a", "mix-b", "mix-c"),
            listOf("mix-d", "mix-e", "mix-f"),
            listOf("mix-g", "mix-h", "mix-i"),
        )
    }
}
