package com.nexa.app.presentation.chat.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.data.local.preferences.ConversationPreferences
import com.nexa.app.presentation.common.glass.NexaGlassSurface

data class WallpaperPreset(
    val id: String,
    val name: String,
    val backgroundBrush: Brush,
    val isDefault: Boolean = false
)

/**
 * Chat Wallpaper Customization Screen
 *
 * Allows selecting theme-aware glass presets, previewing with realistic
 * message bubbles, and persisting per conversationId.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatWallpaperScreen(
    conversationId: String,
    conversationPreferences: ConversationPreferences,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val context = LocalContext.current

    val screenBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val currentSaved = remember { conversationPreferences.getWallpaper(conversationId) }

    val presets = remember(isDark) {
        listOf(
            WallpaperPreset(
                id = "default",
                name = "Default Glass",
                backgroundBrush = Brush.verticalGradient(
                    if (isDark) listOf(Color(0xFF000000), Color(0xFF121212))
                    else listOf(Color(0xFFFFFFFF), Color(0xFFF3F4F6))
                ),
                isDefault = true
            ),
            WallpaperPreset(
                id = "midnight",
                name = "Midnight",
                backgroundBrush = Brush.verticalGradient(
                    listOf(Color(0xFF050B14), Color(0xFF0A192F))
                )
            ),
            WallpaperPreset(
                id = "emerald",
                name = "Emerald",
                backgroundBrush = Brush.verticalGradient(
                    listOf(Color(0xFF021B14), Color(0xFF063327))
                )
            ),
            WallpaperPreset(
                id = "amethyst",
                name = "Amethyst",
                backgroundBrush = Brush.verticalGradient(
                    listOf(Color(0xFF180A22), Color(0xFF281139))
                )
            ),
            WallpaperPreset(
                id = "graphite",
                name = "Graphite",
                backgroundBrush = Brush.verticalGradient(
                    listOf(Color(0xFF1A1A1A), Color(0xFF262626))
                )
            )
        )
    }

    var selectedPreset by remember {
        mutableStateOf(presets.find { it.id == currentSaved } ?: presets.first())
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg),
        containerColor = screenBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Chat Wallpaper",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Preview Card with Mock Chat Bubbles
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(selectedPreset.backgroundBrush)
                    .border(
                        1.dp,
                        if (isDark) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.10f),
                        RoundedCornerShape(24.dp)
                    )
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Incoming Mock Bubble
                    NexaGlassSurface(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .align(Alignment.Start),
                        shape = RoundedCornerShape(18.dp),
                        surfaceColor = if (isDark) Color(0xCC1E1E1E) else Color(0xEEFFFFFF),
                        elevation = 2.dp
                    ) {
                        Text(
                            text = "Hey! How does this wallpaper look?",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = primaryTextColor
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Outgoing Mock Bubble
                    NexaGlassSurface(
                        modifier = Modifier
                            .fillMaxWidth(0.75f)
                            .align(Alignment.End),
                        shape = RoundedCornerShape(18.dp),
                        surfaceColor = if (isDark) Color(0xFF1D4ED8) else Color(0xFF2563EB),
                        elevation = 2.dp
                    ) {
                        Text(
                            text = "Pure Liquid Glass. Text contrast is crisp and readable!",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Preset Selector Row
            Text(
                text = "Preset Wallpapers",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                ),
                color = primaryTextColor,
                modifier = Modifier.align(Alignment.Start)
            )

            Spacer(modifier = Modifier.height(10.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(presets) { preset ->
                    val isSelected = preset.id == selectedPreset.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selectedPreset = preset }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(preset.backgroundBrush)
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF2979FF) else Color.Gray.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(16.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2979FF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (isSelected) primaryTextColor else secondaryTextColor
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons (Set for Chat / Reset to Default)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        conversationPreferences.setWallpaper(conversationId, null)
                        selectedPreset = presets.first()
                        Toast.makeText(context, "Reset to default wallpaper", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Reset Default", color = primaryTextColor)
                }

                Button(
                    onClick = {
                        val toSave = if (selectedPreset.isDefault) null else selectedPreset.id
                        conversationPreferences.setWallpaper(conversationId, toSave)
                        Toast.makeText(context, "Wallpaper set for this chat", Toast.LENGTH_SHORT).show()
                        onBack()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Set for Chat", color = Color.White)
                }
            }
        }
    }
}
