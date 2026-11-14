package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.domain.model.Message
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bottom Sheet displaying matching search results inside the active chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsListSheet(
    query: String,
    results: List<Message>,
    participantName: String,
    onSelectMessage: (Message) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val surfaceColor = if (isDark) Color(0xF5181818) else Color(0xF5FFFFFF)
    val contentColor = if (isDark) Color.White else Color(0xFF111111)
    val secondaryColor = if (isDark) Color(0xAAFFFFFF) else Color(0x88000000)
    val highlightColor = NexaBadgeMonospace

    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("d MMM yyyy, hh:mm a", Locale.getDefault())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = surfaceColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Search Results",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDark) Color(0x3300E5FF) else Color(0x2200BCD4))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${results.size} found",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = highlightColor
                    )
                }
            }

            HorizontalDivider(
                color = if (isDark) Color(0x22FFFFFF) else Color(0x18000000),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (results.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No messages match '$query'",
                        color = secondaryColor,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items = results, key = { it.packetId }) { message ->
                        SearchResultItemCard(
                            message = message,
                            query = query,
                            participantName = participantName,
                            timeFormatter = timeFormatter,
                            contentColor = contentColor,
                            secondaryColor = secondaryColor,
                            highlightColor = highlightColor,
                            onClick = { onSelectMessage(message) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItemCard(
    message: Message,
    query: String,
    participantName: String,
    timeFormatter: DateTimeFormatter,
    contentColor: Color,
    secondaryColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) Color(0x22FFFFFF) else Color(0x0C000000)

    val senderDisplay = if (message.isOutgoing) {
        "You"
    } else {
        participantName.ifBlank { message.senderNxId }
    }

    val formattedTime = remember(message.timestamp) {
        val instant = Instant.ofEpochMilli(message.timestamp)
        instant.atZone(ZoneId.systemDefault()).format(timeFormatter)
    }

    val highlightedSnippet = remember(message.plainTextContent, query) {
        highlightSearchQuery(message.plainTextContent, query, highlightColor)
    }

    // Determine type indicator icon
    val iconRes = remember(message.plainTextContent) {
        val lower = message.plainTextContent.lowercase(Locale.getDefault())
        when {
            lower.contains(".mp4") || lower.contains("vid_") -> R.drawable.ic_nexa_camera
            lower.contains(".jpg") || lower.contains(".png") || lower.contains("img_") -> R.drawable.ic_nexa_camera
            lower.contains(".mp3") || lower.contains(".aac") || lower.contains(".m4a") -> R.drawable.ic_nexa_music
            lower.contains(".pdf") || lower.contains(".doc") || lower.contains(".zip") -> R.drawable.ic_nexa_file
            else -> R.drawable.ic_nexa_file
        }
    }

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        surfaceColor = cardBg,
        elevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isDark) Color(0x28FFFFFF) else Color(0x18000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = highlightColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = senderDisplay,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = contentColor
                    )
                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = secondaryColor
                    )
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = highlightedSnippet,
                    fontSize = 13.sp,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Highlights all occurrences of query in text in a case-insensitive, Unicode-aware fashion.
 */
fun highlightSearchQuery(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    val builder = AnnotatedString.Builder()
    var start = 0
    val lowerText = text.lowercase(Locale.getDefault())
    val lowerQuery = query.lowercase(Locale.getDefault())
    while (start < text.length) {
        val index = lowerText.indexOf(lowerQuery, start)
        if (index == -1) {
            builder.append(text.substring(start))
            break
        }
        builder.append(text.substring(start, index))
        val matchStart = builder.length
        builder.append(text.substring(index, index + query.length))
        builder.addStyle(
            SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
            matchStart,
            builder.length
        )
        start = index + query.length
    }
    return builder.toAnnotatedString()
}
