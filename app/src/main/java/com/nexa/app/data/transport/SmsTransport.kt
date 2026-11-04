package com.nexa.app.data.transport

import com.nexa.app.data.remote.sms.NexaDataSmsSender

class SmsTransport(
    private val dataSmsSender: NexaDataSmsSender
) {
    suspend fun sendDataSms(recipientPhone: String, rawWireBytes: ByteArray): Boolean {
        val messageId = System.currentTimeMillis()
        val result = dataSmsSender.sendDataSms(
            destinationPhone = recipientPhone,
            messageId = messageId,
            encryptedPayload = rawWireBytes
        )
        return result.isSuccess
    }
}
