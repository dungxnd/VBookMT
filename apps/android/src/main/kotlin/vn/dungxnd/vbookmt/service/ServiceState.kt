package vn.dungxnd.vbookmt.service

import android.util.Log
import com.vbookmt.core.model.ModelRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide status and log surface shared by the foreground service, the HTTP layer and the UI.
 *
 * This used to be the `TranslationService` companion object, which made the service file own three
 * unrelated things at once (lifecycle + shared state + logging) and forced every consumer - the
 * engine wrapper included - to depend on the Android `Service` class. Keeping it here means the
 * engine, the routes and the UI can all observe status without a Service reference.
 */
object ServiceState {

    private const val TAG = "TranslationService"

    /** Oldest entries are dropped past this many, so the debug card stays cheap to render. */
    private const val MAX_LOG_LINES = 150

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _activeModel = MutableStateFlow(ModelRegistry.HACHIMI_60_QT.id)
    val activeModel: StateFlow<String> = _activeModel.asStateFlow()

    private val _lastLatencyMs = MutableStateFlow(0L)
    val lastLatencyMs: StateFlow<Long> = _lastLatencyMs.asStateFlow()

    private val _requestCount = MutableStateFlow(0)
    val requestCount: StateFlow<Int> = _requestCount.asStateFlow()

    private val _debugLogs = MutableStateFlow(listOf("[Init] VBookMT Service logger initialized."))
    val debugLogs: StateFlow<List<String>> = _debugLogs.asStateFlow()

    // SimpleDateFormat is not thread-safe, and allocating one per log entry was measurable inside
    // the translation loop. One instance per thread is enough.
    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    fun log(message: String) {
        val time = timeFormatter.get()?.format(Date()).orEmpty()
        val formatted = "[$time] $message"
        Log.d(TAG, formatted)
        _debugLogs.value = (_debugLogs.value + formatted).takeLast(MAX_LOG_LINES)
    }

    fun clearLogs() {
        _debugLogs.value = listOf("[Init] Nhật ký đã được làm mới.")
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun setActiveModel(modelId: String) {
        _activeModel.value = modelId
    }

    fun noteRequestStarted() {
        _requestCount.value += 1
    }

    fun setLastLatency(millis: Long) {
        _lastLatencyMs.value = millis
    }
}
