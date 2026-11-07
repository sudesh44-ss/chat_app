package com.nexa.app.presentation.settings.account.deletion

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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

enum class DeletionStep {
    WARNING_AND_IMPACT,
    ACKNOWLEDGEMENT,
    IDENTITY_VERIFICATION,
    PROCESSING,
    SUCCESS
}

/**
 * 7-Step Irreversible Permanent Account Deletion Coordinator.
 */
@Composable
fun AccountDeletionFlow(
    authRepository: AuthRepository,
    onNavigateBack: () -> Unit,
    onAccountDeletedDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val profileState by authRepository.getActiveProfile().collectAsState(initial = null)
    val phone = profileState?.phoneNumber ?: ""
    val maskedPhone = remember(phone) {
        if (phone.length > 6) {
            val start = phone.take(4)
            val end = phone.takeLast(2)
            "$start •••• $end"
        } else {
            phone
        }
    }

    var currentStep by remember { mutableStateOf(DeletionStep.WARNING_AND_IMPACT) }
    var isAcknowledged by remember { mutableStateOf(false) }
    var otpInput by remember { mutableStateOf("") }
    var showFinalConfirmDialog by remember { mutableStateOf(false) }
    var isVerifyingOtp by remember { mutableStateOf(false) }
    var resendCountdown by remember { mutableIntStateOf(60) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(resendCountdown) {
        if (resendCountdown > 0) {
            delay(1000)
            resendCountdown--
        }
    }

    // Step 5: Final Destructive Confirmation Dialog
    if (showFinalConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showFinalConfirmDialog = false },
            title = {
                Text(
                    text = "Are You Absolutely Sure?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = {
                Text(
                    text = "Your account is about to be permanently deleted. This is your final confirmation. All local sessions, device keys, and cryptographic associations will be destroyed immediately.",
                    fontSize = 14.sp,
                    color = secondaryText,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFinalConfirmDialog = false
                        currentStep = DeletionStep.PROCESSING
                        coroutineScope.launch {
                            delay(1200) // Brief graceful processing window
                            val result = authRepository.confirmPermanentDeletion()
                            if (result.isSuccess) {
                                currentStep = DeletionStep.SUCCESS
                            } else {
                                currentStep = DeletionStep.ACKNOWLEDGEMENT
                                snackbarHostState.showSnackbar("Deletion failed: ${result.exceptionOrNull()?.message}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        topBar = {
            if (currentStep != DeletionStep.PROCESSING && currentStep != DeletionStep.SUCCESS) {
                SettingsHeader(
                    title = "Delete Account",
                    onBack = {
                        if (currentStep == DeletionStep.WARNING_AND_IMPACT) {
                            onNavigateBack()
                        } else {
                            currentStep = DeletionStep.WARNING_AND_IMPACT
                        }
                    }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentStep) {
                // Step 1 & 2: Warning & Data Impact Review
                DeletionStep.WARNING_AND_IMPACT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Delete Account?",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )

                        Text(
                            text = "This will permanently delete your account and associated data. You may not be able to recover your account after deletion.",
                            fontSize = 14.sp,
                            color = secondaryText,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Data that will be permanently deleted:",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText
                        )

                        // 5 Categories from Section 10 Step 2
                        DataImpactItem(
                            number = 1,
                            title = "Profile & Account Information",
                            description = "Your display name, bio, phone number link, and cryptographic NX-ID registration will be deleted from the identity registry."
                        )

                        DataImpactItem(
                            number = 2,
                            title = "Contacts & Connections",
                            description = "All bilateral peer connections, pending requests, and cryptographic prekeys will be revoked and pruned."
                        )

                        DataImpactItem(
                            number = 3,
                            title = "Messages Stored on This Account",
                            description = "All local message history, media caches, and ephemeral queue buffers will be securely wiped from this device."
                        )

                        DataImpactItem(
                            number = 4,
                            title = "Groups & Memberships",
                            description = "You will be removed from all group chats. Groups you administer will transfer ownership or be dissolved."
                        )

                        DataImpactItem(
                            number = 5,
                            title = "Account Settings",
                            description = "All custom appearance, notification, and privacy preferences will be reset."
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { currentStep = DeletionStep.ACKNOWLEDGEMENT },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                text = "Continue to Deletion",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Step 3: Explicit Checkbox Acknowledgement
                DeletionStep.ACKNOWLEDGEMENT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Permanently Delete Account?",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Your account and associated data will be permanently deleted. This action cannot be undone.",
                            fontSize = 14.sp,
                            color = secondaryText,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        // Explicit Checkbox Requirement
                        NexaGlassSurface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            surfaceColor = if (isDark) Color(0xCC1E1E1E) else Color(0xEEF8F9FA),
                            elevation = 2.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAcknowledged = !isAcknowledged }
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isAcknowledged,
                                    onCheckedChange = { isAcknowledged = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.error
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "I understand that this action cannot be undone.",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryText
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    authRepository.requestDeletionOtp()
                                    resendCountdown = 60
                                    currentStep = DeletionStep.IDENTITY_VERIFICATION
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            enabled = isAcknowledged
                        ) {
                            Text(
                                text = "Proceed to Identity Verification",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                // Step 4: OTP Identity Verification
                DeletionStep.IDENTITY_VERIFICATION -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(68.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            NexaGlassSurface(
                                modifier = Modifier.size(68.dp),
                                shape = CircleShape,
                                surfaceColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                                elevation = 2.dp
                            ) {
                                Box(
                                    modifier = Modifier.size(68.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_nexa_shield),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Verify Your Identity",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "We sent a 6-digit confirmation code to $maskedPhone to confirm you own this account.",
                            fontSize = 13.sp,
                            color = secondaryText,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedTextField(
                            value = otpInput,
                            onValueChange = { if (it.length <= 6) otpInput = it },
                            label = { Text("6-Digit Code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(0.75f),
                            shape = RoundedCornerShape(16.dp)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                isVerifyingOtp = true
                                coroutineScope.launch {
                                    val result = authRepository.verifyDeletionOtp(otpInput.trim())
                                    isVerifyingOtp = false
                                    if (result.isSuccess) {
                                        showFinalConfirmDialog = true
                                    } else {
                                        snackbarHostState.showSnackbar("Verification code invalid or expired")
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(26.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            enabled = !isVerifyingOtp && otpInput.length == 6
                        ) {
                            if (isVerifyingOtp) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = "Verify Code",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = {
                                if (resendCountdown == 0) {
                                    coroutineScope.launch {
                                        authRepository.requestDeletionOtp()
                                        resendCountdown = 60
                                        snackbarHostState.showSnackbar("Verification code resent")
                                    }
                                }
                            },
                            enabled = resendCountdown == 0
                        ) {
                            Text(if (resendCountdown > 0) "Resend code in ${resendCountdown}s" else "Resend Code")
                        }
                    }
                }

                // Step 6: Secure Deletion Progress
                DeletionStep.PROCESSING -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(54.dp),
                            color = MaterialTheme.colorScheme.error,
                            strokeWidth = 3.dp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Deleting Your Account",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Please wait while we securely delete your account.",
                            fontSize = 14.sp,
                            color = secondaryText,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Step 7: Account Deleted Success
                DeletionStep.SUCCESS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier.size(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            NexaGlassSurface(
                                modifier = Modifier.size(80.dp),
                                shape = CircleShape,
                                surfaceColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                borderColor = MaterialTheme.colorScheme.error,
                                elevation = 4.dp
                            ) {
                                Box(
                                    modifier = Modifier.size(80.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_nexa_delete),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "Account Deleted",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Your NEXA account has been permanently deleted.",
                            fontSize = 14.sp,
                            color = secondaryText,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(36.dp))

                        Button(
                            onClick = onAccountDeletedDone,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(26.dp)
                        ) {
                            Text(
                                text = "Done",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataImpactItem(
    number: Int,
    title: String,
    description: String
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    NexaGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        surfaceColor = surfaceColor,
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                NexaGlassSurface(
                    modifier = Modifier.size(24.dp),
                    shape = CircleShape,
                    surfaceColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                    elevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier.size(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = number.toString(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primaryText
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = secondaryText,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
