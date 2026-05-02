package com.epher.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.epher.app.data.model.AttachmentTransferState
import com.epher.app.data.model.MessageDeliveryState
import com.epher.app.data.model.Participant
import com.epher.app.data.model.ResolvedAttachment
import com.epher.app.data.model.RoomAttachment
import com.epher.app.data.model.RoomMessage
import com.epher.app.data.model.RoomSummary
import com.epher.app.ui.components.AppBodyPanel
import com.epher.app.ui.components.BottomStatusStrip
import com.epher.app.ui.components.DarkInsetPanel
import com.epher.app.ui.components.DetailLine
import com.epher.app.ui.components.DrawerTab
import com.epher.app.ui.components.DrawerTabs
import com.epher.app.ui.components.EpherBackdrop
import com.epher.app.ui.components.EpherTopChrome
import com.epher.app.ui.components.sessionIndicators
import com.epher.app.ui.displayRoomLabel
import com.epher.app.ui.theme.AlertRed
import com.epher.app.ui.theme.ChromePurple
import com.epher.app.ui.theme.ChromePurpleDark
import com.epher.app.ui.theme.ChromePurpleSoft
import com.epher.app.ui.theme.EmberCoral
import com.epher.app.ui.theme.FieldBlue
import com.epher.app.ui.theme.InkCard
import com.epher.app.ui.theme.InkPanel
import com.epher.app.ui.theme.LogPanel
import com.epher.app.ui.theme.SignalMint
import com.epher.app.ui.theme.TideTeal
import com.epher.app.ui.theme.VerifiedGreen
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

