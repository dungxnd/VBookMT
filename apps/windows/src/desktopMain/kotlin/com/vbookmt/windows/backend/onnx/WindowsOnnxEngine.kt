package com.vbookmt.windows.backend.onnx

import com.vbookmt.core.onnx.DelegateOnnxTokenizer
import com.vbookmt.core.onnx.ort.OnnxProvider
import com.vbookmt.core.onnx.ort.OrtEngineHost
import com.vbookmt.windows.backend.WindowsNativeTokenizer
import com.vbookmt.windows.backend.WindowsState
import java.io.File

object WindowsOnnxEngine {

    const val DEFAULT_MAX_BATCH_SIZE = OrtEngineHost.DEFAULT_MAX_BATCH_SIZE
    const val MAX_STEPS = OrtEngineHost.MAX_STEPS

    private val host = OrtEngineHost()

    val maxBatchSize: Int get() = host.maxBatchSize
    val providerLabel: String get() = host.providerLabel
    val activeThreads: Int get() = host.activeThreads

    fun setThreads(value: Int) = host.setThreads(value)
    fun isReady(): Boolean = host.isReady()
    fun getActiveModelPath(): String? = host.getActiveModelPath()
    fun setBatchSize(value: Int) = host.setBatchSize(value)

    @Synchronized
    fun init(modelDir: File, provider: OnnxProvider? = null): Boolean {
        if (!WindowsNativeTokenizer.load(modelDir.absolutePath)) {
            WindowsState.log("ONNX: không nạp được tokenizer (thiếu .spm hoặc thư viện native)")
            return false
        }

        val tokenizer = DelegateOnnxTokenizer(
            encodeFn = { WindowsNativeTokenizer.encode(it) },
            decodeFn = { WindowsNativeTokenizer.decode(it) },
        )
        val threads = host.resolveThreads(WindowsNativeTokenizer.recommendedThreadCount())

        return host.init(
            modelDir = modelDir,
            tokenizer = tokenizer,
            threads = threads,
            provider = host.resolveProvider(provider ?: OnnxProvider.defaultForPlatform()),
            onLog = { WindowsState.log(it) }
        )
    }

    fun closeQuietly() = host.closeQuietly()

    fun rawTranslateBatch(pieces: List<String>, numBeams: Int = 1): List<String>? =
        host.rawTranslateBatch(pieces, numBeams) { WindowsState.log(it) }

    fun benchmarkMs(pieces: List<String>, numBeams: Int = 1): Long =
        host.benchmarkMs(pieces) { WindowsState.log(it) }

    fun tokenCount(text: String): Int = WindowsNativeTokenizer.tokenCount(text)
}
