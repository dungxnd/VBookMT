package vn.dungxnd.vbookmt.service

import android.util.Log
import com.vbookmt.core.state.BaseHostState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide status and log surface shared by the foreground service, the HTTP layer and the UI.
 * Inherits state flows and properties from [BaseHostState] in `core`.
 */
object ServiceState : BaseHostState() {

    private const val TAG = "TranslationService"

    private val timeFormatter = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    }

    override fun log(message: String) {
        val time = timeFormatter.get()?.format(Date()).orEmpty()
        val formatted = "[$time] $message"
        Log.d(TAG, formatted)
        super.log(formatted)
    }

    fun clearLogs() {
        super.clearLogs("[Init] Nhật ký đã được làm mới.")
    }
}
