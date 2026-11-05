package com.nexa.app.presentation.common.avatar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

sealed class ResolvedAvatar {
    data class NetworkOrLocalUri(val model: Any) : ResolvedAvatar()
    data class InitialAvatar(val char: Char, val backgroundColorHex: Long) : ResolvedAvatar()
}

/**
 * Resolves the 3-Tier Profile Photo Priority & Fallback Logic:
 * Priority 1: VEYRA / NEXA official profile photo (R2 CDN or local cache)
 * Priority 2: Device phone contacts photo (PHOTO_URI)
 * Priority 3: Alphabet Initial Avatar with deterministic Material palette color
 */
fun resolveAvatar(
    nexaAvatarPathOrUrl: String?,
    deviceContactPhotoUri: String?,
    effectiveName: String
): ResolvedAvatar {
    // Priority 1: VEYRA / NEXA official profile photo
    if (!nexaAvatarPathOrUrl.isNullOrBlank()) {
        return ResolvedAvatar.NetworkOrLocalUri(nexaAvatarPathOrUrl)
    }

    // Priority 2: Device phone contacts saved photo
    if (!deviceContactPhotoUri.isNullOrBlank()) {
        return ResolvedAvatar.NetworkOrLocalUri(deviceContactPhotoUri)
    }

    // Priority 3: Alphabet Initial Avatar (Deterministic Color Hash)
    val firstChar = effectiveName.trim().firstOrNull()?.uppercaseChar() ?: '?'
    val colorHex = getDeterministicColorForName(effectiveName)
    return ResolvedAvatar.InitialAvatar(firstChar, colorHex)
}

/**
 * Checks whether the current avatar can be opened in the full-screen photo viewer.
 * STRICT RULE: Only actual photos (Priority 1 or Priority 2) can be viewed full-screen.
 * Alphabet Initial Avatars (Priority 3) NEVER trigger the viewer.
 */
fun canOpenPhotoViewer(
    resolved: ResolvedAvatar,
    isLoadedSuccessfully: Boolean = true
): Boolean {
    return resolved is ResolvedAvatar.NetworkOrLocalUri && isLoadedSuccessfully
}

/**
 * Generates a consistent, deterministic Material palette color for any name.
 */
fun getDeterministicColorForName(name: String): Long {
    val palette = listOf(
        0xFF1E88E5, // Blue
        0xFF43A047, // Green
        0xFF8E24AA, // Purple
        0xFFE53935, // Red
        0xFFFB8C00, // Orange
        0xFF00ACC1, // Cyan
        0xFF3949AB  // Indigo
    )
    val hash = kotlin.math.abs(name.hashCode())
    return palette[hash % palette.size]
}

/**
 * Universal Production Avatar Component
 * Handles remote images, local content URIs, loading states, error fallbacks,
 * and seamless fallback to deterministic Initial avatars.
 *
 * @param onPhotoClick Optional callback invoked with the image model (String URL, Uri)
 *                     ONLY when tapping on an actual photo. Alphabet avatars DO NOT trigger this.
 */
@Composable
fun NexaAvatar(
    nexaAvatarUrl: String?,
    deviceContactPhotoUri: String?,
    displayName: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    onPhotoClick: ((Any) -> Unit)? = null
) {
    val resolved = remember(nexaAvatarUrl, deviceContactPhotoUri, displayName) {
        resolveAvatar(nexaAvatarUrl, deviceContactPhotoUri, displayName)
    }

    val fallbackInitial = displayName.trim().firstOrNull()?.uppercaseChar() ?: '?'
    val fallbackColor = getDeterministicColorForName(displayName)

    when (resolved) {
        is ResolvedAvatar.NetworkOrLocalUri -> {
            var isImageLoaded by remember(resolved.model) { mutableStateOf(true) }

            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(resolved.model)
                    .crossfade(true)
                    .build(),
                contentDescription = "$displayName Avatar",
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .size(size)
                    .clip(CircleShape)
                    .then(
                        if (onPhotoClick != null && isImageLoaded) {
                            Modifier.clickable { onPhotoClick(resolved.model) }
                        } else {
                            Modifier
                        }
                    ),
                onSuccess = { isImageLoaded = true },
                loading = {
                    InitialAvatarView(
                        initial = fallbackInitial,
                        backgroundColor = fallbackColor,
                        size = size
                    )
                },
                error = {
                    // Graceful fallback: If Priority 1 failed to load and Priority 2 exists, try Priority 2
                    if (resolved.model == nexaAvatarUrl && !deviceContactPhotoUri.isNullOrBlank()) {
                        var isSecondaryLoaded by remember(deviceContactPhotoUri) { mutableStateOf(true) }
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(deviceContactPhotoUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = "$displayName Device Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(size)
                                .clip(CircleShape)
                                .then(
                                    if (onPhotoClick != null && isSecondaryLoaded) {
                                        Modifier.clickable { onPhotoClick(deviceContactPhotoUri) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            onSuccess = { isSecondaryLoaded = true },
                            onError = { isSecondaryLoaded = false },
                            error = {
                                InitialAvatarView(
                                    initial = fallbackInitial,
                                    backgroundColor = fallbackColor,
                                    size = size
                                )
                            }
                        )
                    } else {
                        isImageLoaded = false
                        // Fallback to Priority 3: Alphabet Initial Avatar (Not clickable for photo viewer)
                        InitialAvatarView(
                            initial = fallbackInitial,
                            backgroundColor = fallbackColor,
                            size = size
                        )
                    }
                }
            )
        }
        is ResolvedAvatar.InitialAvatar -> {
            // Alphabet Avatar: NOT clickable for photo viewer
            InitialAvatarView(
                initial = resolved.char,
                backgroundColor = resolved.backgroundColorHex,
                size = size,
                modifier = modifier
            )
        }
    }
}
