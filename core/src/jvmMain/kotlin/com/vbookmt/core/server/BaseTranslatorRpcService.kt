package com.vbookmt.core.server

import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.wire.v1.ConfigResult
import com.vbookmt.wire.v1.GrpcWebDispatcher
import com.vbookmt.wire.v1.HealthReport
import com.vbookmt.wire.v1.TranslateConfig
import com.vbookmt.wire.v1.TranslateOutcome
import com.vbookmt.wire.v1.TranslatorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * Base implementation of [TranslatorService] consolidating RPC handlers across Android and Windows.
 *
 * Implements:
 *  - [translate]: input length & blank guards, serialised permit queue, timeout, accounting, outcomes.
 *  - [health]: comprehensive system, cache and process memory report.
 *  - [getConfig]: current runtime tuning parameters.
 *  - [setConfig]: configuration mutations and JSON feedback.
 */
abstract class BaseTranslatorRpcService(
    val hostName: String,
    val port: Int,
    val maxTranslateTimeoutMs: Long = 110_000L,
) : TranslatorService {

    abstract val activeModelId: String
    abstract val isEngineReady: Boolean
    abstract val activeThreads: Int
    abstract val maxBatchSize: Int
    abstract val providerLabel: String
    abstract var chunkChars: Int
    abstract var cacheEnabled: Boolean

    abstract fun cacheStats(): TranslationCache.Stats
    abstract fun diskCacheBytes(): Long
    abstract fun restoredCacheEntries(): Int
    abstract fun requestCount(): Int
    abstract fun inFlightCount(): Int

    open fun pssMb(): Int = -1
    open fun jvmHeapMb(): Int {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt()
    }

    abstract suspend fun ensureReady(): Boolean
    abstract suspend fun executeTranslate(text: String): String

    open fun onNoteRequestStarted() {}
    open fun onInFlightChanged(delta: Int) {}
    open fun onRecordLatency(latencyMs: Long) {}
    open fun log(message: String) {}

    open fun onApplyBatchSize(batchSize: Int) {}
    open suspend fun onReloadThreads(threads: Int) {}
    open fun onClearCache() {}

    private val executionGate = Semaphore(1)

    override suspend fun translate(text: String): TranslateOutcome {
        if (text.isBlank()) return TranslateOutcome.Success("", 0)
        if (text.length > GrpcWebDispatcher.MAX_SOURCE_CHARS) {
            return TranslateOutcome.TooLong(text.length, GrpcWebDispatcher.MAX_SOURCE_CHARS)
        }

        onNoteRequestStarted()
        onInFlightChanged(1)
        try {
            log("Nhận yêu cầu dịch: ${text.length} ký tự (model=$activeModelId)")
            if (!ensureReady()) {
                return TranslateOutcome.ModelUnavailable(
                    "Mô hình chưa sẵn sàng. Hãy mở VBookMT và nạp mô hình trước khi dịch."
                )
            }

            var output = ""
            var elapsed = 0L
            try {
                executionGate.withPermit {
                    val started = System.currentTimeMillis()
                    output = withTimeout(maxTranslateTimeoutMs) {
                        executeTranslate(text)
                    }
                    elapsed = System.currentTimeMillis() - started
                }
            } catch (e: TimeoutCancellationException) {
                log("Timeout dịch sau ${maxTranslateTimeoutMs}ms")
                return TranslateOutcome.DeadlineExceeded(maxTranslateTimeoutMs)
            }

            onRecordLatency(elapsed)
            log("Dịch xong ${text.length} -> ${output.length} ký tự trong ${elapsed}ms")
            return TranslateOutcome.Success(output, elapsed.toInt())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("LỖI dịch: ${e.message}")
            return TranslateOutcome.Failure(e.message ?: "Lỗi dịch không xác định")
        } finally {
            onInFlightChanged(-1)
        }
    }

    override suspend fun health(): HealthReport {
        val cache = cacheStats()
        return HealthReport(
            status = "healthy",
            host = hostName,
            engine = "ONNX",
            port = port,
            model = activeModelId,
            ready = isEngineReady,
            requests = requestCount(),
            inflight = inFlightCount(),
            threads = activeThreads,
            maxBatch = maxBatchSize,
            batchType = "examples",
            computeType = providerLabel,
            chunkChars = chunkChars,
            cacheEnabled = cacheEnabled,
            cacheEntries = cache.entries,
            cacheChars = cache.payloadChars.toInt(),
            cacheHits = cache.hits.toInt(),
            cacheMisses = cache.misses.toInt(),
            cacheHitPct = cache.hitRatePercent.toDouble(),
            cacheDiskBytes = diskCacheBytes().toInt(),
            cacheDiskRestored = restoredCacheEntries(),
            pssMb = pssMb(),
            jvmHeapMb = jvmHeapMb(),
        )
    }

    override suspend fun getConfig(): TranslateConfig = TranslateConfig(
        maxBatch = maxBatchSize,
        batchType = "examples",
        threads = activeThreads,
        chunkChars = chunkChars,
        computeType = providerLabel,
        clearCache = false,
        cacheEnabled = cacheEnabled,
    )

    override suspend fun setConfig(patch: TranslateConfig): ConfigResult {
        patch.maxBatch?.let { onApplyBatchSize(it) }
        patch.chunkChars?.let { chunkChars = it }
        patch.clearCache?.let { if (it) onClearCache() }
        patch.cacheEnabled?.let { cacheEnabled = it }

        if (patch.threads != null && patch.threads != activeThreads) {
            onReloadThreads(patch.threads)
        }

        val applied = buildString {
            append("{\"threads\":$activeThreads,")
            append("\"max_batch\":$maxBatchSize,")
            append("\"batch_type\":\"examples\",")
            append("\"compute_type\":\"$providerLabel\",")
            append("\"chunk_chars\":$chunkChars,")
            append("\"cache_enabled\":$cacheEnabled,")
            append("\"ready\":$isEngineReady}")
        }
        log("CẤU HÌNH: $applied")
        return ConfigResult(appliedJson = applied)
    }
}
