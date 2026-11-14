package com.nexa.app.presentation.settings.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.BuildConfig
import com.nexa.app.presentation.settings.components.SettingsActionRow
import com.nexa.app.presentation.settings.components.SettingsCard
import com.nexa.app.presentation.settings.components.SettingsDetailPage
import com.nexa.app.presentation.settings.components.SettingsDivider
import com.nexa.app.presentation.settings.components.SettingsInfoBanner
import com.nexa.app.presentation.settings.components.SettingsInfoRow
import com.nexa.app.presentation.settings.components.SettingsSectionLabel

private const val SUPPORT_EMAIL = "support@nexa.network"

@Composable
fun HelpSupportScreen(
    onOpenReportBug: () -> Unit,
    onOpenSourceCode: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    SettingsDetailPage(title = "Help & Support", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "NEXA is a privacy-first, zero-knowledge messenger. Support requests are sent " +
                "through your own email app; nothing is uploaded automatically."
        )

        SettingsSectionLabel("Common Questions")
        SettingsCard {
            HelpFaq(
                question = "How are messages encrypted?",
                answer = "Each conversation uses the Double Ratchet with X25519 and Ed25519 keys. " +
                    "Private keys are sealed by the Android Keystore and never leave the device."
            )
            SettingsDivider()
            HelpFaq(
                question = "What does the transport badge mean?",
                answer = "Internet means the encrypted WebSocket relay, Mesh means Nearby / DTN " +
                    "store-and-forward, and SMS means binary Data SMS on port 8901."
            )
            SettingsDivider()
            HelpFaq(
                question = "Does NEXA read my messages?",
                answer = "No. Message content is encrypted on-device. The relay only sees routing " +
                    "metadata required to deliver sealed packets."
            )
        }

        SettingsSectionLabel("Contact")
        SettingsCard {
            SettingsActionRow(
                title = "Email Support",
                subtitle = SUPPORT_EMAIL,
                onClick = {
                    val ok = openEmail(
                        context,
                        "NEXA support request",
                        "\n\n---\nApp: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                            "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE}"
                    )
                    message = if (ok) "Email app opened" else "No email app is available on this device"
                }
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Report a Bug",
                subtitle = "Describe the issue and attach logs yourself if needed",
                onClick = onOpenReportBug
            )
            SettingsDivider()
            SettingsActionRow(
                title = "Request Source Code",
                subtitle = "Ask about source availability for this build",
                onClick = onOpenSourceCode
            )
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun HelpFaq(question: String, answer: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = question,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = answer,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ReportBugScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var summary by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsDetailPage(title = "Report a Bug", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "Bug reports are delivered through your email app. This build has no server-side " +
                "report endpoint, so NEXA will not pretend a submission succeeded."
        )

        SettingsSectionLabel("Report")
        SettingsCard {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it.take(120) },
                    label = { Text("Summary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(4000) },
                    label = { Text("What happened?") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        if (summary.isBlank() || details.isBlank()) {
                            message = "Please enter a summary and description"
                        } else {
                            val body = buildString {
                                appendLine(details)
                                appendLine()
                                appendLine("---")
                                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                            }
                            val ok = openEmail(context, "NEXA bug: $summary", body)
                            message = if (ok) "Email app opened with your report" else "No email app is available"
                        }
                    }
                ) { Text("Open email draft") }
            }
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun RequestSourceCodeScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }

    SettingsDetailPage(title = "Request Source Code", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "NEXA does not bundle the application source in this build, and no automated " +
                "source-delivery endpoint is configured. You can send a request through email."
        )

        SettingsSectionLabel("Request")
        SettingsCard {
            SettingsActionRow(
                title = "Email a Source Code Request",
                subtitle = SUPPORT_EMAIL,
                onClick = {
                    val ok = openEmail(
                        context,
                        "NEXA source code request",
                        "I would like to request source code for NEXA ${BuildConfig.VERSION_NAME} " +
                            "(${BuildConfig.VERSION_CODE}).\n\nPlease include the applicable terms " +
                            "and delivery method.\n"
                    )
                    message = if (ok) "Email app opened" else "No email app is available"
                }
            )
        }

        if (message != null) {
            Text(
                text = message!!,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
fun AboutScreen(onNavigateBack: () -> Unit) {
    SettingsDetailPage(title = "About", onBack = onNavigateBack) {
        SettingsInfoBanner(
            "NEXA Mobile ${BuildConfig.VERSION_NAME} — a privacy-first, zero-knowledge " +
                "decentralized messenger."
        )

        SettingsSectionLabel("Application")
        SettingsCard {
            SettingsInfoRow("Version", BuildConfig.VERSION_NAME)
            SettingsDivider()
            SettingsInfoRow("Build", BuildConfig.VERSION_CODE.toString())
            SettingsDivider()
            SettingsInfoRow("Package", BuildConfig.APPLICATION_ID)
            SettingsDivider()
            SettingsInfoRow("Platform", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            SettingsDivider()
            SettingsInfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }

        SettingsSectionLabel("Security Stack")
        SettingsCard {
            SettingsInfoRow("Transport", "Double Ratchet • X25519 • Ed25519")
            SettingsDivider()
            SettingsInfoRow("Key storage", "Android Keystore")
            SettingsDivider()
            SettingsInfoRow("Database", "SQLCipher 4")
            SettingsDivider()
            SettingsInfoRow("Backup", "Encrypted Google Drive AppData")
        }

        SettingsSectionLabel("Updates")
        SettingsCard {
            Text(
                text = "This build does not include an update-check channel. NEXA will never " +
                    "fabricate an update result. Install updates from the distribution source " +
                    "you originally used.",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }

        Text(
            text = "Your keys and data remain exclusively on your device.",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )
    }
}

private fun openEmail(context: Context, subject: String, body: String): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:$SUPPORT_EMAIL")
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    return try {
        context.startActivity(Intent.createChooser(intent, "Send email"))
        true
    } catch (e: Exception) {
        false
    }
}
