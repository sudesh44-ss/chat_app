package com.nexa.app.data.remote.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import com.nexa.app.core.sms.SmsComposerRequest
import com.nexa.app.core.sms.nx1.SmsSegmentCalculator
import com.nexa.app.core.sms.SmsSubscriptionPolicy
import com.nexa.app.core.sms.SmsSubscriptionSlot
import com.nexa.app.core.sms.SmsSimSelection

/**
 * Zero-permission SMS composer handoff (Mode A).
 *
 * Uses ACTION_SENDTO with an `smsto:` URI. No SMS permission is declared or requested.
 * NEXA cannot observe whether the user pressed Send, so callers must never treat this as
 * a successful send — only as a handoff.
 */
object SmsComposerLauncher {

    private const val TAG = "SmsComposerLauncher"

    fun buildIntent(request: SmsComposerRequest): Intent {
        val uri = Uri.fromParts("smsto", request.destinationNumber, null)
        return Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra("sms_body", request.bodyText)
            putExtra(Intent.EXTRA_TEXT, request.bodyText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** @return true when an SMS application accepted the handoff. Never implies delivery. */
    fun launch(context: Context, request: SmsComposerRequest): Boolean = try {
        val intent = buildIntent(request)
        if (intent.resolveActivity(context.packageManager) == null) {
            Log.w(TAG, "No SMS application available to handle ACTION_SENDTO")
            false
        } else {
            context.startActivity(intent)
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unable to launch external SMS composer: ${e.javaClass.simpleName}")
        false
    }

    /**
     * True when the device reports SMS capability. Uses only permission-free APIs:
     * TelephonyManager.isSmsCapable() and feature detection.
     */
    fun isSmsCapable(context: Context): Boolean = try {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val hasSmsFeature = context.packageManager.hasSystemFeature("android.hardware.telephony")
        (telephony?.isSmsCapable ?: hasSmsFeature) && hasSmsFeature
    } catch (e: Exception) {
        false
    }

    /**
     * Segment count for a body using the platform's own splitter when available.
     * Falls back to the deterministic GSM-7/UCS-2 calculator.
     */
    fun segmentCount(context: Context, body: String): Int = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            manager?.divideMessage(body)?.size ?: SmsSegmentCalculator.segmentCount(body)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault().divideMessage(body)?.size
                ?: SmsSegmentCalculator.segmentCount(body)
        }
    } catch (e: Exception) {
        SmsSegmentCalculator.segmentCount(body)
    }

    /**
     * Subscription policy. NEXA does not declare READ_PHONE_STATE, so subscription enumeration
     * is unavailable and the policy reports DELEGATED_TO_SYSTEM_SMS_APP. The method exists so the
     * same policy object is used if Mode B is ever approved.
     */
    fun simSelection(context: Context, hasReadPhoneState: Boolean = false): SmsSimSelection {
        val slots: List<SmsSubscriptionSlot>? = if (hasReadPhoneState) {
            try {
                val manager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                manager?.activeSubscriptionInfoList?.map {
                    SmsSubscriptionSlot(
                        subscriptionId = it.subscriptionId,
                        slotIndex = it.simSlotIndex,
                        carrierName = it.carrierName?.toString() ?: "",
                        isDefaultSmsSubscription = it.subscriptionId ==
                            android.telephony.SubscriptionManager.getDefaultSmsSubscriptionId()
                    )
                }
            } catch (e: SecurityException) {
                null
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        return SmsSubscriptionPolicy.resolve(slots, null, null)
    }
}
