package com.nexa.app.presentation.qr

import android.graphics.Bitmap
import com.nexa.app.core.qr.QrPeerPayload

data class QrUiState(
    val nxId: String = "",
    val displayName: String = "",
    val isEphemeral: Boolean = false,
    val qrBitmap: Bitmap? = null,
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val scannedPeer: QrPeerPayload? = null,
    val error: String? = null,
    val successNotice: String? = null
)
