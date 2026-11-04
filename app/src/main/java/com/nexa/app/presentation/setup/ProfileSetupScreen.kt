package com.nexa.app.presentation.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSetupScreen(
    viewModel: SetupViewModel,
    googleToken: String?,
    onBack: () -> Unit,
    onProceedToOtp: (name: String, phone: String, age: Int?, gender: String?, about: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(googleToken) {
        viewModel.onEvent(ProfileSetupUiEvent.SetGoogleToken(googleToken))
    }

    LaunchedEffect(uiState.isOtpTriggered) {
        if (uiState.isOtpTriggered) {
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
                placeholder = { Text("Alice Vance") },
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

            // Phone Number (Mandatory)
            OutlinedTextField(
                value = uiState.phone,
                onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnPhoneChanged(it)) },
                label = { Text("Phone Number *") },
                placeholder = { Text("+1XXXXXXXXXX or +91XXXXXXXXXX") },
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
                    label = { Text("Age (Optional)") },
                    placeholder = { Text("25") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = uiState.gender,
                    onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnGenderChanged(it)) },
                    label = { Text("Gender (Optional)") },
                    placeholder = { Text("Non-binary / Other") },
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
                listOf("Female", "Male", "Other").forEach { option ->
                    FilterChip(
                        selected = uiState.gender.equals(option, ignoreCase = true),
                        onClick = {
                            val newGender = if (uiState.gender.equals(option, ignoreCase = true)) "" else option
                            viewModel.onEvent(ProfileSetupUiEvent.OnGenderChanged(newGender))
                        },
                        label = { Text(option) }
                    )
                }
            }

            // About / Bio (Optional)
            OutlinedTextField(
                value = uiState.bio,
                onValueChange = { viewModel.onEvent(ProfileSetupUiEvent.OnBioChanged(it)) },
                label = { Text("About / Bio (Optional)") },
                placeholder = { Text("Decentralized enthusiast. Offline mesh explorer.") },
                minLines = 3,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // CTA Button
            Button(
                onClick = { viewModel.onEvent(ProfileSetupUiEvent.RequestOtp) },
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
                    Text(
                        text = "Get Verification Code",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
