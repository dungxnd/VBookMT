package com.vbookmt.core.processor

/**
 * Text preprocessing and postprocessing utility for MarianMT (HachimiMT/MoxhiMT).
 *
 * Implements the delimiter and tokenization contract:
 * - Preservation of newline breaks via `<NL>` tokens.
 * - Quote opener and punctuation spacing normalization.
 */
object TextProcessor {
    private const val OPENERS = "“‘«「『【《([{（［｛"
    private val OPENER_SPACE_REGEX = Regex("([$OPENERS]) (?=[^\\W\\d_])")
    private val NL_MARKER_REGEX = Regex("[^\\S\\n]*<NL>[^\\S\\n]*")
    private val MULTI_NEWLINE_REGEX = Regex("[^\\S\\n]*\\n[^\\S\\n]*")

    /**
     * Preprocesses raw source text before tokenization.
     * Replaces newlines with `<NL>` tokens if supported by the model.
     */
    fun encodeSource(text: String): String {
        var normalized = text.replace("\r\n", "\n").replace("\r", "\n")
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
     * Chunks a long chapter into smaller blocks (e.g. max ~400 tokens / 300 words)
     * so it doesn't exceed the MarianMT position capacity (512 tokens).
     */
    fun splitIntoParagraphs(text: String, maxCharsPerChunk: Int = 1200): List<String> {
        val paragraphs = text.split("\n")
        val chunks = mutableListOf<String>()
        val currentChunk = StringBuilder()

        for (para in paragraphs) {
            val trimmed = para.trim()
            if (trimmed.isEmpty()) continue

            if (currentChunk.isNotEmpty() && (currentChunk.length + trimmed.length + 1) > maxCharsPerChunk) {
                chunks.add(currentChunk.toString())
                currentChunk.clear()
            }

            if (currentChunk.isNotEmpty()) {
                currentChunk.append("\n")
            }
            currentChunk.append(trimmed)
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toString())
        }

        return if (chunks.isEmpty() && text.isNotBlank()) listOf(text) else chunks
    }
}
