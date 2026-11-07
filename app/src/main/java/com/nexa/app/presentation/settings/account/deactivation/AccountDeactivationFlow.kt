package com.nexa.app.presentation.settings.account.deactivation

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaWarningOrange
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import kotlinx.coroutines.launch

/**
 * Step 1: Initial Warning Dialog for Account Deactivation.
 */
@Composable
fun DeactivationWarningDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Deactivate Account?",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "Your account will be temporarily disabled. Your data will be preserved, and you can reactivate your account later.",
                fontSize = 14.sp,
                color = secondaryText,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = NexaWarningOrange)
            ) {
                Text("Deactivate Account", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Step 2: Final Confirmation Dialog for Account Deactivation.
 */
@Composable
fun DeactivationConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Confirm Deactivation",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Text(
                text = "Are you sure you want to deactivate your account?",
                fontSize = 14.sp,
                color = secondaryText
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = NexaWarningOrange)
            ) {
                Text("Yes, Deactivate", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Step 4 & 5: Dedicated Deactivated Account Screen with reversible Reactivation.
 */
@Composable
fun AccountDeactivatedScreen(
    authRepository: AuthRepository,
    onKeepDeactivated: () -> Unit,
    onReactivatedSuccessfully: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var isReactivating by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = backgroundColor,
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
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
                    surfaceColor = NexaWarningOrange.copy(alpha = 0.15f),
                    borderColor = NexaWarningOrange.copy(alpha = 0.35f),
                    elevation = 4.dp
                ) {
                    Box(
                        modifier = Modifier.size(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_help),
                            contentDescription = null,
                            tint = NexaWarningOrange,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Account Deactivated",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = primaryText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Your account is currently deactivated. Would you like to reactivate it?",
                fontSize = 14.sp,
                color = secondaryText,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Reactivate Action
            Button(
                onClick = {
                    isReactivating = true
                    coroutineScope.launch {
                        val result = authRepository.reactivateAccount()
                        isReactivating = false
                        if (result.isSuccess) {
                            snackbarHostState.showSnackbar("Account successfully reactivated!")
                            onReactivatedSuccessfully()
                        } else {
                            snackbarHostState.showSnackbar("Reactivation error: ${result.exceptionOrNull()?.message}")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                enabled = !isReactivating
            ) {
                if (isReactivating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = "Reactivate Account",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Keep Deactivated Action
            OutlinedButton(
                onClick = onKeepDeactivated,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(26.dp)
            ) {
                Text(
                    text = "Keep Deactivated",
                    fontSize = 15.sp,
                    color = secondaryText
                )
            }
        }
    }
}
