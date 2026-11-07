package com.nexa.app.presentation.profile.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Mute Notifications Duration Dialog
 */
@Composable
fun MuteNotificationsDialog(
    contactName: String,
    isCurrentlyMuted: Boolean,
    onSetMuteDuration: (Long) -> Unit, // duration in millis, or -1 for indefinite, 0 for unmute
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val options = listOf(
        "8 Hours" to 8 * 3600 * 1000L,
        "1 Week" to 7 * 24 * 3600 * 1000L,
        "Always" to -1L
    )

    var selectedIndex by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaceColor,
        title = {
            Text(
                text = if (isCurrentlyMuted) "Unmute or Change Mute" else "Mute Notifications",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = primaryTextColor
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Other participants will not see that you muted this conversation.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = secondaryTextColor
                )
                Spacer(modifier = Modifier.height(14.dp))

                options.forEachIndexed { index, (label, _) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIndex = index }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = primaryTextColor
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val duration = options[selectedIndex].second
                    onSetMuteDuration(duration)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Mute", color = Color.White)
            }
        },
        dismissButton = {
            if (isCurrentlyMuted) {
                TextButton(
                    onClick = {
                        onSetMuteDuration(0L) // Unmute
                        onDismiss()
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Unmute", color = if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32))
                }
            } else {
                TextButton(onClick = onDismiss, shape = RoundedCornerShape(20.dp)) {
                    Text("Cancel", color = secondaryTextColor)
                }
            }
        }
    )
}

/**
 * Custom Notifications Preference Dialog
 */
@Composable
fun CustomNotificationsDialog(
    contactName: String,
    currentTone: String?,
    isVibrationEnabled: Boolean,
    onSavePreferences: (tone: String, vibration: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val tones = listOf("Default NEXA Chime", "Subtle Pulse", "Soft Whistle", "Silent")
    var selectedTone by remember { mutableStateOf(currentTone ?: "Default NEXA Chime") }
    var vibration by remember { mutableStateOf(isVibrationEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = surfaceColor,
        title = {
            Text(
                text = "Custom Notifications",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = primaryTextColor
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Notification Sound",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = primaryTextColor
                )
                Spacer(modifier = Modifier.height(8.dp))

                tones.forEach { tone ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedTone = tone }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedTone == tone,
                            onClick = { selectedTone = tone },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = tone,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = primaryTextColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Vibrate",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = primaryTextColor
                        )
                        Text(
                            text = "Vibrate device on incoming message",
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryTextColor
                        )
                    }

                    Switch(
                        checked = vibration,
                        onCheckedChange = { vibration = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSavePreferences(selectedTone, vibration)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isDark) Color(0xFF2979FF) else Color(0xFF1976D2)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("Save", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(20.dp)) {
                Text("Cancel", color = secondaryTextColor)
            }
        }
    )
}
