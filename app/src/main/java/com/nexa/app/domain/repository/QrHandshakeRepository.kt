package com.nexa.app.domain.repository

import android.graphics.Bitmap
import com.nexa.app.core.qr.QrPeerPayload
import com.nexa.app.core.qr.QrValidationResult

/**
 * Domain repository contract for generating and validating cryptographically authenticated
 * Day-0 offline QR identity exchanges.
 */
interface QrHandshakeRepository {

    /**
     * Generates a signed `nx://add` URI string for the current active user.
     * @param isEphemeral If true, attaches an expiration timestamp of Now + 15 minutes.
     */
    suspend fun generateMyQrPayload(isEphemeral: Boolean): String

    /**
     * Generates a crisp, high-contrast RGB_565 Android Bitmap from the signed URI.
     */
    suspend fun generateMyQrBitmap(isEphemeral: Boolean, sizePx: Int = 600): Bitmap

    /**
     * Validates cryptographic signatures, expiry dates, seeds Double Ratchet,
     * persists CONTACT_VERIFIED in Room DB, and whitelists BLE peer for Day-0 offline messaging.
     */
    suspend fun processScannedQr(payload: QrPeerPayload): Result<QrValidationResult>

    fun getMyNxId(): String

    fun getMyDisplayName(): String
}
