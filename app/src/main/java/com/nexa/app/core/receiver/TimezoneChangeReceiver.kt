package com.nexa.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nexa.app.domain.manager.BackupScheduleManager

/**
 * Broadcast receiver listening for system timezone or clock adjustments.
 * Immediately recalculates the automated backup schedule to ensure zero wall-clock drift.
 */
class TimezoneChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED) {
            Log.i(TAG, "System clock/timezone event ($action) detected. Recalculating backup schedule.")
            try {
                BackupScheduleManager.reschedule(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reschedule on timezone change: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "TimezoneChangeReceiver"
    }
}
