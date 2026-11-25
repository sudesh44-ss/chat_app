package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.sms.SmsFallbackState

/**
 * Subtle transport/status pill for the encrypted SMS fallback.
 * Never displays a carrier delivery claim; "Delivered" requires an application ACK.
 */
@Composable
fun SmsTransportStatusChip(
    state: SmsFallbackState,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (state) {
        SmsFallbackState.INTERNET_ONLINE -> "Net" to MaterialTheme.colorScheme.primary
        SmsFallbackState.MESH_AVAILABLE -> "Mesh" to MaterialTheme.colorScheme.tertiary
        SmsFallbackState.SMS_READY -> "SMS ready" to MaterialTheme.colorScheme.secondary
        SmsFallbackState.SMS_SESSION_NOT_READY -> "SMS key not ready" to MaterialTheme.colorScheme.error
        SmsFallbackState.SMS_PHONE_UNAVAILABLE -> "No phone number" to MaterialTheme.colorScheme.error
        SmsFallbackState.SMS_SIM_UNAVAILABLE -> "No SIM" to MaterialTheme.colorScheme.error
        SmsFallbackState.SMS_GROUP_DISABLED -> "Group: SMS off" to MaterialTheme.colorScheme.outline
        SmsFallbackState.SMS_SENDING -> "SMS sending" to MaterialTheme.colorScheme.secondary
        SmsFallbackState.SMS_SENT_UNCONFIRMED -> "SMS sent · unconfirmed" to MaterialTheme.colorScheme.secondary
        SmsFallbackState.SMS_DELIVERED_APP_ACK -> "SMS delivered ✓✓" to MaterialTheme.colorScheme.primary
        SmsFallbackState.SMS_FAILED -> "SMS failed" to MaterialTheme.colorScheme.error
    }

    Surface(
        color = color.copy(alpha = 0.16f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "✉️", fontSize = 9.sp)
            Spacer(modifier = Modifier.size(3.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}
