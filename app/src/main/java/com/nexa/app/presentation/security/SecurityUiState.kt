package com.nexa.app.presentation.security

sealed interface SecurityUiState {
    data object Idle : SecurityUiState
    data class Scanning(val fileName: String, val progress: Float = 0f) : SecurityUiState
    data class Clean(val fileName: String, val fileHash: String) : SecurityUiState
    data class QuarantinedThreat(val fileName: String, val fileHash: String, val threatReason: String) : SecurityUiState
    data class Corrupted(val fileName: String, val message: String) : SecurityUiState
}
