package com.nexa.app.presentation.chat.components

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.sms.SmsEligibility
import com.nexa.app.core.sms.SmsOutboundPlan
import com.nexa.app.core.theme.NexaWarningOrange

/**
 * Encrypted SMS fallback sheet.
 *
 * Honest by construction:
 *  - no claim of automatic SMS interception or guaranteed delivery;
 *  - the external SMS app performs the send (Mode A, zero SMS permissions);
 *  - "Delivered" is only ever shown after an authenticated NX1 ACK is imported.
 */
data class SmsFallbackUiState(
    val isOpen: Boolean = false,
    val isPreparing: Boolean = false,
    val eligibility: SmsEligibility? = null,
    val plan: SmsOutboundPlan? = null,
    val segmentCount: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val ackToken: String? = null,
    val importSender: String = "",
    val importBody: String = "",
    val isImporting: Boolean = false,
    val hasReadPhoneState: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsFallbackSheet(
    state: SmsFallbackUiState,
    draftText: String,
    onDismiss: () -> Unit,
    onPrepare: () -> Unit,
    onOpenSmsApp: () -> Unit,
    onMarkSent: () -> Unit,
    onCopyPacket: () -> Unit,
    onImportSenderChanged: (String) -> Unit,
    onImportBodyChanged: (String) -> Unit,
    onImport: () -> Unit,
    onCopyAck: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(NexaWarningOrange.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = null,
                        tint = NexaWarningOrange,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = "Encrypted SMS fallback",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Text only · 1-to-1 · end-to-end encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val eligibility = state.eligibility
                    Text(
                        text = eligibility?.userMessage
                            ?: "Checking whether encrypted SMS is available for this conversation…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "NEXA cannot read or send SMS without SMS permissions. Your own messaging app " +
                            "sends the encrypted packet, and NEXA only confirms delivery when the recipient " +
                            "returns an authenticated SMS acknowledgement.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Your carrier can still see the destination number, timing and message length. " +
                            "Only the content is encrypted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val plan = state.plan
            if (plan == null) {
                Button(
                    onClick = onPrepare,
                    enabled = !state.isPreparing && draftText.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isPreparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypting…")
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypt current message", fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("NX1 PACKET READY", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NexaWarningOrange)
                        Text(
                            text = "To ${plan.destinationNumber.take(4)}…${plan.destinationNumber.takeLast(2)} " +
                                "· ${plan.partCount} NX1 part(s) · $state.segmentCount SMS segment(s)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "Carrier SMS charges apply. NEXA cannot confirm that the carrier delivered it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onOpenSmsApp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open SMS app to send", fontWeight = FontWeight.SemiBold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onCopyPacket, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy packet")
                    }
                    OutlinedButton(onClick = onMarkSent, modifier = Modifier.weight(1f)) {
                        Text("I sent it")
                    }
                }
            }

            state.errorMessage?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            state.statusMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Import an incoming encrypted SMS",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Paste the NX1 message your contact received. NEXA verifies the sender, decrypts it " +
                    "and files it in the same NEXA conversation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.importSender,
                onValueChange = onImportSenderChanged,
                label = { Text("Sender phone number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.importBody,
                onValueChange = onImportBodyChanged,
                label = { Text("NX1 packet") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onImport,
                enabled = !state.isImporting &&
                    state.importBody.isNotBlank() &&
                    state.importSender.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NexaWarningOrange, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import & decrypt", fontWeight = FontWeight.SemiBold)
            }

            state.ackToken?.let { token ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "DELIVERY ACK READY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "NEXA cannot send SMS automatically. Copy this authenticated ACK and send it back " +
                                "to confirm delivery.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = onCopyAck) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy ACK packet")
                        }
                    }
                }
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Close", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
