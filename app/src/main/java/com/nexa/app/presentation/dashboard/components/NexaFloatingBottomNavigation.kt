package com.nexa.app.presentation.dashboard.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexa.app.R
import com.nexa.app.core.theme.NexaGlassTokens
import com.nexa.app.presentation.common.glass.NexaGlassSurface

enum class BottomNavDestination {
    HOME,
    PROFILE
}

@Composable
fun NexaFloatingBottomNavigation(
    currentDestination: BottomNavDestination = BottomNavDestination.HOME,
    isPlusMenuOpen: Boolean = false,
    showPlusButton: Boolean = true,
    onNavigateHome: () -> Unit,
    onNavigateProfile: () -> Unit,
    onTogglePlusMenu: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = 16.dp,
                end = 16.dp,
                bottom = NexaGlassTokens.FloatingControlsBottomMargin
            ),
        contentAlignment = Alignment.Center
    ) {
        // Combined horizontal group centered as one unit
        Row(
            modifier = Modifier.wrapContentSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Floating Navigation Capsule (Home & Profile)
            NexaGlassSurface(
                modifier = Modifier
                    .width(136.dp)
                    .height(NexaGlassTokens.FloatingCapsuleHeight),
                shape = RoundedCornerShape(NexaGlassTokens.FloatingCapsuleRadius),
                elevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val interactionSource = remember { MutableInteractionSource() }

                    // Home Item
                    val isHome = currentDestination == BottomNavDestination.HOME
                    val homeBgColor by animateColorAsState(
                        targetValue = if (isHome) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
                        animationSpec = tween(250),
                        label = "homeBg"
                    )
                    val homeIconTint by animateColorAsState(
                        targetValue = if (isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        animationSpec = tween(250),
                        label = "homeTint"
                    )

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(homeBgColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onNavigateHome
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_home),
                            contentDescription = "Home",
                            tint = homeIconTint,
                            modifier = Modifier.size(NexaGlassTokens.FloatingCapsuleIconSize)
                        )
                    }

                    // Profile Item
                    val isProfile = currentDestination == BottomNavDestination.PROFILE
                    val profileBgColor by animateColorAsState(
                        targetValue = if (isProfile) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
                        animationSpec = tween(250),
                        label = "profileBg"
                    )
                    val profileIconTint by animateColorAsState(
                        targetValue = if (isProfile) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        animationSpec = tween(250),
                        label = "profileTint"
                    )

                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(profileBgColor)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = onNavigateProfile
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_nexa_profile),
                            contentDescription = "Profile",
                            tint = profileIconTint,
                            modifier = Modifier.size(NexaGlassTokens.FloatingCapsuleIconSize)
                        )
                    }
                }
            }

            // 2. Circular Plus Button (Omitted when showPlusButton is false, e.g. on Settings)
            if (showPlusButton) {
                NexaGlassSurface(
                    modifier = Modifier
                        .size(NexaGlassTokens.FloatingPlusSize)
                        .clickable(onClick = onTogglePlusMenu),
                    shape = CircleShape,
                    surfaceColor = MaterialTheme.colorScheme.primary,
                    borderColor = Color.White.copy(alpha = 0.35f),
                    elevation = 14.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Crossfade(
                            targetState = isPlusMenuOpen,
                            animationSpec = tween(220),
                            label = "plusToCloseTransition"
                        ) { isOpen ->
                            Icon(
                                painter = painterResource(id = if (isOpen) R.drawable.ic_nexa_close else R.drawable.ic_nexa_plus),
                                contentDescription = if (isOpen) "Close Menu" else "Quick Actions",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
