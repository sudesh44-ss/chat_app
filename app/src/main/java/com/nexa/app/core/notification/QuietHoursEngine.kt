package com.nexa.app.core.notification

import com.nexa.app.data.local.preferences.NotificationPreferences
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Client-side Quiet Hours / Do Not Disturb Engine.
 * Enforces Zero Server Knowledge: Quiet hours windows, weekday schedules,
 * and suppression rules are strictly evaluated on-device using local wall-clock time.
 */
object QuietHoursEngine {

    /**
     * Determines whether an incoming alert should be suppressed by Quiet Hours.
     *
     * @param isCall True if the incoming event is a voice or video call.
     * @param settings Active user notification preferences.
     * @param now Current device date and time (defaults to LocalDateTime.now()).
     * @return True if sound/heads-up alert should be suppressed; false if it should alert normally.
     */
    fun shouldSuppressNotification(
        isCall: Boolean,
        settings: NotificationPreferences,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false

        // Emergency / critical calls bypass quiet hours if user permitted
        if (isCall && settings.allowCallsDuringQuietHours) return false

        return isWithinQuietHoursWindow(settings, now)
    }

    /**
     * Overload for LocalTime (preserves backward compatibility with legacy callers/tests).
     */
    fun shouldSuppressNotification(
        isCall: Boolean,
        settings: NotificationPreferences,
        currentTime: LocalTime
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false
        if (isCall && settings.allowCallsDuringQuietHours) return false
        return isWithinQuietHoursWindow(settings, currentTime)
    }

    /**
     * Evaluates if [now] falls within the configured start and end time window and selected weekdays.
     * Accurately supports windows crossing midnight (e.g., 22:00 to 07:00) with weekday scheduling:
     * - Evening portion (22:00-23:59) checks if currentDay is selected.
     * - Morning portion (00:00-06:59) checks if the PREVIOUS day was selected (overnight continuation).
     */
    fun isWithinQuietHoursWindow(
        settings: NotificationPreferences,
        now: LocalDateTime = LocalDateTime.now()
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false

        val selectedDays = settings.quietHoursDays
        if (selectedDays.isEmpty()) return false

        val start = settings.quietHoursStart
        val end = settings.quietHoursEnd
        val currentTime = now.toLocalTime()
        val currentDay = now.dayOfWeek.value // 1 (Monday) to 7 (Sunday)

        return if (start == end) {
            // Equal start and end denotes an active 24-hour quiet window on selected days
            selectedDays.contains(currentDay)
        } else if (start.isBefore(end)) {
            // Same-day window (e.g. 13:00 to 17:00)
            selectedDays.contains(currentDay) && !currentTime.isBefore(start) && currentTime.isBefore(end)
        } else {
            // Window crosses midnight (e.g. 22:00 to 07:00)
            // Part 1: Evening of a selected day (e.g. Monday 22:00 to 23:59)
            val isEveningOfSelectedDay = selectedDays.contains(currentDay) && !currentTime.isBefore(start)

            // Part 2: Morning belonging to previous day's overnight window (e.g. Tuesday 00:00 to 06:59 from Monday night)
            val previousDay = if (currentDay == 1) 7 else currentDay - 1
            val isMorningOfPreviousDayOvernight = selectedDays.contains(previousDay) && currentTime.isBefore(end)

            isEveningOfSelectedDay || isMorningOfPreviousDayOvernight
        }
    }

    /**
     * Overload for LocalTime (preserves backward compatibility assuming every day is active).
     */
    fun isWithinQuietHoursWindow(
        settings: NotificationPreferences,
        currentTime: LocalTime
    ): Boolean {
        if (!settings.isQuietHoursEnabled) return false

        val start = settings.quietHoursStart
        val end = settings.quietHoursEnd

        return if (start == end) {
            true
        } else if (start.isBefore(end)) {
            !currentTime.isBefore(start) && currentTime.isBefore(end)
        } else {
            !currentTime.isBefore(start) || currentTime.isBefore(end)
        }
    }

    /**
     * Human-readable status description for UI banners and cards.
     */
    fun getQuietHoursStatusDescription(
        settings: NotificationPreferences,
        now: LocalDateTime = LocalDateTime.now()
    ): String {
        if (!settings.isQuietHoursEnabled) return "Disabled"

        val isActive = isWithinQuietHoursWindow(settings, now)
        return if (isActive) {
            String.format("Active now • Until %02d:%02d", settings.quietHoursEndHour, settings.quietHoursEndMinute)
        } else {
            String.format(
                "Inactive • Starts %02d:%02d (%s)",
                settings.quietHoursStartHour,
                settings.quietHoursStartMinute,
                settings.quietHoursDaysFormatted
            )
        }
    }
}
