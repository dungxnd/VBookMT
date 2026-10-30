package vn.dungxnd.vbookmt.service

import android.util.Log

/**
 * Thread-safe wrapper around the native CTranslate2 engine (`ct2_jni.cpp`).
 *
 * Responsibilities are deliberately narrow: load the model, expose raw batch translation, count
 * tokens and report what was actually loaded. Caching, script detection and the Sino-Vietnamese
 * fallback live in [ChunkTranslator]; chapter layout lives in [ChapterTranslator]; both are free to
 * change without touching the JNI contract.
 *
 * Every native entry point is `@Synchronized`: CTranslate2's `Translator` is not re-entrant, and its
 * own replica pool would otherwise interleave two batches on the same buffers.
 */
object Ct2Translator {

    private const val TAG = "Ct2Translator"

    /** MarianMT source *and* target position tables are 512 deep; CT2 throws past that. */
    const val MODEL_POSITION_LIMIT = 512

    /**
     * Working source budget, comfortably under [MODEL_POSITION_LIMIT].
     *
     * Measured against this model's source.spm: normal Chinese prose costs ~0.62 tokens/char, but
     * rare glyphs (龘靐齉齾…) cost ~2.0 because they fall back to byte tokens, so a character-based
     * budget is never safe on its own - 350 chars can be 215 or 720 tokens.
     */
    const val TOKEN_BUDGET = 440

    /**
     * Default sequences per translate_batch call.
     *
     * Runtime-tunable via [setBatchSize]/[setTokenBatching] (`POST /config`) so the optimum can be
     * measured on a device: x86 saturated at 16 (4→1555, 8→1748, 16→2293, 32→2270 chars/s), but
     * ARM padding/attention costs differ.
     */
    const val DEFAULT_MAX_BATCH_SIZE = 16

    /** "" means "keep the model's own compute type" (CTranslate2 DEFAULT). */
    const val DEFAULT_COMPUTE_TYPE = ""

    /** Decoder ceiling. Both tables are 512 deep, so never ask for more than this. */
    private const val MIN_DECODING_LENGTH = 256
    private const val MAX_DECODING_LENGTH = 448

    @Volatile
    var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE
        private set

    /** When true, [maxBatchSize] is a *token* budget and CT2 batches by BatchType::Tokens. */
    @Volatile
    var useTokenBatching: Boolean = false
        private set

    /** Intra-op threads the native engine was actually initialised with (0 = not yet). */
    @Volatile
    var activeThreads: Int = 0
        private set

    /**
     * Compute type the native engine actually accepted. An unsupported request (e.g. `bfloat16` on
     * a CPU without AVX512-BF16, or `int16` on most ARM cores) is retried as [DEFAULT_COMPUTE_TYPE]
     * and reported here as "" so `/health` shows reality rather than the request.
     */
    @Volatile
    var activeComputeType: String = DEFAULT_COMPUTE_TYPE
        private set

    private var isNativeLoaded = false
    private var isModelInitialized = false
    private var activeModelPath: String? = null

