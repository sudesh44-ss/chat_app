package com.nexa.app.presentation.profile.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.presentation.common.glass.NexaGlassSurface

/**
 * Information Card displaying Phone Number and About/Bio.
 * Liquid Glass styling with 20dp corner radius.
 */
@Composable
fun ContactInfoCard(
    phoneNumber: String?,
    aboutText: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()

    val surfaceColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f)
    val borderColor = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.07f)
    val primaryTextColor = if (isDark) Color.White else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xFFA0A7B5) else Color(0xFF555B66)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.05f)

    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        surfaceColor = surfaceColor,
        borderColor = borderColor,
        elevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 1. Phone Number Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val num = phoneNumber ?: "Not available"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phone Number", num))
                        Toast.makeText(context, "Copied phone number", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_phone_call),
                    contentDescription = "Phone",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Phone Number",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (!phoneNumber.isNullOrBlank()) phoneNumber else "Not shared by contact",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp
                        ),
                        color = primaryTextColor
                    )
                }

                if (!phoneNumber.isNullOrBlank()) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = secondaryTextColor.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = dividerColor,
                thickness = 1.dp
            )

            // 2. About / Bio Section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nexa_info),
                    contentDescription = "About",
                    tint = secondaryTextColor,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                        color = secondaryTextColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (!aboutText.isNullOrBlank()) aboutText else "Hey there! I am using NEXA encrypted chat.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = 14.sp
                        ),
                        color = primaryTextColor
                    )
                }
            }
        }
    }
}
