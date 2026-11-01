package com.vbookmt.core.processor

/**
 * Line codec for persisting a [TranslationCache] to a single file.
 *
 * Why a journal, and why not a database: the payload is a bounded key→text map whose contents the
 * engine can always regenerate. What such a map actually needs is cheap appends, cheap replay, and a
 * corrupt tail that costs one chapter instead of the whole file. Line-oriented text gives all three,
 * stays portable across Kotlin targets (no platform API, no driver), and is reviewable with a text
 * editor when something looks wrong.
 *
 * The format is one record per line:
 *
 * ```
 * vbookmt-cache/1
 * <escaped key>\t<escaped value>
 * ```
 *
 * Records are appended oldest-first, so replaying them in file order rebuilds the cache's recency
 * order. [HEADER] exists so a future format change can be detected and discarded instead of
 * misparsed - the file is a cache, and throwing it away is always a safe outcome.
 */
object TranslationCacheJournal {

    /** First line of every journal. A file that does not start with it is discarded, never guessed at. */
    const val HEADER = "vbookmt-cache/1"

    /** Field separator. Never appears unescaped inside a record. */
    private const val SEPARATOR = '\t'

    /**
     * Encodes one record from [TranslationCache.key] and its translation.
     *
     * Every character that could break the one-record-per-line framing is escaped, because a chunk
     * translation is arbitrary text: a literal newline, tab or NUL in the payload would otherwise
     * split a record in two or shift the field boundary.
     */
    fun encode(key: String, value: String): String =
        StringBuilder(key.length + value.length + 8)
            .append(escape(key))
            .append(SEPARATOR)
            .append(escape(value))
            .toString()

    /**
     * Decodes one record.
     *
     * @return the key/value pair, or null when the line is corrupt — a missing field separator, or an
     *   escape sequence that was cut in half by a partial write.
     *
     * **A record truncated at a character boundary is indistinguishable from a valid one** and decodes
     * successfully: the format has no per-line checksum or length prefix. Recovery from such a cut is
     * therefore by *ordering*, not by detection — the next replay finds the line after it unparseable
     * (its bytes are the tail of the split record) and stops there. A single record lost in the middle
     * of an append is accepted as the cost of never surfacing corrupt text; the alternative, verifying
     * every line against a checksum, buys nothing for a cache whose contents the engine can regenerate.
     */
    fun decode(line: String): Pair<String, String>? {
        val separator = line.indexOf(SEPARATOR)
        // A record always carries a non-empty key (model + beam count + source text), so a separator
        // at position 0 is corrupt by definition rather than an empty key.
        if (separator <= 0) return null
        val key = unescape(line.substring(0, separator)) ?: return null
        val value = unescape(line.substring(separator + 1)) ?: return null
        return key to value
    }

    private fun escape(text: String): String {
        // Fast path: the overwhelming majority of records carry no escape-worthy character.
        var needsEscape = false
        for (ch in text) {
            if (ch == '\\' || ch == '\n' || ch == '\r' || ch == SEPARATOR || ch == '\u0000') {
                needsEscape = true
                break
            }
        }
        if (!needsEscape) return text

        val out = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                SEPARATOR -> out.append("\\t")
                '\u0000' -> out.append("\\0")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun unescape(text: String): String? {
        if (text.indexOf('\\') < 0) return text

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch != '\\') {
                out.append(ch)
                i++
                continue
            }
            // A trailing backslash is a truncated record: the write was cut mid-escape.
            if (i + 1 >= text.length) return null
            when (text[i + 1]) {
                '\\' -> out.append('\\')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append(SEPARATOR)
                '0' -> out.append('\u0000')
                else -> return null
            }
            i += 2
        }
        return out.toString()
    }
}
