package com.nexa.app.presentation.radar

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
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

/**
 * VerificationRadarScreen implements Method 1 (Zero-Permission Client-Initiated Reverse SMS).
 *
 * Characteristics:
 * - Zero dangerous permissions (SEND_SMS, RECEIVE_SMS, READ_PHONE_STATE are NOT requested).
 * - Dispatches standard Android Intent.ACTION_SENDTO to the user's default SMS app.
 * - Displays active radar pulse while polling the inbound gateway for cryptographic confirmation.
 * - 100% compliant with Google Play Protect and Android 15 Restricted Settings (ECM).
 */
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

    // Initialize Zero-Permission Reverse SMS verification flow
    LaunchedEffect(nonce) {
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

    // Auto-navigate when inbound SMS is confirmed by gateway
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
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (uiState.status) {
                        RadarVerificationStatus.VERIFIED -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Verified",
                                tint = NexaVerifiedGreen,
                                modifier = Modifier.size(44.dp)
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
                            Text(
                                text = "Cryptographic device identity established. Redirecting...",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.EXPIRED -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Expired",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(44.dp)
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
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Verification Failed",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = uiState.errorMessage ?: "An unexpected error occurred during verification.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        RadarVerificationStatus.LISTENING_RADAR,
                        RadarVerificationStatus.AWAITING_SMS_DISPATCH,
                        RadarVerificationStatus.SENDING_SMS,
                        RadarVerificationStatus.DETECTING_SIM,
                        RadarVerificationStatus.WAITING_SIM_SELECTION,
                        RadarVerificationStatus.IDLE -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Surface(
                                    color = NexaVerifiedGreen,
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
                                text = "Pre-filled verification SMS opened in your default SMS app. Tap Send, then return here to complete verification.",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            // Gateway & Nonce Summary Box
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Inbound Gateway:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = uiState.inboundNumber,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Beacon Payload:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = uiState.smsPayload,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }

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
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Controls
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

                if (uiState.status == RadarVerificationStatus.LISTENING_RADAR ||
                    uiState.status == RadarVerificationStatus.AWAITING_SMS_DISPATCH ||
                    uiState.status == RadarVerificationStatus.IDLE
                ) {
                    Button(
                        onClick = { viewModel.openNativeSmsApp(context) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open SMS App Again")
                    }

                    Text(
                        text = "Zero permissions required. Tap Send in your default SMS app, then return here.",
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
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

            if (status != RadarVerificationStatus.VERIFIED &&
                status != RadarVerificationStatus.EXPIRED &&
                status != RadarVerificationStatus.ERROR
            ) {
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

        // Central Shield / Status Badge
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
