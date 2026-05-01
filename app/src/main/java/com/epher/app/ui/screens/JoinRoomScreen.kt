package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.ErrorNoticeCard
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.NightInk

@Composable
fun JoinRoomScreen(
    prefilledInviteToken: String?,
    isSubmitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onJoinRoom: (String) -> Unit,
) {
    val clipboardManager = LocalClipboardManager.current
    var inviteToken by remember(prefilledInviteToken) { mutableStateOf(prefilledInviteToken.orEmpty()) }
    val indicators = remember { sessionIndicators(room = null, participants = emptyList()) }

    LaunchedEffect(prefilledInviteToken) {
        val token = prefilledInviteToken?.trim().orEmpty()
        if (token.isNotBlank()) {
            onJoinRoom(token)
        }
    }

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    com.epher.app.ui.components.GlitchLogo(modifier = Modifier.size(60.dp), size = 46)
                },
                right = {},
            )
            AppBodyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = -18.dp),
                        color = NightInk,
                        shape = RoundedCornerShape(26.dp),
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Rounded.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(42.dp),
                            )
                            Text(
                                text = "JOIN ROOM",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (prefilledInviteToken.isNullOrBlank()) {
                                    "Paste the signed invite token from the room owner to join the encrypted room."
                                } else {
                                    "Opening the shared invite and preparing the encrypted room now."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (errorMessage != null) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                                ) {
                                    ErrorNoticeCard(body = errorMessage)
                                    TextButton(onClick = onDismissError) {
                                        Text("DISMISS")
                                    }
                                }
                            }
                            if (isSubmitting) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = ChromePurpleDark,
                                    shape = RoundedCornerShape(18.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = ChromePurple,
                                        )
                                        Text(
                                            text = "Verifying invite and preparing encrypted peer state...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = InkCard,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TextField(
                                        value = inviteToken,
                                        onValueChange = { inviteToken = it },
                                        modifier = Modifier.weight(1f),
                                        enabled = !isSubmitting,
                                        singleLine = true,
                                        maxLines = 1,
                                        label = { Text("INVITE TOKEN") },
                                        placeholder = { Text("Paste invite token") },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                        ),
                                    )
                                    IconButton(
                                        onClick = { inviteToken = clipboardManager.getText()?.text.orEmpty() },
                                        enabled = !isSubmitting,
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste invite")
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                                    18.dp,
                                    Alignment.End,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = onBack) {
                                    Text("CANCEL")
                                }
                                Button(
                                    onClick = { onJoinRoom(inviteToken.trim()) },
                                    modifier = Modifier.height(52.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = ChromePurple),
                                    enabled = inviteToken.isNotBlank() && !isSubmitting,
                                ) {
                                    Text(if (isSubmitting) "JOINING..." else "JOIN ROOM")
                                }
                            }
                        }
                    }
                }
            }
            BottomStatusStrip(indicators = indicators)
        }
    }
}
