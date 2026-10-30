package com.vbookmt.core.processor

/**
 * Classifies text by writing system so the pipeline can skip the model entirely when there is
 * nothing to translate.
 *
 * Chinese-to-Vietnamese novel chapters routinely carry lines that contain no Han characters at all:
 * bare numbers ("12"), scene separators ("***"), romanised names, URLs, emoji, or plain Vietnamese
 * the source site already mixed in. Pushing those through MarianMT is pure waste - and actively
 * harmful, because the decoder tends to invent text rather than copy an input it cannot align.
 * Measured on the bundled HachimiMT-60: a 1000-character chapter of such lines costs ~600 ms of
 * decoder time and comes back altered.
 */
object ScriptClassifier {

    /**
     * True when [text] contains at least one Han (CJK ideograph), i.e. it is worth translating.
     *
     * Covers the Unified Repertoire plus extensions A and B, the compatibility block, and the two
     * ideographic marks (々 U+3005, 〇 U+3007). Extension B lives above the BMP, so surrogate pairs
     * are decoded manually - these are exactly the rare xianxia glyphs the tokenizer notes call out
     * as costing multiple tokens each.
     */
    fun hasHan(text: String): Boolean {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val code = c.code
            if (code < 0x2E80) {
                // ASCII/Latin/Cyrillic branches can never hold a Han ideograph; skip the
                // surrogate bookkeeping entirely for the common case.
                i++
                continue
            }
            val codePoint = if (c.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) {
                val pair = 0x10000 + ((code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
                i += 2
                pair
            } else {
                i++
                code
            }
            if (isHan(codePoint)) return true
        }
        return false
    }

    /** Convenience inverse, used by callers that think in terms of "needs the engine". */
    fun needsTranslation(text: String): Boolean = hasHan(text)

    private fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||      // CJK Extension A
            codePoint in 0x4E00..0x9FFF ||  // CJK Unified Ideographs
            codePoint in 0xF900..0xFAFF ||  // CJK Compatibility Ideographs
            codePoint in 0x20000..0x2FA1F || // Extensions B-F (supplementary plane)
            codePoint == 0x3005 ||          // 々 iteration mark
            codePoint == 0x3007             // 〇 ideographic zero
}
