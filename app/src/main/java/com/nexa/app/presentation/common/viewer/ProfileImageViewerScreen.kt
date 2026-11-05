package com.nexa.app.presentation.common.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * Full-Screen Profile Photo Viewer Screen
 *
 * Rules:
 * - Photo displayed in strict 1:1 (4:4) square ratio.
 * - Remaining space is pure pitch black (#000000).
 * - Maximum possible square size according to screen dimensions without distortion.
 * - Top-right ✕ Close button with white tint.
 * - Hardware back press / back gesture handling.
 * - Pinch-to-zoom (up to 4x) and pan gestures when zoomed.
 * - Double-tap to zoom toggle.
 */
@Composable
fun ProfileImageViewerScreen(
    imageModel: Any,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intercept hardware and gesture back button
    BackHandler(enabled = true, onBack = onCloseClick)

    // Zoom and pan state
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
    ) {
        // 1. CENTERED 1:1 SQUARE IMAGE CONTAINER
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center)
        ) {
            val maxSquareSize = minOf(maxWidth, maxHeight)

            Box(
                modifier = Modifier
                    .size(maxSquareSize)
                    .aspectRatio(1f) // Strict 1:1 square ratio
                    .align(Alignment.Center)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4.0f)
                            if (scale > 1f) {
                                val maxBoundX = (maxSquareSize.toPx() * (scale - 1f)) / 2f
                                val maxBoundY = (maxSquareSize.toPx() * (scale - 1f)) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxBoundX, maxBoundX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxBoundY, maxBoundY)
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                        }
                    }
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageModel)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Profile Photo Full Screen",
                    contentScale = ContentScale.Crop, // Strict 1:1 fill
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            }
        }

        // 2. TOP-RIGHT CLOSE BUTTON (✕)
        IconButton(
            onClick = onCloseClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close Viewer",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/**
 * Convenient Full-Screen Immersive Dialog wrapper for [ProfileImageViewerScreen].
 * Spans edge-to-edge with pure black canvas.
 */
@Composable
fun ProfileImageViewerDialog(
    imageModel: Any,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ProfileImageViewerScreen(
            imageModel = imageModel,
            onCloseClick = onDismiss
        )
    }
}