@Composable
fun RoomChatScreen(
    room: RoomSummary,
    messages: List<RoomMessage>,
    participants: List<Participant>,
    logLines: List<String>,
    onBack: () -> Unit,
    onOpenRoster: () -> Unit,
    onOpenSafety: () -> Unit,
    onLeaveRoom: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendAttachment: (fileName: String, mimeType: String, bytes: ByteArray, onError: (String) -> Unit) -> Unit,
    onResolveAttachment: suspend (messageId: String) -> ResolvedAttachment?,
) {
    var composer by remember { mutableStateOf("") }
    var showLeaveConfirmation by remember { mutableStateOf(false) }
    var pendingAttachmentMessage by remember { mutableStateOf<RoomMessage?>(null) }
    var pendingAttachmentSave by remember { mutableStateOf<ResolvedAttachment?>(null) }
    val listState = rememberLazyListState()
    val indicators = remember(room, participants) { sessionIndicators(room, participants) }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { readAttachmentFromUri(context, uri) }
                .onSuccess { selected ->
                    onSendAttachment(
                        selected.fileName,
                        selected.mimeType,
                        selected.bytes,
                    ) { error ->
                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { throwable ->
                    Toast.makeText(
                        context,
                        throwable.message ?: "Couldn't attach that file.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
    val attachmentSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val resolved = pendingAttachmentSave
        pendingAttachmentSave = null
        if (uri == null || resolved == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(resolved.bytes)
                } ?: error("Couldn't write the selected file")
            }.onSuccess {
                Toast.makeText(context, "Saved copy to device", Toast.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                Toast.makeText(
                    context,
                    throwable.message ?: "Couldn't save that attachment.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    EpherBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            EpherTopChrome(
                left = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Rooms")
                    }
                    Column(
                        modifier = Modifier
                            .clickable(onClick = onOpenSafety)
                            .padding(start = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = displayRoomLabel(room),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "ID: ${room.id}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color.White.copy(alpha = 0.70f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                right = {
                    IconButton(onClick = onOpenRoster) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, contentDescription = "Peers")
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(17.dp),
                                color = InkPanel,
                                shape = CircleShape,
                                border = BorderStroke(1.dp, ChromePurple),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = participants.size.coerceAtMost(9).toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onOpenSafety) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Info")
                    }
                    IconButton(onClick = { showLeaveConfirmation = true }) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Leave room")
                    }
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
                        .imePadding(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .padding(bottom = if (imeVisible) 86.dp else 96.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            items(messages, key = { it.id }) { message ->
                                ChatMessageBubble(
                                    message = message,
                                    onAttachmentClick = {
                                        if (message.attachment != null) {
                                            pendingAttachmentMessage = message
                                        }
                                    },
                                )
                            }
                        }
                    }

                    MessageComposer(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
                            .padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
                        value = composer,
                        onValueChange = { composer = it },
                        onSend = {
                            val trimmed = composer.trim()
                            if (trimmed.isNotBlank()) {
                                composer = ""
                                onSendMessage(trimmed)
                            }
                        },
                        onPickAttachment = {
                            attachmentPicker.launch("*/*")
                        },
                    )
                }
            }

            if (!imeVisible) {
                BottomStatusStrip(indicators = indicators)
            }
        }

        if (showLeaveConfirmation) {
            AlertDialog(
                onDismissRequest = { showLeaveConfirmation = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showLeaveConfirmation = false
                            onLeaveRoom()
                        },
                    ) {
                        Text("LEAVE + WIPE")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLeaveConfirmation = false }) {
                        Text("CANCEL")
                    }
                },
                title = {
                    Text("Leave and wipe this room?")
                },
                text = {
                    Text(
                        "This removes local chat history, queued messages, peer state, room persona keys, and room secrets from this device immediately.",
                    )
                },
            )
        }

        pendingAttachmentMessage?.let { selectedMessage ->
            val attachment = selectedMessage.attachment
            if (attachment != null) {
                AlertDialog(
                    onDismissRequest = { pendingAttachmentMessage = null },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val messageId = selectedMessage.id
                                pendingAttachmentMessage = null
                                scope.launch {
                                    val resolved = onResolveAttachment(messageId)
                                    if (resolved == null) {
                                        Toast.makeText(context, "Couldn't open that attachment.", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    runCatching {
                                        openResolvedAttachment(context, resolved)
                                    }.onFailure { throwable ->
                                        Toast.makeText(
                                            context,
                                            throwable.message ?: "Couldn't open that attachment.",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                        ) {
                            Text("OPEN")
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    val messageId = selectedMessage.id
                                    pendingAttachmentMessage = null
                                    scope.launch {
                                        val resolved = onResolveAttachment(messageId)
                                        if (resolved == null) {
                                            Toast.makeText(context, "Couldn't save that attachment.", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        pendingAttachmentSave = resolved
                                        attachmentSaver.launch(resolved.fileName)
                                    }
                                },
                            ) {
                                Text("SAVE COPY")
                            }
                            TextButton(onClick = { pendingAttachmentMessage = null }) {
                                Text("CANCEL")
                            }
                        }
                    },
                    title = {
                        Text(attachment.fileName)
                    },
                    text = {
                        Text("Open this encrypted attachment, or save a copy to your device.")
                    },
                )
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: RoomMessage,
    onAttachmentClick: () -> Unit,
) {
    val isRoomCreatedNotice = message.isSystemEvent && message.body.startsWith("Room created.")
    if (message.isSystemEvent) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isRoomCreatedNotice) {
                    Text(
                        text = message.body,
                        modifier = Modifier
                            .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        lineHeight = 20.sp,
                        textAlign = TextAlign.Center,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                )
            } else {
                Text(
                    text = message.sentAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
                Surface(
                    color = ChromePurple.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = message.body,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            lineHeight = 20.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else if (message.isLocalUser) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 292.dp),
                color = ChromePurple,
                shape = RoundedCornerShape(24.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MessageBubbleContent(
                        message = message,
                        textColor = Color.White,
                        onAttachmentClick = onAttachmentClick,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message.sentAt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        LocalDeliveryIndicator(state = message.deliveryState)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            PeerAvatar(name = message.senderName, size = 38.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.widthIn(max = 292.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = nameColor(message.senderName),
                )
                Surface(
                    color = InkCard,
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MessageBubbleContent(
                            message = message,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            onAttachmentClick = onAttachmentClick,
                        )
                        Text(
                            text = message.sentAt,
                            modifier = Modifier.align(Alignment.End),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubbleContent(
    message: RoomMessage,
    textColor: Color,
    onAttachmentClick: () -> Unit,
) {
    message.attachment?.let { attachment ->
        val visual = attachmentVisualSpec(attachment)
        val progressFraction = attachmentReceiveProgress(attachment)
        val attachmentCard: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    color = visual.accent.copy(alpha = 0.18f),
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = visual.icon,
                            contentDescription = null,
                            tint = visual.accent,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = attachment.fileName,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = textColor,
                    )
                    Text(
                        text = if (attachment.transferState == AttachmentTransferState.Receiving) {
                            "${visual.label} • Receiving ${attachmentReceiveProgressPercent(attachment)}%"
                        } else {
                            "${visual.label} • ${formatAttachmentSize(attachment.byteSize)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.78f),
                    )
                    if (attachment.transferState == AttachmentTransferState.Receiving) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = visual.accent,
                            trackColor = textColor.copy(alpha = 0.12f),
                        )
                        Text(
                            text = "${attachment.receivedChunks.coerceAtLeast(0)}/${attachment.totalChunks.coerceAtLeast(1)} encrypted chunks",
                            style = MaterialTheme.typography.labelSmall,
                            color = textColor.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        if (attachment.transferState == AttachmentTransferState.Available) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp),
                onClick = onAttachmentClick,
            ) {
                attachmentCard()
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.14f),
                shape = RoundedCornerShape(18.dp),
            ) {
                attachmentCard()
            }
        }
    }
    if (message.body.isNotBlank()) {
        Text(
            text = message.body,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = textColor,
        )
    }
}

@Composable
private fun LocalDeliveryIndicator(
    state: MessageDeliveryState,
) {
    when (state) {
        MessageDeliveryState.Sent -> {
            Icon(
                Icons.Rounded.DoneAll,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(18.dp),
            )
        }

        MessageDeliveryState.Sending,
        MessageDeliveryState.Queued,
        MessageDeliveryState.Retry -> {
            Text(
                text = state.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.86f),
            )
        }
    }
}

@Composable
private fun MessageComposer(
    modifier: Modifier = Modifier,
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachment: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = FieldBlue,
        shape = RoundedCornerShape(22.dp),
    ) {
        var composerLineCount by remember(value) { mutableStateOf(maxOf(1, value.lines().size)) }
        val multiline = composerLineCount > 1
        val verticalPadding = if (multiline) 4.dp else 0.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = verticalPadding, end = 6.dp, bottom = verticalPadding),
            verticalAlignment = if (multiline) Alignment.Bottom else Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPickAttachment,
                modifier = Modifier
                    .size(40.dp)
                    .padding(bottom = if (multiline) 6.dp else 0.dp),
            ) {
                Icon(
                    Icons.Rounded.AttachFile,
                    contentDescription = "Attach file",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 22.sp,
                    ),
                    cursorBrush = SolidColor(ChromePurple),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 56.dp),
                    singleLine = false,
                    minLines = 1,
                    maxLines = 4,
                    onTextLayout = { textLayoutResult ->
                        composerLineCount = maxOf(1, textLayoutResult.lineCount)
                    },
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (multiline) 48.dp else 44.dp, max = 96.dp)
                                .padding(top = if (multiline) 4.dp else 0.dp, bottom = if (multiline) 4.dp else 0.dp),
                            contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                        ) {
                            if (value.isBlank()) {
                                Text(
                                    text = "Message",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
                            ) {
                                innerTextField()
                            }
                        }
                    },
                )
                Surface(
                    modifier = Modifier
                        .align(if (multiline) Alignment.BottomEnd else Alignment.CenterEnd)
                        .size(46.dp),
                    color = ChromePurple,
                    shape = CircleShape,
                    shadowElevation = 4.dp,
                    onClick = onSend,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun readAttachmentFromUri(
    context: Context,
    uri: Uri,
): ResolvedAttachment {
    val mimeType = context.contentResolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
    val metadata = queryAttachmentMetadata(context, uri)
    metadata.byteSize?.let { declaredSize ->
        require(declaredSize <= MAX_SELECTED_ATTACHMENT_BYTES) {
            "Current attachment limit is 1 MB in this build"
        }
    }
    val bytes = readContentUriBytes(
        context = context,
        uri = uri,
        maxBytes = MAX_SELECTED_ATTACHMENT_BYTES,
    )
    return ResolvedAttachment(
        fileName = metadata.fileName,
        mimeType = mimeType,
        bytes = bytes,
    )
}

private fun openResolvedAttachment(
    context: Context,
    attachment: ResolvedAttachment,
) {
    val directory = File(context.cacheDir, "attachments").apply {
        deleteRecursively()
        mkdirs()
    }
    val file = File(directory, sanitizeAttachmentFileName(attachment.fileName))
    file.writeBytes(attachment.bytes)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val viewIntent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(Intent.createChooser(viewIntent, "Open attachment"))
}

private fun queryAttachmentMetadata(
    context: Context,
    uri: Uri,
): AttachmentPickerMetadata {
    return context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            AttachmentPickerMetadata(
                fileName = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else "",
                byteSize = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex).takeIf { it >= 0L }
                } else {
                    null
                },
            )
        }
        ?.let { metadata ->
            metadata.copy(fileName = metadata.fileName.ifBlank { "attachment" })
        }
        ?: AttachmentPickerMetadata(fileName = "attachment", byteSize = null)
}

