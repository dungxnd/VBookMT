package vn.dungxnd.vbookmt.util

import android.content.Context
import android.util.Log
import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.core.tuner.BenchmarkProse
import com.vbookmt.core.tuner.TuningEngine
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator

/**
 * Android performance tuner delegating benchmark math and presentation to [TuningEngine].
 */
object PerformanceTuner {

    private const val TAG = "PerformanceTuner"

    private const val PREFS_NAME = "vbookmt_perf_prefs"
    private const val KEY_THREADS = "threads"
    private const val KEY_COMPUTE = "compute_type"
    private const val KEY_BATCH = "max_batch"
    private const val KEY_CHARS_PER_SEC = "chars_per_sec"
    private const val KEY_MEASURED = "measured"

    const val DEFAULT_MAX_BATCH = TuningEngine.DEFAULT_MAX_BATCH

    typealias Tuning = TuningEngine.TuningResult

    sealed interface TuneResult {
        data class Success(val tuning: Tuning) : TuneResult
        data class Failure(val reason: String) : TuneResult
    }

    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    fun bigCoreCount(): Int {
        val reported = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until reported).mapNotNull { cpu ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLong()
            }.getOrNull()
        }
        if (freqs.isEmpty()) return (reported / 2).coerceAtLeast(1)

        val fastest = freqs.max()
        return freqs.count { it >= (fastest * 8) / 10 }.coerceAtLeast(1)
    }

    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores <= 1) return 1
        return bigCoreCount().coerceIn(2, 6).coerceAtMost(cores)
    }

    fun loadSaved(context: Context): Tuning? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val threads = prefs.getInt(KEY_THREADS, 0)
        if (threads <= 0) return null
        return Tuning(
            threads = threads,
            computeType = prefs.getString(KEY_COMPUTE, "").orEmpty(),
            maxBatch = prefs.getInt(KEY_BATCH, DEFAULT_MAX_BATCH),
            charsPerSec = prefs.getInt(KEY_CHARS_PER_SEC, 0),
            measured = prefs.getBoolean(KEY_MEASURED, false),
        )
    }

    fun save(context: Context, tuning: Tuning) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THREADS, tuning.threads)
            .putString(KEY_COMPUTE, tuning.computeType)
            .putInt(KEY_BATCH, tuning.maxBatch)
            .putInt(KEY_CHARS_PER_SEC, tuning.charsPerSec)
            .putBoolean(KEY_MEASURED, tuning.measured)
            .apply()
    }

    fun current(context: Context): Tuning =
        loadSaved(context) ?: Tuning(
            threads = recommendedThreads(),
            computeType = "",
            maxBatch = DEFAULT_MAX_BATCH,
        )

    fun applySaved(context: Context, modelDir: File): Boolean {
        val tuning = loadSaved(context) ?: return false
        OnnxTranslator.setBatchSize(tuning.maxBatch)
        OnnxTranslator.setThreads(tuning.threads)
        if (tuning.computeType.isNotBlank()) {
            OnnxTranslator.setProviderOverride(tuning.computeType)
        }
        return OnnxTranslator.init(modelDir)
    }

    suspend fun autoTune(context: Context, modelDir: File): TuneResult {
        if (!modelDir.isDirectory) {
            return TuneResult.Failure("Chưa tải mô hình (${modelDir.name}) — tải xong rồi đo lại.")
        }

        val wasReady = OnnxTranslator.isReady()
        val previousThreads = OnnxTranslator.activeThreads
        val previousBatch = OnnxTranslator.maxBatchSize

        if (!OnnxTranslator.init(modelDir)) {
            Log.w(TAG, "autoTune: engine not available for ${modelDir.absolutePath}")
            return TuneResult.Failure("Không nạp được mô hình để đo.")
        }

        val threads = candidateThreads()
        val outcome = TuningEngine.sweepThreads(
            candidates = threads,
            paragraphs = BenchmarkProse.PARAGRAPHS.take(DEFAULT_MAX_BATCH),
            batchSize = DEFAULT_MAX_BATCH,
            computeType = "",
            initCandidate = { threadCount ->
                OnnxTranslator.setThreads(threadCount)
                OnnxTranslator.init(modelDir)
            },
            benchmarkFn = { batch ->
                OnnxTranslator.benchmarkMs(batch)
            },
            onProgress = { done, total ->
                _progress.value = done to total
            },
            onLog = { Log.i(TAG, it) }
        )

        return when (outcome) {
            is TuningEngine.SweepOutcome.Success -> {
                val winner = outcome.tuning
                OnnxTranslator.setThreads(winner.threads)
                OnnxTranslator.setBatchSize(winner.maxBatch)
                OnnxTranslator.init(modelDir)
                save(context, winner)
                _progress.value = 0 to 0
                Log.i(TAG, "autoTune picked ${winner.detailText} -> ${winner.speedText}")
                TuneResult.Success(winner)
            }
            is TuningEngine.SweepOutcome.Failure -> {
                OnnxTranslator.setThreads(previousThreads.takeIf { it > 0 } ?: 0)
                OnnxTranslator.setBatchSize(previousBatch)
                OnnxTranslator.init(modelDir)
                _progress.value = 0 to 0
                Log.w(TAG, "autoTune found no winner: ${outcome.reason}")
                TuneResult.Failure(outcome.reason)
            }
        }
    }

    private fun candidateThreads(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val big = bigCoreCount()
        return listOf(big, (big + 2), (big - 1), 4)
            .map { it.coerceIn(1, cores.coerceAtMost(8)) }
            .distinct()
    }

    fun cacheText(stats: TranslationCache.Stats): String? =
        TuningEngine.cacheSummaryText(stats)
}
