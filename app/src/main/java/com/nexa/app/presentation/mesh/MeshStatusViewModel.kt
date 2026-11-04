package com.nexa.app.presentation.mesh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexa.app.core.mesh.engine.CompositeMetricEngine
import com.nexa.app.core.mesh.engine.LqiDecayEngine
import com.nexa.app.core.mesh.model.LinkQualityMetrics
import com.nexa.app.core.mesh.model.RouteEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MeshUiState(
    val activePeerCount: Int = 0,
    val routes: List<RouteEntry> = emptyList(),
    val batteryPct: Int = 100,
    val isRelayActive: Boolean = true,
    val linkMetrics: Map<String, LinkQualityMetrics> = emptyMap()
)

class MeshStatusViewModel(
    private val metricEngine: CompositeMetricEngine,
    private val lqiEngine: LqiDecayEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(MeshUiState())
    val uiState: StateFlow<MeshUiState> = _uiState.asStateFlow()

    fun updateBattery(batteryPct: Int) {
        _uiState.value = _uiState.value.copy(
            batteryPct = batteryPct,
            isRelayActive = batteryPct >= 5
        )
    }

    fun refreshTopology() {
        viewModelScope.launch {
            val allRoutes = metricEngine.getAllRoutes()
            _uiState.value = _uiState.value.copy(
                routes = allRoutes,
                activePeerCount = allRoutes.size
            )
        }
    }

    fun recordLinkSample(peerId: String, rawRssi: Int, failedRetries: Int = 0, transmitted: Int = 1) {
        val state = lqiEngine.recordSample(peerId, rawRssi, failedRetries, transmitted)
        val metric = lqiEngine.getMetrics(peerId)
        if (metric != null) {
            val updated = _uiState.value.linkMetrics.toMutableMap()
            updated[peerId] = metric
            _uiState.value = _uiState.value.copy(linkMetrics = updated)
        }
    }
}
