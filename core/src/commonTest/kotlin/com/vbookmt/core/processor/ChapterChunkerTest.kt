package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChapterChunkerTest {

    private fun planOf(text: String, tokenBudget: Int = 440, tokenCount: (String) -> Int = { 0 }) =
        ChapterChunker.plan(text, ChapterChunker.DEFAULT_CHUNK_CHARS, tokenBudget, tokenCount)

    @Test
    fun blankTextProducesNoChunks() {
        assertEquals(0, planOf("").chunks.size)
        assertEquals(0, planOf("   \n\n  ").chunks.size)
        assertEquals(0, planOf("\n \n").paragraphCount)
    }

    @Test
    fun shortParagraphStaysWhole() {
        val plan = planOf("他抬头看向远处的山门。")
        assertEquals(1, plan.paragraphCount)
        assertEquals(1, plan.chunks.size)
        assertEquals(0, plan.chunks[0].newlinesBefore)
        assertEquals("他抬头看向远处的山门。", plan.chunks[0].text)
    }

    @Test
    fun paragraphsBecomeTheirOwnChunksWithBreakMarkers() {
        val plan = planOf("第一段。\n第二段。\n\n第三段。")
        assertEquals(3, plan.paragraphCount)
        assertEquals(listOf(0, 1, 2), plan.chunks.map { it.newlinesBefore })
        assertEquals(listOf("第一段。", "第二段。", "第三段。"), plan.chunks.map { it.text })
    }

    @Test
    fun longParagraphSplitsAtSentenceEndsWithoutLosingText() {
        val sentence = "他抬头看向远处的山门，山门前已有一众年轻弟子排队等候。"
        val paragraph = sentence.repeat(8)
        val plan = planOf(paragraph)

        assertTrue(plan.chunks.size >= 2, "expected the paragraph to split, got ${plan.chunks.size}")
        // No character is dropped and nothing is duplicated.
        assertEquals(paragraph, plan.chunks.joinToString("") { it.text })
        // The 8% hard-limit allowance is the documented ceiling.
        val ceiling = (ChapterChunker.DEFAULT_CHUNK_CHARS * 1.08).toInt() + 1
        assertTrue(
            plan.chunks.all { it.text.length <= ceiling },
            "chunk longer than $ceiling: ${plan.chunks.map { it.text.length }}"
        )
        // Splits land on sentence terminators, not mid-sentence.
        assertTrue(plan.chunks.dropLast(1).all { it.text.endsWith("。") })
    }

    @Test
    fun runOnSentenceWithoutTerminatorStillSplits() {
        val runOn = "山".repeat(400)
        val plan = planOf(runOn)
        assertTrue(plan.chunks.size >= 2)
        assertEquals(runOn, plan.chunks.joinToString("") { it.text })
    }

    @Test
    fun tokenGuardSplitsRareGlyphChunksUnderBudget() {
        // ~4 tokens per character: a 150-char chunk would cost 600 tokens, over the 440 budget.
        val rareGlyphs: (String) -> Int = { it.length * 4 }
        val plan = planOf("龘".repeat(300), tokenCount = rareGlyphs)

        assertTrue(plan.chunks.size >= 2)
        assertEquals("龘".repeat(300), plan.chunks.joinToString("") { it.text })
        assertTrue(
            plan.chunks.all { rareGlyphs(it.text) <= 440 },
            "chunk over token budget: ${plan.chunks.map { rareGlyphs(it.text) }}"
        )
    }

    @Test
    fun tokenGuardIsSkippedWhenTokenizerIsUnavailable() {
        // 0 = engine unavailable: the guard must not treat every chunk as fitting, and must not
        // split into single characters either - it simply defers to the character budget.
        val plan = planOf("龘".repeat(300), tokenCount = { 0 })
        assertTrue(plan.chunks.size >= 2)
        assertEquals("龘".repeat(300), plan.chunks.joinToString("") { it.text })
    }

    @Test
    fun tokenGuardCannotRecurseForeverOnASingleLongWord() {
        // No clause boundaries at all: the guard must give up instead of recursing on one chunk.
        val word = "a".repeat(120)
        val plan = planOf(word, tokenCount = { it.length * 10 })
        assertEquals(word, plan.chunks.joinToString("") { it.text })
        assertTrue(plan.chunks.isNotEmpty())
    }
}
