package com.nexa.app.core.network

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val cause: Throwable) : ConnectionState
}
