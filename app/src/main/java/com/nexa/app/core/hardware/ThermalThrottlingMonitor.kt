package com.nexa.app.core.hardware

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Monitors hardware temperature via the Android PowerManager Thermal API (Scenario 15).
 * Detects severe thermal escalation during sustained heavy crypto & radio usage,
 * signaling the streaming engine to inject backoff delays and cut transfer rates by 50%.
 */
class ThermalThrottlingMonitor(context: Context) {

    private var isThrottled = false
    private var thermalStatus = 0

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.addThermalStatusListener { status ->
                    thermalStatus = status
                    isThrottled = when (status) {
                        PowerManager.THERMAL_STATUS_SEVERE,
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> true
                        else -> false
                    }
                }
            } catch (ignored: Exception) {
                isThrottled = false
            }
        }
    }

    fun isThrottlingRequired(): Boolean = isThrottled

    fun getThermalStatus(): Int = thermalStatus

    // Visible for testing
    fun setThrottledForTesting(throttled: Boolean) {
        isThrottled = throttled
    }
}
