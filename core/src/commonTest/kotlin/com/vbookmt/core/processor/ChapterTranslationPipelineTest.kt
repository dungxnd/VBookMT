package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ChapterTranslationPipelineTest {

    private class MockEngine(
        var ready: Boolean = true,
        var batchResult: ((List<String>) -> List<String>)? = null
    ) : ChapterTranslationPipeline.EngineDelegate {
        override fun isReady(): Boolean = ready
        override fun getActiveModelPath(): String = "/models/mock"
        override val maxBatchSize: Int = 16
        override fun tokenCount(text: String): Int = text.length

        override suspend fun rawTranslateBatch(texts: List<String>, numBeams: Int): List<String>? {
            return batchResult?.invoke(texts) ?: texts.map { "Dịch: $it" }
        }
    }

    private class MockCache : ChapterTranslationPipeline.CacheDelegate {
        val map = mutableMapOf<String, String>()
        val persisted = mutableListOf<Pair<String, String>>()
        override var cacheEnabled: Boolean = true

        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun onPersist(entries: List<Pair<String, String>>) { persisted.addAll(entries) }
    }

    @Test
    fun testTranslateBlankText() = runTest {
        val engine = MockEngine()
        val cache = MockCache()
        val result = ChapterTranslationPipeline.translate(
            rawText = "   \n\n  ",
            chunkChars = 100,
            tokenBudget = 280,
            engine = engine,
            cache = cache
        )
        assertEquals("", result.translatedText)
        assertEquals(0, result.chunkCount)
        assertTrue(result.fullyCached)
    }

    @Test
    fun testTranslateNormalFlow() = runTest {
        val engine = MockEngine()
        val cache = MockCache()
        val input = "第一章 初始。\n\n林默缓缓睁开双眼。"
        val result = ChapterTranslationPipeline.translate(
            rawText = input,
            chunkChars = 100,
            tokenBudget = 280,
            engine = engine,
            cache = cache
        )
        assertTrue(result.translatedText.isNotEmpty())
        assertEquals(2, result.chunkCount)
        assertEquals(false, result.fullyCached)
        assertEquals(2, cache.persisted.size)

        // Second translation should hit cache
        val retest = ChapterTranslationPipeline.translate(
            rawText = input,
            chunkChars = 100,
            tokenBudget = 280,
            engine = engine,
            cache = cache
        )
        assertTrue(retest.fullyCached)
        assertEquals(result.translatedText, retest.translatedText)
    }

    @Test
    fun testTranslateFallbackWhenEngineNotReady() = runTest {
        val engine = MockEngine(ready = false)
        val cache = MockCache()
        val input = "第一章"
        val result = ChapterTranslationPipeline.translate(
            rawText = input,
            chunkChars = 100,
            tokenBudget = 280,
            engine = engine,
            cache = cache
        )
        assertTrue(result.fallbackCount > 0)
        assertTrue(result.translatedText.isNotBlank())
    }
}
