package com.nexa.app.presentation.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.media.ProfilePhotoOptimizer
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.presentation.common.avatar.NexaAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: SetupViewModel,
    googleToken: String? = null,
    googleUid: String? = null,
    prefillName: String? = null,
    prefillAvatar: String? = null,
    email: String? = null,
    onBack: () -> Unit,
    onProceedToReverseVerify: (
        nonce: String,
        inboundNumber: String,
        smsPayload: String,
        expiresInSeconds: Int,
        name: String,
        phone: String,
        avatarUrl: String?,
        age: Int?,
        gender: String?,
        bio: String?
    ) -> Unit,
    onProceedToOtp: ((name: String, phone: String, age: Int?, gender: String?, about: String?) -> Unit)? = null,
    onProceedToBadge: ((nxId: String, name: String, phone: String, avatarUrl: String?) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val optimizedFile = ProfilePhotoOptimizer.optimizeProfilePhoto(context, uri)
                if (optimizedFile != null) {
                    viewModel.onEvent(ProfileSetupUiEvent.OnAvatarChanged(optimizedFile.absolutePath))
                } else {
                    snackbarHostState.showSnackbar("Unable to process selected photo")
                }
            }
        }
    }

    LaunchedEffect(googleToken, googleUid, prefillName, prefillAvatar, email) {
        viewModel.onEvent(
            ProfileSetupUiEvent.SetPrefillData(
                token = googleToken,
                uid = googleUid,
                name = prefillName,
                avatarUrl = prefillAvatar,
                email = email
            )
        )
    }

    LaunchedEffect(uiState.isAutoVerified, uiState.allocatedNxId) {
        if (uiState.isAutoVerified && !uiState.allocatedNxId.isNullOrBlank() && onProceedToBadge != null) {
            onProceedToBadge(
                uiState.allocatedNxId ?: "",
                uiState.name.trim(),
                uiState.phone.trim(),
                uiState.avatarUrl
            )
        }
    }

    LaunchedEffect(uiState.reverseVerifyPayload, uiState.isAutoVerified) {
        val payload = uiState.reverseVerifyPayload
        if (payload != null && !uiState.isAutoVerified) {
            onProceedToReverseVerify(
                payload.nonce,
                payload.inboundReceiverNumber,
                payload.smsPayload,
                payload.expiresInSeconds,
                uiState.name.trim(),
                uiState.phone.trim(),
                uiState.avatarUrl,
                uiState.age.toIntOrNull(),
                uiState.gender.trim().takeIf { it.isNotBlank() },
                uiState.bio.trim().takeIf { it.isNotBlank() }
            )
        }
    }

    LaunchedEffect(uiState.isOtpTriggered) {
        if (uiState.isOtpTriggered && uiState.reverseVerifyPayload == null && onProceedToOtp != null) {
            onProceedToOtp(
                uiState.name.trim(),
                uiState.phone.trim(),
                uiState.age.toIntOrNull(),
                uiState.gender.trim().takeIf { it.isNotBlank() },
                uiState.bio.trim().takeIf { it.isNotBlank() }
            )
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.onEvent(ProfileSetupUiEvent.ClearError)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Create Identity",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Profile Photo Hero with Camera Badge Picker
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                NexaAvatar(
                    nexaAvatarUrl = uiState.avatarUrl,
                    deviceContactPhotoUri = null,
                    displayName = uiState.name.ifBlank { "User" },
                    size = 92.dp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { photoPickerLauncher.launch("image/*") }
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                        .clickable { photoPickerLauncher.launch("image/*") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Select Profile Photo",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Google Profile Link indicator if available
            if (!uiState.googleToken.isNullOrBlank() || !uiState.email.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Verified Google Account",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = uiState.email ?: "Google Account Linked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Text(
                text = "Enter your details to generate your cryptographic NX-ID and local vault.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Full Name (Mandatory)
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnNameChanged(it)) },
                label = { Text("Full Name *") },
                placeholder = { Text("e.g. Rahul Sharma") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Badge,
                        contentDescription = null
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth()
            )

            // Phone Number (Mandatory, Domestic India / Domestic series)
            OutlinedTextField(
                value = uiState.phone,
                onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged(it)) },
                label = { Text("Mobile Phone Number *") },
                placeholder = { Text("+91 98765 43210") },
                supportingText = {
                    Text(
                        if (uiState.phone.isNotBlank() && !uiState.isPhoneValid)
                            "Enter a valid 10-digit mobile number starting with 6-9"
                        else
                            "Used for secure UPI-style reverse SMS verification (₹0 cost)"
                    )
                },
                isError = uiState.phone.isNotBlank() && !uiState.isPhoneValid,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )

            // Age & Gender (Optional)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = uiState.age,
                    onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnAgeChanged(it)) },
                    label = { Text("Age (13+)") },
                    placeholder = { Text("24") },
                    isError = !uiState.isAgeValid,
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = uiState.gender,
                    onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnGenderChanged(it)) },
                    label = { Text("Gender (Optional)") },
                    placeholder = { Text("Male / Female") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.3f)
                )
            }

            // Quick Gender Filter Chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                listOf("Male", "Female", "Other", "Prefer not to say").forEach { option ->
                    FilterChip(
                        selected = uiState.gender.equals(option, ignoreCase = true),
                        onClick = {
                            val newGender = if (uiState.gender.equals(option, ignoreCase = true)) "" else option
                            viewModel.onEvent(ProfileSetupUiEvent.OnGenderChanged(newGender))
                        },
                        label = { Text(option, fontSize = 12.sp) }
                    )
                }
            }

            // About / Bio (Optional, max 140 chars)
            OutlinedTextField(
                value = uiState.bio,
                onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnBioChanged(it)) },
                label = { Text("About / Bio (Max 140 chars)") },
                placeholder = { Text("Exploring offline mesh communications.") },
                supportingText = {
                    Text("${uiState.bio.length} / 140")
                },
                minLines = 2,
                maxLines = 3,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // CTA Button: Reverse SIM Verification
            Button(
                onClick = { viewModel.onEvent(ProfileSetupUiEvent.RequestReverseVerification) },
                enabled = uiState.isFormValid && !uiState.isLoading,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Verify Phone with SIM (₹0)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
