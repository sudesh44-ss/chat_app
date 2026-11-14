package com.nexa.app.presentation.security

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.nexa.app.core.security.SecureStorage
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Full-screen local app-lock gate. Re-locks when the activity is stopped so the
 * lock also applies to the Android recents preview on the next foreground.
 */
@Composable
fun AppLockGate(
    secureStorage: SecureStorage,
    content: @Composable () -> Unit
) {
    var enabled by remember { mutableStateOf(secureStorage.isAppLockEnabled()) }
    var unlocked by remember { mutableStateOf(!secureStorage.isAppLockEnabled()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Re-read the persisted flag so enabling/disabling App Lock in Settings
                // takes effect without a process restart.
                Lifecycle.Event.ON_START -> {
                    enabled = secureStorage.isAppLockEnabled()
                    if (!enabled) unlocked = true
                }
                Lifecycle.Event.ON_STOP -> {
                    if (secureStorage.isAppLockEnabled()) {
                        enabled = true
                        unlocked = false
                    }
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
            onUnlocked = { unlocked = true }
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
    var failedAttempts by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    fun tryBiometric() {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!secureStorage.isBiometricUnlockEnabled()) return
        if (activity.isFinishing || activity.isDestroyed) return

        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle("Unlock NEXA")
            .setSubtitle("Confirm your identity to open the app")
            .setNegativeButton("Use PIN", executor) { _, _ -> }
            .build()

        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
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

    LaunchedEffect(Unit) { tryBiometric() }

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
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
                text = "Enter your app-lock PIN to continue",
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
                                        enabled = secureStorage.isBiometricUnlockEnabled() &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
                                        onClick = { tryBiometric() }
                                    )
                                    "del" -> KeypadButton(
                                        label = "",
                                        iconRes = R.drawable.ic_nexa_close,
                                        onClick = {
                                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                            error = null
                                        }
                                    )
                                    else -> KeypadButton(
                                        label = key,
                                        onClick = {
                                            if (pin.length < 8) {
                                                pin += key
                                                error = null
                                                if (pin.length >= 4 && secureStorage.verifyAppLockPin(pin)) {
                                                    onUnlocked()
                                                } else if (pin.length == 8) {
                                                    failedAttempts++
                                                    pin = ""
                                                    error = if (failedAttempts >= 5) {
                                                        "Too many attempts. Try biometric unlock or restart the app."
                                                    } else {
                                                        "Incorrect PIN. ${5 - failedAttempts} attempt(s) remaining."
                                                    }
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
            if (pin.length >= 4) {
                androidx.compose.material3.TextButton(onClick = {
                    if (secureStorage.verifyAppLockPin(pin)) {
                        onUnlocked()
                    } else {
                        failedAttempts++
                        pin = ""
                        error = "Incorrect PIN"
                    }
                }) {
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
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
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
                    color = content
                )
            }
        }
    }
}
