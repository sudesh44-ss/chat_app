package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class AttachmentType {
    CAMERA,
    GALLERY,
    AUDIO,
    CONTACT,
    LINK,
    DOCUMENT,
    MUSIC,
    VIDEO,
    LOCATION
}

/**
 * 3x3 Attachment Menu Grid with exact 9 items:
 * 1. Camera
 * 2. Gallery
 * 3. Audio
 * 4. Contact
 * 5. Link
 * 6. Document
 * 7. Music
 * 8. Video
 * 9. Location
 */
@Composable
fun NexaAttachmentMenu(
    onAttachmentSelected: (AttachmentType) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) {
        Color(0xEE1E1E1E)
    } else {
        Color(0xEEFFFFFF)
    }

    val contentColor = if (isDark) Color.White else Color(0xFF111111)

    val items = listOf(
        Triple(AttachmentType.CAMERA, "Camera", Pair(R.drawable.ic_nexa_camera, Color(0xFFE53935))),
        Triple(AttachmentType.GALLERY, "Gallery", Pair(R.drawable.ic_nexa_image, Color(0xFF8E24AA))),
        Triple(AttachmentType.AUDIO, "Audio", Pair(R.drawable.ic_nexa_audio_lines, Color(0xFFFB8C00))),
        Triple(AttachmentType.CONTACT, "Contact", Pair(R.drawable.ic_nexa_contact_card, Color(0xFF039BE5))),
        Triple(AttachmentType.LINK, "Link", Pair(R.drawable.ic_nexa_link, Color(0xFF00897B))),
        Triple(AttachmentType.DOCUMENT, "Document", Pair(R.drawable.ic_nexa_file, Color(0xFF5E35B1))),
        Triple(AttachmentType.MUSIC, "Music", Pair(R.drawable.ic_nexa_music, Color(0xFFD81B60))),
        Triple(AttachmentType.VIDEO, "Video", Pair(R.drawable.ic_nexa_video_film, Color(0xFF43A047))),
        Triple(AttachmentType.LOCATION, "Location", Pair(R.drawable.ic_nexa_location, Color(0xFF3949AB)))
    )

    NexaGlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        surfaceColor = surfaceColor,
        elevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            for (row in 0..2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    for (col in 0..2) {
                        val index = row * 3 + col
                        val item = items[index]
                        AttachmentGridItem(
                            type = item.first,
                            title = item.second,
                            iconRes = item.third.first,
                            accentColor = item.third.second,
                            textColor = contentColor,
                            onClick = { onAttachmentSelected(item.first) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentGridItem(
    type: AttachmentType,
    title: String,
    iconRes: Int,
    accentColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp),
            contentAlignment = Alignment.Center
        ) {
            NexaGlassSurface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                surfaceColor = accentColor.copy(alpha = 0.15f),
                borderColor = accentColor.copy(alpha = 0.35f),
                borderWidth = 1.dp,
                elevation = 2.dp
            ) {
                Box(
                    modifier = Modifier.size(52.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = title,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            textAlign = TextAlign.Center
        )
    }
}
