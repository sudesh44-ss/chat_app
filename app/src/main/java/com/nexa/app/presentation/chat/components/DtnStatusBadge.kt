package com.nexa.app.presentation.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.core.theme.NexaBadgeMonospace
import com.nexa.app.core.theme.NexaVerifiedGreen
import com.nexa.app.core.theme.NexaWarningOrange

sealed interface DtnRoutingState {
    data object SearchingMeshPeers : DtnRoutingState
    data class HeldByMule(val remainingHops: Int) : DtnRoutingState
    data object RelayedViaCloudBridge : DtnRoutingState
}

@Composable
fun DtnStatusBadge(
    routingState: DtnRoutingState,
    modifier: Modifier = Modifier
) {
    when (routingState) {
        is DtnRoutingState.SearchingMeshPeers -> {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaBadgeMonospace.copy(alpha = 0.15f))
                    .border(1.dp, NexaBadgeMonospace.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .alpha(alpha)
                            .background(NexaBadgeMonospace, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = "Searching Mesh Peers...",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = NexaBadgeMonospace
                    )
                }
            }
        }

        is DtnRoutingState.HeldByMule -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaWarningOrange.copy(alpha = 0.15f))
                    .border(1.dp, NexaWarningOrange.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = "Mule Custody",
                        tint = NexaWarningOrange,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Held by Data Mule (Hops: ${routingState.remainingHops})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = NexaWarningOrange
                    )
                }
            }
        }

        is DtnRoutingState.RelayedViaCloudBridge -> {
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaVerifiedGreen.copy(alpha = 0.15f))
                    .border(1.dp, NexaVerifiedGreen.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = "Cloud Bridge Relayed",
                        tint = NexaVerifiedGreen,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Relayed via Cloud Bridge",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = NexaVerifiedGreen
                    )
                }
            }
        }
    }
}