private fun readContentUriBytes(
    context: Context,
    uri: Uri,
    maxBytes: Int,
): ByteArray {
    return context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(ATTACHMENT_READ_BUFFER_BYTES)
        var totalBytes = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalBytes += read
            require(totalBytes <= maxBytes) {
                "Current attachment limit is 1 MB in this build"
            }
            output.write(buffer, 0, read)
        }
        output.toByteArray()
    } ?: error("Couldn't read the selected file")
}

private fun sanitizeAttachmentFileName(name: String): String = name
    .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    .ifBlank { "attachment.bin" }

private data class AttachmentPickerMetadata(
    val fileName: String,
    val byteSize: Long?,
)

private const val MAX_SELECTED_ATTACHMENT_BYTES = 1024 * 1024
private const val ATTACHMENT_READ_BUFFER_BYTES = 16 * 1024

private data class AttachmentVisualSpec(
    val icon: ImageVector,
    val accent: Color,
    val label: String,
)

private fun attachmentVisualSpec(attachment: RoomAttachment): AttachmentVisualSpec {
    val mimeType = attachment.mimeType.lowercase(Locale.US)
    val extension = attachment.fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return when {
        mimeType == "application/pdf" || extension == "pdf" -> AttachmentVisualSpec(
            icon = Icons.Rounded.PictureAsPdf,
            accent = EmberCoral,
            label = "PDF document",
        )

        mimeType.startsWith("image/") || extension in setOf("png", "jpg", "jpeg", "gif", "webp", "heic") -> AttachmentVisualSpec(
            icon = Icons.Rounded.Image,
            accent = TideTeal,
            label = "Image",
        )

        mimeType.startsWith("video/") || extension in setOf("mp4", "mov", "webm", "mkv") -> AttachmentVisualSpec(
            icon = Icons.Rounded.Movie,
            accent = ChromePurpleSoft,
            label = "Video",
        )

        mimeType.startsWith("audio/") || extension in setOf("mp3", "wav", "m4a", "aac", "ogg", "flac") -> AttachmentVisualSpec(
            icon = Icons.Rounded.GraphicEq,
            accent = SignalMint,
            label = "Audio",
        )

        mimeType.contains("zip") || mimeType.contains("gzip") || extension in setOf("zip", "tar", "gz", "rar", "7z") -> AttachmentVisualSpec(
            icon = Icons.Rounded.FolderZip,
            accent = ChromePurpleDark,
            label = "Archive",
        )

        mimeType.startsWith("text/") || extension in setOf("txt", "md", "rtf", "csv", "log") -> AttachmentVisualSpec(
            icon = Icons.Rounded.Description,
            accent = Color(0xFFD7CFF8),
            label = "Text file",
        )

        mimeType.contains("json") || mimeType.contains("xml") || extension in setOf("json", "xml", "kt", "java", "js", "ts", "py", "rs", "go", "swift", "cpp", "c", "h") -> AttachmentVisualSpec(
            icon = Icons.Rounded.Code,
            accent = TideTeal,
            label = "Code file",
        )

        else -> AttachmentVisualSpec(
            icon = Icons.AutoMirrored.Rounded.InsertDriveFile,
            accent = Color.White.copy(alpha = 0.88f),
            label = "Encrypted file",
        )
    }
}

