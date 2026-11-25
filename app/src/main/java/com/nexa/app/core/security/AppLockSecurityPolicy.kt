package com.nexa.app.core.security

import android.view.Window
import android.view.WindowManager

/**
 * Supported Auto-Lock timeout intervals.
 */
enum class AutoLockTimeout(val seconds: Long, val displayName: String) {
    IMMEDIATELY(0L, "Immediately"),
    ONE_MINUTE(60L, "After 1 minute"),
    FIVE_MINUTES(300L, "After 5 minutes"),
    FIFTEEN_MINUTES(900L, "After 15 minutes");

    companion object {
        fun fromSeconds(seconds: Long): AutoLockTimeout =
            entries.firstOrNull { it.seconds == seconds } ?: IMMEDIATELY
    }
}

/**
 * Pure policy rules for App Lock lifecycle triggers, window privacy flags, and PIN constraints.
 * Factoring this logic into deterministic functions enables comprehensive unit testing.
 */
object AppLockSecurityPolicy {

    const val PIN_MIN_LENGTH = 4
    const val PIN_MAX_LENGTH = 8
    const val PIN_RECOMMENDED_LENGTH = 6
    const val MAX_FAILED_ATTEMPTS = 5
    const val LOCKOUT_DURATION_MS = 30_000L

    /**
     * Determines whether the app should transition to locked state upon returning
     * to the foreground.
     *
     * @param lastBackgroundTimestamp Monotonic timestamp (ms) recorded on backgrounding (0 if cold start).
     * @param currentTimestamp Current monotonic timestamp (ms).
     * @param lockOnLeave User preference: require authentication when leaving the app.
     * @param timeoutSeconds Configured auto-lock delay in seconds (0 = Immediately).
     * @return True if the session must be locked, false otherwise.
     */
    fun shouldLockOnReturn(
        lastBackgroundTimestamp: Long,
        currentTimestamp: Long,
        lockOnLeave: Boolean,
        timeoutSeconds: Long
    ): Boolean {
        // Cold start or no background timestamp recorded: App starts locked if App Lock is enabled.
        if (lastBackgroundTimestamp <= 0L) return true

        val elapsedMs = (currentTimestamp - lastBackgroundTimestamp).coerceAtLeast(0L)
        val timeoutMs = timeoutSeconds * 1000L

        return when {
            // When "Lock when leaving NEXA" is ON:
            // If timeout is Immediately (0s), lock immediately.
            // Otherwise, lock if elapsed time has reached or exceeded the configured timeout.
            lockOnLeave -> (timeoutMs == 0L || elapsedMs >= timeoutMs)

            // When "Lock when leaving NEXA" is OFF:
            // Leaving the app does not trigger immediate locking.
            // But if a timed duration was configured (> 0) and the app remained in background
            // longer than that duration, lock on return.
            timeoutMs > 0L -> elapsedMs >= timeoutMs

            else -> false
        }
    }

    /**
     * Determines whether [WindowManager.LayoutParams.FLAG_SECURE] should be active on the window.
     *
     * - If App Lock is disabled: False.
     * - If Block Screenshots is ON: True at all times (foreground and background).
     * - If Hide App Preview is ON and Block Screenshots is OFF: True when in background/paused
     *   to prevent task switcher thumbnail leaks, but False in foreground so user screenshots work.
     * - If both are OFF: False.
     */
    fun computeSecureWindowFlag(
        isAppLockEnabled: Boolean,
        blockScreenshots: Boolean,
        hideAppPreview: Boolean,
        isForeground: Boolean
    ): Boolean {
        if (!isAppLockEnabled) return false

        return when {
            blockScreenshots -> true
            hideAppPreview && !isForeground -> true
            else -> false
        }
    }

    /**
     * Applies or clears FLAG_SECURE on the given window according to the current policy.
     */
    fun applyWindowSecurity(
        window: Window?,
        isAppLockEnabled: Boolean,
        blockScreenshots: Boolean,
        hideAppPreview: Boolean,
        isForeground: Boolean
    ) {
        if (window == null) return
        val shouldSecure = computeSecureWindowFlag(
            isAppLockEnabled = isAppLockEnabled,
            blockScreenshots = blockScreenshots,
            hideAppPreview = hideAppPreview,
            isForeground = isForeground
        )
        if (shouldSecure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
