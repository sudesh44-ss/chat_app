package com.nexa.app.presentation.qr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.qr.QrPeerPayload
import com.nexa.app.core.qr.QrValidationResult
import com.nexa.app.data.remote.scanner.GoogleCodeScannerEngine
import com.nexa.app.domain.repository.QrHandshakeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel managing personal QR generation (permanent vs ephemeral), screen brightness,
 * Google Code Scanner integration, and scanned peer validation.
 */
class QrViewModel(
    private val qrRepository: QrHandshakeRepository,
    private val scannerEngine: GoogleCodeScannerEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        QrUiState(
            nxId = qrRepository.getMyNxId(),
            displayName = qrRepository.getMyDisplayName()
        )
    )
    val uiState: StateFlow<QrUiState> = _uiState.asStateFlow()

    init {
        loadQrCode(isEphemeral = false)
    }

    fun loadQrCode(isEphemeral: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isEphemeral = isEphemeral) }
            try {
                val bitmap = qrRepository.generateMyQrBitmap(isEphemeral)
                _uiState.update {
                    it.copy(
                        qrBitmap = bitmap,
                        isLoading = false,
                        error = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to generate QR code: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun toggleEphemeral(isEphemeral: Boolean) {
        if (_uiState.value.isEphemeral != isEphemeral) {
            loadQrCode(isEphemeral)
        }
    }

    fun startScan(onFallbackToCameraX: () -> Unit) {
        if (!scannerEngine.isGooglePlayServicesAvailable()) {
            onFallbackToCameraX()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, error = null) }
            val result = scannerEngine.scanQrCode()
            _uiState.update { it.copy(isScanning = false) }

            result.onSuccess { payload ->
                handleScannedPayload(payload)
            }.onFailure { error ->
                if (error !is java.util.concurrent.CancellationException) {
                    _uiState.update { it.copy(error = error.localizedMessage ?: "Failed to scan QR code") }
                }
            }
        }
    }

    fun handleScannedPayload(payload: QrPeerPayload) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val outcome = qrRepository.processScannedQr(payload)
            _uiState.update { it.copy(isLoading = false) }

            outcome.onSuccess { validationResult ->
                when (validationResult) {
                    QrValidationResult.Valid -> {
                        _uiState.update { it.copy(scannedPeer = payload, error = null) }
                    }
                    QrValidationResult.Expired -> {
                        _uiState.update {
                            it.copy(error = "This QR code has expired (15-minute anti-stalking limit). Please ask your peer to refresh.")
                        }
                    }
                    QrValidationResult.SignatureMismatch -> {
                        _uiState.update {
                            it.copy(error = "Tampered QR code detected! Cryptographic Ed25519 signature mismatch.")
                        }
                    }
                    QrValidationResult.MalformedData -> {
                        _uiState.update {
                            it.copy(error = "Invalid or corrupted NEXA QR code format.")
                        }
                    }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(error = err.localizedMessage ?: "Failed to process QR code") }
            }
        }
    }

    fun clearScannedPeer() {
        _uiState.update { it.copy(scannedPeer = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
