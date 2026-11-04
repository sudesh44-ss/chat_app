package com.nexa.app.data.remote.scanner

import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.nexa.app.core.qr.QrPayloadCodec
import com.nexa.app.core.qr.QrPeerPayload

/**
 * CameraX ImageAnalysis analyzer providing seamless QR scanning on de-Googled AOSP ROMs
 * (e.g., GrapheneOS, LineageOS, CalyxOS) where Google Play Services are absent.
 */
class CameraXScannerFallback(
    private val onPeerScanned: (QrPeerPayload) -> Unit,
    private val onError: (Exception) -> Unit = {}
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader()
    private var isScanComplete = false

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isScanComplete) {
            imageProxy.close()
            return
        }

        try {
            val image = imageProxy.image
            if (image == null) {
                imageProxy.close()
                return
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = imageProxy.width
            val height = imageProxy.height

            val source = PlanarYUVLuminanceSource(
                bytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )

            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(bitmap)
            val decodedText = result.text

            val payload = QrPayloadCodec.decode(decodedText)
            if (payload != null) {
                isScanComplete = true
                onPeerScanned(payload)
            }
        } catch (ignored: Exception) {
            // Expected while scanning frames before QR code enters bounding box
        } finally {
            imageProxy.close()
        }
    }

    fun reset() {
        isScanComplete = false
    }
}
