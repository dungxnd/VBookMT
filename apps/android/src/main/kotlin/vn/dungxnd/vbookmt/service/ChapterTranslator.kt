package vn.dungxnd.vbookmt.service

import android.os.SystemClock
import com.vbookmt.core.processor.ChapterChunker
import com.vbookmt.core.processor.ChapterTranslationPipeline
import vn.dungxnd.vbookmt.util.EnergyHarness

/**
 * Translates one chapter: delegates pipeline orchestration to [ChapterTranslationPipeline] in `core`.
 */
object ChapterTranslator {

    const val MIN_CHUNK_CHARS = ChapterChunker.MIN_CHUNK_CHARS
    const val MAX_CHUNK_CHARS = ChapterChunker.MAX_CHUNK_CHARS

    @Volatile
    var chunkChars: Int = ChapterChunker.DEFAULT_CHUNK_CHARS
        private set

    fun setChunkChars(value: Int) {
        chunkChars = value.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    suspend fun translate(rawText: String): String {
        if (rawText.isBlank()) return ""

        val chapterStart = SystemClock.elapsedRealtime()
        val measure = EnergyHarness.begin(
            sourceChars = rawText.length,
            threads = NativeTokenizer.recommendedThreadCount(),
            model = ServiceState.activeModel.value,
            startedAtMs = chapterStart,
        )

        val res = ChapterTranslationPipeline.translate(
            rawText = rawText,
            chunkChars = chunkChars,
            tokenBudget = NativeTokenizer.TOKEN_BUDGET,
            numBeams = 1,
            engine = ChunkTranslator.engineDelegate,
            cache = ChunkTranslator.cacheDelegate,
            logger = { ServiceState.log(it) }
        )

        ServiceState.log(
            "Dịch xong: ${res.paragraphCount} đoạn, ${res.chunkCount} chunks, " +
                "${res.translatedText.length} ký tự đầu ra (chunk $chunkChars ký tự)"
        )

        if (measure != 0L) {
            if (res.fullyCached) {
                EnergyHarness.discard(measure)
            } else {
                EnergyHarness.end(measure, res.translatedText.length)?.let { ServiceState.log(it) }
            }
        }
        return res.translatedText
    }
}
