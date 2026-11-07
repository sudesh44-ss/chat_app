package com.nexa.app.presentation.settings.account

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Change Phone Number Screen.
 *
 * Implements:
 * 1. Current masked phone number display.
 * 2. Country code validated new phone entry.
 * 3. Two-phase OTP request & server-side verification.
 * 4. Account synchronization without invalidating cryptographic keypairs.
 */
@Composable
fun ChangePhoneNumberScreen(
    authRepository: AuthRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val profileState by authRepository.getActiveProfile().collectAsState(initial = null)
    val currentPhone = profileState?.phoneNumber ?: ""
    val maskedCurrentPhone = remember(currentPhone) {
        if (currentPhone.length > 6) {
            val start = currentPhone.take(4)
            val end = currentPhone.takeLast(2)
            "$start •••• $end"
        } else {
            currentPhone
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var newPhoneInput by remember { mutableStateOf("") }
    var otpInput by remember { mutableStateOf("") }
    var isOtpSent by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var resendCountdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        topBar = {
            SettingsHeader(
                title = "Change Phone Number",
                onBack = onNavigateBack
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Explanatory Card
            NexaGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA),
                elevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Current Linked Number",
                        fontSize = 12.sp,
                        color = secondaryText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = maskedCurrentPhone,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Changing your phone number updates your discovery address on the network. Your cryptographic identity, private keys, and message history will remain completely preserved.",
                        fontSize = 13.sp,
                        color = secondaryText,
                        lineHeight = 18.sp
                    )
                }
            }

            if (!isOtpSent) {
                // Phase 1: Enter New Phone Number
                Text(
                    text = "Enter New Phone Number",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )

                OutlinedTextField(
                    value = newPhoneInput,
                    onValueChange = { newPhoneInput = it },
                    label = { Text("E.164 Format (e.g. +919876543210)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                Button(
                    onClick = {
                        val trimmed = newPhoneInput.trim()
                        if (trimmed.length >= 10 && (trimmed.startsWith("+") || trimmed.all { it.isDigit() })) {
                            isLoading = true
                            coroutineScope.launch {
                                val result = authRepository.requestChangePhoneOtp(trimmed)
                                isLoading = false
                                if (result.isSuccess) {
                                    isOtpSent = true
                                    resendCountdown = 60
                                    snackbarHostState.showSnackbar("Verification code dispatched to $trimmed")
                                } else {
                                    snackbarHostState.showSnackbar("Failed to dispatch code: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Please enter a valid international phone number with country code")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    enabled = !isLoading && newPhoneInput.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Send Verification Code",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                // Phase 2: Enter Verification Code
                Text(
                    text = "Enter 6-Digit Verification Code",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )

                Text(
                    text = "A verification code was sent to ${newPhoneInput.trim()}",
                    fontSize = 13.sp,
                    color = secondaryText
                )

                OutlinedTextField(
                    value = otpInput,
                    onValueChange = { if (it.length <= 6) otpInput = it },
                    label = { Text("6-Digit Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )

                Button(
                    onClick = {
                        if (otpInput.length == 6) {
                            isLoading = true
                            coroutineScope.launch {
                                val result = authRepository.verifyChangePhoneOtp(newPhoneInput.trim(), otpInput.trim())
                                isLoading = false
                                if (result.isSuccess) {
                                    snackbarHostState.showSnackbar("Phone number successfully updated!")
                                    delay(800)
                                    onNavigateBack()
                                } else {
                                    snackbarHostState.showSnackbar("Verification failed: ${result.exceptionOrNull()?.message ?: "Incorrect code"}")
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    enabled = !isLoading && otpInput.length == 6
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Verify & Update Number",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { isOtpSent = false }
                    ) {
                        Text("Edit Number")
                    }

                    TextButton(
                        onClick = {
                            if (resendCountdown == 0) {
                                coroutineScope.launch {
                                    val result = authRepository.requestChangePhoneOtp(newPhoneInput.trim())
                                    if (result.isSuccess) {
                                        resendCountdown = 60
                                        snackbarHostState.showSnackbar("Verification code resent")
                                    }
                                }
                            }
                        },
                        enabled = resendCountdown == 0
                    ) {
                        Text(if (resendCountdown > 0) "Resend in ${resendCountdown}s" else "Resend Code")
                    }
                }
            }
        }
    }
}