    init {
        try {
            System.loadLibrary("vbookmt_native")
            isNativeLoaded = true
            Log.i(TAG, "Native library 'vbookmt_native' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            Log.e(TAG, "Failed to load 'vbookmt_native': ${e.message}")
        } catch (e: Exception) {
            isNativeLoaded = false
            Log.e(TAG, "Unexpected error loading 'vbookmt_native': ${e.message}")
        }
    }

    private external fun nativeInit(modelDir: String, numThreads: Int, computeType: String): Boolean
    private external fun nativeTranslate(inputText: String, numBeams: Int): String
    private external fun nativeTranslateBatch(
        inputs: Array<String>,
        numBeams: Int,
        maxDecodingLength: Int,
        maxBatchSize: Int,
        batchType: Int
    ): Array<String>
    private external fun nativeTokenCount(text: String): Int

    fun isReady(): Boolean = isNativeLoaded && isModelInitialized

    fun getActiveModelPath(): String? = activeModelPath

    fun setBatchSize(value: Int) {
        maxBatchSize = value.coerceIn(1, 64)
    }

    fun setTokenBatching(enabled: Boolean) {
        useTokenBatching = enabled
    }

    /**
     * CT2 silently ignores `intra_threads` unless it is passed explicitly, and its own default is 4.
     * Oversubscribing a big.LITTLE phone pushes work onto little cores (slower and hotter), so use
     * half the reported cores clamped to 2..4. [vn.dungxnd.vbookmt.util.PerformanceTuner] refines
     * this using the big-core count.
     */
    fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /**
     * Loads (or reloads) the native engine.
     *
     * Loading costs less than a second, so a reload is a fine price for switching compute type or
     * thread count from `/config` or from the performance card.
     *
     * @param threads <= 0 selects [recommendedThreadCount].
     * @param computeType one of CT2's names, or "" / "default" for the model's own type.
     */
    @Synchronized
    fun init(
        modelPath: String,
        threads: Int = 0,
        computeType: String = DEFAULT_COMPUTE_TYPE
    ): Boolean {
        if (!isNativeLoaded) {
            Log.w(TAG, "Cannot init model: Native library is not loaded.")
            return false
        }
        val effectiveThreads = if (threads > 0) threads else recommendedThreadCount()
        val requested = computeType.trim().lowercase()

        return try {
            var success = nativeInit(modelPath, effectiveThreads, requested)
            var applied = requested

            // An unsupported compute type fails inside CT2; fall back to the model's own type instead
            // of leaving the user with a dead server.
            if (!success && requested.isNotEmpty()) {
                Log.w(TAG, "Compute type '$requested' rejected by this device, retrying with default")
                ServiceState.log(
                    "CẢNH BÁO: compute_type '$requested' không được hỗ trợ -> dùng mặc định"
                )
                success = nativeInit(modelPath, effectiveThreads, DEFAULT_COMPUTE_TYPE)
                applied = DEFAULT_COMPUTE_TYPE
            }

            isModelInitialized = success
            if (success) {
                activeThreads = effectiveThreads
                activeComputeType = applied
                activeModelPath = modelPath
                Log.i(
                    TAG,
                    "Model initialized at: $modelPath ($effectiveThreads threads, " +
                        "compute=${applied.ifEmpty { "default" }})"
                )
            } else {
                Log.e(TAG, "nativeInit returned false for path: $modelPath")
            }
            success
        } catch (e: Throwable) {
            isModelInitialized = false
            Log.e(TAG, "Error initializing CT2 model: ${e.message}", e)
            false
        }
    }

    /**
     * SentencePiece length of [text] plus the trailing `</s>`, or 0 when the engine is unavailable
     * (callers must then skip token-based budgeting rather than treat it as "fits").
     */
    fun tokenCount(text: String): Int {
        if (text.isEmpty() || !isNativeLoaded) return 0
        return try {
            nativeTokenCount(text)
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * One native batch, no caching and no script filtering.
     *
     * @return one entry per input in input order, or null when the engine is unavailable or the
     *   native call failed (callers then fall back per item).
     */
    @Synchronized
    fun rawTranslateBatch(pieces: List<String>, numBeams: Int = 1): List<String>? {
        if (!isNativeLoaded || !isModelInitialized || pieces.isEmpty()) return null
        return try {
            val out = nativeTranslateBatch(
                pieces.toTypedArray(),
                numBeams,
                decodingLengthFor(pieces),
                maxBatchSize,
                if (useTokenBatching) 1 else 0
            )
            if (out.size == pieces.size) out.toList() else null
        } catch (e: Throwable) {
            Log.e(TAG, "nativeTranslateBatch failed: ${e.message}", e)
            null
        }
    }

    /**
     * Raw throughput probe for [vn.dungxnd.vbookmt.util.PerformanceTuner], in milliseconds.
     *
     * Bypasses every layer CTranslate2 does not own: a cached second run would look infinitely
     * fast, which would make the tuner pick whatever it measured first.
     *
     * @return elapsed milliseconds, or -1 when the engine is unavailable or the batch failed.
     */
    @Synchronized
    fun benchmarkMs(pieces: List<String>, numBeams: Int = 1): Long {
        if (!isNativeLoaded || !isModelInitialized || pieces.isEmpty()) return -1
        return try {
            val startedNs = System.nanoTime()
            val out = nativeTranslateBatch(
                pieces.toTypedArray(),
                numBeams,
                decodingLengthFor(pieces),
                maxBatchSize,
                if (useTokenBatching) 1 else 0
            )
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            if (out.size == pieces.size) elapsedMs else -1
        } catch (e: Throwable) {
            Log.e(TAG, "benchmarkMs failed: ${e.message}", e)
            -1
        }
    }

    /**
     * CT2 sizes its decoder buffers from max_decoding_length, so keep it as small as the batch
     * actually needs. Vietnamese output runs ~1.0-1.6x the source token count.
     */
    private fun decodingLengthFor(pieces: List<String>): Int {
        var maxTokens = 0
        for (piece in pieces) {
            val tokens = tokenCount(piece)
            if (tokens > maxTokens) maxTokens = tokens
        }
        if (maxTokens == 0) return MIN_DECODING_LENGTH
        return (maxTokens * 2).coerceIn(MIN_DECODING_LENGTH, MAX_DECODING_LENGTH)
    }
}
