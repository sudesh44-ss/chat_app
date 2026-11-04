package com.nexa.app.data.remote.scanner

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nexa.app.core.qr.QrPayloadCodec
import com.nexa.app.core.qr.QrPeerPayload
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * High-speed, zero-permission Google Code Scanner (ML Kit) engine.
 * Renders an isolated Play Services modal dialog without requesting CAMERA runtime permissions.
 */
class GoogleCodeScannerEngine(private val context: Context) {

    private val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()

    fun isGooglePlayServicesAvailable(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(context)
        return resultCode == ConnectionResult.SUCCESS
    }

    suspend fun scanQrCode(): Result<QrPeerPayload> = suspendCancellableCoroutine { continuation ->
        if (!isGooglePlayServicesAvailable()) {
            continuation.resume(Result.failure(IllegalStateException("Google Play Services unavailable on this device")))
            return@suspendCancellableCoroutine
        }

        val scanner = GmsBarcodeScanning.getClient(context, options)

        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue.isNullOrBlank()) {
                    continuation.resume(Result.failure(IllegalArgumentException("Empty QR code scanned")))
                    return@addOnSuccessListener
                }

                val payload = QrPayloadCodec.decode(rawValue)
                if (payload != null) {
                    continuation.resume(Result.success(payload))
                } else {
                    continuation.resume(Result.failure(IllegalArgumentException("Scanned QR is not a valid NEXA cryptographic contact code")))
                }
            }
            .addOnCanceledListener {
                continuation.resume(Result.failure(java.util.concurrent.CancellationException("QR scan cancelled by user")))
            }
            .addOnFailureListener { error ->
                continuation.resume(Result.failure(error))
            }
    }
}
