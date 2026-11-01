package com.vbookmt.core.state

import com.vbookmt.core.model.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared state container and log buffer for host runners (Android service & Windows desktop).
 */
open class BaseHostState(
    defaultModelId: String = ModelRegistry.HACHIMI_60_QT.id,
    private val maxLogLines: Int = 150
) {
    enum class EngineState {
        STOPPED,
        LOADING,
        READY,
        NOT_FOUND,
        ERROR
    }

    protected val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    protected val _activeModel = MutableStateFlow(defaultModelId)
    val activeModel: StateFlow<String> = _activeModel.asStateFlow()

    protected val _requestCount = MutableStateFlow(0)
    val requestCount: StateFlow<Int> = _requestCount.asStateFlow()

    val inFlight = AtomicInteger(0)

    protected val _inFlightCount = MutableStateFlow(0)
    val inFlightCount: StateFlow<Int> = _inFlightCount.asStateFlow()

    protected val _engineState = MutableStateFlow(EngineState.STOPPED)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    protected val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    protected val _debugLogs = MutableStateFlow(listOf("[Init] VBookMT logger initialized."))
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    open fun setRunning(running: Boolean) {
        _isRunning.value = running
        if (!running) {
            _engineState.value = EngineState.STOPPED
            inFlight.set(0)
            _inFlightCount.value = 0
        }
    }

    open fun setActiveModel(modelId: String) {
        _activeModel.value = modelId
    }

    open fun setEngineState(state: EngineState) {
        _engineState.value = state
    }

    open fun noteRequestStarted() {
        _requestCount.update { it + 1 }
    }

    open fun changeInFlight(delta: Int): Int {
        val current = inFlight.addAndGet(delta)
        _inFlightCount.value = current.coerceAtLeast(0)
        return current
    }

    open fun setLastLatency(ms: Long) {
        _lastLatencyMs.value = ms
    }

    open fun log(message: String) {
        _debugLogs.update { (it + message).takeLast(maxLogLines) }
    }

    open fun clearLogs(initialMessage: String = "[Init] Nhật ký đã được làm mới.") {
        _debugLogs.value = listOf(initialMessage)
    }
}
