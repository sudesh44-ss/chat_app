package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.domain.model.Message

enum class QuotedContentType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    DELETED_FALLBACK
}

data class QuotedMessageDisplayInfo(
    val senderName: String,
    val previewText: String,
    val contentType: QuotedContentType,
    val iconRes: Int? = null,
    val isDeletedOrUnavailable: Boolean = false
)

object QuotedMessageHelper {

    fun resolveDisplayInfo(
        originalMessage: Message?,
        fallbackSenderName: String?,
        fallbackSnippet: String?,
        currentUserId: String? = null
    ): QuotedMessageDisplayInfo {
        if (originalMessage == null) {
            val hasSnippet = !fallbackSnippet.isNullOrBlank()
            val sender = fallbackSenderName?.ifBlank { null } ?: "Unknown"
            val text = if (hasSnippet) fallbackSnippet!! else "Original message was deleted"
            val contentType = if (hasSnippet) detectContentType(text) else QuotedContentType.DELETED_FALLBACK
            return QuotedMessageDisplayInfo(
                senderName = sender,
                previewText = text,
                contentType = contentType,
                iconRes = getIconForType(contentType),
                isDeletedOrUnavailable = !hasSnippet
            )
        }

        val isSenderSelf = currentUserId != null && originalMessage.senderNxId == currentUserId || originalMessage.isOutgoing
        val sender = if (isSenderSelf) {
            "You"
        } else {
            fallbackSenderName?.ifBlank { null } ?: originalMessage.senderNxId
        }

        val rawText = originalMessage.plainTextContent
        val type = detectContentType(rawText)
        val cleanPreview = formatPreviewText(rawText, type)

        return QuotedMessageDisplayInfo(
            senderName = sender,
            previewText = cleanPreview,
            contentType = type,
            iconRes = getIconForType(type),
            isDeletedOrUnavailable = false
        )
    }

    private fun detectContentType(text: String): QuotedContentType {
        val lower = text.lowercase()
        return when {
            lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") ||
                    lower.contains(".webp") || lower.startsWith("[image]") -> QuotedContentType.IMAGE

            lower.contains(".mp4") || lower.contains(".mov") || lower.contains(".mkv") ||
                    lower.contains(".webm") || lower.startsWith("[video]") -> QuotedContentType.VIDEO

            lower.contains(".mp3") || lower.contains(".m4a") || lower.contains(".wav") ||
                    lower.contains(".aac") || lower.startsWith("[voice]") || lower.startsWith("[audio]") -> QuotedContentType.AUDIO

            lower.contains(".pdf") || lower.contains(".doc") || lower.contains(".docx") ||
                    lower.contains(".zip") || lower.startsWith("[document]") || lower.startsWith("[file]") -> QuotedContentType.DOCUMENT

            else -> QuotedContentType.TEXT
        }
    }

    private fun formatPreviewText(text: String, type: QuotedContentType): String {
        return when (type) {
            QuotedContentType.IMAGE -> if (text.startsWith("[Image]")) text.removePrefix("[Image]").trim().ifEmpty { "Photo" } else text
            QuotedContentType.VIDEO -> if (text.startsWith("[Video]")) text.removePrefix("[Video]").trim().ifEmpty { "Video" } else text
            QuotedContentType.AUDIO -> if (text.startsWith("[Audio]") || text.startsWith("[Voice]")) "Voice note" else text
            QuotedContentType.DOCUMENT -> if (text.startsWith("[Document]") || text.startsWith("[File]")) text.removePrefix("[Document]").removePrefix("[File]").trim().ifEmpty { "Document" } else text
            QuotedContentType.DELETED_FALLBACK -> "Original message was deleted"
            QuotedContentType.TEXT -> text
        }
    }

    private fun getIconForType(type: QuotedContentType): Int? {
        return when (type) {
            QuotedContentType.IMAGE -> R.drawable.ic_nexa_image
            QuotedContentType.VIDEO -> R.drawable.ic_nexa_video_film
            QuotedContentType.AUDIO -> R.drawable.ic_nexa_audio_lines
            QuotedContentType.DOCUMENT -> R.drawable.ic_nexa_file
            QuotedContentType.DELETED_FALLBACK -> R.drawable.ic_nexa_delete
            QuotedContentType.TEXT -> null
        }
    }
}

/**
 * Quoted Message Preview block embedded inside message bubbles.
 * Visually distinguishes the quoted section with an accent vertical bar and subtle background.
 * Tapping triggers navigation to the original message.
 */
@Composable
fun QuotedMessageBubbleCard(
    displayInfo: QuotedMessageDisplayInfo,
    accentColor: Color,
    onQuoteClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0x33000000) else Color(0x18000000)
    val senderColor = if (displayInfo.isDeletedOrUnavailable) {
        if (isDark) Color(0xFF9E9E9E) else Color(0xFF757575)
    } else {
        accentColor
    }
    val contentTextColor = if (displayInfo.isDeletedOrUnavailable) {
        if (isDark) Color(0xFF757575) else Color(0xFF9E9E9E)
    } else {
        if (isDark) Color(0xFFE0E0E0) else Color(0xFF333333)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .then(
                if (onQuoteClick != null && !displayInfo.isDeletedOrUnavailable) {
                    Modifier.clickable(onClick = onQuoteClick)
                } else Modifier
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Vertical Accent Line
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(36.dp)
                .background(senderColor, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Optional Media / Attachment Icon
        if (displayInfo.iconRes != null) {
            Icon(
                painter = painterResource(id = displayInfo.iconRes),
                contentDescription = null,
                tint = senderColor,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 4.dp)
            )
        }

        // Sender & Preview text
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        ) {
            Text(
                text = displayInfo.senderName,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = senderColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = displayInfo.previewText,
                fontSize = 12.sp,
                fontStyle = if (displayInfo.isDeletedOrUnavailable) FontStyle.Italic else FontStyle.Normal,
                color = contentTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
