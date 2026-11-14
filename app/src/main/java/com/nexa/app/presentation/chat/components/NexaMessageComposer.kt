package com.nexa.app.presentation.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.Message
import com.nexa.app.domain.model.TransportType
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.delay

/**
 * Premium Liquid Glass Message Composer.
 *
 * Implements:
 * 1. Left circular emoji button (44dp).
 * 2. Center message input capsule (32dp radius) with camera and attachment icons.
 * 3. Right circular action button (Mic when empty, Send when text present).
 * 4. Reply preview header banner when replying to a message.
 * 5. Full voice recording mode with timer and cancel controls.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NexaMessageComposer(
    text: String,
    onTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onEmojiToggle: () -> Unit,
    isEmojiPanelOpen: Boolean,
    onAttachmentToggle: () -> Unit,
    isAttachmentMenuOpen: Boolean,
    onCameraClicked: () -> Unit,
    selectedTransport: TransportType,
    replyingMessage: Message? = null,
    replySenderName: String? = null,
    onCancelReply: () -> Unit = {},
    isRecordingVoice: Boolean = false,
    onStartVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onSendVoiceRecording: () -> Unit = {},
    onScheduleClicked: (() -> Unit)? = null,
    textScale: Float = 1f,
    enterToSend: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val scale = textScale.coerceIn(0.8f, 1.5f)

    val surfaceColor = if (isDark) {
        Color(0xEE1E1E1E)
    } else {
        Color(0xEEF8F9FA)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val hintColor = if (isDark) Color(0xFF888888) else Color(0xFF999999)

    val actionButtonTint = when (selectedTransport) {
        TransportType.DATA_SMS -> NexaWarningOrange
        TransportType.DTN_MESH -> NexaBadgeMonospace
        TransportType.INTERNET -> MaterialTheme.colorScheme.primary
    }

    var recordSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordSeconds = 0
            while (true) {
                delay(1000)
                recordSeconds++
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // Reply Preview Banner
        AnimatedVisibility(
            visible = replyingMessage != null,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut()
        ) {
            if (replyingMessage != null) {
                val displayInfo = remember(replyingMessage, replySenderName) {
                    QuotedMessageHelper.resolveDisplayInfo(
                        originalMessage = replyingMessage,
                        fallbackSenderName = replySenderName,
                        fallbackSnippet = null
                    )
                }

                NexaGlassSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    shape = RoundedCornerShape(16.dp),
                    surfaceColor = surfaceColor,
                    elevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(36.dp)
                                .background(actionButtonTint, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Replying to ${displayInfo.senderName}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = actionButtonTint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (displayInfo.iconRes != null) {
                                    Icon(
                                        painter = painterResource(id = displayInfo.iconRes),
                                        contentDescription = null,
                                        tint = contentColor.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(end = 4.dp)
                                    )
                                }
                                Text(
                                    text = displayInfo.previewText,
                                    fontSize = 13.sp,
                                    color = contentColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = onCancelReply,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_close),
                                contentDescription = "Cancel Reply",
                                tint = hintColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Voice Recording UI or Standard Input
        if (isRecordingVoice) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Recording Container
                NexaGlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    surfaceColor = surfaceColor,
                    elevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pulsing red recording dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.Red, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val minutes = recordSeconds / 60
                        val seconds = recordSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = contentColor
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "Slide to cancel",
                            fontSize = 12.sp,
                            color = hintColor,
                            modifier = Modifier.clickable { onCancelVoiceRecording() }
                        )
                    }
                }

                // Send Audio Button
                IconButton(
                    onClick = onSendVoiceRecording,
                    modifier = Modifier
                        .size(48.dp)
                        .background(actionButtonTint, CircleShape)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_send),
                        contentDescription = "Send Audio",
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. Left circular emoji button (44dp)
                NexaGlassSurface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    surfaceColor = surfaceColor,
                    elevation = 4.dp
                ) {
                    IconButton(
                        onClick = onEmojiToggle,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_smile),
                            contentDescription = "Emoji Picker",
                            tint = if (isEmojiPanelOpen) actionButtonTint else contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 2. Center rounded message input capsule (32dp radius)
                NexaGlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 120.dp),
                    shape = RoundedCornerShape(24.dp),
                    surfaceColor = surfaceColor,
                    elevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = onTextChanged,
                            textStyle = TextStyle(
                                color = contentColor,
                                fontSize = (15f * scale).sp
                            ),
                            cursorBrush = SolidColor(actionButtonTint),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                imeAction = if (enterToSend) {
                                    androidx.compose.ui.text.input.ImeAction.Send
                                } else {
                                    androidx.compose.ui.text.input.ImeAction.Default
                                }
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onSend = {
                                    if (enterToSend && text.isNotBlank()) onSendClicked()
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                            decorationBox = { innerTextField ->
                                if (text.isEmpty()) {
                                    Text(
                                        text = "Type a message...",
                                        color = hintColor,
                                        fontSize = (15f * scale).sp
                                    )
                                }
                                innerTextField()
                            }
                        )

                        // Camera shortcut
                        IconButton(
                            onClick = onCameraClicked,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_camera),
                                contentDescription = "Camera",
                                tint = hintColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Attachment shortcut
                        IconButton(
                            onClick = onAttachmentToggle,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_nexa_plus),
                                contentDescription = "Attachments",
                                tint = if (isAttachmentMenuOpen) actionButtonTint else hintColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 3. Right circular action button (Mic when empty, Send when text present; Long-press to Schedule)
                val isSendMode = text.isNotBlank()
                NexaGlassSurface(
                    modifier = Modifier.size(46.dp),
                    shape = CircleShape,
                    surfaceColor = actionButtonTint,
                    elevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = {
                                    if (isSendMode) {
                                        onSendClicked()
                                    } else {
                                        onStartVoiceRecording()
                                    }
                                },
                                onLongClick = {
                                    if (isSendMode && onScheduleClicked != null) {
                                        onScheduleClicked()
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isSendMode) R.drawable.ic_nexa_send else R.drawable.ic_nexa_mic
                            ),
                            contentDescription = if (isSendMode) "Send (Hold to Schedule)" else "Record Voice",
                            tint = Color.Black,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
