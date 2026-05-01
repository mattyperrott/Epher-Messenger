package com.epher.app

import android.content.Context
import android.util.Log
import com.epher.app.data.AttachmentFileStore
import com.epher.app.data.LocalRoomCacheStore
import com.epher.app.data.RoomsRepository
import com.epher.app.data.StartupDisplayNameGenerator
import com.epher.app.engine.BareWorkletEngine
import com.epher.app.engine.MixnetRelayEngine
import com.epher.app.engine.P2PEngine
import com.epher.app.engine.RelayWebSocketEngine
import com.epher.app.engine.UnavailableTransportEngine
import com.epher.app.security.DeviceIdentityManager
import com.epher.app.security.PairwiseProtocolService
import com.epher.app.security.RoomIdentityManager
import com.epher.app.security.SecureBlobStore
import com.epher.app.security.SecureRoomService
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppContainer(
    context: Context,
) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rawRoomRelayUrl = BuildConfig.ROOM_RELAY_URL.trim()
    private val rawMixnetRelayUrl = BuildConfig.MIXNET_RELAY_URL.trim()
    private val rawMixnetProviderUrl = BuildConfig.MIXNET_PROVIDER_URL.trim()
    private val roomRelayUrl = validatedTransportUrl(
        rawUrl = rawRoomRelayUrl,
        secureScheme = "wss",
        insecureDebugScheme = "ws",
    )
    private val mixnetRelayUrl = validatedTransportUrl(
        rawUrl = rawMixnetRelayUrl,
        secureScheme = "wss",
        insecureDebugScheme = "ws",
    )
    private val mixnetProviderUrl = validatedTransportUrl(
        rawUrl = rawMixnetProviderUrl,
        secureScheme = "https",
        insecureDebugScheme = "http",
    )
    private val transportConfigurationIssues = listOfNotNull(
        transportConfigIssue(
            envName = "MIXNET_RELAY_URL",
            rawUrl = rawMixnetRelayUrl,
            validatedUrl = mixnetRelayUrl,
            secureScheme = "wss",
            insecureDebugScheme = "ws",
        ),
        transportConfigIssue(
            envName = "MIXNET_PROVIDER_URL",
            rawUrl = rawMixnetProviderUrl,
            validatedUrl = mixnetProviderUrl,
            secureScheme = "https",
            insecureDebugScheme = "http",
        ),
        transportConfigIssue(
            envName = "ROOM_RELAY_URL",
            rawUrl = rawRoomRelayUrl,
            validatedUrl = roomRelayUrl,
            secureScheme = "wss",
            insecureDebugScheme = "ws",
        ),
    )
    private val transportModeLabel = when {
        useConfiguredMixnetRelayTransport() -> "Development mix relay"
        useConfiguredRelayTransport() -> "Relay-assisted delivery"
        useBareTransport() -> "Hyperswarm peer-to-peer"
        else -> "Transport unavailable"
    }
    private val transportEncryptionLabel = when {
        useConfiguredMixnetRelayTransport() ->
            "Client-side E2EE envelopes over padded relay frames"
        useConfiguredRelayTransport() ->
            "Client-side E2EE envelopes over a room relay"
        useBareTransport() ->
            "Noise protocol transport encryption + client-side E2EE room envelopes"
        else -> "No active transport configured in this build"
    }
    private val trafficObfuscationLabel = when {
        useConfiguredMixnetRelayTransport() ->
            "Fixed-size packets + jitter + cover traffic + mailbox polling"
        useConfiguredRelayTransport() ->
            "No active traffic shaping on the room relay path"
        useBareTransport() ->
            "No active traffic shaping on the direct peer-to-peer path"
        else -> "No active transport configured in this build"
    }
    private val metadataRetentionLabel = when {
        useConfiguredMixnetRelayTransport() ->
            "No central plaintext storage; transient mailbox ciphertext + local encrypted session state"
        useConfiguredRelayTransport() ->
            "No central plaintext storage; relay-transit ciphertext + local encrypted session state"
        useBareTransport() ->
            "No central message server; direct peer-to-peer + local encrypted session state"
        else -> "No central transport active; local encrypted session state only"
    }
    private val overlayModeLabel = when {
        useConfiguredMixnetRelayTransport() -> "No overlay tunnel; development mix relay transport active"
        useConfiguredRelayTransport() -> "No overlay tunnel; room relay transport active"
        useBareTransport() -> "Hyperswarm DHT; Noise-encrypted direct peer sockets"
        else -> "No active transport"
    }
    private val secureBlobStore = SecureBlobStore(context)
    private val localRoomCacheStore = LocalRoomCacheStore(secureBlobStore)
    private val attachmentFileStore = AttachmentFileStore(context)
    private val identityManager = DeviceIdentityManager(secureBlobStore)
    private val roomIdentityManager = RoomIdentityManager(secureBlobStore)
    private val roomSecurity = SecureRoomService(
        secureBlobStore = secureBlobStore,
        identityManager = identityManager,
        roomIdentityManager = roomIdentityManager,
        transportEncryptionLabel = transportEncryptionLabel,
        trafficObfuscationLabel = trafficObfuscationLabel,
        metadataRetentionLabel = metadataRetentionLabel,
        transportModeLabel = transportModeLabel,
        overlayModeLabel = overlayModeLabel,
    )
    private val pairwiseProtocol = PairwiseProtocolService(roomSecurity, secureBlobStore, roomIdentityManager)
    private val startupDisplayName = StartupDisplayNameGenerator.generate()
    private val engine: P2PEngine = if (useConfiguredMixnetRelayTransport()) {
        MixnetRelayEngine(
            relayUrl = mixnetRelayUrl,
            providerBaseUrl = mixnetProviderUrl,
            scope = applicationScope,
            clientId = "mix-android-${UUID.randomUUID().toString().take(8)}",
        )
    } else if (useConfiguredRelayTransport()) {
        RelayWebSocketEngine(
            relayUrl = roomRelayUrl,
            scope = applicationScope,
            clientId = "android-${UUID.randomUUID().toString().take(8)}",
        )
    } else if (transportConfigurationIssues.isEmpty() && useBareTransport()) {
        // No relay configured — use the Hyperswarm P2P engine directly.
        BareWorkletEngine(
            assets = context.assets,
            bootstrapNodes = BuildConfig.HYPERSWARM_BOOTSTRAP_NODES
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            bootstrapRelayPublicKeys = BuildConfig.HYPERSWARM_RELAY_PUBLIC_KEYS
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() },
            scope = applicationScope,
        )
    } else {
        UnavailableTransportEngine(
            runtimeLabel = "Transport blocked",
            detail = if (transportConfigurationIssues.isNotEmpty()) {
                transportConfigurationIssues.joinToString(" ")
            } else {
                "No relay configured. Bare transport is disabled in this build."
            },
        )
    }

    val repository = RoomsRepository(
        scope = applicationScope,
        engine = engine,
        localCacheStore = localRoomCacheStore,
        attachmentFileStore = attachmentFileStore,
        roomSecurity = roomSecurity,
        pairwiseProtocol = pairwiseProtocol,
        localDisplayName = startupDisplayName,
        allowOptimisticConnectedState = false,
    )

    private fun useConfiguredRelayTransport(): Boolean = roomRelayUrl.isNotEmpty()
    private fun useConfiguredMixnetRelayTransport(): Boolean =
        mixnetRelayUrl.isNotEmpty() && mixnetProviderUrl.isNotEmpty()
    private fun useBareTransport(): Boolean = BuildConfig.ENABLE_BARE_TRANSPORT

    init {
        val detail = if (useConfiguredMixnetRelayTransport()) {
            "mix relay at $mixnetRelayUrl"
        } else if (useConfiguredRelayTransport()) {
            "room relay at $roomRelayUrl"
        } else if (useBareTransport()) {
            "Hyperswarm P2P engine"
        } else if (transportConfigurationIssues.isNotEmpty()) {
            transportConfigurationIssues.joinToString(" ")
        } else {
            "transport unavailable (configure relay URLs or explicitly enable Bare transport)"
        }
        Log.d(TAG, "App container using $detail as $startupDisplayName")
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up app container resources")
        applicationScope.cancel()
    }

    private fun validatedTransportUrl(
        rawUrl: String,
        secureScheme: String,
        insecureDebugScheme: String,
    ): String {
        if (rawUrl.isBlank()) return ""
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return ""
        val scheme = uri.scheme?.lowercase() ?: return ""
        val host = uri.host?.lowercase() ?: return ""
        if (scheme == secureScheme) return rawUrl
        if (BuildConfig.DEBUG && scheme == insecureDebugScheme && isAllowedInsecureDebugHost(host)) {
            return rawUrl
        }
        return ""
    }

    private fun transportConfigIssue(
        envName: String,
        rawUrl: String,
        validatedUrl: String,
        secureScheme: String,
        insecureDebugScheme: String,
    ): String? {
        if (rawUrl.isBlank() || validatedUrl.isNotEmpty()) return null
        return "$envName must use $secureScheme://. Debug builds may use $insecureDebugScheme:// only for localhost or 10.0.2.2."
    }

    private fun isAllowedInsecureDebugHost(host: String): Boolean = host in setOf(
        "127.0.0.1",
        "localhost",
        "10.0.2.2",
    )

    private companion object {
        const val TAG = "AppContainer"
    }
}
