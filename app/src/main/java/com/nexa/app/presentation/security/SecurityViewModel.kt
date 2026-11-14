package com.nexa.app.presentation.security

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.security.DualScanResult
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
                is DualScanResult.Clean -> {
                    _uiState.value = SecurityUiState.Clean(fileName, verdict.hash)
                    onClean(verdict.hash)
                }
                is DualScanResult.Malicious -> {
                    _uiState.value = SecurityUiState.QuarantinedThreat(fileName, "", verdict.reason)
                    onBlocked(verdict.reason)
                }
                is DualScanResult.Corrupted -> {
                    _uiState.value = SecurityUiState.Corrupted(fileName, verdict.message)
                    onBlocked(verdict.message)
                }
                is DualScanResult.Inconclusive -> {
                    _uiState.value = SecurityUiState.Corrupted(fileName, verdict.reason)
                    onBlocked(verdict.reason)
                }
            }
        }
    }

    fun handleInboundScanResult(
        result: DualScanResult,
        fileName: String,
        fileHash: String,
        onCleanQuarantined: (quarantinePath: String) -> Unit
    ) {
        when (result) {
            is DualScanResult.Clean -> {
                _uiState.value = SecurityUiState.Clean(fileName, fileHash)
                onCleanQuarantined(result.hash)
            }
            is DualScanResult.Malicious -> {
                _uiState.value = SecurityUiState.QuarantinedThreat(fileName, fileHash, result.reason)
            }
            is DualScanResult.Corrupted -> {
                _uiState.value = SecurityUiState.Corrupted(fileName, result.message)
            }
            is DualScanResult.Inconclusive -> {
                _uiState.value = SecurityUiState.Corrupted(fileName, result.reason)
            }
        }
    }

    fun deleteAndShredQuarantine(fileHash: String) {
        viewModelScope.launch {
            securityRepository.shredQuarantinedFile(fileHash)
            _uiState.value = SecurityUiState.Idle
        }
    }

    fun forceOverrideQuarantine(
        fileHash: String,
        fileName: String,
        authorization: com.nexa.app.core.security.ReleaseAuthorization,
        onReleased: (File) -> Unit
    ) {
        viewModelScope.launch {
            val file = securityRepository.forceOverrideQuarantine(fileHash, fileName, authorization)
            if (file != null) {
                _uiState.value = SecurityUiState.Clean(fileName, fileHash)
                onReleased(file)
            } else {
                _uiState.value = SecurityUiState.QuarantinedThreat(
                    fileName,
                    fileHash,
                    "Release was blocked by the security gate"
                )
            }
        }
    }

    fun dismissAlert() {
        _uiState.value = SecurityUiState.Idle
    }
}
