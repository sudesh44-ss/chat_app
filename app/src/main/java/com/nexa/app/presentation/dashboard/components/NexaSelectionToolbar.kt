package com.nexa.app.presentation.dashboard.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.presentation.common.glass.NexaGlassSurface

@Composable
fun NexaSelectionToolbar(
    selectedCount: Int,
    onCloseSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBulkPin: () -> Unit,
    onBulkMute: () -> Unit,
    onBulkArchive: () -> Unit,
    onBulkDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    NexaGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(NexaGlassTokens.SmallCardRadius),
        elevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCloseSelection) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_close),
                        contentDescription = "Close Selection",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "$selectedCount selected",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onSelectAll) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_select),
                        contentDescription = "Select All",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onBulkPin) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_pin),
                        contentDescription = "Pin Selected",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onBulkMute) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_mute),
                        contentDescription = "Mute Selected",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onBulkArchive) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_archive),
                        contentDescription = "Archive Selected",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onBulkDelete) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_delete),
                        contentDescription = "Delete Selected",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}
