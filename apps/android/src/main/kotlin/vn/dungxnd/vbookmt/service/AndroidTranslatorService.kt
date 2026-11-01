package vn.dungxnd.vbookmt.service

import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.core.server.BaseTranslatorRpcService
import com.vbookmt.wire.v1.EnergySnapshot
import com.vbookmt.wire.v1.ResetResult
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator
import vn.dungxnd.vbookmt.util.EnergyHarness
import vn.dungxnd.vbookmt.util.PerformanceTuner

/**
 * Android's implementation of the shared RPC contract delegating to [BaseTranslatorRpcService].
 */
internal class AndroidTranslatorService(
    port: Int,
    private val inFlight: AtomicInteger,
    private val ensureModelReady: suspend () -> Boolean,
) : BaseTranslatorRpcService(
    hostName = "android",
    port = port,
    maxTranslateTimeoutMs = ServiceContract.TRANSLATE_TIMEOUT_MS,
) {
    override val activeModelId: String get() = ServiceState.activeModel.value
    override val isEngineReady: Boolean get() = Engines.isActiveReady()
    override val activeThreads: Int get() = NativeTokenizer.recommendedThreadCount()
    override val maxBatchSize: Int get() = Engines.activeBatchSize()
    override val providerLabel: String get() = Engines.activeDetail()
    override var chunkChars: Int
        get() = ChapterTranslator.chunkChars
        set(value) { ChapterTranslator.setChunkChars(value) }
    override var cacheEnabled: Boolean
        get() = ChunkTranslator.cacheEnabled
        set(value) { ChunkTranslator.cacheEnabled = value }

    override fun cacheStats(): TranslationCache.Stats = ChunkTranslator.cacheStats()
    override fun diskCacheBytes(): Long = CacheStore.diskBytes()
    override fun restoredCacheEntries(): Int = CacheStore.restoredEntries()
    override fun requestCount(): Int = ServiceState.requestCount.value
    override fun inFlightCount(): Int = inFlight.get()

    override fun pssMb(): Int = Diagnostics.processPssMb()
    override fun jvmHeapMb(): Int = Diagnostics.jvmHeapMb().toInt()

    override suspend fun ensureReady(): Boolean = ensureModelReady()

    override suspend fun executeTranslate(text: String): String =
        ChapterTranslator.translate(text)

    override fun onNoteRequestStarted() {
        ServiceState.noteRequestStarted()
    }

    override fun onInFlightChanged(delta: Int) {
        inFlight.addAndGet(delta)
    }

    override fun onRecordLatency(latencyMs: Long) {
        ServiceState.setLastLatency(latencyMs)
    }

    override fun log(message: String) {
        ServiceState.log(message)
    }

    override fun onApplyBatchSize(batchSize: Int) {
        OnnxTranslator.setBatchSize(batchSize)
    }

    override suspend fun onReloadThreads(threads: Int) {
        val path = OnnxTranslator.getActiveModelPath()
        if (path == null) {
            ServiceState.log("CẤU HÌNH: không đổi được threads, model chưa nạp")
        } else {
            ServiceState.log("CẤU HÌNH: nạp lại model (threads=$threads)")
            OnnxTranslator.setThreads(threads)
            OnnxTranslator.init(File(path))
            runCatching {
                val context = ServiceContextHolder.require()
                PerformanceTuner.save(
                    context,
                    PerformanceTuner.Tuning(
                        threads = OnnxTranslator.activeThreads,
                        computeType = "",
                        maxBatch = OnnxTranslator.maxBatchSize,
                    )
                )
            }
        }
    }

    override fun onClearCache() {
        ChunkTranslator.clearCache()
        ServiceState.log("CẤU HÌNH: đã xoá cache bản dịch")
    }

    override suspend fun getEnergy(): EnergySnapshot = EnergySnapshot(recordsJsonl = EnergyHarness.recordsJsonl())

    override suspend fun resetEnergy(): ResetResult {
        EnergyHarness.reset()
        return ResetResult(reset = true)
    }
}
