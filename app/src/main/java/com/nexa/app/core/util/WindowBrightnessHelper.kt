package com.nexa.app.core.util

import android.app.Activity
import android.view.WindowManager

/**
 * Utility to temporarily maximize screen brightness during QR presentation to ensure
 * instantaneous camera sensor acquisition under any ambient lighting condition.
 */
object WindowBrightnessHelper {

    fun setMaxBrightness(activity: Activity?): Float? {
        val window = activity?.window ?: return null
        val layoutParams = window.attributes
        val originalBrightness = layoutParams.screenBrightness
        layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = layoutParams
        return originalBrightness
    }

    fun restoreBrightness(activity: Activity?, originalBrightness: Float?) {
        val window = activity?.window ?: return
        val layoutParams = window.attributes
        layoutParams.screenBrightness = originalBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = layoutParams
    }
}
