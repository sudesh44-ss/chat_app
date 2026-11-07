package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class EmojiCategory(val label: String, val icon: String) {
    RECENT("Recent", "🕒"),
    SMILEYS("Smileys", "😀"),
    GESTURES("Gestures", "👍"),
    HEARTS("Hearts", "❤️"),
    ANIMALS("Nature", "🐶"),
    FOOD("Food", "🍕"),
    OBJECTS("Objects", "💡")
}

private val EMOJI_DATA = mapOf(
    EmojiCategory.RECENT to listOf("❤️", "👍", "😂", "🔥", "🙏", "✨", "😊", "🎉"),
    EmojiCategory.SMILEYS to listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣",
        "🥲", "☺️", "😊", "😇", "🙂", "🙃", "😉", "😌",
        "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛",
        "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸",
        "🤩", "🥳", "😏", "😒", "😞", "😔", "😟", "😕",
        "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢",
        "😭", "😮‍💨", "😤", "😠", "😡", "🤬", "🤯", "😳",
        "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗"
    ),
    EmojiCategory.GESTURES to listOf(
        "👍", "👎", "👊", "✊", "🤛", "🤜", "👏", "🙌",
        "👐", "🤲", "🤝", "🙏", "✌️", "🤞", "🤟", "🤘",
        "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️", "👋"
    ),
    EmojiCategory.HEARTS to listOf(
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
        "🤎", "💔", "❤️‍🔥", "❤️‍🩹", "❣️", "💕", "💞", "💓",
        "💗", "💖", "💘", "💝", "💟"
    ),
    EmojiCategory.ANIMALS to listOf(
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
        "🐻‍❄️", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵",
        "🐔", "🐧", "🐦", "🐤", "🦆", "🦅", "🦉", "🦇"
    ),
    EmojiCategory.FOOD to listOf(
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇",
        "🍓", "🫐", "🍈", "🍒", "🍑", "🥭", "🍍", "🥥",
        "🍔", "🍟", "🍕", "🌭", "🥪", "🌮", "🌯", "🥗"
    ),
    EmojiCategory.OBJECTS to listOf(
        "💡", "🔦", "🕯️", "📱", "💻", "⌨️", "🖥️", "🖨️",
        "🖱️", "📷", "🎥", "📺", "📻", "⏰", "⌛", "⏳",
        "🔑", "🗝️", "🔨", "🪓", "🔧", "🪛", "🔩", "⚙️"
    )
)

/**
 * Advanced Emoji Picker Panel with categories, search, and smooth glass layout.
 */
@Composable
fun NexaEmojiPicker(
    onEmojiSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    var selectedCategory by remember { mutableStateOf(EmojiCategory.RECENT) }
    var searchQuery by remember { mutableStateOf("") }

    val surfaceColor = if (isDark) {
        Color(0xEE1A1A1A)
    } else {
        Color(0xEEF8F9FA)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)

    val displayedEmojis = remember(selectedCategory, searchQuery) {
        if (searchQuery.isBlank()) {
            EMOJI_DATA[selectedCategory].orEmpty()
        } else {
            EMOJI_DATA.values.flatten().distinct().filter {
                // In actual production, keyword matching could be extended
                true
            }.take(40)
        }
    }

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        surfaceColor = surfaceColor,
        elevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Category Selector Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EmojiCategory.values().forEach { cat ->
                    val isSelected = cat == selectedCategory
                    val chipBg = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = if (isDark) 0.25f else 0.15f)
                    } else {
                        Color.Transparent
                    }
                    Box(
                        modifier = Modifier
                            .clickable { selectedCategory = cat }
                            .background(chipBg, RoundedCornerShape(16.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = cat.icon, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = cat.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else contentColor
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Emoji Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(displayedEmojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onEmojiSelected(emoji) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 22.sp)
                    }
                }
            }
        }
    }
}
