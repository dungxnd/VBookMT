package vn.dungxnd.vbookmt.util

import android.content.Context
import android.util.Log
import com.vbookmt.core.processor.TranslationCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.dungxnd.vbookmt.service.ChunkTranslator
import vn.dungxnd.vbookmt.service.Ct2Translator

/**
 * Picks the fastest safe CTranslate2 configuration *for this phone*, so the reader never has to
 * think about threads, batch sizes or quantisation formats.
 *
 * Two levels, deliberately:
 *  1. [recommendedThreads] - free, instant, no model reload. Counts the big cores by reading each
 *     CPU's max frequency instead of "half the reported cores", because a big.LITTLE phone reports
 *     all eight cores but schedules intra-op threads onto the little ones too, which is slower
 *     *and* hotter. This is what the service uses out of the box, with no user action.
 *  2. [autoTune] - the single optional button in the UI. Reloads the model with a few candidate
 *     settings, measures real throughput on bundled sample prose, then remembers the winner in
 *     SharedPreferences. ~8-12 s one time, then free forever.
 *
 * Measured facts this encodes (Ryzen 7 5800H desktop, 4 threads, HachimiMT-60 int8_float32):
 *  - batch 16 is the throughput knee (4→1555, 8→1748, 16→2293, 32→2270 chars/s), so it is fixed
 *    rather than searched;
 *  - the service already decodes greedily, and beam 2 costs +60% for no pronoun gain;
 *  - `int8` vs `int8_float32` is a wash on x86 without VNNI (203 vs 205 ms, identical output) but
 *    can win on ARMv8.2 with dotprod/i8mm - which is exactly why it must be measured on the device
 *    instead of hardcoded.
 */
object PerformanceTuner {

    private const val TAG = "PerformanceTuner"

    private const val PREFS_NAME = "vbookmt_perf_prefs"
    private const val KEY_THREADS = "threads"
    private const val KEY_COMPUTE = "compute_type"
    private const val KEY_BATCH = "max_batch"
    private const val KEY_CHARS_PER_SEC = "chars_per_sec"
    private const val KEY_MEASURED = "measured"

    /** Not searched: 16 is the measured knee on x86 and ARM. */
    const val DEFAULT_MAX_BATCH = 16

    /** Chapter length used for the human-readable "x giây/chương" estimate. */
    private const val CHARS_PER_CHAPTER = 3000

    /**
     * ~230 characters of ordinary xianxia prose: long enough to time reliably, short enough that a
     * full sweep stays around ten seconds on a mid-range phone.
     */
    private val SAMPLE = listOf(
        "他抬头看向远处的山门，山门前已有一众年轻弟子排队等候。山风掠过石阶，卷起几片枯叶。",
        "“你休要胡言乱语！”师姐冷哼一声，“我与师尊之事，岂容你随意置喙？”",
        "少年握紧手中长刀，刀锋映出天边残阳。他知道，今日若是后退半步，便再无颜面立于宗门之前。",
        "大殿之内烛火摇曳，众长老分坐两侧。宗主沉默良久，终于抬手指向殿外：“让他进来。”",
        "山谷深处传来一声长啸，惊起满林飞鸟。那声音中带着几分怒意，又似藏着说不出的悲凉。",
    )

    private val SAMPLE_CHARS = SAMPLE.sumOf { it.length }

    /** A resolved, human-explainable configuration. */
    data class Tuning(
        val threads: Int,
        val computeType: String,
        val maxBatch: Int = DEFAULT_MAX_BATCH,
        val charsPerSec: Int = 0,
        val measured: Boolean = false,
    ) {
        /** The only speed number a reader cares about. */
        val speedText: String
            get() = if (charsPerSec > 0) "≈ $charsPerSec ký tự/giây" else "chưa đo"

        /** Same speed, expressed the way a reader thinks about it. */
        val chapterText: String
            get() {
                if (charsPerSec <= 0) return "tối ưu tự động theo máy"
                val seconds = (CHARS_PER_CHAPTER.toDouble() / charsPerSec).coerceAtLeast(0.1)
                return "~${String.format(Locale.US, "%.1f", seconds)} giây cho chương $CHARS_PER_CHAPTER ký tự"
            }

        /** Technical line, shown only behind the "Chi tiết" toggle. */
        val detailText: String
            get() = "$threads luồng • batch $maxBatch • tính toán " +
                computeType.ifEmpty { "mặc định của model" }
    }

    private val _progress = MutableStateFlow(0 to 0)

    /** (done, total) while [autoTune] runs, for the UI progress line. */
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    // -----------------------------------------------------------------------------------------
    // Device facts + defaults (no measurement needed)
    // -----------------------------------------------------------------------------------------

    /**
     * Number of "big" cores: cores whose max frequency is within 20% of the fastest one.
     * Falls back to half the reported cores when sysfs is unreadable (some OEM ROMs restrict it).
     */
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

