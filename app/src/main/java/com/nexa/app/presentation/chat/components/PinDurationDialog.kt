package com.nexa.app.presentation.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class PinDuration(val label: String, val durationMillis: Long?) {
    HOURS_24("24 hours", 24 * 60 * 60 * 1000L),
    HOURS_48("48 hours", 48 * 60 * 60 * 1000L),
    DAYS_7("7 days", 7 * 24 * 60 * 60 * 1000L),
    DAYS_30("30 days", 30L * 24 * 60 * 60 * 1000L),
    INDEFINITE("Until I unpin it", null)
}

@Composable
fun PinDurationDialog(
    onDismiss: () -> Unit,
    onConfirmPin: (PinDuration) -> Unit
) {
    var selectedOption by remember { mutableStateOf(PinDuration.HOURS_24) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin Message", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text(
                    text = "How long do you want to pin this message?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                PinDuration.entries.forEach { duration ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedOption = duration }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selectedOption == duration),
                            onClick = { selectedOption = duration }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = duration.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirmPin(selectedOption); onDismiss() }) {
                Text("Pin")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
