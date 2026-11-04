package com.nexa.app.presentation.transfer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Inline alert chip rendered when hardware thermal throttling is engaged (Scenario 15).
 */
@Composable
fun ThermalWarningBadge(
    isThrottled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isThrottled) return

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFE65100),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "⚠️ Device warm: Transfer speed reduced to preserve hardware",
            color = Color(0xFFE65100),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
