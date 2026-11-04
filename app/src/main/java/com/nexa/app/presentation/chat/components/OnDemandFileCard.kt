package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.data.local.entity.FileMetadataEntity
import com.nexa.app.data.local.entity.FileStatus
import java.util.Locale

@Composable
fun OnDemandFileCard(
    fileMetadata: FileMetadataEntity,
    onDownloadClick: () -> Unit,
    onOpenClick: () -> Unit,
    onThreatDetailsClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedSize = remember(fileMetadata.fileSizeBytes) {
        formatFileSize(fileMetadata.fileSizeBytes)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File Icon with state-based background
            val (iconVector, iconTint) = when (fileMetadata.status) {
                FileStatus.VERIFIED_SAFE -> Pair(Icons.Default.Security, NexaVerifiedGreen)
                FileStatus.QUARANTINED_THREAT -> Pair(Icons.Default.Warning, MaterialTheme.colorScheme.error)
                FileStatus.IN_QUARANTINE -> Pair(Icons.Default.Security, NexaBadgeMonospace)
                else -> Pair(Icons.Default.Download, MaterialTheme.colorScheme.primary)
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(iconTint.copy(alpha = 0.15f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = iconVector,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileMetadata.fileName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val statusText = when (fileMetadata.status) {
                        FileStatus.METADATA_ONLY -> "On-Demand (0 MB Used)"
                        FileStatus.DOWNLOADING -> "Streaming from Swarm..."
                        FileStatus.IN_QUARANTINE -> "Gate 2 Sandbox Scanning..."
                        FileStatus.VERIFIED_SAFE -> "Verified Safe"
                        FileStatus.QUARANTINED_THREAT -> "Threat Blocked ⚠️"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = iconTint,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action Control depending on FileStatus
            when (fileMetadata.status) {
                FileStatus.METADATA_ONLY -> {
                    IconButton(onClick = onDownloadClick) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download File",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                FileStatus.DOWNLOADING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                FileStatus.IN_QUARANTINE -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = NexaBadgeMonospace
                    )
                }

                FileStatus.VERIFIED_SAFE -> {
                    Button(
                        onClick = onOpenClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NexaVerifiedGreen)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Open", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                FileStatus.QUARANTINED_THREAT -> {
                    Button(
                        onClick = onThreatDetailsClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Review", color = MaterialTheme.colorScheme.onError, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[digitGroups])
}
