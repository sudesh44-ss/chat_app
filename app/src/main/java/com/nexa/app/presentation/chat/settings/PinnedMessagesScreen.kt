package com.nexa.app.presentation.chat.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.data.local.dao.MessageDao
import com.nexa.app.domain.repository.ChatRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pinned Messages Screen
 * Displays active pinned messages in the conversation with unpin and jump-to-chat options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinnedMessagesScreen(
    conversationId: String,
    messageDao: MessageDao,
    chatRepository: ChatRepository,
    onBack: () -> Unit,
    onNavigateToMessage: (packetId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val now = System.currentTimeMillis()
    val pinnedMessages by messageDao.getPinnedMessagesFlow(conversationId, now)
        .collectAsState(initial = emptyList())

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Pinned Messages",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = primaryTextColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = primaryTextColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            if (pinnedMessages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_pin),
                            contentDescription = null,
                            tint = secondaryTextColor,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "No Pinned Messages",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = primaryTextColor
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Long-press any message in the chat and choose 'Pin' to keep important messages pinned here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryTextColor,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(pinnedMessages, key = { it.packetId }) { msg ->
                        NexaGlassSurface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToMessage(msg.packetId) },
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = surfaceColor,
                            borderColor = borderColor,
                            elevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_pin),
                                    contentDescription = "Pinned",
                                    tint = if (isDark) Color(0xFF64B5F6) else Color(0xFF1976D2),
                                    modifier = Modifier.size(20.dp)
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = msg.plainTextContent,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 15.sp
                                        ),
                                        color = primaryTextColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Pinned • ${formatTimestamp(msg.pinnedAt ?: msg.timestamp)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                        color = secondaryTextColor
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Unpin action button
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            withContext(Dispatchers.IO) {
                                                messageDao.unpinMessage(msg.packetId)
                                            }
                                            Toast.makeText(context, "Message unpinned", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_nexa_close),
                                        contentDescription = "Unpin",
                                        tint = secondaryTextColor,
                                        modifier = Modifier.size(16.dp)
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

private fun formatTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(millis))
}
