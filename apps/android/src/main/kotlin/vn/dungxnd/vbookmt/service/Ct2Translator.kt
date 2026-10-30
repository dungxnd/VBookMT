package vn.dungxnd.vbookmt.service

import android.util.Log
import com.vbookmt.core.processor.TextProcessor

object Ct2Translator {
    private const val TAG = "Ct2Translator"

    /** MarianMT source *and* target position tables are 512 deep; CT2 throws past that. */
    const val MODEL_POSITION_LIMIT = 512

    /**
     * Working source budget, comfortably under [MODEL_POSITION_LIMIT].
     *
     * Measured against this model's source.spm: normal Chinese prose costs ~0.62 tokens/char,
     * but rare glyphs (龘靐齉齾…) cost ~2.0 because they fall back to byte tokens. A character
     * based budget is therefore never safe on its own - 350 chars can be 215 or 720 tokens.
     */
    const val TOKEN_BUDGET = 440

    /** Sequences per translate_batch call. Throughput saturates at ~16 on 4 threads. */
    const val MAX_BATCH_SIZE = 16

    /**
     * Decoder ceiling. Bounded well under [MODEL_POSITION_LIMIT] because the decoder shares the
     * same 512-position table; the old `tokens * 4` could ask for 1400+ positions and throw.
     */
    private const val MIN_DECODING_LENGTH = 256
    private const val MAX_DECODING_LENGTH = 448

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
            Log.e(TAG, "Unexpected error loading native library: ${e.message}")
        }
    }

    private external fun nativeInit(modelDir: String, numThreads: Int): Boolean
    private external fun nativeTranslate(inputText: String, numBeams: Int): String
    private external fun nativeTranslateBatch(
        inputs: Array<String>,
        numBeams: Int,
        maxDecodingLength: Int,
        maxBatchSize: Int
    ): Array<String>
    private external fun nativeTokenCount(text: String): Int

    fun isReady(): Boolean = isNativeLoaded && isModelInitialized

    fun getActiveModelPath(): String? = activeModelPath

    /**
     * CT2 silently ignores `intra_threads` unless it is passed explicitly, and its own default is 4.
     * Oversubscribing a big.LITTLE phone pushes work onto little cores (slower and hotter), so use
     * half the reported cores clamped to 2..4. Pass an explicit [init] `threads` value to override.
     */
    fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** [threads] <= 0 selects [recommendedThreadCount]. */
    @Synchronized
    fun init(modelPath: String, threads: Int = 0): Boolean {
        if (!isNativeLoaded) {
            Log.w(TAG, "Cannot init model: Native library is not loaded.")
            return false
        }
        val effectiveThreads = if (threads > 0) threads else recommendedThreadCount()
        return try {
            val success = nativeInit(modelPath, effectiveThreads)
            isModelInitialized = success
            if (success) {
                activeModelPath = modelPath
                Log.i(TAG, "Model successfully initialized at: $modelPath ($effectiveThreads threads)")
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
     * Translate a whole chapter's chunks in batched inference calls.
     *
     * Every paragraph used to be its own native call, so a 7k-character chapter paid ~300 fixed
     * call costs and never filled a batch. Handing the same sequences over in batches of
     * [MAX_BATCH_SIZE] measured 3-4.6x faster, with output equivalent to one-call-per-piece (padding
     * inside a batch can shift a stray token, so not literally byte-identical).
     */
    @Synchronized
    fun translateBatch(pieces: List<String>, numBeams: Int = 1): List<String> {
        if (pieces.isEmpty()) return emptyList()

        // Never hand the user raw Chinese: fall back per item when the engine is cold.
        if (!isNativeLoaded || !isModelInitialized) {
            TranslationService.log(
                "THÔNG BÁO: CT2 chưa sẵn sàng (native=$isNativeLoaded, init=$isModelInitialized) " +
                    "-> dùng Hán-Việt fallback cho ${pieces.size} đoạn"
            )
            return pieces.map { TextProcessor.toSinoVietnamese(it) }
        }

        val translated = try {
            nativeTranslateBatch(
                pieces.toTypedArray(),
                numBeams,
                decodingLengthFor(pieces),
                MAX_BATCH_SIZE
            )
        } catch (e: Throwable) {
            Log.e(TAG, "nativeTranslateBatch failed: ${e.message}", e)
            TranslationService.log("LỖI nativeTranslateBatch: ${e.message}")
            null
        }

        if (translated == null || translated.size != pieces.size) {
            TranslationService.log("CẢNH BÁO: CT2 batch thất bại -> dùng Hán-Việt fallback")
            return pieces.map { TextProcessor.toSinoVietnamese(it) }
        }

        var fallbacks = 0
        val out = ArrayList<String>(translated.size)
        for (i in translated.indices) {
            val item = translated[i]
            if (item.isBlank()) {
                fallbacks++
                out.add(TextProcessor.toSinoVietnamese(pieces[i]))
            } else {
                out.add(item)
            }
        }
        if (fallbacks > 0) {
            TranslationService.log("CẢNH BÁO: $fallbacks/${pieces.size} đoạn rơi vào Hán-Việt fallback")
        }
        return out
    }

    /** Single-sequence convenience wrapper. */
    @Synchronized
    fun translate(text: String, numBeams: Int = 1): String {
        if (text.isBlank()) return ""
        return translateBatch(listOf(text), numBeams).firstOrNull() ?: ""
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
