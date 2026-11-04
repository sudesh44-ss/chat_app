package com.nexa.app.presentation.security

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.security.ScanResult
import com.nexa.app.domain.repository.SecurityScannerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Presentation ViewModel coordinating dual-gate scan flows, quarantine alert dialogs,
 * and multi-pass cryptographic file shredding.
 */
class SecurityViewModel(
    private val securityRepository: SecurityScannerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SecurityUiState>(SecurityUiState.Idle)
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    fun scanOutboundFile(
        uri: Uri,
        fileName: String,
        declaredExtension: String,
        onClean: (fileHash: String) -> Unit,
        onBlocked: (reason: String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Scanning(fileName)
            val verdict = securityRepository.scanOutboundFile(uri, declaredExtension)
            when (verdict) {
                is ScanResult.Clean -> {
                    _uiState.value = SecurityUiState.Clean(fileName, verdict.hash)
                    onClean(verdict.hash)
                }
                is ScanResult.Malicious -> {
                    _uiState.value = SecurityUiState.QuarantinedThreat(fileName, "", verdict.reason)
                    onBlocked(verdict.reason)
                }
                is ScanResult.Corrupted -> {
                    _uiState.value = SecurityUiState.Corrupted(fileName, verdict.message)
                    onBlocked(verdict.message)
                }
            }
        }
    }

    fun handleInboundScanResult(
        result: ScanResult,
        fileName: String,
        fileHash: String,
        onCleanPromoted: (safePath: String) -> Unit
    ) {
        when (result) {
            is ScanResult.Clean -> {
                _uiState.value = SecurityUiState.Clean(fileName, fileHash)
                onCleanPromoted(result.hash)
            }
            is ScanResult.Malicious -> {
                _uiState.value = SecurityUiState.QuarantinedThreat(fileName, fileHash, result.reason)
            }
            is ScanResult.Corrupted -> {
                _uiState.value = SecurityUiState.Corrupted(fileName, result.message)
            }
        }
    }

    fun deleteAndShredQuarantine(fileHash: String) {
        viewModelScope.launch {
            securityRepository.shredQuarantinedFile(fileHash)
            _uiState.value = SecurityUiState.Idle
        }
    }

    fun forceOverrideQuarantine(fileHash: String, fileName: String, onPromoted: (File) -> Unit) {
        viewModelScope.launch {
            val file = securityRepository.forceOverrideQuarantine(fileHash, fileName)
            _uiState.value = SecurityUiState.Clean(fileName, fileHash)
            if (file != null) {
                onPromoted(file)
            }
        }
    }

    fun dismissAlert() {
        _uiState.value = SecurityUiState.Idle
    }
}
