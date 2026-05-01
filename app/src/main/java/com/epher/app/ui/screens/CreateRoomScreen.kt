package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.RetentionPreset
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.ErrorNoticeCard
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue
import com.epher.app.ui.theme.NightInk

@Composable
fun CreateRoomScreen(
    isSubmitting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onDismissError: () -> Unit,
    onCreateRoom: (String, RetentionPreset, InviteExpiryPreset) -> Unit,
) {
    val initialSuggestionIndex = remember { ROOM_LABEL_SUGGESTIONS.indices.random() }
    var generatedLabel by remember { mutableStateOf(ROOM_LABEL_SUGGESTIONS[initialSuggestionIndex]) }
    var roomLabel by remember { mutableStateOf(generatedLabel) }
    var retention by remember { mutableStateOf(RetentionPreset.LeaveOnly) }
    var inviteExpiry by remember { mutableStateOf(InviteExpiryPreset.Day) }
    var suggestionIndex by remember {
        mutableStateOf((initialSuggestionIndex + 1) % ROOM_LABEL_SUGGESTIONS.size)
    }

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                right = {},
            )
            AppBodyPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = "GENERATE ROOM",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Start a room, land directly in chat, and copy the invite from the info panel.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (errorMessage != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = ChromePurple,
                                    )
                                    Text(
                                        text = "Generating room secrets and preparing the chat...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = NightInk,
                            shape = RoundedCornerShape(22.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Text(
                                    text = "ROOM LABEL",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MistBlue,
                                )
                                Text(
                                    text = "Optional. Leave it blank and we will generate a private room label for you.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = InkCard,
                                        shape = RoundedCornerShape(18.dp),
                                        border = BorderStroke(
                                            1.dp,
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f),
                                    ),
                                ) {
                                    TextField(
                                        value = roomLabel,
                                        onValueChange = { roomLabel = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !isSubmitting,
                                        placeholder = { Text("Night Shift, Harbor Watch, Sprint Pod") },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = {
                                                    generatedLabel = ROOM_LABEL_SUGGESTIONS[suggestionIndex % ROOM_LABEL_SUGGESTIONS.size]
                                                    roomLabel = generatedLabel
                                                    suggestionIndex = (suggestionIndex + 1) % ROOM_LABEL_SUGGESTIONS.size
                                                },
                                                enabled = !isSubmitting,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Autorenew,
                                                    contentDescription = "Generate room label",
                                                )
                                            }
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent,
                                            disabledContainerColor = Color.Transparent,
                                            focusedIndicatorColor = Color.Transparent,
                                            unfocusedIndicatorColor = Color.Transparent,
                                        ),
                                        shape = RoundedCornerShape(18.dp),
                                    )
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = InkCard,
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "RETENTION PRESET",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MistBlue,
                                )
                                RetentionPreset.entries.forEach { preset ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = retention == preset,
                                                enabled = !isSubmitting,
                                                onClick = { retention = preset },
                                            ),
                                        color = if (retention == preset) ChromePurpleDark else InkCard,
                                        shape = RoundedCornerShape(20.dp),
                                        border = if (retention == preset) BorderStroke(1.dp, ChromePurple) else null,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            RadioButton(
                                                selected = retention == preset,
                                                onClick = { retention = preset },
                                                enabled = !isSubmitting,
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = preset.label,
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                Text(
                                                    text = preset.detail,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = InkCard,
                            shape = RoundedCornerShape(28.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "INVITE LINK LIVENESS",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MistBlue,
                                )
                                InviteExpiryPreset.entries
                                    .filter { it.durationMillis != null }
                                    .forEach { preset ->
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = inviteExpiry == preset,
                                                enabled = !isSubmitting,
                                                onClick = { inviteExpiry = preset },
                                            ),
                                        color = if (inviteExpiry == preset) ChromePurpleDark else InkCard,
                                        shape = RoundedCornerShape(20.dp),
                                        border = if (inviteExpiry == preset) BorderStroke(1.dp, ChromePurple) else null,
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.Top,
                                        ) {
                                            RadioButton(
                                                selected = inviteExpiry == preset,
                                                onClick = { inviteExpiry = preset },
                                                enabled = !isSubmitting,
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(
                                                    text = preset.label,
                                                    style = MaterialTheme.typography.titleMedium,
                                                )
                                                Text(
                                                    text = preset.detail,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Button(
                        onClick = {
                            onCreateRoom(roomLabel.ifBlank { generatedLabel }, retention, inviteExpiry)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ChromePurple),
                        enabled = !isSubmitting,
                    ) {
                        Text(if (isSubmitting) "SECURING ROOM..." else "CREATE AND OPEN")
                    }
                }
            }
        }
    }
}

private val ROOM_LABEL_SUGGESTIONS = listOf(
    "Night Shift",
    "Harbor Watch",
    "Signal Room",
    "Quiet Channel",
    "Backline",
    "Safehouse",
    "Dockside",
    "North Relay",
    "Patron Circle",
    "Sprint Pod",
)
