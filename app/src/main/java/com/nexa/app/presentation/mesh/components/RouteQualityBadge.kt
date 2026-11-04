package com.nexa.app.presentation.mesh.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.mesh.model.LinkState

/**
 * Diagnostic pill showing real-time RF link quality:
 * Healthy (Green), Degraded (Yellow), or Unstable/Tombstoned (Red).
 */
@Composable
fun RouteQualityBadge(
    state: LinkState,
    modifier: Modifier = Modifier
) {
    val (dotColor, label, bgColor) = when (state) {
        LinkState.HEALTHY -> Triple(Color(0xFF2E7D32), "Healthy", Color(0xFFE8F5E9))
        LinkState.DEGRADED -> Triple(Color(0xFFF57F17), "Degraded", Color(0xFFFFFDE7))
        LinkState.UNSTABLE -> Triple(Color(0xFFC62828), "Unstable", Color(0xFFFFEBEE))
        LinkState.TOMBSTONED -> Triple(Color(0xFF757575), "Tombstoned", Color(0xFFEEEEEE))
    }

    Box(
        modifier = modifier
            .background(bgColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = dotColor
            )
        }
    }
}
