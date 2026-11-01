package com.vbookmt.core.tuner

import com.vbookmt.core.processor.TranslationCache
import kotlin.math.roundToInt

/**
 * Shared performance tuning engine and benchmark calculations for all platforms.
 */
object TuningEngine {

    const val DEFAULT_MAX_BATCH = 16
    const val CHARS_PER_CHAPTER = BenchmarkProse.CHARS_PER_CHAPTER

    /** A resolved, human-explainable configuration. */
    data class TuningResult(
        val threads: Int,
        val computeType: String = "",
        val maxBatch: Int = DEFAULT_MAX_BATCH,
        val charsPerSec: Int = 0,
        val measured: Boolean = false,
    ) {
        val speedCharsPerSec: Double get() = charsPerSec.toDouble()

        /** The only speed number a reader cares about. */
        val speedText: String
            get() = if (charsPerSec > 0) "≈ $charsPerSec ký tự/giây" else "chưa đo"

        /** Same speed, expressed as seconds per chapter. */
        val chapterText: String
            get() {
                if (charsPerSec <= 0) return "tối ưu tự động theo máy"
                val seconds = (CHARS_PER_CHAPTER.toDouble() / charsPerSec).coerceAtLeast(0.1)
                val rounded = (seconds * 10).roundToInt() / 10.0
                return "~$rounded giây cho chương $CHARS_PER_CHAPTER ký tự"
            }

        /** Technical summary line. */
        val detailText: String
            get() {
                val comp = if (computeType.isNotBlank()) computeType else "mặc định của model"
                return if (charsPerSec > 0) {
                    "Tối ưu: $threads luồng CPU, Batch $maxBatch, $comp, ~$charsPerSec ký tự/s"
                } else {
                    "$threads luồng • batch $maxBatch • tính toán $comp"
                }
            }
    }

    sealed interface SweepOutcome {
        data class Success(val tuning: TuningResult) : SweepOutcome
        data class Failure(val reason: String) : SweepOutcome
    }

    /**
     * Measures median throughput of [benchmarkFn] on [paragraphs] over [runs] repetitions.
     * Includes one warmup pass which is discarded.
     *
     * @return chars per second, or 0 if unmeasurable.
     */
    suspend fun measureThroughput(
        paragraphs: List<String>,
        runs: Int = 3,
        benchmarkFn: suspend (List<String>) -> Long,
    ): Int {
        val totalChars = paragraphs.sumOf { it.length }
        if (totalChars == 0) return 0

        // Warmup pass
        benchmarkFn(paragraphs)

        val samples = ArrayList<Long>(runs)
        repeat(runs) {
            val ms = benchmarkFn(paragraphs)
            if (ms > 0) samples.add(ms)
        }
        if (samples.isEmpty()) return 0

        samples.sort()
        val median = samples[samples.size / 2]
        if (median <= 0) return 0

        return ((totalChars * 1000L) / median).toInt()
    }

    /**
     * Executes a thread sweep across [candidates].
     */
    suspend fun sweepThreads(
        candidates: List<Int>,
        paragraphs: List<String> = BenchmarkProse.PARAGRAPHS,
        batchSize: Int = DEFAULT_MAX_BATCH,
        computeType: String = "",
        initCandidate: suspend (threads: Int) -> Boolean,
        benchmarkFn: suspend (List<String>) -> Long,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): SweepOutcome {
        val total = candidates.size
        if (total == 0) {
            return SweepOutcome.Failure("Không có cấu hình nào để đo.")
        }

        val results = ArrayList<TuningResult>(total)
        var done = 0
        onProgress?.invoke(0, total)

        try {
            for (threads in candidates) {
                done++
                onProgress?.invoke(done, total)
                if (!initCandidate(threads)) {
                    onLog?.invoke("Bỏ qua $threads luồng: không nạp được engine")
                    continue
                }

                val speed = measureThroughput(paragraphs, runs = 3, benchmarkFn = benchmarkFn)
                if (speed > 0) {
                    onLog?.invoke("threads=$threads -> $speed ký tự/s")
                    results.add(
                        TuningResult(
                            threads = threads,
                            computeType = computeType,
                            maxBatch = batchSize,
                            charsPerSec = speed,
                            measured = true,
                        )
                    )
                } else {
                    onLog?.invoke("candidate threads=$threads produced no usable timing")
                }
            }
        } finally {
            onProgress?.invoke(total, total)
        }

        val winner = results.maxByOrNull { it.charsPerSec }
            ?: return SweepOutcome.Failure("Đã thử $total cấu hình nhưng không đo được tốc độ.")

        return SweepOutcome.Success(winner)
    }

    /** Cache line for the UI, phrased as a benefit rather than a hit-rate metric. */
    fun cacheSummaryText(stats: TranslationCache.Stats): String? {
        if (stats.entries == 0) return null
        return if (stats.hits > 0) {
            "Đã nhớ ${stats.entries} đoạn — đọc lại tốn 0 giây (trúng ${stats.hitRatePercent}%)"
        } else {
            "Đã nhớ ${stats.entries} đoạn đã dịch, sẵn sàng cho lần đọc lại"
        }
    }
}
