package com.epher.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.epher.app.ui.EpherApp
import com.epher.app.ui.EpherViewModel
import com.epher.app.ui.theme.EpherTheme

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<EpherViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.KEEP_NETWORKING_ALIVE_IN_BACKGROUND) {
            NetworkingForegroundService.start(this)
        }
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            EpherTheme {
                EpherApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onPause() {
        if (!BuildConfig.KEEP_NETWORKING_ALIVE_IN_BACKGROUND) {
            viewModel.suspendNetworking()
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        viewModel.resumeNetworking()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val inviteToken = extractInviteToken(intent) ?: return
        viewModel.stageIncomingInvite(inviteToken)
    }

    private fun extractInviteToken(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        if (data.scheme != "epher" || data.host != "room") return null
        return data.lastPathSegment?.trim()?.takeIf { it.isNotBlank() }
    }
}
