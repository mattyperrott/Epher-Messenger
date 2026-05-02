package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.ErrorNoticeCard
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.FieldBlue
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        text = "JOIN ROOM",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 14.sp,
                            letterSpacing = 1.8.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                },
                right = {
                    Spacer(modifier = Modifier.size(48.dp))
                },
            )
            AppBodyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        shape = RoundedCornerShape(26.dp),
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Surface(
                                modifier = Modifier.size(72.dp),
                                color = InkCard,
                                shape = RoundedCornerShape(24.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.Key,
                                        contentDescription = null,
                                        tint = MistBlue,
                                        modifier = Modifier.size(38.dp),
                                    )
                                }
                            }
                            Text(
                                text = "JOIN ROOM",
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp,
                                ),
                                color = Color.White,
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
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
                                color = FieldBlue,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
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
                                        minLines = 1,
                                        maxLines = 4,
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
                                horizontalArrangement = Arrangement.spacedBy(
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
