package com.nexa.app.core.notification

import com.nexa.app.data.local.preferences.NotificationPreferences
import java.time.LocalTime

/**
 * Client-side Quiet Hours / Do Not Disturb Engine.
 * Enforces Zero Server Knowledge: Quiet hours windows and suppression rules
 * are strictly evaluated on-device using local wall-clock time.
 */
object QuietHoursEngine {

    /**
     * Determines whether an incoming alert should be suppressed by Quiet Hours.
     *
     * @param isCall True if the incoming event is a voice or video call.
     * @param settings Active user notification preferences.
     * @param currentTime Current device time (defaults to LocalTime.now()).
     * @return True if sound/heads-up alert should be suppressed; false if it should alert normally.
     */
    fun shouldSuppressNotification(
        isCall: Boolean,
        settings: NotificationPreferences,
        currentTime: LocalTime = LocalTime.now()
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false

        // Emergency / critical calls bypass quiet hours if user permitted
        if (isCall && settings.allowCallsDuringQuietHours) return false

        return isWithinQuietHoursWindow(settings, currentTime)
    }

    /**
     * Evaluates if [currentTime] falls within the configured start and end time window.
     * Accurately supports windows crossing midnight (e.g., 23:00 to 07:00).
     */
    fun isWithinQuietHoursWindow(
        settings: NotificationPreferences,
        currentTime: LocalTime = LocalTime.now()
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false

        val start = settings.quietHoursStart
        val end = settings.quietHoursEnd

        return if (start == end) {
            // Equal start and end denotes an active 24-hour quiet window
            true
        } else if (start.isBefore(end)) {
            // Same-day window (e.g. 14:00 to 18:00)
            !currentTime.isBefore(start) && currentTime.isBefore(end)
        } else {
            // Window crosses midnight (e.g. 23:00 to 07:00)
            !currentTime.isBefore(start) || currentTime.isBefore(end)
        }
    }
}
