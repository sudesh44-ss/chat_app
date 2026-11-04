package com.nexa.app.presentation.transfer.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.core.webrtc.FileChunk
import com.nexa.app.data.local.entity.TransferEntity
import java.util.Locale

@Composable
fun TransferProgressCard(
    transfer: TransferEntity,
    speedMBps: Double? = null,
    etaSeconds: Long? = null,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (transfer.totalChunks > 0) {
        (transfer.chunksCompleted.toFloat() / transfer.totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (progress * 100).toInt()

    val completedBytes = (transfer.chunksCompleted.toLong() * FileChunk.DEFAULT_CHUNK_SIZE)
        .coerceAtMost(transfer.fileSizeBytes)
    val formattedCurrent = formatFileSize(completedBytes)
    val formattedTotal = formatFileSize(transfer.fileSizeBytes)

    val (statusColor, statusLabel) = when (transfer.status) {
        "COMPLETED" -> Pair(NexaVerifiedGreen, "Completed")
        "TRANSFERRING" -> Pair(MaterialTheme.colorScheme.primary, "Transferring ($percent%)")
        "CONNECTING" -> Pair(NexaWarningOrange, "Connecting WebRTC...")
        "PAUSED" -> Pair(NexaWarningOrange, "Paused")
        "VERIFYING" -> Pair(NexaBadgeMonospace, "Verifying Blake3 Hash...")
        "FAILED" -> Pair(MaterialTheme.colorScheme.error, "Transfer Failed")
        "DECLINED" -> Pair(MaterialTheme.colorScheme.error, "Declined")
        "CANCELLED" -> Pair(MaterialTheme.colorScheme.outline, "Cancelled")
        else -> Pair(MaterialTheme.colorScheme.outline, "Pending...")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header Row: Icon, File Name, Direction & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(statusColor.copy(alpha = 0.15f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (transfer.isIncoming) Icons.Default.FileDownload else Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (transfer.isIncoming) "P2P Incoming" else "P2P Outgoing",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = statusColor
                        )
                    }
                }

                // Action buttons: Pause, Resume, Cancel
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (transfer.status) {
                        "TRANSFERRING" -> {
                            IconButton(
                                onClick = { onPause(transfer.transferId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Pause",
                                    tint = NexaWarningOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onCancel(transfer.transferId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        "PAUSED" -> {
                            IconButton(
                                onClick = { onResume(transfer.transferId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Resume",
                                    tint = NexaVerifiedGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onCancel(transfer.transferId) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        "COMPLETED" -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completed",
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        "FAILED" -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Failed",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = statusColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Metadata footer: Size transferred, Speed MB/s, ETA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$formattedCurrent / $formattedTotal",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (transfer.status == "TRANSFERRING") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (speedMBps != null && speedMBps > 0.0) {
                            Text(
                                text = String.format(Locale.US, "%.1f MB/s", speedMBps),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = NexaVerifiedGreen
                            )
                        }
                        if (etaSeconds != null && etaSeconds > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "• ETA: ${formatEta(etaSeconds)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
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

private fun formatEta(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
