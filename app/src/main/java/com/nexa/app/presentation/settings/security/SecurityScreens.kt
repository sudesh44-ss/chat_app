package com.nexa.app.presentation.settings.security

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.remote.dto.DeviceInfoDto
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.settings.components.SettingsActionRow
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsInfoRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel
import com.nexa.app.presentation.settings.components.SettingsSwitchRow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AppLockScreen(
    secureStorage: SecureStorage,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val biometricsAvailable = remember(context) { supportsBiometrics(context) }
    var enabled by remember { mutableStateOf(secureStorage.isAppLockEnabled()) }
    var biometricEnabled by remember { mutableStateOf(secureStorage.isBiometricUnlockEnabled()) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    SettingsDetailPage(title = "App Lock", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "The PIN is stored only as a salted PBKDF2 hash inside EncryptedSharedPreferences " +
                "protected by the Android Keystore. NEXA never stores or transmits the PIN itself."
        )

        SettingsSectionLabel("Lock")
        SettingsCard {
            SettingsSwitchRow(
                title = "App Lock",
                subtitle = if (enabled) "NEXA asks for your PIN when the app returns to the foreground"
                else "Require a PIN to open NEXA",
                checked = enabled,
                onCheckedChange = { wantsEnabled ->
                    if (wantsEnabled) {
                        showSetPinDialog = true
                    } else {
                        showDisableDialog = true
                    }
                }
            )
            if (enabled) {
                SettingsDivider()
                SettingsActionRow(
                    title = "Change PIN",
                    subtitle = "Replace the current 4–8 digit PIN",
                    onClick = { showSetPinDialog = true }
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Biometric Unlock",
                    subtitle = if (biometricsAvailable) {
                        "Allow the device fingerprint or face unlock to open NEXA"
                    } else {
                        "Not available on this device"
                    },
                    checked = biometricEnabled,
                    enabled = biometricsAvailable,
                    onCheckedChange = {
                        biometricEnabled = it
                        secureStorage.setBiometricUnlockEnabled(it)
                    }
                )
            }
        }

        if (error != null) {
            Text(
                text = error!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        if (info != null) {
            Text(
                text = info!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        SettingsSectionLabel("Status")
        SettingsCard {
            SettingsInfoRow("Protection", if (enabled) "Enabled" else "Disabled")
            SettingsDivider()
            SettingsInfoRow("Hash algorithm", "PBKDF2-HMAC-SHA1 • 150,000 iterations")
            SettingsDivider()
            SettingsInfoRow("Secret storage", "Android Keystore / EncryptedSharedPreferences")
        }
    }

    if (showSetPinDialog) {
        PinSetupDialog(
            title = if (enabled) "Change PIN" else "Set App Lock PIN",
            onDismiss = { showSetPinDialog = false },
            onConfirm = { pin ->
                secureStorage.setAppLockPin(pin)
                enabled = true
                showSetPinDialog = false
                error = null
                info = "App lock PIN saved"
            }
        )
    }

    if (showDisableDialog) {
        PinVerifyDialog(
            title = "Turn off App Lock",
            confirmLabel = "Turn Off",
            onDismiss = { showDisableDialog = false },
            onVerify = { pin ->
                if (secureStorage.verifyAppLockPin(pin)) {
                    secureStorage.clearAppLock()
                    enabled = false
                    biometricEnabled = false
                    showDisableDialog = false
                    error = null
                    info = "App lock disabled"
                    true
                } else {
                    false
                }
            }
        )
    }
}

private fun supportsBiometrics(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
    val pm = context.packageManager
    return pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT) ||
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE) ||
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_IRIS)
}

@Composable
private fun PinSetupDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose a 4–8 digit PIN.", fontSize = 13.sp)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter { it.isDigit() }.take(8) },
                    label = { Text("New PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { value -> confirm = value.filter { it.isDigit() }.take(8) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    !com.nexa.app.core.security.AppLockPinHasher.isValidPin(pin) ->
                        error = "PIN must be 4–8 digits"
                    pin != confirm -> error = "PINs do not match"
                    else -> onConfirm(pin)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PinVerifyDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onVerify: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Enter your current PIN to continue.", fontSize = 13.sp)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter { it.isDigit() }.take(8) },
                    label = { Text("Current PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onVerify(pin)) Unit else error = "Incorrect PIN"
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConnectedDevicesScreen(
    authRepository: AuthRepository,
    currentDeviceId: String,
    hardwareSecurityLevel: String? = null,
    onNavigateBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfoDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<DeviceInfoDto?>(null) }

    fun reload() {
        scope.launch {
            isLoading = true
            authRepository.getDevices()
                .onSuccess {
                    devices = it
                    message = null
                }
                .onFailure {
                    message = "Could not load devices: ${it.localizedMessage ?: "network error"}"
                }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val activeDevices = devices.filter { !it.isRevoked }
    val revokedDevices = devices.filter { it.isRevoked }
    val currentDevice = devices.firstOrNull { it.deviceId == currentDeviceId }

    SettingsDetailPage(title = "Connected Devices", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "NEXA keeps one active device per account. Signing in on a new device " +
                "revokes the previous device and its session. Revoked devices are kept below " +
                "for your history."
        )

        SettingsSectionLabel("This device")
        SettingsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = currentDevice?.deviceName?.ifBlank { "NEXA Device" } ?: "Current device",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = currentDeviceId.ifBlank { "Device id unavailable" },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Hardware security: ${hardwareSecurityLabel(hardwareSecurityLevel)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val lastActive = currentDevice?.let { formatTimestamp(it.lastActiveAt) }
                if (lastActive != null) {
                    Text(
                        text = "Last active $lastActive",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        SettingsSectionLabel("Active devices")
        SettingsCard {
            when {
                isLoading -> Text(
                    "Loading devices…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                activeDevices.isEmpty() -> Text(
                    "No active devices were returned by the server.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
                else -> activeDevices.forEachIndexed { index, device ->
                    if (index > 0) SettingsDivider()
                    val isCurrent = device.deviceId == currentDeviceId
                    Column {
                        SettingsInfoRow(
                            label = device.deviceName.ifBlank { "NEXA Device" },
                            value = if (isCurrent) "This device" else "Active"
                        )
                        Text(
                            text = buildString {
                                append(device.deviceId)
                                val lastActive = formatTimestamp(device.lastActiveAt)
                                if (lastActive != null) append(" • Last active ").append(lastActive)
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                        if (!isCurrent) {
                            SettingsActionRow(
                                title = "Revoke Session",
                                destructive = true,
                                onClick = { pendingRevoke = device }
                            )
                        }
                    }
                }
            }
        }

        if (revokedDevices.isNotEmpty()) {
            SettingsSectionLabel("Revocation history")
            SettingsCard {
                revokedDevices.forEachIndexed { index, device ->
                    if (index > 0) SettingsDivider()
                    Column {
                        SettingsInfoRow(
                            label = device.deviceName.ifBlank { "NEXA Device" },
                            value = "Revoked"
                        )
                        Text(
                            text = buildString {
                                append(device.deviceId)
                                val revokedAt = formatTimestamp(device.revokedAt)
                                if (revokedAt != null) append(" • Revoked ").append(revokedAt)
                                val reason = device.revokedReason
                                if (!reason.isNullOrBlank()) append(" • ").append(reason)
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                        )
                    }
                }
            }
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        SettingsActionRow(
            title = "Refresh",
            subtitle = "Query the relay for the current device list",
            onClick = { reload() }
        )
    }

    val revokeTarget = pendingRevoke
    if (revokeTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text("Revoke device?") },
            text = {
                Text(
                    "Revoke ${revokeTarget.deviceName.ifBlank { revokeTarget.deviceId }}? " +
                        "That device will be signed out and must re-authenticate."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRevoke = null
                    scope.launch {
                        authRepository.revokeDevice(revokeTarget.deviceId)
                            .onSuccess {
                                reload()
                                message = "Device revoked"
                            }
                            .onFailure {
                                message = "Revoke failed: ${it.localizedMessage ?: "unknown error"}"
                            }
                    }
                }) { Text("Revoke", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRevoke = null }) { Text("Cancel") } }
        )
    }
}

/**
 * Only a security level actually probed from the Keystore is allowed to make a
 * hardware claim. A missing value is reported as unverified, never as TEE.
 */
private fun hardwareSecurityLabel(level: String?): String = when (level?.uppercase(Locale.US)) {
    "STRONGBOX" -> "StrongBox (hardware-isolated)"
    "TEE" -> "TEE (hardware-backed)"
    "SOFTWARE" -> "Software only"
    else -> "Not verified"
}

private fun formatTimestamp(raw: Long): String? {
    if (raw <= 0L) return null
    val millis = if (raw < 10_000_000_000L) raw * 1000L else raw
    return try {
        SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(millis))
    } catch (e: Exception) {
        null
    }
}
