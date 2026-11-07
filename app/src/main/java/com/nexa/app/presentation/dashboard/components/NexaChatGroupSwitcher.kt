package com.nexa.app.presentation.dashboard.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens

enum class HomeDashboardTab {
    CHATS,
    GROUPS
}

@Composable
fun NexaChatGroupSwitcher(
    selectedTab: HomeDashboardTab,
    onTabSelected: (HomeDashboardTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val pillShape = RoundedCornerShape(NexaGlassTokens.PillRadius)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(pillShape)
            .background(
                color = if (isDark) Color(0x66161D2B) else Color(0x33000000)
            )
            .border(
                width = 1.dp,
                color = if (isDark) Color(0x26FFFFFF) else Color(0x1F000000),
                shape = pillShape
            )
            .padding(4.dp)
    ) {
        val tabWidth = (maxWidth - 8.dp) / 2
        val animatedOffset by animateDpAsState(
            targetValue = if (selectedTab == HomeDashboardTab.CHATS) 0.dp else tabWidth,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            label = "tabIndicatorOffset"
        )

        // Animated Active Pill Background
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .shadow(elevation = 6.dp, shape = pillShape)
                .clip(pillShape)
                .background(
                    color = if (isDark) Color(0xFFFFFFFF) else Color(0xFF1E293B)
                )
        )

        // Tab Labels (Chats | Groups)
        Row(modifier = Modifier.fillMaxSize()) {
            val interactionSource = remember { MutableInteractionSource() }

            // Chats Tab
            val chatsTextColor by animateColorAsState(
                targetValue = if (selectedTab == HomeDashboardTab.CHATS) {
                    if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "chatsTextColor"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onTabSelected(HomeDashboardTab.CHATS) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_chats),
                        contentDescription = null,
                        tint = chatsTextColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Chats",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selectedTab == HomeDashboardTab.CHATS) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp
                        ),
                        color = chatsTextColor
                    )
                }
            }

            // Groups Tab
            val groupsTextColor by animateColorAsState(
                targetValue = if (selectedTab == HomeDashboardTab.GROUPS) {
                    if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "groupsTextColor"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onTabSelected(HomeDashboardTab.GROUPS) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nexa_groups),
                        contentDescription = null,
                        tint = groupsTextColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Groups",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selectedTab == HomeDashboardTab.GROUPS) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp
                        ),
                        color = groupsTextColor
                    )
                }
            }
        }
    }
}
