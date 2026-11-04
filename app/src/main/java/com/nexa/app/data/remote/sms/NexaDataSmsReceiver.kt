package com.nexa.app.data.remote.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import com.nexa.app.NexaApplication
import com.nexa.app.core.proto.NexaWirePacket
import com.nexa.app.core.sms.ChunkReassembler
import com.nexa.app.core.sms.SmsPayloadCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * High-priority BroadcastReceiver listening on port 8901 (android.intent.action.DATA_SMS_RECEIVED).
 * Bypasses the standard Android Telephony Provider (content://sms/inbox).
 * Validates NEXA magic bytes [0x4E, 0x58], reassembles multi-part chunks,
 * and passes the complete binary Protobuf packet to the repository layer.
 */
class NexaDataSmsReceiver : BroadcastReceiver() {

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DATA_SMS_RECEIVED) return

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        Log.d(TAG, "Received DATA_SMS_RECEIVED broadcast on port 8901 with ${pdus.size} PDU(s)")

        for (pduObj in pdus) {
            val pduBytes = pduObj as? ByteArray ?: continue
            val smsMessage = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pduBytes, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pduBytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SMS PDU", e)
                continue
            }

            val userData = smsMessage.userData ?: bundle.getByteArray("data") ?: continue

            // 1. Validate magic bytes (0x4E, 0x58) and extract chunk metadata
            val chunk = SmsPayloadCodec.decodeChunk(userData)
            if (chunk == null) {
                // Not a valid NEXA port 8901 packet -> Silent drop
                continue
            }

            Log.d(
                TAG,
                "Valid NEXA Data SMS chunk: msgId=${chunk.messageId}, chunk=${chunk.chunkIndex + 1}/${chunk.totalChunks}"
            )

            // 2. Reassemble multi-part binary chunks
            val completePayload = reassembler.ingestChunk(chunk) ?: continue

            Log.d(TAG, "Successfully reassembled full payload of size ${completePayload.size} bytes for msgId=${chunk.messageId}")

            // 3. Process complete packet asynchronously in background coroutine
            val pendingResult = goAsync()
            receiverScope.launch {
                try {
                    val app = context.applicationContext as? NexaApplication
                    if (app != null) {
                        val packet = NexaWirePacket.decode(completePayload)
                        app.chatRepository.handleIncomingDataSmsPacket(packet)
                    } else {
                        Log.e(TAG, "NexaApplication instance is null; cannot ingest packet")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error decoding or storing incoming Data SMS packet", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "NexaDataSmsReceiver"
        private const val ACTION_DATA_SMS_RECEIVED = "android.intent.action.DATA_SMS_RECEIVED"

        // Singleton reassembler instance across broadcast deliveries
        val reassembler = ChunkReassembler()
    }
}
