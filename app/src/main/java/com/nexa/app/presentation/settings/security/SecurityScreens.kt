package com.nexa.app.presentation.settings.security

import android.app.Activity
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.security.AppLockPinHasher
import com.nexa.app.core.security.AppLockSecurityPolicy
import com.nexa.app.core.security.AutoLockTimeout
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.data.remote.dto.DeviceInfoDto
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.settings.components.SettingsActionRow
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsInfoRow
import com.nexa.app.presentation.settings.components.SettingsNavRow
import com.nexa.app.presentation.settings.components.SettingsRadioRow
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
    val activity = context as? Activity
    val biometricsAvailable = remember(context) { supportsBiometrics(context) }
    var enabled by remember { mutableStateOf(secureStorage.isAppLockEnabled()) }
    var biometricEnabled by remember { mutableStateOf(secureStorage.isBiometricUnlockEnabled()) }
    var autoLockTimeout by remember { mutableStateOf(secureStorage.getAutoLockTimeout()) }
    var lockOnLeave by remember { mutableStateOf(secureStorage.isLockOnLeaveEnabled()) }
    var hideAppPreview by remember { mutableStateOf(secureStorage.isHideAppPreviewEnabled()) }
    var blockScreenshots by remember { mutableStateOf(secureStorage.isBlockScreenshotsEnabled()) }

    var showPinSetupFlow by remember { mutableStateOf(false) }
    var showChangePinFlow by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var showAutoLockDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun syncWindowSecurity() {
        AppLockSecurityPolicy.applyWindowSecurity(
            window = activity?.window,
            isAppLockEnabled = enabled,
            blockScreenshots = blockScreenshots,
            hideAppPreview = hideAppPreview,
            isForeground = true
        )
    }

    SettingsDetailPage(title = "App Lock", onBack = onNavigateBack) {
        // Section: LOCK
        SettingsSectionLabel("Lock")
        SettingsCard {
            SettingsSwitchRow(
                title = "App Lock",
                subtitle = "Require authentication to open NEXA",
                checked = enabled,
                onCheckedChange = { wantsEnabled ->
                    if (wantsEnabled) {
                        showPinSetupFlow = true
                    } else {
                        showDisableDialog = true
                    }
                }
            )
        }

        if (enabled) {
            // Section: AUTHENTICATION
            SettingsSectionLabel("Authentication")
            SettingsCard {
                SettingsSwitchRow(
                    title = "Unlock with Biometrics",
                    subtitle = if (biometricsAvailable) {
                        "Use fingerprint or supported biometric authentication"
                    } else {
                        "Not available on this device"
                    },
                    checked = biometricEnabled,
                    enabled = biometricsAvailable,
                    onCheckedChange = { wantsBiometric ->
                        biometricEnabled = wantsBiometric
                        secureStorage.setBiometricUnlockEnabled(wantsBiometric)
                    }
                )
            }

            // Section: LOCK BEHAVIOR
            SettingsSectionLabel("Lock Behavior")
            SettingsCard {
                SettingsNavRow(
                    title = "Auto-Lock",
                    subtitle = "Choose when NEXA locks after you leave the app",
                    value = autoLockTimeout.displayName,
                    onClick = { showAutoLockDialog = true }
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Lock when leaving NEXA",
                    subtitle = "Require authentication when leaving the app",
                    checked = lockOnLeave,
                    onCheckedChange = { wantsLockOnLeave ->
                        lockOnLeave = wantsLockOnLeave
                        secureStorage.setLockOnLeaveEnabled(wantsLockOnLeave)
                    }
                )
            }

            // Section: PIN
            SettingsSectionLabel("PIN")
            SettingsCard {
                SettingsActionRow(
                    title = "Change PIN",
                    subtitle = "Update your App Lock PIN",
                    onClick = { showChangePinFlow = true }
                )
            }

            // Section: PRIVACY
            SettingsSectionLabel("Privacy")
            SettingsCard {
                SettingsSwitchRow(
                    title = "Hide App Preview",
                    subtitle = "Hide NEXA content in Recent Apps",
                    checked = hideAppPreview,
                    onCheckedChange = { wantsHide ->
                        hideAppPreview = wantsHide
                        secureStorage.setHideAppPreviewEnabled(wantsHide)
                        syncWindowSecurity()
                    }
                )
                SettingsDivider()
                SettingsSwitchRow(
                    title = "Block Screenshots",
                    subtitle = "Restrict screenshots and screen recordings",
                    checked = blockScreenshots,
                    onCheckedChange = { wantsBlock ->
                        blockScreenshots = wantsBlock
                        secureStorage.setBlockScreenshotsEnabled(wantsBlock)
                        syncWindowSecurity()
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
    }

    if (showPinSetupFlow) {
        StepByStepPinSetupDialog(
            onDismiss = { showPinSetupFlow = false },
            onSuccess = { pin ->
                try {
                    secureStorage.setAppLockPin(pin)
                    enabled = true
                    showPinSetupFlow = false
                    error = null
                    info = "App Lock enabled"
                    syncWindowSecurity()
                } catch (e: Exception) {
                    error = "Failed to save PIN: ${e.localizedMessage ?: "storage error"}"
                }
            }
        )
    }

    if (showChangePinFlow) {
        ChangePinDialog(
            secureStorage = secureStorage,
            onDismiss = { showChangePinFlow = false },
            onSuccess = {
                showChangePinFlow = false
                error = null
                info = "App Lock PIN updated"
            }
        )
    }

    if (showAutoLockDialog) {
        AutoLockSelectionDialog(
            currentSelection = autoLockTimeout,
            onDismiss = { showAutoLockDialog = false },
            onSelect = { option ->
                autoLockTimeout = option
                secureStorage.setAutoLockTimeout(option)
                showAutoLockDialog = false
            }
        )
    }

    if (showDisableDialog) {
        DisableAppLockDialog(
            onDismiss = { showDisableDialog = false },
            onVerify = { pin ->
                if (secureStorage.verifyAppLockPin(pin)) {
                    secureStorage.clearAppLock()
                    enabled = false
                    biometricEnabled = false
                    showDisableDialog = false
                    error = null
                    info = "App Lock turned off"
                    syncWindowSecurity()
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
    val hasHardware = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT) ||
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FACE) ||
        pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_IRIS)
    if (!hasHardware) return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val bm = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        if (bm != null) {
            val res = bm.canAuthenticate()
            return res == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS ||
                res == android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        }
    }
    return true
}

@Composable
private fun StepByStepPinSetupDialog(
    onDismiss: () -> Unit,
    onSuccess: (String) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (step == 1) "Create PIN" else "Confirm PIN",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (step == 1) "Set a 4–8 digit PIN to protect NEXA."
                    else "Enter your PIN again to confirm.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (step == 1) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value ->
                            pin = value.filter { it.isDigit() }.take(8)
                            error = null
                        },
                        label = { Text("New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                } else {
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { value ->
                            confirmPin = value.filter { it.isDigit() }.take(8)
                            error = null
                        },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                TextButton(
                    enabled = AppLockPinHasher.isValidPin(pin),
                    onClick = {
                        if (AppLockPinHasher.isValidPin(pin)) {
                            step = 2
                            confirmPin = ""
                            error = null
                        } else {
                            error = "PIN must be 4–8 digits"
                        }
                    }
                ) {
                    Text("Continue")
                }
            } else {
                TextButton(
                    enabled = confirmPin.isNotEmpty(),
                    onClick = {
                        if (confirmPin != pin) {
                            error = "PINs do not match"
                            confirmPin = ""
                        } else {
                            onSuccess(pin)
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            if (step == 2) {
                TextButton(onClick = {
                    step = 1
                    confirmPin = ""
                    error = null
                }) {
                    Text("Back")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun ChangePinDialog(
    secureStorage: SecureStorage,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmNewPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (step) {
                    1 -> "Verify Current PIN"
                    2 -> "Enter New PIN"
                    else -> "Confirm New PIN"
                },
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = when (step) {
                        1 -> "Enter your current PIN to continue."
                        2 -> "Choose a new 4–8 digit PIN."
                        else -> "Enter your new PIN again to confirm."
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (step) {
                    1 -> OutlinedTextField(
                        value = currentPin,
                        onValueChange = { value ->
                            currentPin = value.filter { it.isDigit() }.take(8)
                            error = null
                        },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    2 -> OutlinedTextField(
                        value = newPin,
                        onValueChange = { value ->
                            newPin = value.filter { it.isDigit() }.take(8)
                            error = null
                        },
                        label = { Text("New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    3 -> OutlinedTextField(
                        value = confirmNewPin,
                        onValueChange = { value ->
                            confirmNewPin = value.filter { it.isDigit() }.take(8)
                            error = null
                        },
                        label = { Text("Confirm New PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
                if (error != null) {
                    Text(error!!, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (step) {
                1 -> TextButton(
                    enabled = currentPin.length >= 4,
                    onClick = {
                        if (secureStorage.verifyAppLockPin(currentPin)) {
                            step = 2
                            error = null
                        } else {
                            error = "Incorrect PIN"
                        }
                    }
                ) {
                    Text("Continue")
                }
                2 -> TextButton(
                    enabled = AppLockPinHasher.isValidPin(newPin),
                    onClick = {
                        if (AppLockPinHasher.isValidPin(newPin)) {
                            step = 3
                            confirmNewPin = ""
                            error = null
                        } else {
                            error = "PIN must be 4–8 digits"
                        }
                    }
                ) {
                    Text("Continue")
                }
                3 -> TextButton(
                    enabled = confirmNewPin.isNotEmpty(),
                    onClick = {
                        if (confirmNewPin != newPin) {
                            error = "PINs do not match"
                            confirmNewPin = ""
                        } else {
                            val updated = secureStorage.changeAppLockPin(currentPin, newPin)
                            if (updated) {
                                onSuccess()
                            } else {
                                error = "Failed to update PIN"
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            when (step) {
                1 -> TextButton(onClick = onDismiss) { Text("Cancel") }
                2 -> TextButton(onClick = { step = 1; error = null }) { Text("Back") }
                3 -> TextButton(onClick = { step = 2; error = null }) { Text("Back") }
            }
        }
    )
}

@Composable
private fun AutoLockSelectionDialog(
    currentSelection: AutoLockTimeout,
    onDismiss: () -> Unit,
    onSelect: (AutoLockTimeout) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auto-Lock", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AutoLockTimeout.entries.forEach { option ->
                    SettingsRadioRow(
                        title = option.displayName,
                        selected = (option == currentSelection),
                        onClick = {
                            onSelect(option)
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DisableAppLockDialog(
    onDismiss: () -> Unit,
    onVerify: (String) -> Boolean
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn off App Lock", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your current PIN to turn off App Lock.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value ->
                        pin = value.filter { it.isDigit() }.take(8)
                        error = null
                    },
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
            TextButton(
                enabled = pin.length >= 4,
                onClick = {
                    if (onVerify(pin)) {
                        Unit
                    } else {
                        error = "Incorrect PIN"
                    }
                }
            ) {
                Text("Turn Off", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
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
