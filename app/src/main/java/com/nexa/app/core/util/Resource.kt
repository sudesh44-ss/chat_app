package com.nexa.app.core.util

sealed interface Resource<out T> {
    data object Idle : Resource<Nothing>
    data object Loading : Resource<Nothing>
    data class Success<out T>(val data: T) : Resource<T>
    data class Error(val message: String, val cause: Throwable? = null) : Resource<Nothing>
}
