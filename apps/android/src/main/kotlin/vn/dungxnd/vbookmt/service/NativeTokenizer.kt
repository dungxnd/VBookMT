package vn.dungxnd.vbookmt.service

import android.util.Log

/**
 * JNI facade over the native SentencePiece tokenizer (`native/jni_bridge.cpp`).
 *
 * **Why this file is not called Ct2Translator any more.** It used to be, because CTranslate2 owned both
 * translation and the two `.spm` tables. Translation moved to ONNX Runtime, so what remains is the
 * tokenizer — and the class name is part of the JNI contract: `jni_bridge.cpp` exports
 * `Java_vn_dungxnd_vbookmt_service_NativeTokenizer_*`, so renaming this class without renaming the
 * bridge (or the reverse) fails every native call with UnsatisfiedLinkError.
 *
 * The ONNX graphs were exported against exactly the ids this produces, including the trailing `</s>`,
 * so this is the single place text becomes model input on both hosts.
 *
 * Every native entry point is `@Synchronized`: SentencePiece processors are not re-entrant, and the
 * two `Encode`/`Decode` calls share their internal lattice.
 */
object NativeTokenizer {

    private const val TAG = "NativeTokenizer"

    /**
     * SentencePiece budget per chunk, sized so the *translation* fits the decoder ceiling.
     *
     * Vietnamese costs ~1.34 output pieces per source token on this model (p90 1.51, max 1.62 over 150
     * paragraphs) and the decoder has 448 positions, so anything much above ~276 source tokens is
     * silently cut off mid-sentence. Measured on production-shaped chunks: 440 tokens → 48% of chunks
     * hit the cap, 340 → 16%, 300 → 9%, 280 → 4%, 240 → 2%. Throughput does not suffer for the smaller
     * budget (280: 1640 chars/s vs 440: 1521), and a shorter chunk also decodes sooner.
     */
    const val TOKEN_BUDGET = 280

    /**
     * MarianMT source *and* target position tables are 512 deep; the decoder must never reach them.
     */
    const val MODEL_POSITION_LIMIT = 512

    private var isNativeLoaded = false

    @Volatile
    private var tablesLoaded = false

    @Volatile
    private var loadedDir: String? = null

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

    /** Loads `source.spm` and `target.spm` from [modelDir]. Idempotent. */
    private external fun nativeLoad(modelDir: String): Boolean

    /** SentencePiece length of [text] plus the trailing `</s>`; 0 for an empty text. */
    private external fun nativeTokenCount(text: String): Int

    /** Source text to model ids, including the trailing `</s>`. */
    private external fun nativeEncode(text: String): IntArray

    /** Model ids back to text, skipping special tokens. */
    private external fun nativeDecode(ids: IntArray): String

    /** True once the tables are loaded and encode/decode will return real values. */
    fun isReady(): Boolean = isNativeLoaded && tablesLoaded

    /** Directory the tables came from; null until [load]. */
    fun getActiveModelPath(): String? = loadedDir

    /**
     * Loads the SentencePiece tables for a model directory.
     *
     * @return false when the native library is missing or the directory has no `.spm` files.
     */
    @Synchronized
    fun load(modelDir: String): Boolean {
        if (!isNativeLoaded) return false
        // Same directory already loaded: reloading would reset the processors for no benefit, and
        // `init` runs on every model switch.
        if (tablesLoaded && loadedDir == modelDir) return true
        return try {
            nativeLoad(modelDir).also { ok ->
                tablesLoaded = ok
                loadedDir = if (ok) modelDir else null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load tokenizers: ${e.message}", e)
            tablesLoaded = false
            loadedDir = null
            false
        }
    }

    /**
     * SentencePiece length of [text] plus the trailing `</s>`, or 0 when the tables are unavailable
     * (callers must then skip token-based budgeting rather than treat it as "fits").
     */
    fun tokenCount(text: String): Int {
        if (text.isEmpty() || !isReady()) return 0
        return try {
            nativeTokenCount(text)
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * Source text to model ids, including the trailing `</s>`.
     *
     * @return empty only when the tokenizer is unavailable; callers treat that as "cannot encode"
     *   rather than as an empty sentence.
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty() || !isReady()) return LongArray(0)
        return try {
            nativeEncode(text).let { ints -> LongArray(ints.size) { ints[it].toLong() } }
        } catch (e: Throwable) {
            LongArray(0)
        }
    }

    /** Model ids back to text. Ids outside the target vocabulary are skipped, never fatal. */
    fun decode(ids: LongArray): String {
        if (ids.isEmpty() || !isReady()) return ""
        return try {
            nativeDecode(IntArray(ids.size) { ids[it].toInt() })
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * Intra-op thread count for inference.
     *
     * ONNX Runtime does not manage its own pool the way CTranslate2 did, and oversubscribing a
     * big.LITTLE phone pushes work onto little cores (slower and hotter), so use half the reported
     * cores clamped to 2..4. [vn.dungxnd.vbookmt.util.PerformanceTuner] refines this using the
     * big-core count.
     */
    fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
}
