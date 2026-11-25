package com.nexa.app.core.config

import com.nexa.app.BuildConfig

/**
 * Centralized launch-flow configuration for NEXA.
 *
 * Manages development-only launch overrides such as temporarily bypassing the login
 * and onboarding flow to navigate directly to the Home Screen (MainChatDashboardScreen)
 * after the Splash Screen completes.
 *
 * SECURITY INVARIANT:
 * This bypass is strictly disabled in release/production builds (`BuildConfig.DEBUG == false`).
 * In production builds, [isBypassAuthToHomeScreenEnabled] always evaluates to `false`,
 * guaranteeing that production authentication and session checks are completely unmodified.
 */
object AppLaunchConfig {

    /**
     * Development-only toggle to bypass the login / onboarding flow and open the Home Screen
     * directly after the Splash Screen.
     *
     * - `true`: Splash Screen navigates directly to Screen.Dashboard (Home Screen) if explicitly enabled.
     * - `false`: Normal authentication check (Splash Screen checks session and routes to Welcome / Dashboard).
     *
     * Default: false to ensure the authentic startup and onboarding flow is always active.
     */
    @Volatile
    var DEV_BYPASS_AUTH_TO_HOME_SCREEN: Boolean = false

    /**
     * Runtime guard for authentication bypass.
     * Guaranteed to be `false` in production release builds.
     */
    val isBypassAuthToHomeScreenEnabled: Boolean
        get() = BuildConfig.DEBUG && DEV_BYPASS_AUTH_TO_HOME_SCREEN
}
