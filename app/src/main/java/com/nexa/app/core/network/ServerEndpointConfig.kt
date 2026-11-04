package com.nexa.app.core.network

import android.os.Build

object ServerEndpointConfig {
    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk")
    }

    val host: String
        get() = if (isEmulator) "10.0.2.2" else "127.0.0.1"

    val httpBaseUrl: String
        get() = "http://$host:8080"

    val wsBaseUrl: String
        get() = "ws://$host:8080"

    val securityRulesUrl: String
        get() = "http://$host:8086"
}
