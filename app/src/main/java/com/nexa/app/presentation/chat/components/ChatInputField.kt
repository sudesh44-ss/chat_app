package com.nexa.app.presentation.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.model.TransportType

@Composable
fun ChatInputField(
    text: String,
    selectedTransport: TransportType = TransportType.INTERNET,
    onTextChanged: (String) -> Unit,
    onSendClicked: () -> Unit,
    onToggleTransport: () -> Unit = {},
    onAttachFileClicked: () -> Unit = {},
    onAttachVideoClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val (activeAccentColor, iconVector, desc, placeholderText) = when (selectedTransport) {
        TransportType.DATA_SMS -> Quadruple(
            NexaWarningOrange,
            Icons.Default.SignalCellularAlt,
            "Port 8901 Data SMS Active",
            "Port 8901 SMS (Invisible)..."
        )
        TransportType.DTN_MESH -> Quadruple(
            NexaBadgeMonospace,
            Icons.Default.Hub,
            "DTN Mesh Active",
            "DTN Sealed Envelope (7 Hops)..."
        )
        TransportType.INTERNET -> Quadruple(
            MaterialTheme.colorScheme.primary,
            Icons.Default.Cloud,
            "Internet Relay Active",
            "Encrypted message..."
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Transport Mode Toggle Icon Button
        IconButton(
            onClick = onToggleTransport,
            modifier = Modifier
                .size(42.dp)
                .background(
                    color = activeAccentColor.copy(alpha = 0.15f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = iconVector,
                contentDescription = desc,
                tint = activeAccentColor,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChanged,
            placeholder = {
                Text(
                    text = placeholderText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                focusedBorderColor = activeAccentColor.copy(alpha = 0.6f),
                unfocusedBorderColor = Color.Transparent
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (text.isNotBlank()) onSendClicked()
                }
            ),
            maxLines = 4,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // Direct P2P File Attachment Button
        IconButton(
            onClick = onAttachFileClicked,
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.AttachFile,
                contentDescription = "Send File via Direct P2P",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        if (onAttachVideoClicked != null) {
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onAttachVideoClicked,
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = CircleShape
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Send Video (Optimized HD)",
                    tint = activeAccentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        val canSend = text.isNotBlank()
        val targetButtonBackground = if (canSend) activeAccentColor else MaterialTheme.colorScheme.surfaceVariant
        val buttonBackground by animateColorAsState(
            targetValue = targetButtonBackground,
            animationSpec = tween(150),
            label = "sendBtnBg"
        )
        val iconTint = if (canSend) {
            if (selectedTransport == TransportType.DATA_SMS || selectedTransport == TransportType.DTN_MESH) {
                Color.Black
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
        } else {
            MaterialTheme.colorScheme.outline
        }

        Box(
            modifier = Modifier
                .size(46.dp)
                .background(color = buttonBackground, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onSendClicked,
                enabled = canSend
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
