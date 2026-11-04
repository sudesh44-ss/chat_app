package com.nexa.app.presentation.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Minimal non-blocking progress pill alias for [VideoTranscodeProgressOverlay].
 */
@Composable
fun CompressionProgressOverlay(
    isVisible: Boolean,
    progressPercent: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    VideoTranscodeProgressOverlay(
        isVisible = isVisible,
        progressPercent = progressPercent,
        onCancel = onCancel,
        modifier = modifier
    )
}

