package com.epher.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Autorenew
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epher.app.data.model.InviteExpiryPreset
import com.epher.app.data.model.RetentionPreset
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.ErrorNoticeCard
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.FieldBlue
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.MistBlue

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
                    Text(
                        text = "GENERATE ROOM",
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
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 118.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Text(
                            text = "Establish a new encrypted instance. A secure keypair will be generated solely for this session.",
                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
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
                        RoomLabelField(
                            value = roomLabel,
                            enabled = !isSubmitting,
                            onValueChange = { roomLabel = it },
                            onRandomize = {
                                generatedLabel = ROOM_LABEL_SUGGESTIONS[suggestionIndex % ROOM_LABEL_SUGGESTIONS.size]
                                roomLabel = generatedLabel
                                suggestionIndex = (suggestionIndex + 1) % ROOM_LABEL_SUGGESTIONS.size
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CreateRoomSectionLabel("RETENTION PRESET")
                            RetentionPreset.entries.forEach { preset ->
                                RetentionOptionCard(
                                    preset = preset,
                                    selected = retention == preset,
                                    enabled = !isSubmitting,
                                    onSelect = { retention = preset },
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CreateRoomSectionLabel("INVITE LINK LIVENESS")
                            InviteExpiryPreset.entries.chunked(2).forEach { rowPresets ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowPresets.forEach { preset ->
                                        ExpiryPresetButton(
                                            preset = preset,
                                            selected = inviteExpiry == preset,
                                            enabled = !isSubmitting,
                                            onSelect = { inviteExpiry = preset },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (rowPresets.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth(),
                        color = Color.Transparent,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                                .padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 30.dp),
                        ) {
                            Button(
                                onClick = {
                                    onCreateRoom(roomLabel.ifBlank { generatedLabel }, retention, inviteExpiry)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(58.dp),
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ChromePurple),
                                enabled = !isSubmitting,
                            ) {
                                Text(
                                    text = if (isSubmitting) "SECURING ROOM..." else "INITIALIZE",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                )
                                if (!isSubmitting) {
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Icon(
                                        Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomLabelField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onRandomize: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CreateRoomSectionLabel("ROOM LABEL")
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = FieldBlue,
            shape = RoundedCornerShape(20.dp),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                placeholder = { Text("Night Shift, Harbor Watch, Sprint Pod") },
                trailingIcon = {
                    IconButton(
                        onClick = onRandomize,
                        enabled = enabled,
                    ) {
                        Icon(
                            Icons.Rounded.Autorenew,
                            contentDescription = "Generate room label",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                ),
                shape = RoundedCornerShape(20.dp),
            )
        }
        Text(
            text = "Local alias. Not transmitted to peers.",
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MistBlue.copy(alpha = 0.70f),
        )
    }
}

@Composable
private fun CreateRoomSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 14.dp),
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 10.sp,
            letterSpacing = 1.8.sp,
            fontWeight = FontWeight.Bold,
        ),
        color = MistBlue,
    )
}

@Composable
private fun RetentionOptionCard(
    preset: RetentionPreset,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
            ),
        color = if (selected) ChromePurple.copy(alpha = 0.10f) else InkCard,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(
            width = 2.dp,
            color = if (selected) ChromePurple else Color.Transparent,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) ChromePurple else Color.Transparent)
                    .clickable(enabled = enabled, onClick = onSelect),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selected) 8.dp else 24.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.White else MistBlue.copy(alpha = 0.70f)),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = preset.label,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Color.White,
                )
                Text(
                    text = preset.detail,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExpiryPresetButton(
    preset: InviteExpiryPreset,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(56.dp)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
            ),
        color = if (selected) ChromePurple.copy(alpha = 0.10f) else InkCard,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 2.dp,
            color = if (selected) ChromePurple else Color.Transparent,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = preset.label.uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
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
