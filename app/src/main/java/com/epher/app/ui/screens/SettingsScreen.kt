package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.BuildConfig
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.SignalMint
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
                    Text(
                        text = "SETTINGS",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                },
                right = {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Settings, contentDescription = null)
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
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    Text(
                        text = "Build Controls",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                    Text(
                        text = "Local device identity, transport mode, and privacy defaults for this build.",
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SettingsCard(
                        title = "INSTALL IDENTITY",
                        icon = Icons.Rounded.Fingerprint,
                        body = "This install keeps a local device fingerprint, but active room personas are generated per room and can be wiped independently.",
                        rows = listOf(
                            SettingsRow("Display Name", localDisplayName),
                            SettingsRow("Install Fingerprint", localFingerprint, maxLines = 2),
                        ),
                    ) {
                    }

                    SettingsCard(
                        title = "TRANSPORT",
                        icon = Icons.Rounded.Lock,
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
                        rows = listOf(
                            SettingsRow(
                                "Mode",
                                when {
                                    hasMixnetTransport -> "Single-hop development mix relay"
                                    hasRoomRelayTransport -> "Configured room relay"
                                    hasBareTransport -> "Hyperswarm peer-to-peer"
                                    else -> "No transport configured"
                                },
                                valueColor = if (hasMixnetTransport || hasRoomRelayTransport || hasBareTransport) SignalMint else MistBlue,
                            ),
                            SettingsRow(
                                "Transport Profile",
                                when {
                                    hasMixnetTransport -> "Padded mailbox relay with scheduled polling"
                                    hasRoomRelayTransport -> "Straight room relay delivery"
                                    hasBareTransport -> "Hyperswarm DHT discovery with Noise-encrypted peer sockets"
                                    else -> "Local-only encrypted state"
                                },
                                maxLines = 2,
                            ),
                            SettingsRow("Build", if (BuildConfig.DEBUG) "Debug" else "Production"),
                            SettingsRow("Background Networking", if (BuildConfig.KEEP_NETWORKING_ALIVE_IN_BACKGROUND) "Enabled" else "Disabled"),
                        ),
                    ) {
                    }

                    SettingsCard(
                        title = "PRIVACY DEFAULTS",
                        icon = Icons.Rounded.Shield,
                        body = "Messages are encrypted end to end. Room secrets, room personas, and peer state stay on the device, with no central plaintext message storage.",
                        rows = listOf(
                            SettingsRow("Message Storage", "No central plaintext message storage"),
                            SettingsRow("Local Retention", "Per-room retention preset"),
                            SettingsRow("Room Personas", "Per-room and wipeable on leave or expiry"),
                            SettingsRow("Invite Flow", "Signed invite token + local verification"),
                            SettingsRow(
                                "Traffic Shaping",
                                when {
                                    hasMixnetTransport -> "Fixed-size packets, jitter, and scheduled mailbox polling on the mix relay path"
                                    hasRoomRelayTransport -> "No active traffic shaping on the room relay path"
                                    hasBareTransport -> "No active traffic shaping on the direct peer-to-peer path"
                                    else -> "No transport active"
                                },
                                maxLines = 3,
                            ),
                            SettingsRow("Group Rekey", "Owner removal rotates future room traffic to a new epoch", maxLines = 2),
                            SettingsRow("Attachments", "Experimental and not part of canonical v1", valueColor = MistBlue),
                            SettingsRow("Protocol Mimicry", "Planned for v2, not active in this build", valueColor = MistBlue),
                        ),
                    ) {
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
    icon: ImageVector,
    body: String,
    rows: List<SettingsRow>,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = InkCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        shadowElevation = 4.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.20f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, tint = MistBlue, modifier = Modifier.size(17.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 10.sp,
                        letterSpacing = 1.8.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MistBlue,
                )
            }
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal, lineHeight = 20.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                rows.forEach { row ->
                    SettingsDetailRow(row)
                }
            }
            content()
        }
    }
}

private data class SettingsRow(
    val title: String,
    val value: String,
    val maxLines: Int = 1,
    val valueColor: Color? = null,
)

@Composable
private fun SettingsDetailRow(row: SettingsRow) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.title,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MistBlue,
        )
        Text(
            text = row.value,
            modifier = Modifier.weight(0.58f),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = row.valueColor ?: MaterialTheme.colorScheme.onSurface,
            maxLines = row.maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
