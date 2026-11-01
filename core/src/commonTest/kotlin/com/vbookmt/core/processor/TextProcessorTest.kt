package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextProcessorTest {

    @Test
    fun testSanitizePreservesChapterTitlesAndNovelNumbers() {
        assertEquals("Chương 1", TextProcessor.sanitize("Chương 1"))
        assertEquals("Chương 123", TextProcessor.sanitize("Chương 123"))
        assertEquals("Hồi 10", TextProcessor.sanitize("Hồi 10"))
        assertEquals("第1章", TextProcessor.sanitize("第1章"))
        assertEquals("第1", TextProcessor.sanitize("第1"))
        assertEquals("Năm 2024", TextProcessor.sanitize("Năm 2024"))
        assertEquals("Cấp 9", TextProcessor.sanitize("Cấp 9"))
        assertEquals("HP: 100", TextProcessor.sanitize("HP: 100"))
    }

    @Test
    fun testSanitizeStripsCrawlerCommentNumbers() {
        // Enclosed numbers at line end
        assertEquals("他说完这句话。", TextProcessor.sanitize("他说完这句话。(12)"))
        assertEquals("他说完这句话。", TextProcessor.sanitize("他说完这句话。[35]"))
        assertEquals("他说完这句话。", TextProcessor.sanitize("他说完这句话。( 100 )"))
        
        // Trailing numbers after Chinese terminal punctuation
        assertEquals("他说完这句话。", TextProcessor.sanitize("他说完这句话。 12"))
        assertEquals("他说完这句话！", TextProcessor.sanitize("他说完这句话！ 99"))
        assertEquals("他说完这句话？", TextProcessor.sanitize("他说完这句话？ 5"))
        assertEquals("“好的。”", TextProcessor.sanitize("“好的。” 42"))
    }

    @Test
    fun testSanitizeNormalizesNonBreakingSpace() {
        val withNbsp = "Chương\u00A01:\u00A0Khởi\u00A0đầu"
        val clean = TextProcessor.sanitize(withNbsp)
        assertEquals("Chương 1: Khởi đầu", clean)
        assertFalse(clean.contains('\u00A0'))
    }

    @Test
    fun testSanitizeStripsZeroWidthCharacters() {
        val withZeroWidth = "Hello\u200BWorld\u200C!\u200D\uFEFF"
        val clean = TextProcessor.sanitize(withZeroWidth)
        assertEquals("HelloWorld!", clean)
    }

    @Test
    fun testSinoVietnameseCapitalizationWithChinesePunctuation() {
        val input = "现场。归来！海啸？"
        val translated = SinoVietnameseDict.translate(input)
        // Check capitalization after each sentence
        assertTrue(translated.contains("Hiện trường"))
        assertTrue(translated.contains("Trở về"))
        assertTrue(translated.contains("Sóng thần"))
    }
}
