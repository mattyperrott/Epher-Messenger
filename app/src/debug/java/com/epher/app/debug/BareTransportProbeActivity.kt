package com.epher.app.debug

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.epher.app.BuildConfig
import com.epher.app.ui.theme.EpherTheme
import kotlinx.coroutines.launch
import java.security.MessageDigest

class BareTransportProbeActivity : ComponentActivity() {

    private lateinit var session: BareTransportProbeSession
    private val logLines = mutableStateListOf<String>()

    private val probeTopicHex by lazy { sha256Hex("epher-bare-probe-v1-topic") }
    private val probeSeedHex by lazy {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        sha256Hex("${BuildConfig.APPLICATION_ID}:$androidId")
    }
    private val directServerSeedHex by lazy { sha256Hex("epher-bare-direct-server-v1") }
    private val directClientSeedHex by lazy { sha256Hex("epher-bare-direct-client-v1") }
    private val loopbackServerSeedHex by lazy { sha256Hex("${BuildConfig.APPLICATION_ID}:loopback-server-v1") }
    private val loopbackClientSeedHex by lazy { sha256Hex("${BuildConfig.APPLICATION_ID}:loopback-client-v1") }
    private val isPeerBuild by lazy { BuildConfig.APPLICATION_ID.endsWith(".peer") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        session = BareTransportProbeSession(
            assets = assets,
            bootstrapNodes = BuildConfig.HYPERSWARM_BOOTSTRAP_NODES.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            bootstrapRelayPublicKeys = BuildConfig.HYPERSWARM_RELAY_PUBLIC_KEYS.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            scope = lifecycleScope,
        )

        lifecycleScope.launch {
            session.logs.collect { line ->
                logLines.add(line)
                if (logLines.size > 400) {
                    logLines.removeRange(0, logLines.size - 400)
                }
            }
        }

        lifecycleScope.launch {
            logLines.add("starting probe topic=${probeTopicHex.take(16)} seed=${probeSeedHex.take(16)}")
            session.startProbe(topicHex = probeTopicHex, transportSeedHex = probeSeedHex)
            logLines.add("starting in-process loopback probe")
            session.startLoopbackProbe(
                transportSeedHex = probeSeedHex,
                loopbackServerSeedHex = loopbackServerSeedHex,
                loopbackClientSeedHex = loopbackClientSeedHex,
            )
            if (isPeerBuild) {
                logLines.add("starting direct probe client remote-seed=${directServerSeedHex.take(16)} localConnection=true")
                session.startDirectProbe(
                    role = "client",
                    transportSeedHex = probeSeedHex,
                    directSeedHex = directClientSeedHex,
                    remoteSeedHex = directServerSeedHex,
                    localConnection = true,
                )
            } else {
                logLines.add("starting direct probe server seed=${directServerSeedHex.take(16)}")
                session.startDirectProbe(
                    role = "server",
                    transportSeedHex = probeSeedHex,
                    directSeedHex = directServerSeedHex,
                    remoteSeedHex = directClientSeedHex,
                    localConnection = false,
                )
            }
        }

        enableEdgeToEdge()
        setContent {
            EpherTheme {
                var copied by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF111111))
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Bare Transport Probe",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            text = "package=${BuildConfig.APPLICATION_ID}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFE8DDF7)
                        )
                        Text(
                            text = "topic=${probeTopicHex.take(24)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8ABC9),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "seed=${probeSeedHex.take(24)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8ABC9),
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "direct=${if (isPeerBuild) "client" else "server"} server-seed=${directServerSeedHex.take(24)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8ABC9),
                            fontFamily = FontFamily.Monospace
                        )
                        Button(
                            onClick = {
                                copied = true
                                lifecycleScope.launch {
                                    logLines.add("manual probe restart requested")
                                    session.startProbe(topicHex = probeTopicHex, transportSeedHex = probeSeedHex)
                                }
                            }
                        ) {
                            Text(if (copied) "RESTART PROBE" else "START / RESTART")
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF1A1A1A)),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(logLines) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFE8DDF7),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            session.suspendNetworking()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            session.resumeNetworking()
        }
    }

    override fun onDestroy() {
        session.cleanup()
        super.onDestroy()
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytesToHex(digest)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                append(((byte.toInt() ushr 4) and 0xF).toString(16))
                append((byte.toInt() and 0xF).toString(16))
            }
        }
    }
}
