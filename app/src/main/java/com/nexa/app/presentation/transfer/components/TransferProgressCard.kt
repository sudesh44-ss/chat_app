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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nexa.app.core.security.ReleaseAuthorization
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
    onOpen: ((TransferEntity) -> Unit)? = null,
    onSaveToDevice: ((TransferEntity) -> Unit)? = null,
    onShredQuarantine: ((Long) -> Unit)? = null,
    onAcceptOffer: ((Long) -> Unit)? = null,
    onRelease: ((Long, ReleaseAuthorization) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val progress = if (transfer.totalChunks > 0) {
        (transfer.chunksCompleted.toFloat() / transfer.totalChunks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val percent = (progress * 100).toInt()
    val confirmationStep = remember(transfer.transferId) { mutableIntStateOf(0) }
    val pendingAuthorization = remember(transfer.transferId) { mutableStateOf<ReleaseAuthorization?>(null) }
    val isFailedScan = transfer.status == "REJECTED_MALICIOUS" && transfer.scanResult == "MALICIOUS"
    val isApk = transfer.fileClassification == "APK"
    val isExecutable = transfer.fileClassification == "EXECUTABLE"
    val mayOfferRiskRelease = isFailedScan &&
        transfer.fileClassification in setOf("NORMAL", "APK", "EXECUTABLE")

    fun startConfirmation(authorization: ReleaseAuthorization) {
        pendingAuthorization.value = authorization
        confirmationStep.intValue = 1
    }

    fun denyAndDelete() {
        confirmationStep.intValue = 0
        pendingAuthorization.value = null
        onShredQuarantine?.invoke(transfer.transferId)
    }

    fun completeRelease() {
        pendingAuthorization.value?.let { onRelease?.invoke(transfer.transferId, it) }
        confirmationStep.intValue = 0
        pendingAuthorization.value = null
    }

    val completedBytes = (transfer.chunksCompleted.toLong() * FileChunk.DEFAULT_CHUNK_SIZE)
        .coerceAtMost(transfer.fileSizeBytes)
    val formattedCurrent = formatFileSize(completedBytes)
    val formattedTotal = formatFileSize(transfer.fileSizeBytes)

    val (statusColor, statusLabel) = when (transfer.status) {
        "OFFERED" -> Pair(MaterialTheme.colorScheme.primary, "Transfer Offer Received")
        "SCAN_PASSED", "WAITING_FOR_USER_ACCEPTANCE" -> Pair(NexaVerifiedGreen, "Scan Passed • Action Required")
        "COMPLETED", "RELEASED" -> Pair(NexaVerifiedGreen, "Verified & Released")
        "TRANSFERRING" -> Pair(MaterialTheme.colorScheme.primary, "Transferring ($percent%)")
        "CONNECTING" -> Pair(NexaWarningOrange, "Connecting WebRTC...")
        "PAUSED" -> Pair(NexaWarningOrange, "Paused")
        "QUARANTINED", "VERIFYING" -> Pair(NexaBadgeMonospace, "Quarantined Sandbox (Scanning...)")
        "REJECTED_MALICIOUS" -> Pair(MaterialTheme.colorScheme.error, "Security scan failed • Quarantined")
        "REJECTED_CORRUPTED" -> Pair(MaterialTheme.colorScheme.error, "Corrupted / Hash Mismatch ⚠️")
        "REJECTED_SENDER" -> Pair(MaterialTheme.colorScheme.error, "Pre-Dispatch Blocked ⚠️")
        "SCAN_INCONCLUSIVE", "SCAN_ERROR" -> Pair(MaterialTheme.colorScheme.error, "Scan incomplete • Restricted")
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
                // Direction icon
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            color = if (transfer.isIncoming) NexaVerifiedGreen.copy(alpha = 0.15f) else NexaBadgeMonospace.copy(alpha = 0.15f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (transfer.isIncoming) Icons.Default.FileDownload else Icons.Default.FileUpload,
                        contentDescription = if (transfer.isIncoming) "Incoming" else "Outgoing",
                        tint = if (transfer.isIncoming) NexaVerifiedGreen else NexaBadgeMonospace,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // File Name & Status
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(color = statusColor, shape = CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }

                // Action icons: Pause, Resume, Cancel
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

                        "COMPLETED", "RELEASED" -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Completed",
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        "SCAN_PASSED", "WAITING_FOR_USER_ACCEPTANCE" -> {
                            Icon(
                                imageVector = Icons.Default.VerifiedUser,
                                contentDescription = "Verified",
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        "FAILED", "SCAN_INCONCLUSIVE", "SCAN_ERROR", "REJECTED_MALICIOUS", "REJECTED_CORRUPTED", "REJECTED_SENDER" -> {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                if (confirmationStep.intValue > 0) {
                    val firstWarning = confirmationStep.intValue == 1
                    val failedApk = isApk && isFailedScan
                    val failedExecutable = isExecutable && isFailedScan
                    val title = when {
                        failedApk && firstWarning -> "HIGH-RISK APPLICATION"
                        failedApk -> "FINAL WARNING"
                        failedExecutable && firstWarning -> "HIGH-RISK EXECUTABLE"
                        failedExecutable -> "FINAL WARNING"
                        isApk && firstWarning -> "Android Application File"
                        isApk -> "Are You Sure?"
                        isExecutable && firstWarning -> "Executable File"
                        isExecutable -> "Final Warning"
                        firstWarning -> "Suspicious File Detected"
                        else -> "Are You Sure?"
                    }
                    val message = when {
                        failedApk && firstWarning ->
                            "NEXA's security scan detected suspicious or potentially malicious characteristics."
                        failedApk ->
                            "This application failed NEXA's security check. Installing it may compromise your device, accounts or data."
                        failedExecutable && firstWarning ->
                            "NEXA's security scan detected suspicious executable characteristics. This file may run code when opened."
                        failedExecutable ->
                            "This executable did not pass NEXA's security check. Releasing it may compromise your device or data."
                        isApk && firstWarning ->
                            "This file is an APK and can install software on your device."
                        isApk ->
                            "Only install applications you trust. NEXA cannot guarantee the application's future behavior."
                        isExecutable && firstWarning ->
                            "This file can run code. NEXA will not execute it, but another application might."
                        isExecutable ->
                            "Only release executable files from sources you trust. NEXA will not run this file."
                        firstWarning ->
                            "NEXA's security scan found content that may be unsafe or malicious."
                        else ->
                            "This file failed the security check. Downloading it may put your device or data at risk."
                    }
                    val confirmLabel = when {
                        firstWarning && (failedApk || failedExecutable || (!isApk && !isExecutable && isFailedScan)) ->
                            "I Understand, Continue"
                        firstWarning -> "Continue"
                        failedApk || failedExecutable -> "Release Anyway"
                        isApk || isExecutable -> "Continue"
                        else -> "Download Anyway"
                    }
                    val cancelLabel = when {
                        failedApk && firstWarning -> "Deny"
                        failedExecutable && firstWarning -> "Deny"
                        firstWarning -> "Cancel"
                        isApk || isExecutable -> "Deny"
                        else -> "Deny"
                    }

                    AlertDialog(
                        onDismissRequest = {
                            confirmationStep.intValue = 0
                            pendingAuthorization.value = null
                        },
                        title = { Text(title, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                Text(message)
                                if (failedApk) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Sender's scan status: ${transfer.senderScanReported ?: "Unknown"}")
                                    Text("Your device's scan: Failed")
                                } else if (failedExecutable && firstWarning) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text("Sender's scan status: ${transfer.senderScanReported ?: "Unknown"}")
                                    Text("Your device's scan: Failed")
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    if (firstWarning) {
                                        confirmationStep.intValue = 2
                                    } else {
                                        completeRelease()
                                    }
                                }
                            ) {
                                Text(confirmLabel)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    when {
                                        firstWarning && (failedApk || failedExecutable) -> denyAndDelete()
                                        !firstWarning && (isFailedScan || failedExecutable) -> denyAndDelete()
                                        else -> {
                                            confirmationStep.intValue = 0
                                            pendingAuthorization.value = null
                                        }
                                    }
                                }
                            ) {
                                Text(cancelLabel)
                            }
                        }
                    )
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

            // PHASE 4: RECEIVER OFFER ACCEPTANCE GATE
            if (transfer.status == "OFFERED" && transfer.isIncoming) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Sender scan (unverified): ${transfer.senderScanReported ?: "Unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { onCancel(transfer.transferId) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Decline", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onAcceptOffer?.invoke(transfer.transferId) ?: onResume(transfer.transferId)
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Download & Scan", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // PHASE 6: EXPLICIT RECEIVER RELEASE GATE ([Save to NEXA] vs [Decline])
            if (transfer.status in listOf("SCAN_PASSED", "WAITING_FOR_USER_ACCEPTANCE")) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "✓ File verified • ✓ Security scan passed\nThe file is ready in quarantine. It has not been saved or opened.",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexaVerifiedGreen
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = { onCancel(transfer.transferId) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Decline", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                when {
                                    isApk -> startConfirmation(ReleaseAuthorization.CLEAN_APK)
                                    isExecutable -> startConfirmation(ReleaseAuthorization.CLEAN_EXECUTABLE)
                                    transfer.fileClassification == "NORMAL" ->
                                        onRelease?.invoke(transfer.transferId, ReleaseAuthorization.CLEAN_NORMAL)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NexaVerifiedGreen, contentColor = Color.Black),
                            modifier = Modifier.height(34.dp),
                            enabled = transfer.fileClassification != "UNKNOWN" &&
                                transfer.fileClassification != "UNSUPPORTED"
                        ) {
                            Text(
                                text = if (isApk || isExecutable) "Review Security Warnings" else "Save to NEXA",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Post-Release Controlled Actions
            if (transfer.status in listOf("COMPLETED", "RELEASED")) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onSaveToDevice != null) {
                        OutlinedButton(
                            onClick = { onSaveToDevice(transfer) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save to Device", fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    if (onOpen != null) {
                        Button(
                            onClick = { onOpen(transfer) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NexaVerifiedGreen,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Open", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Quarantined Threat Resolution Actions
            if (transfer.status in listOf(
                    "REJECTED_MALICIOUS",
                    "REJECTED_CORRUPTED",
                    "SCAN_INCONCLUSIVE",
                    "SCAN_ERROR"
                )
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (transfer.status) {
                            "REJECTED_MALICIOUS" -> "Receiver security scan failed. File remains quarantined."
                            "REJECTED_CORRUPTED" -> "Integrity check failed. The file cannot be released."
                            else -> "File could not be fully scanned and remains restricted."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (mayOfferRiskRelease) {
                        OutlinedButton(
                            onClick = {
                                val authorization = when {
                                    isApk -> ReleaseAuthorization.FAILED_APK_RISK
                                    isExecutable -> ReleaseAuthorization.EXECUTABLE_RISK
                                    else -> ReleaseAuthorization.FAILED_NORMAL_RISK
                                }
                                startConfirmation(authorization)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Review Risk", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Button(
                        onClick = { denyAndDelete() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Delete & Shred", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (transfer.status == "REJECTED_SENDER") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Blocked by security policy. Aborted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onCancel(transfer.transferId) },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Dismiss", fontSize = 11.sp)
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
