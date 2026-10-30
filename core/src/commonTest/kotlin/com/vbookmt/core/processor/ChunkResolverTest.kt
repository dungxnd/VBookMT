package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkResolverTest {

    private fun noCache(@Suppress("UNUSED_PARAMETER") text: String): String? = null

    @Test
    fun blankAndNonHanChunksAreAnsweredWithoutTheEngine() {
        val pieces = listOf("", "   ", "***", "12", "Lan", "他点了点头。")
        val plan = ChunkResolver.resolve(pieces, ::noCache)

        // Blank chunks collapse to "" so the caller never emits a stray separator.
        assertEquals("", plan.resolved[0])
        assertEquals("", plan.resolved[1])
        // No Han character: returned verbatim, never sent to the model.
        assertEquals("***", plan.resolved[2])
        assertEquals("12", plan.resolved[3])
        assertEquals("Lan", plan.resolved[4])
        // Only the Han chunk is engine work.
        assertNull(plan.resolved[5])
        assertEquals(1, plan.engineChunks)
        assertEquals("他点了点头。", plan.misses.single().text)
        assertEquals(listOf(5), plan.misses.single().positions)
        assertEquals(3, plan.passthrough)
        assertEquals(0, plan.cacheHits)
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun cacheHitsAreReplayedVerbatim() {
        val pieces = listOf("已缓存。", "未缓存。")
        val plan = ChunkResolver.resolve(pieces) { text ->
            if (text == "已缓存。") "Đã lưu." else null
        }

        assertEquals("Đã lưu.", plan.resolved[0])
        assertNull(plan.resolved[1])
        assertEquals(1, plan.cacheHits)
        assertEquals(1, plan.engineChunks)
        assertEquals(listOf(1), plan.misses.single().positions)
    }

    @Test
    fun theCacheIsNeverProbedForPassthroughOrBlankChunks() {
        val probed = mutableListOf<String>()
        val pieces = listOf("", "***", "12", "他点头。")
        ChunkResolver.resolve(pieces) { text ->
            probed.add(text)
            null
        }
        assertEquals(listOf("他点头。"), probed)
    }

    @Test
    fun identicalTextIsAskedOnceAndScatteredToEveryPosition() {
        val pieces = listOf("他点头。", "他摇头。", "他点头。", "他点头。")
        val plan = ChunkResolver.resolve(pieces, ::noCache)

        assertEquals(2, plan.engineChunks)
        assertEquals(2, plan.duplicates)
        assertEquals("他点头。", plan.misses[0].text)
        assertEquals(listOf(0, 2, 3), plan.misses[0].positions)
        assertEquals("他摇头。", plan.misses[1].text)
        assertEquals(listOf(1), plan.misses[1].positions)
    }

    @Test
    fun repeatedCachedTextCountsAsAHitEveryTime() {
        val pieces = listOf("已缓存。", "已缓存。")
        val plan = ChunkResolver.resolve(pieces) { "Đã lưu." }
        assertEquals("Đã lưu.", plan.resolved[0])
        assertEquals("Đã lưu.", plan.resolved[1])
        assertEquals(2, plan.cacheHits)
        assertEquals(0, plan.engineChunks)
    }

    @Test
    fun everyMissIsAnsweredExactlyOnce() {
        val pieces = listOf("一。", "二。", "三。", "四。", "五。", "一。")
        val plan = ChunkResolver.resolve(pieces, ::noCache)
        val covered = plan.misses.flatMap { it.positions }.sorted()
        assertEquals(pieces.indices.toList(), covered)
    }

    private fun missesOf(vararg texts: String): List<ChunkResolver.Miss> =
        ChunkResolver.resolve(texts.toList(), ::noCache).misses

    @Test
    fun batchesRespectTheBatchSize() {
        val misses = missesOf("一。", "二。", "三。", "四。", "五。")
        val groups = ChunkResolver.batches(misses, { 0 }, batchSize = 2)
        assertEquals(listOf(2, 2, 1), groups.map { it.size })
        assertEquals(misses.size, groups.sumOf { it.size })
    }

    @Test
    fun batchesGatherSimilarSizesTogether() {
        // Deliberately out of order, and one pathological rare-glyph chunk last.
        val tokens = mapOf("一。" to 20, "二。" to 22, "三。" to 18, "四。" to 400)
        val misses = missesOf("一。", "二。", "三。", "四。")

        val groups = ChunkResolver.batches(misses, { tokens.getValue(it) }, batchSize = 2)

        // Ascending by size: 18, 20 | 22, 400. The 400-token chunk is isolated with one neighbour
        // instead of padding three cheap sequences up to 400 steps each.
        assertEquals(listOf("三。", "一。"), groups[0].map { it.text })
        assertEquals(listOf("二。", "四。"), groups[1].map { it.text })
    }

    @Test
    fun batchingKeepsChapterOrderWhenSizesAreUnknown() {
        // tokenCount == 0 means the engine is unavailable; sorting must stay stable.
        val misses = missesOf("一。", "二。", "三。", "四。")
        val groups = ChunkResolver.batches(misses, { 0 }, batchSize = 3)
        assertEquals(listOf("一。", "二。", "三。"), groups[0].map { it.text })
        assertEquals(listOf("四。"), groups[1].map { it.text })
    }

    @Test
    fun batchingALargerThanTheWorkIsOneGroup() {
        val misses = missesOf("一。", "二。")
        assertEquals(1, ChunkResolver.batches(misses, { 0 }, batchSize = 16).size)
        assertEquals(0, ChunkResolver.batches(emptyList(), { 0 }, batchSize = 16).size)
    }

    @Test
    fun aZeroOrNegativeBatchSizeStillMakesProgress() {
        val misses = missesOf("一。", "二。")
        assertTrue(ChunkResolver.batches(misses, { 0 }, batchSize = 0).all { it.size == 1 })
        assertTrue(ChunkResolver.batches(misses, { 0 }, batchSize = -4).all { it.size == 1 })
    }

    @Test
    fun deduplicatedWorkStillReachesEveryPositionAfterBatching() {
        val pieces = listOf("他点头。", "他摇头。", "他点头。")
        val tokens = mapOf("他点头。" to 10, "他摇头。" to 90)
        val plan = ChunkResolver.resolve(pieces, ::noCache)
        val groups = ChunkResolver.batches(plan.misses, { tokens.getValue(it) }, batchSize = 1)

        val resolved: MutableList<String?> = plan.resolved.toMutableList()
        var translated = 0
        for (group in groups) {
            for (miss in group) {
                translated++
                for (position in miss.positions) resolved[position] = "x$translated"
            }
        }
        // Two decodes, three filled positions, and the repeat shares the first answer.
        assertEquals(2, translated)
        assertEquals(listOf<String?>("x1", "x2", "x1"), resolved)
    }
}
