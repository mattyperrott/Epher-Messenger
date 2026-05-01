package com.epher.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.epher.app.BuildConfig
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.DetailLine
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.InkCard
import java.net.URI

@Composable
fun SettingsScreen(
    localDisplayName: String,
    localFingerprint: String,
    onBack: () -> Unit,
) {
    val hasMixnetTransport = hasAllowedTransportUrl(BuildConfig.MIXNET_RELAY_URL, "wss", "ws") &&
        hasAllowedTransportUrl(BuildConfig.MIXNET_PROVIDER_URL, "https", "http")
    val hasRoomRelayTransport = hasAllowedTransportUrl(BuildConfig.ROOM_RELAY_URL, "wss", "ws")
    val hasBareTransport = BuildConfig.ENABLE_BARE_TRANSPORT &&
        BuildConfig.ROOM_RELAY_URL.trim().isBlank() &&
        BuildConfig.MIXNET_RELAY_URL.trim().isBlank() &&
        BuildConfig.MIXNET_PROVIDER_URL.trim().isBlank()

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                right = {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Local device identity, transport mode, and privacy defaults for this build.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SettingsCard(
                        title = "INSTALL IDENTITY",
                        body = "This install keeps a local device fingerprint, but active room personas are generated per room and can be wiped independently.",
                    ) {
                        DetailLine(title = "DISPLAY NAME", value = localDisplayName)
                        DetailLine(title = "INSTALL FINGERPRINT", value = localFingerprint)
                    }

                    SettingsCard(
                        title = "TRANSPORT",
                        body = when {
                            hasMixnetTransport -> {
                                "This build uses the current mix-relay path with padded packets and scheduled mailbox polling."
                            }
                            hasRoomRelayTransport -> {
                                "This build uses the configured room relay path for encrypted message delivery between peers."
                            }
                            hasBareTransport -> {
                                "This build uses the Bare/Hyperswarm peer-to-peer transport when no relay is configured."
                            }
                            else -> {
                                "This build does not have a usable secure relay transport configured, so networking is unavailable until a valid relay URL is provided."
                            }
                        },
                    ) {
                        DetailLine(
                            title = "MODE",
                            value = when {
                                hasMixnetTransport -> "Single-hop development mix relay"
                                hasRoomRelayTransport -> "Configured room relay"
                                hasBareTransport -> "Hyperswarm peer-to-peer"
                                else -> "No transport configured"
                            },
                        )
                        DetailLine(
                            title = "TRANSPORT PROFILE",
                            value = when {
                                hasMixnetTransport -> "Padded mailbox relay with scheduled polling"
                                hasRoomRelayTransport -> "Straight room relay delivery"
                                hasBareTransport -> "Hyperswarm DHT discovery with Noise-encrypted peer sockets"
                                else -> "Local-only encrypted state"
                            },
                        )
                        DetailLine(
                            title = "BUILD",
                            value = if (BuildConfig.DEBUG) "Debug" else "Production",
                        )
                    }

                    SettingsCard(
                        title = "PRIVACY DEFAULTS",
                        body = "Messages are encrypted end to end. Room secrets, room personas, and peer state stay on the device, with no central plaintext message storage.",
                    ) {
                        DetailLine(title = "MESSAGE STORAGE", value = "No central plaintext message storage")
                        DetailLine(title = "LOCAL RETENTION", value = "Per-room retention preset")
                        DetailLine(title = "ROOM PERSONAS", value = "Per-room and wipeable on leave or expiry")
                        DetailLine(title = "INVITE FLOW", value = "Signed invite token + local verification")
                        DetailLine(
                            title = "TRAFFIC SHAPING",
                            value = when {
                                hasMixnetTransport -> "Fixed-size packets, jitter, and scheduled mailbox polling on the mix relay path"
                                hasRoomRelayTransport -> "No active traffic shaping on the room relay path"
                                hasBareTransport -> "No active traffic shaping on the direct peer-to-peer path"
                                else -> "No transport active"
                            },
                        )
                        DetailLine(title = "GROUP REKEY", value = "Owner removal rotates future room traffic to a new epoch")
                        DetailLine(title = "ATTACHMENTS", value = "Experimental and not part of canonical v1")
                        DetailLine(title = "PROTOCOL MIMICRY", value = "Planned for v2, not active in this build")
                    }
                }
            }
        }
    }
}

private fun hasAllowedTransportUrl(
    rawUrl: String,
    secureScheme: String,
    insecureDebugScheme: String,
): Boolean {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return false
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase() ?: return false
    if (scheme == secureScheme) return true
    return BuildConfig.DEBUG &&
        scheme == insecureDebugScheme &&
        host in setOf("127.0.0.1", "localhost", "10.0.2.2")
}

@Composable
private fun SettingsCard(
    title: String,
    body: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = InkCard,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = ChromePurple,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