    /**
     * Thread count used when the user never presses the tune button: the big cores, clamped to
     * 2..6 and never above the reported core count. More threads oversubscribe into little cores
     * and lose throughput; a single thread leaves most of the phone idle while decoding.
     */
    fun recommendedThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if (cores <= 1) return 1
        return bigCoreCount().coerceIn(2, 6).coerceAtMost(cores)
    }

    // -----------------------------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------------------------

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

    /** Current settings as the UI should describe them (saved values, else the auto default). */
    fun current(context: Context): Tuning =
        loadSaved(context) ?: Tuning(
            threads = recommendedThreads(),
            computeType = Ct2Translator.activeComputeType,
            maxBatch = DEFAULT_MAX_BATCH,
        )

    /**
     * Loads the engine with the saved settings, if any. Called on every model load so a tuned phone
     * stays tuned across restarts without the user touching anything.
     *
     * @return true when a saved tuning was applied; false lets the caller use its own defaults.
     */
    fun applySaved(context: Context, modelPath: String): Boolean {
        val tuning = loadSaved(context) ?: return false
        Ct2Translator.setBatchSize(tuning.maxBatch)
        return Ct2Translator.init(modelPath, tuning.threads, tuning.computeType)
    }

    // -----------------------------------------------------------------------------------------
    // The one-button optimiser
    // -----------------------------------------------------------------------------------------

    /**
     * Measures a few candidate configurations on this device and persists the fastest.
     *
     * Call from an IO dispatcher: it reloads the model several times (~8-12 s). The engine is left
     * loaded with the winning configuration, so the service keeps serving immediately afterwards.
     * Returns null when nothing could be measured (model missing / engine unavailable).
     */
    fun autoTune(context: Context, modelPath: String): Tuning? {
        if (!Ct2Translator.isReady() && !Ct2Translator.init(modelPath)) {
            Log.w(TAG, "autoTune: engine not available, skipping")
            return null
        }

        val threads = candidateThreads()
        // "" = keep the model's own compute type; "int8" is the only alternative worth measuring,
        // since float32 costs ~46% more time for ~0.7% output-similarity gain.
        val computes = listOf("", "int8")
        val total = threads.size * computes.size
        var done = 0
        _progress.value = 0 to total

        val results = ArrayList<Tuning>(total)
        for (compute in computes) {
            for (threadCount in threads) {
                done++
                if (Ct2Translator.init(modelPath, threadCount, compute)) {
                    // Two passes, best wins: the first is a warmup that also faults pages in.
                    Ct2Translator.benchmarkMs(SAMPLE)
                    val millis = Ct2Translator.benchmarkMs(SAMPLE)
                    if (millis > 0) {
                        val charsPerSec = ((SAMPLE_CHARS * 1000L) / millis).toInt()
                        Log.i(
                            TAG,
                            "threads=$threadCount compute='${compute.ifEmpty { "default" }}' " +
                                "-> $millis ms ($charsPerSec chars/s)"
                        )
                        results.add(
                            Tuning(threadCount, compute, DEFAULT_MAX_BATCH, charsPerSec, measured = true)
                        )
                    }
                } else {
                    Log.i(TAG, "candidate threads=$threadCount compute='$compute' rejected")
                }
                _progress.value = done to total
            }
        }

        // Prefer the model's own compute type: it is the vendor-tuned default and the safest choice
        // for output quality. Take `int8` only when it is clearly (>5%) faster, because on x86
        // without VNNI the two are indistinguishable while on ARM the int8 kernel can really win.
        val bestDefault = results.filter { it.computeType.isEmpty() }.maxByOrNull { it.charsPerSec }
        val bestInt8 = results.filter { it.computeType.isNotEmpty() }.maxByOrNull { it.charsPerSec }
        val winner = when {
            bestDefault == null -> bestInt8
            bestInt8 == null -> bestDefault
            bestInt8.charsPerSec > bestDefault.charsPerSec * 105 / 100 -> bestInt8
            else -> bestDefault
        } ?: return null

        // Leave the engine in the winning state and remember it for every future launch.
        Ct2Translator.init(modelPath, winner.threads, winner.computeType)
        Ct2Translator.setBatchSize(winner.maxBatch)
        ChunkTranslator.clearCache()
        save(context, winner)
        Log.i(TAG, "autoTune picked ${winner.detailText} -> ${winner.speedText}")
        return winner
    }

    /**
     * Thread counts worth measuring: the big-core estimate plus one step either side, bounded by
     * the reported core count so a 4-core device never benchmarks an 8-thread config.
     */
    private fun candidateThreads(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val big = bigCoreCount()
        return listOf(big, (big + 2), (big - 1))
            .map { it.coerceIn(1, cores.coerceAtMost(8)) }
            .distinct()
    }

    /** Cache line for the UI, phrased as a benefit rather than a hit-rate metric. */
    fun cacheText(stats: TranslationCache.Stats): String? {
        if (stats.entries == 0) return null
        return if (stats.hits > 0) {
            "Đã nhớ ${stats.entries} đoạn — đọc lại tốn 0 giây (trúng ${stats.hitRatePercent}%)"
        } else {
            "Đã nhớ ${stats.entries} đoạn đã dịch, sẵn sàng cho lần đọc lại"
        }
    }
}
