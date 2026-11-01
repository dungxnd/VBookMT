package com.vbookmt.core.processor

/**
 * Text preprocessing, sanitization, and postprocessing utility for MarianMT (HachimiMT/MoxhiMT).
 */
object TextProcessor {
    // Escaped bracket \[ inside character class to prevent PatternSyntaxException across Android/JVM
    private val OPENER_SPACE_REGEX = Regex("([“‘«「『【《(\\[{（［｛]) (?=[^\\W\\d_])")
    private val NL_MARKER_REGEX = Regex("[^\\S\\n]*<NL>[^\\S\\n]*")
    private val MULTI_NEWLINE_REGEX = Regex("[^\\S\\n]*\\n[^\\S\\n]*")
    private val TRAILING_COMMENT_NUMS = Regex("(\\D)\\s*\\d+$")
    private val ZERO_WIDTH_CHARS = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u00A0]")

    /**
     * Sanitizes input novel text:
     * - Normalizes Windows/Mac line endings (\r\n, \r -> \n).
     * - Strips invisible zero-width unicode characters.
     * - Strips trailing comment count numbers injected by web crawlers.
     */
    fun sanitize(text: String, stripCrawlerNumbers: Boolean = true): String {
        var clean = text.replace("\r\n", "\n").replace("\r", "\n")
        clean = ZERO_WIDTH_CHARS.replace(clean, "")

        if (stripCrawlerNumbers) {
            val lines = clean.split("\n")
            clean = lines.joinToString("\n") { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) "" else TRAILING_COMMENT_NUMS.replace(trimmed, "$1")
            }
        }
        return clean
    }

    /**
     * Preprocesses source text for MarianMT tokenization.
     * Replaces newlines with `<NL>` tokens if supported by the model.
     */
    fun encodeSource(text: String): String {
        var normalized = sanitize(text)
        normalized = MULTI_NEWLINE_REGEX.replace(normalized, "<NL>")
        return Regex("<NL>(?!<NL>|$)").replace(normalized, "<NL> ")
    }

    /**
     * Postprocesses decoded output text.
     * Restores newlines and fixes spacing after quote openers.
     */
    fun decodeTarget(text: String): String {
        val restoredNewlines = NL_MARKER_REGEX.replace(text, "\n")
        return OPENER_SPACE_REGEX.replace(restoredNewlines, "$1")
    }

    /**
     * Converts Chinese text to Sino-Vietnamese / Convert translation.
     * Guarantees that raw Chinese characters are never returned even when
     * neural weights are still being downloaded.
     */
    fun toSinoVietnamese(text: String): String {
        val sanitized = sanitize(text)
        return SinoVietnameseDict.translate(sanitized)
    }
}
