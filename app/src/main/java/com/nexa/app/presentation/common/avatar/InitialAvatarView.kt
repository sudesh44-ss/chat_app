package com.nexa.app.presentation.common.avatar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * High-Performance Alphabet Initial Avatar
 * Renders a centered initial character with dynamic size scaling and
 * deterministic color distribution from Material palette.
 */
@Composable
fun InitialAvatarView(
    initial: Char,
    backgroundColor: Long,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(backgroundColor)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.45).sp, // Dynamic size scaling relative to avatar diameter
            style = MaterialTheme.typography.titleMedium
        )
    }
}
