package vn.dungxnd.vbookmt.service

import com.vbookmt.core.processor.ChapterChunker
import com.vbookmt.core.processor.TextProcessor

/**
 * Translates one chapter: sanitize, split, translate through [ChunkTranslator], reassemble.
 *
 * Layout decisions (chunk size, paragraph breaks, token guard) live in
 * [com.vbookmt.core.processor.ChapterChunker] so they are testable without Android. This class owns
 * only the chapter-level pass: one call for the whole chapter, because batching decisions belong to
 * [ChunkTranslator] - it is the layer that knows which chunks survive the cache.
 */
object ChapterTranslator {

    /** Below this, chunks are too short to carry context; above it, ARM self-attention dominates. */
    const val MIN_CHUNK_CHARS = 40
    const val MAX_CHUNK_CHARS = 400

    /**
     * Chunk length in characters, the measured throughput/quality point (see [ChapterChunker]).
     *
     * Runtime-tunable via `POST /config` so the sweep that produced [ChapterChunker.DEFAULT_CHUNK_CHARS]
     * can be repeated on another device: on a Snapdragon 8 Gen 3 the same text ran 1556 chars/s at
     * 80 chars per chunk and 1040 at 280. Deliberately *not* persisted - it is a measurement knob,
     * not a user preference, and the winner belongs in the default rather than in SharedPreferences.
     */
    @Volatile
    var chunkChars: Int = ChapterChunker.DEFAULT_CHUNK_CHARS
        private set

    fun setChunkChars(value: Int) {
        chunkChars = value.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    /**
     * A whole chapter, paragraph by paragraph.
     *
     * `suspend` because [ChunkTranslator.translateChunks] yields between engine batches, so the
     * caller's `withTimeout` can actually fire - a fully blocking implementation silently ignores
     * its own timeout.
     */
    suspend fun translate(rawText: String): String {
        val sanitized = TextProcessor.sanitize(rawText)
        if (sanitized.isBlank()) return ""

        val plan = ChapterChunker.plan(
            sanitizedText = sanitized,
            chunkChars = chunkChars,
            tokenBudget = Ct2Translator.TOKEN_BUDGET,
            tokenCount = Ct2Translator::tokenCount,
        )
        if (plan.chunks.isEmpty()) return ""

        // One call for the whole chapter: resolving the cache and batching the misses is
        // ChunkTranslator's job, and slicing here (the old behaviour) capped every native call at the
        // slice's *surviving* chunks - a mostly-cached chapter then sent one sequence per call.
        val translated = ChunkTranslator.translateChunks(plan.chunks.map { it.text })

        val out = StringBuilder(sanitized.length + sanitized.length / 2)
        for (index in plan.chunks.indices) {
            val chunk = plan.chunks[index]
            if (chunk.newlinesBefore > 0) {
                repeat(chunk.newlinesBefore) { out.append('\n') }
            } else if (out.isNotEmpty()) {
                out.append(' ')
            }
            out.append(TextProcessor.decodeTarget(translated[index]))
        }

        ServiceState.log(
            "Dịch xong: ${plan.paragraphCount} đoạn, ${plan.chunks.size} chunks, " +
                "${out.length} ký tự đầu ra (chunk $chunkChars ký tự)"
        )
        return out.toString()
    }
}
