package com.nexa.app.domain.model

import com.nexa.app.data.local.preferences.BackupFrequency

/**
 * Persisted schedule timing configuration for [BackupMode.SCHEDULED].
 *
 * @property frequency Scheduled repetition interval (DAILY, WEEKLY, MONTHLY)
 * @property dailyHour Hour of day (0..23) for daily execution. Default is 2 (02:00 AM).
 * @property dailyMinute Minute of hour (0..59). Default is 0.
 * @property weeklyDayOfWeek Day of week (1 = Monday .. 7 = Sunday) for weekly execution. Default is 1 (Monday).
 * @property monthlyDayOfMonth Day of month (1..31) for monthly execution. Default is 1 (1st of month).
 */
data class BackupScheduleConfig(
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val dailyHour: Int = 2,
    val dailyMinute: Int = 0,
    val weeklyDayOfWeek: Int = 1,
    val monthlyDayOfMonth: Int = 1
) {
    init {
        require(dailyHour in 0..23) { "dailyHour must be within 0..23, was: $dailyHour" }
        require(dailyMinute in 0..59) { "dailyMinute must be within 0..59, was: $dailyMinute" }
        require(weeklyDayOfWeek in 1..7) { "weeklyDayOfWeek must be within 1..7, was: $weeklyDayOfWeek" }
        require(monthlyDayOfMonth in 1..31) { "monthlyDayOfMonth must be within 1..31, was: $monthlyDayOfMonth" }
    }
}
