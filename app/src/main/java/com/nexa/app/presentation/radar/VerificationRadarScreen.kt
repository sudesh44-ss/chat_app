package com.nexa.app.presentation.radar

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerificationRadarScreen(
    viewModel: VerificationRadarViewModel,
    nonce: String,
    inboundNumber: String,
    smsPayload: String,
    phone: String,
    name: String,
    avatarUrl: String?,
    age: Int?,
    gender: String?,
    bio: String?,
    expiresInSeconds: Int = 90,
    isReverify: Boolean = false,
    onBack: () -> Unit,
    onVerificationSuccess: (nxId: String, name: String, phone: String, avatarUrl: String?) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Permissions launcher for SMS dispatch
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.SEND_SMS] == true
        if (smsGranted) {
            viewModel.detectSimsAndSend(context)
        }
    }

    LaunchedEffect(nonce) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_PHONE_STATE
            )
        )
        viewModel.initialize(
            nonce = nonce,
            inboundNumber = inboundNumber,
            smsPayload = smsPayload,
            phone = phone,
            name = name,
            avatarUrl = avatarUrl,
            age = age,
            gender = gender,
            bio = bio,
            expiresInSeconds = expiresInSeconds,
            isReverify = isReverify,
            context = context
        )
    }

    LaunchedEffect(uiState.status, uiState.allocatedNxId) {
        if (uiState.status == RadarVerificationStatus.VERIFIED && !uiState.allocatedNxId.isNullOrBlank()) {
            onVerificationSuccess(
                uiState.allocatedNxId!!,
                uiState.name,
                uiState.phoneNumber,
                uiState.avatarUrl
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Cryptographic Verification",
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Radar Animation Section
            RadarPulseVisualizer(status = uiState.status)

            Spacer(modifier = Modifier.height(24.dp))

            // Status Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (uiState.status) {
                        RadarVerificationStatus.DETECTING_SIM -> {
                            Text(
                                text = "Detecting SIM Slot...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Identifying physical SIM for carrier-grade binding.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.WAITING_SIM_SELECTION -> {
                            Text(
                                text = "Select Verification SIM",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Dual SIMs detected. Choose the SIM card linked to ${uiState.phoneNumber}:",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiState.activeSims.forEach { sim ->
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.selectSimAndSend(context, sim) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.SimCard,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column {
                                                Text(
                                                    text = "${sim.displayName} (${sim.carrierName})",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                                Text(
                                                    text = "Slot ${sim.slotIndex + 1}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        RadarVerificationStatus.SENDING_SMS -> {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                text = "Dispatching Reverse SMS Beacon...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sending cryptographically signed verification nonce from your device (Developer cost: ₹0).",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.LISTENING_RADAR -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    color = NexaVerifiedGreen.copy(alpha = 0.2f),
                                    shape = CircleShape,
                                    modifier = Modifier.size(10.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Radar Active: Listening for Handshake",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = NexaVerifiedGreen
                                )
                            }

                            Text(
                                text = "SMS beacon sent to inbound gateway (${uiState.inboundNumber}). Awaiting cryptographic confirmation...",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Countdown clock
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = "Nonce expires in: ${formatSeconds(uiState.secondsRemaining)}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = if (uiState.secondsRemaining < 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        RadarVerificationStatus.VERIFIED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Carrier Identity Verified!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NexaVerifiedGreen
                            )
                            Text(
                                text = "Minted NX-ID: ${uiState.allocatedNxId}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = NexaBadgeMonospace
                            )
                        }

                        RadarVerificationStatus.EXPIRED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Expired",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Verification Window Expired",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = "The 90-second security window expired before the carrier SMS was matched. Tap Retry to generate a new cryptographic nonce.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Verification Failed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = uiState.errorMessage ?: "Unknown error occurred.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.IDLE -> {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Fallback & Action Controls
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (uiState.status == RadarVerificationStatus.EXPIRED || uiState.status == RadarVerificationStatus.ERROR) {
                    Button(
                        onClick = { viewModel.retryVerification(context) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry Verification")
                    }
                }

                if (uiState.status == RadarVerificationStatus.LISTENING_RADAR || uiState.status == RadarVerificationStatus.ERROR) {
                    // Manual SMS trigger fallback if background SMS is blocked
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:${uiState.inboundNumber}")
                                putExtra("sms_body", uiState.smsPayload)
                            }
                            context.startActivity(intent)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send via Default SMS App (Manual Fallback)", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun RadarPulseVisualizer(status: RadarVerificationStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarTransition")
    val pulse1 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse1"
    )
    val pulse2 by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, delayMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Pulse2"
    )

    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = this.center
            val maxRadius = size.minDimension / 2f

            if (status == RadarVerificationStatus.LISTENING_RADAR || status == RadarVerificationStatus.SENDING_SMS) {
                // Expanding wave 1
                val r1 = maxRadius * pulse1
                val alpha1 = (1f - pulse1).coerceIn(0f, 0.7f)
                drawCircle(
                    color = primaryColor.copy(alpha = alpha1),
                    radius = r1,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Expanding wave 2
                val r2 = maxRadius * pulse2
                val alpha2 = (1f - pulse2).coerceIn(0f, 0.7f)
                drawCircle(
                    color = primaryColor.copy(alpha = alpha2),
                    radius = r2,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Fixed concentric guide circles
            drawCircle(
                color = primaryColor.copy(alpha = 0.15f),
                radius = maxRadius * 0.45f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.10f),
                radius = maxRadius * 0.75f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Central Shield Badge
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    if (status == RadarVerificationStatus.VERIFIED)
                        NexaVerifiedGreen.copy(alpha = 0.2f)
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                )
                .border(
                    width = 2.dp,
                    color = if (status == RadarVerificationStatus.VERIFIED) NexaVerifiedGreen else MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (status) {
                    RadarVerificationStatus.VERIFIED -> Icons.Default.CheckCircle
                    RadarVerificationStatus.WAITING_SIM_SELECTION -> Icons.Default.SimCard
                    RadarVerificationStatus.EXPIRED, RadarVerificationStatus.ERROR -> Icons.Default.Warning
                    else -> Icons.Default.Shield
                },
                contentDescription = null,
                tint = if (status == RadarVerificationStatus.VERIFIED) NexaVerifiedGreen else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}

private fun formatSeconds(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
