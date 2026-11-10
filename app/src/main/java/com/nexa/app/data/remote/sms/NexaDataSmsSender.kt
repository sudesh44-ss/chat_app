package com.nexa.app.data.remote.sms

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.nexa.app.core.sms.SmsConstants
import com.nexa.app.core.sms.SmsPayloadCodec

/**
 * Outbound Data SMS Dispatcher.
 * Transmits binary WAP-push/PDU packets over Port 8901 using SmsManager.sendDataMessage().
 * Strictly ensures packets do not exceed 133 bytes so they never fragment at the telecom layer.
 */
class NexaDataSmsSender(
    private val context: Context
) {

    /**
     * Determines whether binary Data SMS on Port 8901 is operational on this device.
     * Always returns false on production builds where SEND_SMS and RECEIVE_SMS
     * permissions are omitted for Google Play Protect and ECM anti-fraud compliance.
     */
    fun isSupported(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        return hasPermission
    }

    /**
     * Sends a chunked binary payload to [destinationPhone] over Port 8901.
     * @param destinationPhone E.164 formatted phone number (e.g., +919876543210)
     * @param messageId Unique numeric identifier for chunk reassembly
     * @param encryptedPayload Full ChaCha20-Poly1305 encrypted byte array
     * @param packetId Optional UUID packet identifier for delivery tracking
     * @return Result indicating number of SMS chunks dispatched
     */
    fun sendDataSms(
        destinationPhone: String,
        messageId: Long,
        encryptedPayload: ByteArray,
        packetId: String? = null
    ): Result<Int> {
        if (!isSupported()) {
            Log.w(TAG, "Data SMS transport blocked: SEND_SMS permission is omitted in this build for security compliance")
            return Result.failure(
                UnsupportedOperationException(
                    "Data SMS transport is disabled in this build for Google Play Protect compliance. Please use Internet or DTN Mesh."
                )
            )
        }

        val cleanedPhone = destinationPhone.trim()
        if (cleanedPhone.isBlank()) {
            return Result.failure(IllegalArgumentException("Destination phone number cannot be blank"))
        }

        return try {
            val smsManager = getSmsManager()
            val chunks = SmsPayloadCodec.chunkPayload(messageId, encryptedPayload)

            Log.d(TAG, "Dispatching ${chunks.size} Data SMS chunk(s) to $cleanedPhone on port ${SmsConstants.DATA_SMS_PORT}")

            chunks.forEachIndexed { index, chunkBytes ->
                val sentPendingIntent = packetId?.let { pid ->
                    val intent = Intent(ACTION_DATA_SMS_SENT).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_PACKET_ID, pid)
                        putExtra(EXTRA_CHUNK_INDEX, index)
                        putExtra(EXTRA_TOTAL_CHUNKS, chunks.size)
                    }
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    PendingIntent.getBroadcast(
                        context,
                        (messageId xor index.toLong()).toInt(),
                        intent,
                        flags
                    )
                }

                smsManager.sendDataMessage(
                    cleanedPhone,
                    null,
                    SmsConstants.DATA_SMS_PORT,
                    chunkBytes,
                    sentPendingIntent,
                    null
                )
            }

            Result.success(chunks.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch Data SMS to $cleanedPhone", e)
            Result.failure(e)
        }
    }

    @Suppress("DEPRECATION")
    private fun getSmsManager(): SmsManager {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }

    companion object {
        private const val TAG = "NexaDataSmsSender"
        const val ACTION_DATA_SMS_SENT = "com.nexa.app.action.DATA_SMS_SENT"
        const val EXTRA_PACKET_ID = "extra_packet_id"
        const val EXTRA_CHUNK_INDEX = "extra_chunk_index"
        const val EXTRA_TOTAL_CHUNKS = "extra_total_chunks"
    }
}
