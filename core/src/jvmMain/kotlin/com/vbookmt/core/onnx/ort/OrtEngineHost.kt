package com.vbookmt.core.onnx.ort

import com.vbookmt.core.onnx.OnnxDecodeEngine
import com.vbookmt.core.onnx.OnnxTokenizer
import java.io.File

/**
 * Shared lifecycle and execution host for ONNX Runtime inference sessions across JVM targets.
 */
class OrtEngineHost {

    companion object {
        const val DEFAULT_MAX_BATCH_SIZE = 16
        const val MAX_STEPS = 448
    }

    @Volatile
    var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
        private set

    @Volatile
    private var engine: OnnxDecodeEngine? = null

    @Volatile
    private var loaded: OrtOnnxModel? = null

    @Volatile
    private var activeModelPath: String? = null

    @Volatile
    var providerLabel: String = "-"
        private set

    @Volatile
    var activeThreads: Int = 0
        private set

    @Volatile
    var threadsOverride: Int = 0

    fun setThreads(value: Int) {
        threadsOverride = if (value <= 0) 0 else value.coerceIn(1, 32)
    }

    @Volatile
    var providerOverride: OnnxProvider? = null

    fun setProviderOverride(id: String) {
        providerOverride = OnnxProvider.parse(id)
    }

    fun resolveThreads(defaultThreads: Int, markerDir: File? = null): Int {
        if (threadsOverride > 0) return threadsOverride
        val fileOverride = markerDir?.let { dir ->
            runCatching {
                val marker = File(dir, "onnx-threads")
                if (marker.isFile) marker.readText().trim().toIntOrNull() else null
            }.getOrNull()
        } ?: 0
        return if (fileOverride > 0) fileOverride else defaultThreads
    }

    fun resolveProvider(defaultProvider: OnnxProvider = OnnxProvider.defaultForPlatform(), markerDir: File? = null): OnnxProvider {
        val fromProperty = runCatching {
            System.getProperty("vbookmt.onnx.provider").orEmpty()
        }.getOrDefault("")
        if (fromProperty.isNotBlank()) return OnnxProvider.parse(fromProperty)

        val fileOverride = markerDir?.let { dir ->
            runCatching {
                val marker = File(dir, "onnx-provider")
                if (marker.isFile) {
                    val id = marker.readText().trim()
                    if (id.isNotEmpty()) OnnxProvider.parse(id) else null
                } else null
            }.getOrNull()
        }
        return fileOverride ?: providerOverride ?: defaultProvider
    }

    fun isReady(): Boolean = engine != null

    fun getActiveModelPath(): String? = activeModelPath

    fun setBatchSize(value: Int) {
        maxBatchSize = value.coerceIn(1, 64)
    }

    @Synchronized
    fun init(
        modelDir: File,
        tokenizer: OnnxTokenizer,
        threads: Int,
        provider: OnnxProvider,
        onLog: (String) -> Unit = {}
    ): Boolean {
        closeQuietly()

        return try {
            val model = OrtOnnxModel.load(
                modelDir = modelDir.toPath(),
                tokenizer = tokenizer,
                threads = threads,
                provider = provider,
            )
            loaded = model
            engine = OnnxDecodeEngine(model.model)
            activeModelPath = modelDir.absolutePath
            activeThreads = threads
            providerLabel = model.provider.toString()
            if (model.provider.fellBack) {
                onLog("ONNX: ${model.provider}")
            } else {
                onLog("ONNX sẵn sàng (${model.provider})")
            }
            true
        } catch (e: Throwable) {
            onLog("LỖI nạp ONNX: ${e.message}")
            closeQuietly()
            false
        }
    }

    @Synchronized
    fun closeQuietly() {
        engine = null
        activeModelPath = null
        activeThreads = 0
        providerLabel = "-"
        runCatching { loaded?.close() }
        loaded = null
    }

    @Synchronized
    fun rawTranslateBatch(
        pieces: List<String>,
        numBeams: Int = 1,
        onLog: (String) -> Unit = {}
    ): List<String>? {
        val active = engine
        if (active == null) {
            onLog("ONNX translate thất bại: engine chưa được nạp")
            return null
        }
        if (pieces.isEmpty()) return null
        if (numBeams > 1) {
            onLog("ONNX: chỉ hỗ trợ greedy (numBeams=$numBeams bị bỏ qua)")
        }
        return try {
            active.translate(pieces, maxSteps = MAX_STEPS, batchSize = maxBatchSize)
                .takeIf { it.size == pieces.size }
        } catch (e: Throwable) {
            onLog("ONNX translate thất bại: ${e::class.simpleName}: ${e.message ?: e.toString()}")
            null
        }
    }

    @Synchronized
    fun benchmarkMs(
        pieces: List<String>,
        onLog: (String) -> Unit = {}
    ): Long {
        val active = engine
        if (active == null) {
            onLog("benchmarkMs thất bại: engine chưa sẵn sàng (engine == null)")
            return -1
        }
        if (pieces.isEmpty()) {
            onLog("benchmarkMs thất bại: danh sách mẫu rỗng")
            return -1
        }
        return try {
            val startedNs = System.nanoTime()
            val out = active.translate(pieces, maxSteps = MAX_STEPS, batchSize = maxBatchSize)
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            if (out.size == pieces.size) {
                elapsedMs
            } else {
                onLog("benchmarkMs thất bại: kết quả trả về ${out.size}/${pieces.size} đoạn")
                -1
            }
        } catch (e: Throwable) {
            onLog("benchmarkMs thất bại: ${e::class.simpleName}: ${e.message ?: e.toString()}")
            -1
        }
    }
}
