package vn.dungxnd.vbookmt.service

import com.vbookmt.core.processor.ChapterTranslationPipeline
import com.vbookmt.core.processor.TranslationCache
import vn.dungxnd.vbookmt.service.onnx.OnnxTranslator

/**
 * Turns a chapter's chunks into translated text, spending engine time only where it can help.
 * Delegated to [ChapterTranslationPipeline] in `core`.
 */
object ChunkTranslator {

    private val cache = TranslationCache()

    @Volatile
    var cacheEnabled: Boolean = true

    @Volatile
    var lastBatchWasFullyCached: Boolean = false
        private set

    @Synchronized
    fun cacheStats(): TranslationCache.Stats = cache.stats()

    fun clearCache() {
        clearMemory()
        CacheStore.clear()
    }

    @Synchronized
    private fun clearMemory() = cache.clear()

    @Synchronized
    fun snapshotForDisk(): List<Pair<String, String>> = cache.snapshot()

    @Synchronized
    fun restoreFromDisk(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        cache.restore(entries)
    }

    internal val engineDelegate = object : ChapterTranslationPipeline.EngineDelegate {
        override fun isReady(): Boolean = OnnxTranslator.isReady()
        override fun getActiveModelPath(): String? = OnnxTranslator.getActiveModelPath()
        override val maxBatchSize: Int get() = OnnxTranslator.maxBatchSize
        override fun tokenCount(text: String): Int = OnnxTranslator.tokenCount(text)
        override suspend fun rawTranslateBatch(texts: List<String>, numBeams: Int): List<String>? =
            OnnxTranslator.rawTranslateBatch(texts, numBeams)
    }

    internal val cacheDelegate = object : ChapterTranslationPipeline.CacheDelegate {
        override val cacheEnabled: Boolean get() = this@ChunkTranslator.cacheEnabled
        override fun get(key: String): String? = synchronized(this@ChunkTranslator) { cache.get(key) }
        override fun put(key: String, value: String) { synchronized(this@ChunkTranslator) { cache.put(key, value) } }
        override fun onPersist(entries: List<Pair<String, String>>) { CacheStore.append(entries) }
    }

    suspend fun translateChunks(pieces: List<String>, numBeams: Int = 1): List<String> {
        val (chunks, fullyCached, _) = ChapterTranslationPipeline.translateChunks(
            pieces = pieces,
            numBeams = numBeams,
            engine = engineDelegate,
            cache = cacheDelegate,
            logger = { ServiceState.log(it) }
        )
        lastBatchWasFullyCached = fullyCached
        return chunks
    }

    suspend fun translate(text: String, numBeams: Int = 1): String {
        if (text.isBlank()) return ""
        return translateChunks(listOf(text), numBeams).firstOrNull() ?: ""
    }
}
