package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptClassifierTest {

    @Test
    fun detectsHanInNormalProse() {
        assertTrue(ScriptClassifier.hasHan("他抬头看向远处的山门。"))
        assertTrue(ScriptClassifier.hasHan("mixed 中文 text"))
    }

    @Test
    fun rejectsTextWithNothingToTranslate() {
        assertFalse(ScriptClassifier.hasHan(""))
        assertFalse(ScriptClassifier.hasHan("   "))
        assertFalse(ScriptClassifier.hasHan("12"))
        assertFalse(ScriptClassifier.hasHan("***"))
        assertFalse(ScriptClassifier.hasHan("https://example.com/chapter-12"))
        assertFalse(ScriptClassifier.hasHan("Ngươi đừng có nói bậy!"))
        assertFalse(ScriptClassifier.hasHan("Chương 12: Sơn môn"))
    }

    @Test
    fun cjkPunctuationAloneIsNotHan() {
        // 。、“” are CJK punctuation, not ideographs - a line of them needs no translation.
        assertFalse(ScriptClassifier.hasHan("。"))
        assertFalse(ScriptClassifier.hasHan("、。！？…"))
        assertFalse(ScriptClassifier.hasHan("\u201C\u201D"))
    }

    @Test
    fun recognizesSupplementaryPlaneIdeographs() {
        // U+2A6A5 (𪚥) is in CJK Extension B and arrives as a surrogate pair.
        assertTrue(ScriptClassifier.hasHan("\uD869\uDEA5"))
        assertTrue(ScriptClassifier.hasHan("abc \uD869\uDEA5 def"))
    }

    @Test
    fun recognizesIdeographicMarks() {
        assertTrue(ScriptClassifier.hasHan("\u3005")) // 々
        assertTrue(ScriptClassifier.hasHan("\u3007")) // 〇
    }

    @Test
    fun needsTranslationMirrorsHasHan() {
        assertTrue(ScriptClassifier.needsTranslation("山门"))
        assertFalse(ScriptClassifier.needsTranslation("12"))
    }
}