private fun attachmentReceiveProgress(attachment: RoomAttachment): Float {
    if (attachment.transferState != AttachmentTransferState.Receiving) return 1f
    if (attachment.totalChunks <= 0) return 0f
    return (attachment.receivedChunks.toFloat() / attachment.totalChunks.toFloat())
        .coerceIn(0f, 1f)
}

private fun attachmentReceiveProgressPercent(attachment: RoomAttachment): Int {
    if (attachment.transferState != AttachmentTransferState.Receiving) return 100
    val totalChunks = attachment.totalChunks.coerceAtLeast(1)
    val receivedChunks = attachment.receivedChunks.coerceIn(0, totalChunks)
    if (receivedChunks <= 0) return 0
    if (receivedChunks >= totalChunks) return 100
    return ((receivedChunks * 100) / totalChunks).coerceIn(1, 99)
}

private fun formatAttachmentSize(byteSize: Long): String = when {
    byteSize >= 1024 * 1024 -> String.format("%.1f MB", byteSize / (1024f * 1024f))
    byteSize >= 1024 -> String.format("%.1f KB", byteSize / 1024f)
    else -> "$byteSize B"
}

@Composable
private fun BottomDrawerPanel(
    room: RoomSummary,
    participants: List<Participant>,
    logLines: List<String>,
    selectedTab: DrawerTab,
    onSelectTab: (DrawerTab) -> Unit,
    onCopyInvite: () -> Unit,
    onCopyShareUrl: () -> Unit,
    onShareInvite: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(272.dp),
        color = LogPanel,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DrawerTabs(selected = selectedTab, onSelect = onSelectTab)

            when (selectedTab) {
                DrawerTab.Peers -> PeersPanel(participants = participants)
                DrawerTab.Info -> InfoPanel(
                    room = room,
                    participants = participants,
                    onCopyInvite = onCopyInvite,
                    onCopyShareUrl = onCopyShareUrl,
                    onShareInvite = onShareInvite,
                )
                DrawerTab.Log -> LogPanelContent(logLines = logLines)
            }
        }
    }
}

