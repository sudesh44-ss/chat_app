package com.nexa.app.presentation.security

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nexa.app.R
import com.nexa.app.core.security.AppLockSecurityPolicy
import com.nexa.app.core.security.AutoLockTimeout
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.delay

/**
 * Tracks background state and monotonic timestamp across lifecycle events.
 */
object AppLockSessionTracker {
    var lastBackgroundTimestamp: Long = 0L
    var isBackgrounded: Boolean = false
}

/**
 * Full-screen local app-lock gate. Enforces Auto-Lock delay, background lifecycle locks,
 * and window preview/screenshot security flags.
 */
@Composable
fun AppLockGate(
    secureStorage: SecureStorage,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var enabled by remember { mutableStateOf(secureStorage.isAppLockEnabled()) }
    var unlocked by remember { mutableStateOf(!secureStorage.isAppLockEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    enabled = secureStorage.isAppLockEnabled()
                    if (!enabled) {
                        unlocked = true
                    } else if (unlocked && AppLockSessionTracker.isBackgrounded) {
                        val now = SystemClock.elapsedRealtime()
                        val shouldLock = AppLockSecurityPolicy.shouldLockOnReturn(
                            lastBackgroundTimestamp = AppLockSessionTracker.lastBackgroundTimestamp,
                            currentTimestamp = now,
                            lockOnLeave = secureStorage.isLockOnLeaveEnabled(),
                            timeoutSeconds = secureStorage.getAutoLockTimeout().seconds
                        )
                        if (shouldLock) {
                            unlocked = false
                        }
                    }
                    AppLockSessionTracker.isBackgrounded = false
                    AppLockSecurityPolicy.applyWindowSecurity(
                        window = activity?.window,
                        isAppLockEnabled = enabled,
                        blockScreenshots = secureStorage.isBlockScreenshotsEnabled(),
                        hideAppPreview = secureStorage.isHideAppPreviewEnabled(),
                        isForeground = true
                    )
                }
                Lifecycle.Event.ON_RESUME -> {
                    AppLockSecurityPolicy.applyWindowSecurity(
                        window = activity?.window,
                        isAppLockEnabled = enabled,
                        blockScreenshots = secureStorage.isBlockScreenshotsEnabled(),
                        hideAppPreview = secureStorage.isHideAppPreviewEnabled(),
                        isForeground = true
                    )
                }
                Lifecycle.Event.ON_PAUSE -> {
                    AppLockSecurityPolicy.applyWindowSecurity(
                        window = activity?.window,
                        isAppLockEnabled = enabled,
                        blockScreenshots = secureStorage.isBlockScreenshotsEnabled(),
                        hideAppPreview = secureStorage.isHideAppPreviewEnabled(),
                        isForeground = false
                    )
                }
                Lifecycle.Event.ON_STOP -> {
                    AppLockSessionTracker.isBackgrounded = true
                    AppLockSessionTracker.lastBackgroundTimestamp = SystemClock.elapsedRealtime()
                    if (secureStorage.isAppLockEnabled() &&
                        secureStorage.isLockOnLeaveEnabled() &&
                        secureStorage.getAutoLockTimeout() == AutoLockTimeout.IMMEDIATELY
                    ) {
                        unlocked = false
                    }
                    AppLockSecurityPolicy.applyWindowSecurity(
                        window = activity?.window,
                        isAppLockEnabled = enabled,
                        blockScreenshots = secureStorage.isBlockScreenshotsEnabled(),
                        hideAppPreview = secureStorage.isHideAppPreviewEnabled(),
                        isForeground = false
                    )
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!enabled || unlocked) {
        content()
    } else {
        AppLockUnlockScreen(
            secureStorage = secureStorage,
            onUnlocked = {
                secureStorage.resetAppLockFailedAttempts()
                unlocked = true
            }
        )
    }
}

@Composable
private fun AppLockUnlockScreen(
    secureStorage: SecureStorage,
    onUnlocked: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var lockoutSeconds by remember { mutableLongStateOf(secureStorage.getAppLockLockoutRemainingSeconds()) }
    val context = LocalContext.current
    val activity = context as? Activity

    // Ensure window security flag is set if configured
    LaunchedEffect(Unit) {
        AppLockSecurityPolicy.applyWindowSecurity(
            window = activity?.window,
            isAppLockEnabled = true,
            blockScreenshots = secureStorage.isBlockScreenshotsEnabled(),
            hideAppPreview = secureStorage.isHideAppPreviewEnabled(),
            isForeground = true
        )
    }

    // Lockout countdown timer
    LaunchedEffect(lockoutSeconds) {
        if (lockoutSeconds > 0) {
            delay(1000L)
            lockoutSeconds = secureStorage.getAppLockLockoutRemainingSeconds()
            if (lockoutSeconds == 0L) {
                error = null
            }
        }
    }

    val isLockedOut = lockoutSeconds > 0

    fun tryBiometric() {
        if (isLockedOut) return
        val act = activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!secureStorage.isBiometricUnlockEnabled()) return
        if (act.isFinishing || act.isDestroyed) return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt.Builder(act)
            .setTitle("Unlock NEXA")
            .setSubtitle("Confirm your identity to open the app")
            .setNegativeButton("Use PIN", executor) { _, _ -> }
            .build()

        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    secureStorage.resetAppLockFailedAttempts()
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED &&
                        errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                    ) {
                        error = errString?.toString() ?: "Biometric unlock unavailable"
                    }
                }
            }
        )
    }

    LaunchedEffect(isLockedOut) {
        if (!isLockedOut) {
            tryBiometric()
        }
    }

    fun submitPin(enteredPin: String) {
        if (isLockedOut) return
        if (secureStorage.verifyAppLockPin(enteredPin)) {
            secureStorage.resetAppLockFailedAttempts()
            onUnlocked()
        } else {
            pin = ""
            val attempts = secureStorage.incrementAppLockFailedAttempts()
            val rem = secureStorage.getAppLockLockoutRemainingSeconds()
            if (rem > 0) {
                lockoutSeconds = rem
                error = "Too many attempts. Locked for ${rem}s."
            } else {
                val remaining = (AppLockSecurityPolicy.MAX_FAILED_ATTEMPTS - (attempts % AppLockSecurityPolicy.MAX_FAILED_ATTEMPTS))
                error = "Incorrect PIN. $remaining attempt(s) remaining."
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    val background = if (isDark) Color.Black else Color.White
    val primary = if (isDark) Color.White else Color(0xFF111111)
    val secondary = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NexaGlassSurface(
                modifier = Modifier.size(74.dp),
                shape = CircleShape,
                surfaceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                elevation = 0.dp
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_security_lock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "NEXA is Locked",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = primary
            )
            Text(
                text = if (isLockedOut) "Authentication temporarily blocked"
                else "Enter your app-lock PIN to continue",
                fontSize = 13.sp,
                color = secondary,
                textAlign = TextAlign.Center
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 10.dp)
            ) {
                repeat(pin.length.coerceAtMost(8)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
                repeat((4 - pin.length).coerceAtLeast(0)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(secondary.copy(alpha = 0.3f), CircleShape)
                    )
                }
            }

            if (error != null) {
                Text(
                    text = error!!,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else if (isLockedOut) {
                Text(
                    text = "Too many attempts. Locked for ${lockoutSeconds}s.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // Numeric keypad
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "bio", "0", "del")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        row.forEach { key ->
                            Box(modifier = Modifier.weight(1f)) {
                                when (key) {
                                    "bio" -> KeypadButton(
                                        label = "",
                                        iconRes = R.drawable.ic_nexa_shield,
                                        enabled = !isLockedOut && secureStorage.isBiometricUnlockEnabled() &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                                        onClick = { tryBiometric() }
                                    )
                                    "del" -> KeypadButton(
                                        label = "",
                                        iconRes = R.drawable.ic_nexa_close,
                                        enabled = !isLockedOut,
                                        onClick = {
                                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            error = null
                                        }
                                    )
                                    else -> KeypadButton(
                                        label = key,
                                        enabled = !isLockedOut,
                                        onClick = {
                                            if (pin.length < 8) {
                                                val nextPin = pin + key
                                                pin = nextPin
                                                error = null
                                                if (nextPin.length >= 4 && secureStorage.verifyAppLockPin(nextPin)) {
                                                    secureStorage.resetAppLockFailedAttempts()
                                                    onUnlocked()
                                                } else if (nextPin.length == 8) {
                                                    submitPin(nextPin)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (pin.length >= 4 && !isLockedOut) {
                TextButton(onClick = { submitPin(pin) }) {
                    Text("Unlock", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    iconRes: Int? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val surface = if (isDark) Color(0xCC1C1C1C) else Color(0xEEF3F4F6)
    val content = if (isDark) Color.White else Color(0xFF111111)

    NexaGlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        surfaceColor = surface,
        elevation = if (enabled) 2.dp else 0.dp
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = content.copy(alpha = if (enabled) 1f else 0.3f),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                Text(
                    text = label,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = content.copy(alpha = if (enabled) 1f else 0.3f)
                )
            }
        }
    }
}
