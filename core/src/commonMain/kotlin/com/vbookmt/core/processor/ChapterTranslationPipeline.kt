package com.vbookmt.core.processor

/**
 * Shared end-to-end chapter translation pipeline.
 *
 * Owned by `core` so Android and Windows execute the exact same sanitisation, chunking,
 * caching, length-grouped batching, Sino-Vietnamese fallback, and paragraph reassembly.
 */
object ChapterTranslationPipeline {

    interface EngineDelegate {
        fun isReady(): Boolean
        fun getActiveModelPath(): String?
        val maxBatchSize: Int
        fun tokenCount(text: String): Int
        suspend fun rawTranslateBatch(texts: List<String>, numBeams: Int): List<String>?
    }

    interface CacheDelegate {
        val cacheEnabled: Boolean
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun onPersist(entries: List<Pair<String, String>>)
    }

    data class TranslationResult(
        val translatedText: String,
        val paragraphCount: Int,
        val chunkCount: Int,
        val fullyCached: Boolean,
        val fallbackCount: Int
    )

    suspend fun translate(
        rawText: String,
        chunkChars: Int,
        tokenBudget: Int,
        numBeams: Int = 1,
        engine: EngineDelegate,
        cache: CacheDelegate,
        logger: (String) -> Unit = {}
    ): TranslationResult {
        if (rawText.isBlank()) {
            return TranslationResult("", 0, 0, fullyCached = true, fallbackCount = 0)
        }

        val sanitized = TextProcessor.sanitize(rawText)
        if (sanitized.isBlank()) {
            return TranslationResult("", 0, 0, fullyCached = true, fallbackCount = 0)
        }

        val plan = ChapterChunker.plan(
            sanitizedText = sanitized,
            chunkChars = chunkChars,
            tokenBudget = tokenBudget,
            tokenCount = engine::tokenCount,
        )
        if (plan.chunks.isEmpty()) {
            return TranslationResult("", 0, 0, fullyCached = true, fallbackCount = 0)
        }

        val (translatedChunks, fullyCached, fallbackCount) = translateChunks(
            pieces = plan.chunks.map { it.text },
            numBeams = numBeams,
            engine = engine,
            cache = cache,
            logger = logger,
        )

        val out = StringBuilder(sanitized.length + sanitized.length / 2)
        for (index in plan.chunks.indices) {
            val chunk = plan.chunks[index]
            if (chunk.newlinesBefore > 0) {
                repeat(chunk.newlinesBefore) { out.append('\n') }
            } else if (out.isNotEmpty()) {
                out.append(' ')
            }
            out.append(TextProcessor.decodeTarget(translatedChunks[index]))
        }

        return TranslationResult(
            translatedText = out.toString(),
            paragraphCount = plan.paragraphCount,
            chunkCount = plan.chunks.size,
            fullyCached = fullyCached,
            fallbackCount = fallbackCount,
        )
    }

    suspend fun translateChunks(
        pieces: List<String>,
        numBeams: Int = 1,
        engine: EngineDelegate,
        cache: CacheDelegate,
        logger: (String) -> Unit = {}
    ): Triple<List<String>, Boolean, Int> {
        if (pieces.isEmpty()) return Triple(emptyList(), true, 0)

        val model = engine.getActiveModelPath() ?: "-"

        val plan = ChunkResolver.resolve(pieces) { text ->
            if (cache.cacheEnabled) cache.get(TranslationCache.key(model, numBeams, text)) else null
        }
        val resolved: MutableList<String?> = plan.resolved.toMutableList()

        if (plan.misses.isEmpty()) {
            return Triple(finish(resolved), true, 0)
        }

        if (!engine.isReady()) {
            logger("THÔNG BÁO: Engine chưa sẵn sàng -> Hán-Việt cho ${plan.misses.size} đoạn")
            val count = fillFallback(resolved, plan.misses)
            return Triple(finish(resolved), false, count)
        }

        val groups = ChunkResolver.batches(
            misses = plan.misses,
            tokenCount = engine::tokenCount,
            batchSize = engine.maxBatchSize,
        )
        var fallbackPositions = 0
        val toPersist = ArrayList<Pair<String, String>>()

        for (group in groups) {
            val translated = engine.rawTranslateBatch(group.map { it.text }, numBeams)
            if (translated == null) {
                logger("CẢNH BÁO: Batch dịch thất bại -> Hán-Việt cho phần còn lại")
                fallbackPositions += fillFallback(resolved, plan.misses)
                break
            }

            for (k in group.indices) {
                val miss = group[k]
                val item = translated.getOrNull(k) ?: ""
                if (item.isBlank()) {
                    fallbackPositions += miss.positions.size
                    val fallback = TextProcessor.toSinoVietnamese(miss.text)
                    for (position in miss.positions) resolved[position] = fallback
                } else {
                    for (position in miss.positions) resolved[position] = item
                    if (cache.cacheEnabled) {
                        val key = TranslationCache.key(model, numBeams, miss.text)
                        cache.put(key, item)
                        toPersist.add(key to item)
                    }
                }
            }
        }

        if (toPersist.isNotEmpty()) {
            cache.onPersist(toPersist)
        }

        if (fallbackPositions > 0) {
            logger("CẢNH BÁO: $fallbackPositions/${pieces.size} đoạn rơi vào Hán-Việt fallback")
        }

        return Triple(finish(resolved), false, fallbackPositions)
    }

    private fun fillFallback(resolved: MutableList<String?>, misses: List<ChunkResolver.Miss>): Int {
        var count = 0
        for (miss in misses) {
            val fallback = TextProcessor.toSinoVietnamese(miss.text)
            for (position in miss.positions) {
                if (resolved[position] == null) {
                    resolved[position] = fallback
                    count++
                }
            }
        }
        return count
    }

    private fun finish(resolved: List<String?>): List<String> = resolved.map { it ?: "" }
}