@Composable
private fun PeersPanel(
    participants: List<Participant>,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        participants.forEach { participant ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(name = participant.displayName, size = 48.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = participant.displayName,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = nameColor(participant.displayName),
                        )
                        Text(
                            text = "KEY: 0x${participant.fingerprint.take(6)}....................${participant.fingerprint.takeLast(6)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    color = VerifiedGreen,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "VERIFIED",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }
            }
            HorizontalDivider(color = ChromePurple.copy(alpha = 0.16f))
        }
    }
}

@Composable
private fun InfoPanel(
    room: RoomSummary,
    participants: List<Participant>,
    onCopyInvite: () -> Unit,
    onCopyShareUrl: () -> Unit,
    onShareInvite: () -> Unit,
) {
    val profile = room.securityProfile
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        DetailLine(title = "ROOM NAME", value = room.localLabel)
        DetailLine(title = "ROOM ID", value = room.id)
        DetailLine(title = "INVITE TOKEN", value = room.invitePackage.inviteToken)
        DetailLine(title = "SHARE LINK", value = room.invitePackage.shareUrl)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                color = ChromePurple,
                shape = RoundedCornerShape(16.dp),
                onClick = onCopyInvite,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "COPY INVITE",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Surface(
                color = InkCard,
                shape = RoundedCornerShape(16.dp),
                onClick = onCopyShareUrl,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "COPY LINK",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        Surface(
            color = InkCard,
            shape = RoundedCornerShape(16.dp),
            onClick = onShareInvite,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.ArrowOutward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "SHARE INVITE",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        DetailLine(title = "CONNECTION MODE", value = profile.transportMode)
        DetailLine(title = "TRANSPORT ENCRYPTION", value = profile.transportEncryption)
        DetailLine(title = "MESSAGE ENCRYPTION", value = profile.messageEncryption)
        DetailLine(title = "IDENTITY", value = profile.identityScheme)
        DetailLine(title = "KEY DERIVATION", value = profile.keyDerivation)
        DetailLine(title = "PEER AUTHENTICATION", value = profile.peerAuthentication)
        DetailLine(title = "PAIRWISE CONNECTIONS", value = "${participants.size.coerceAtLeast(1) - 1} Connections")
        DetailLine(title = "TRAFFIC OBFUSCATION", value = profile.trafficObfuscation)
        DetailLine(title = "FORWARD SECRECY", value = profile.forwardSecrecy)
        DetailLine(title = "REPLAY PROTECTION", value = profile.replayProtection)
        DetailLine(title = "GROUP KEY POLICY", value = profile.groupKeyPolicy)
        DetailLine(title = "FILE SHARING", value = profile.fileSharing)
        DetailLine(title = "INVITE SIGNATURE", value = room.invitePackage.signatureState)
        DetailLine(title = "OWNER FINGERPRINT", value = room.invitePackage.ownerFingerprint)
        DetailLine(title = "ROOM RETENTION", value = room.retentionPreset.label)
    }
}

@Composable
private fun LogPanelContent(
    logLines: List<String>,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DarkInsetPanel {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                logLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Log data is only shown in DOM,",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PeerAvatar(
    name: String,
    size: androidx.compose.ui.unit.Dp,
) {
    Box {
        Surface(
            modifier = Modifier.size(size),
            color = nameColor(name).copy(alpha = 0.82f),
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.48f),
                )
            }
        }
        Surface(
            modifier = Modifier
                .size(18.dp)
                .align(Alignment.BottomEnd),
            color = SignalMint,
            shape = CircleShape,
        ) {}
    }
}

