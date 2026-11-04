package com.nexa.app.core.media

/**
 * Hardware-Accelerated Video Compression & Perceptual Optimization Configuration.
 * Tuned for mobile SoC DSP/VPU encoders (Qualcomm Spectra, MediaTek MiraVision, Exynos VPU).
 */
object VideoTransformConfig {
    // Resolution Caps
    const val MAX_HEIGHT_1080P = 1080
    const val MAX_HEIGHT_720P = 720
    const val MAX_FPS_CAP = 30.0f

    // Target Bitrates (Constrained Variable Bitrate - VBR)
    // 1080p: ~2.8 Mbps (Crisp desktop/tablet viewing)
    const val TARGET_VIDEO_BITRATE_1080P = 2_800_000
    // 720p: ~2.0 Mbps (Mobile messaging sweet spot - instant P2P streaming)
    const val TARGET_VIDEO_BITRATE_720P = 2_000_000
    // Sub-720p fallback: ~1.2 Mbps
    const val TARGET_VIDEO_BITRATE_SD = 1_200_000

    // Audio Stream Optimization: AAC-LC 128 kbps stereo, 44.1 kHz
    const val AUDIO_BITRATE_AAC = 128_000
    const val AUDIO_SAMPLE_RATE_HZ = 44_100
    const val AUDIO_CHANNEL_COUNT = 2

    // Thresholds for auto-compression decision matrix
    const val COMPRESSION_SIZE_THRESHOLD_BYTES = 15L * 1024L * 1024L // 15 MB
    const val COMPRESSION_MAX_HEIGHT_THRESHOLD = 720
    const val COMPRESSION_MAX_FPS_THRESHOLD = 30.0f

    // Cache directory & polling
    const val TRANSCODE_CACHE_DIR = "transcode"
    const val PROGRESS_POLL_INTERVAL_MS = 150L
}

