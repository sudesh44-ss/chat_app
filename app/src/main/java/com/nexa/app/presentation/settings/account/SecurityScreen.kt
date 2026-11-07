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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.domain.repository.AuthRepository
import com.nexa.app.presentation.common.glass.NexaGlassSurface
import com.nexa.app.presentation.settings.components.SettingsHeader

/**
 * Security & Encryption Screen.
 *
 * Displays:
 * 1. Account Identity Status (NX-ID).
 * 2. Device Identity Status (Hardware-backed Android Keystore).
 * 3. End-to-End Encryption Architecture (Double Ratchet + Curve25519).
 * 4. Active Session state & Cryptographic Fingerprint.
 */
@Composable
fun SecurityScreen(
    authRepository: AuthRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val backgroundColor = if (isDark) Color.Black else Color.White
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    val profileState by authRepository.getActiveProfile().collectAsState(initial = null)
    val nxId = profileState?.nxId ?: "NX-PENDING"
    val pubKey = profileState?.publicKeyHex ?: "Loading..."
    val fingerprint = remember(pubKey) {
        if (pubKey.length >= 16) {
            pubKey.take(8).uppercase() + "..." + pubKey.takeLast(8).uppercase()
        } else {
            "Verified Hardware Identity"
        }
    }

    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            SettingsHeader(
                title = "Security & Encryption",
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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overall Status Banner
            NexaGlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                surfaceColor = NexaVerifiedGreen.copy(alpha = if (isDark) 0.15f else 0.1f),
                borderColor = NexaVerifiedGreen.copy(alpha = 0.35f),
                elevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        NexaGlassSurface(
                            modifier = Modifier.size(44.dp),
                            shape = CircleShape,
                            surfaceColor = NexaVerifiedGreen.copy(alpha = 0.2f),
                            borderColor = NexaVerifiedGreen,
                            elevation = 0.dp
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_nexa_shield),
                                    contentDescription = "Security Active",
                                    tint = NexaVerifiedGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Universal E2EE Active",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Messages, media & audio are encrypted before leaving your hardware.",
                            fontSize = 12.sp,
                            color = secondaryText
                        )
                    }
                }
            }

            // Section 1: Cryptographic Identity
            SecurityDetailCard(
                title = "Account Cryptographic Identity",
                items = listOf(
                    Pair("NEXA ID", nxId),
                    Pair("Identity Algorithm", "Ed25519 (RFC 8032)"),
                    Pair("Public Key Fingerprint", fingerprint)
                )
            )

            // Section 2: Device Key Management
            SecurityDetailCard(
                title = "Hardware Keystore & Enclave",
                items = listOf(
                    Pair("Hardware Backing", "Android StrongBox / TEE"),
                    Pair("Session Ratchet", "Signal-Compatible Double Ratchet"),
                    Pair("Diffie-Hellman Exchange", "Curve25519 (X25519)"),
                    Pair("Private Keys Stored", "Local Hardware Keystore Only")
                )
            )

            // Section 3: Transport & Zero-Knowledge Verification
            SecurityDetailCard(
                title = "Zero-Knowledge Transport Guarantees",
                items = listOf(
                    Pair("Internet Relay", "Zero-Disk Ephemeral RAM Buffer (24h max)"),
                    Pair("Mesh Routing", "Store-Carry-Forward (Zero Cloud Infrastructure)"),
                    Pair("Data SMS", "Port 8901 Binary PDU Direct Carrier Airwaves"),
                    Pair("Server Key Visibility", "Zero Knowledge (Plaintext Never Exposed)")
                )
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SecurityDetailCard(
    title: String,
    items: List<Pair<String, String>>
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) Color(0xCC181818) else Color(0xEEF8F9FA)
    val primaryText = if (isDark) Color.White else Color(0xFF111111)
    val secondaryText = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = secondaryText,
            modifier = Modifier.padding(start = 4.dp)
        )

        NexaGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            surfaceColor = surfaceColor,
            elevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { (label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            color = secondaryText
                        )
                        Text(
                            text = value,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText,
                            fontFamily = if (value.contains("...") || value.startsWith("NX-")) FontFamily.Monospace else FontFamily.Default
                        )
                    }
                }
            }
        }
    }
}
