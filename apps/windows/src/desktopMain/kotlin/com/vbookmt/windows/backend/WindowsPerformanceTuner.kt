package com.vbookmt.windows.backend

import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.core.tuner.BenchmarkProse
import com.vbookmt.core.tuner.TuningEngine
import com.vbookmt.windows.backend.onnx.WindowsOnnxEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Windows performance sweep delegating to [TuningEngine] in `core`.
 */
object WindowsPerformanceTuner {

    typealias Tuning = TuningEngine.TuningResult

    private val _tuning = MutableStateFlow<Tuning?>(null)
    val tuning: StateFlow<Tuning?> = _tuning.asStateFlow()

    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val _isTuning = MutableStateFlow(false)
    val isTuning: StateFlow<Boolean> = _isTuning.asStateFlow()

    suspend fun runBenchmark(): Tuning = withContext(Dispatchers.IO) {
        _isTuning.value = true
        WindowsState.log("BẮT ĐẦU ĐO HIỆU NĂNG CHUẨN (BATCH 16-32, ~1200 KÝ TỰ)...")

        val cores = Runtime.getRuntime().availableProcessors()
        val threadCandidates = listOf(2, 4, cores.coerceIn(4, 16), (cores / 2).coerceIn(2, 8))
            .distinct().sorted()

        val outcome = TuningEngine.sweepThreads(
            candidates = threadCandidates,
            paragraphs = BenchmarkProse.PARAGRAPHS,
            batchSize = 32,
            computeType = "int8",
            initCandidate = { threads ->
                WindowsTranslator.setThreads(threads)
                WindowsTranslator.setBatchSize(32)
                WindowsTranslator.init(threads = threads, silent = true)
            },
            benchmarkFn = { batch ->
                WindowsOnnxEngine.benchmarkMs(batch)
            },
            onProgress = { done, total ->
                _progress.value = done to total
            },
            onLog = { WindowsState.log(it) }
        )

        val winner = when (outcome) {
            is TuningEngine.SweepOutcome.Success -> outcome.tuning
            is TuningEngine.SweepOutcome.Failure -> {
                WindowsState.log("Đo thất bại: ${outcome.reason}")
                Tuning(threads = 4, maxBatch = 32, computeType = "int8", charsPerSec = 0)
            }
        }

        if (WindowsTranslator.activeThreads != winner.threads) {
            WindowsTranslator.setThreads(winner.threads)
            WindowsTranslator.init(threads = winner.threads, silent = true)
        }



        WindowsState.forgetCacheEntries(
            BenchmarkProse.PARAGRAPHS.map {
                TranslationCache.key(WindowsOnnxEngine.getActiveModelPath() ?: "-", 1, it)
            }
        )

        _tuning.value = winner
        _isTuning.value = false
        WindowsState.log("TỐI ƯU HOÀN TẤT: ${winner.detailText}")
        winner
    }
}