private fun buildLogLines(
    room: RoomSummary,
    messages: List<RoomMessage>,
): List<String> = buildList {
    add("16:39 >> SECURITY PROFILE LOADED")
    add("16:39 >> OWNER FINGERPRINT LOADED: ${room.invitePackage.ownerFingerprint}")
    add("16:40 >> KDF: ${room.securityProfile.keyDerivation.uppercase()}")
    add("16:40 >> TRANSPORT: ${room.securityProfile.transportMode.uppercase()}")
    add("16:41 >> ROOM ID ACTIVE: ${room.id.uppercase()}")
    add("16:41 >> INVITE SIGNATURE: ${room.invitePackage.signatureState.uppercase()}")
    add("16:42 >> PEER CARD VERIFICATION PASSED")
    add("16:43 >> REPLAY WINDOW ACTIVE")
    messages.take(4).forEachIndexed { index, message ->
        add("16:4${index + 3} >> MESSAGE FROM ${message.senderName.uppercase()}: ${message.body.take(28).uppercase()}")
    }
}

private val participantPalette = listOf(
    TideTeal,
    Color(0xFF4AB2FF),
    EmberCoral,
    SignalMint,
    AlertRed,
)

private fun nameColor(name: String): Color = when (name) {
    "You" -> TideTeal
    else -> participantPalette[name.hashCode().absoluteValue % participantPalette.size]
}
