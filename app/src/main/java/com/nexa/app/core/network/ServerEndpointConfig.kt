package com.nexa.app.core.network

import android.os.Build

object ServerEndpointConfig {
    const val RENDER_HOST = "nexa-relay.onrender.com"

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

    // Set to true to route all network traffic through the live Render cloud deployment
    const val USE_PRODUCTION_CLOUD = true

    val httpBaseUrl: String
        get() = if (USE_PRODUCTION_CLOUD) {
            "https://$RENDER_HOST"
        } else {
            val localHost = if (isEmulator) "10.0.2.2" else "127.0.0.1"
            "http://$localHost:8080"
        }

    val wsBaseUrl: String
        get() = if (USE_PRODUCTION_CLOUD) {
            "wss://$RENDER_HOST"
        } else {
            val localHost = if (isEmulator) "10.0.2.2" else "127.0.0.1"
            "ws://$localHost:8080"
        }

    val securityRulesUrl: String
        get() = if (USE_PRODUCTION_CLOUD) {
            "https://$RENDER_HOST"
        } else {
            val localHost = if (isEmulator) "10.0.2.2" else "127.0.0.1"
            "http://$localHost:8086"
        }
}

