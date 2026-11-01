package com.vbookmt.core.processor

/**
 * Splits chapter text into chunks that respect paragraph structure and the model's position limit.
 *
 * Pure logic on purpose: the token budget needs the engine, so the tokenizer is injected as
 * [tokenCount]. That keeps this testable on the JVM (and reusable by a future Linux/Windows server)
 * without a native library, and keeps the service layer free of string surgery.
 *
 * Two budgets, in order (quality first, correctness second):
 *  1. [chunkChars] - the measured throughput/quality point. On a Snapdragon 8 Gen 3, identical
 *     12000-char text translated at 80/120/160/200/280 chars per chunk gave 1556/1513/1342/1114/
 *     1040 chars/s, and 250-350 chars started dropping content. 150 is both fast and safe.
 *  2. a token budget - rare glyphs cost up to ~2.9 SentencePiece tokens each instead of ~0.62, so a
 *     chunk of 龘靐齉… can overflow the model's 512-position table (measured: 162 chars = 472
 *     tokens). Those chunks are re-split using the observed tokens/char ratio.
 */
object ChapterChunker {

    /** Default chunk length in characters, measured on device (see class docs). */
    const val DEFAULT_CHUNK_CHARS = 150

    /** Below this, chunks are too short to carry context; above it, attention dominates. */
    const val MIN_CHUNK_CHARS = 40
    const val MAX_CHUNK_CHARS = 400

    /**
     * Default SentencePiece budget per chunk. This is the single source of truth: the app layers read
     * it from here rather than restating 280, so the chunker and the engine cannot disagree.
     *
     * The decoder has 448 positions and Vietnamese costs ~1.34 output pieces per source token, so a
     * 440-token budget truncated ~48% of long chunks mid-sentence (measured: 440 → 48%, 340 → 16%,
     * 300 → 9%, 280 → 4%, 240 → 2%). Throughput does not suffer for the smaller budget (280: 1640
     * chars/s vs 440: 1521), and a shorter chunk decodes sooner, which is what the reader waits for.
     */
    const val DEFAULT_TOKEN_BUDGET = 280

    /** Don't look for a sentence boundary until at least this much text is buffered. */
    private const val MIN_SOFT_SPLIT_CHARS = 110

    /** Smallest chunk the token guard will produce for pathological (rare-glyph) text. */
    private const val MIN_TOKEN_SPLIT_CHARS = 40

    /** Hard-limit overshoot allowance, absorbed by the token-guard rescale below. */
    private const val HARD_LIMIT_FACTOR = 1.08

    /** Rescale safety factor for the token guard (see [splitByTokenBudget]). */
    private const val TOKEN_RESCALE_NUMERATOR = 8L
    private const val TOKEN_RESCALE_DENOMINATOR = 10L

    /** Recursion depth cap for the token guard. */
    private const val MAX_TOKEN_SPLIT_DEPTH = 4

    /** Sentence terminators. '；'/';' are clause separators in Chinese, not sentence ends. */
    private val SENTENCE_ENDERS = charArrayOf('。', '！', '？', '!', '?', '…')

    /** Last-resort cut points inside an over-long run-on sentence. */
    private val CLAUSE_BOUNDARIES = charArrayOf(
        '。', '！', '？', '!', '?', '…', '，', '、', '：', '；', ',', ':', ';'
    )

    /** One unit of translation: [newlinesBefore] newlines to emit before it in the output. */
    data class Chunk(val text: String, val newlinesBefore: Int)

    /** A chapter split into chunks, plus the paragraph count for logging. */
    data class Plan(val chunks: List<Chunk>, val paragraphCount: Int)

    /**
     * Splits already-sanitized chapter text.
     *
     * Paragraph breaks are preserved as `newlinesBefore` (a single blank line between paragraphs is
     * kept as 2, runs of blank lines collapse), mirroring how vBook renders chapters.
     *
     * @param tokenCount SentencePiece length of a chunk, or 0 when the engine is unavailable (0
     *   disables the token guard rather than treating every chunk as fitting).
     */
    fun plan(
        sanitizedText: String,
        chunkChars: Int = DEFAULT_CHUNK_CHARS,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
        tokenCount: (String) -> Int = { 0 },
    ): Plan {
        if (sanitizedText.isBlank()) return Plan(emptyList(), 0)

        val chunks = ArrayList<Chunk>(64)
        var paragraphCount = 0
        var blankRun = 0
        var isFirst = true

        for (paragraph in sanitizedText.split('\n')) {
            val trimmed = paragraph.trim()
            if (trimmed.isEmpty()) {
                if (!isFirst) blankRun = 1 // preserve at most one blank line
                continue
            }
            paragraphCount++

            for ((index, piece) in splitForTranslation(trimmed, chunkChars, tokenBudget, tokenCount)
                .withIndex()) {
                val newlines = when {
                    isFirst -> 0
                    index > 0 -> 0            // continuation of the same source paragraph
                    else -> 1 + blankRun      // paragraph break, plus a blank line if present
                }
                chunks.add(Chunk(piece, newlines))
            }
            isFirst = false
            blankRun = 0
        }

        return Plan(chunks, paragraphCount)
    }

    /** Character-budget split first (quality), then token-safety split (correctness). */
    private fun splitForTranslation(
        paragraph: String,
        chunkChars: Int,
        tokenBudget: Int,
        tokenCount: (String) -> Int,
    ): List<String> =
        splitByCharBudget(paragraph, chunkChars)
            .flatMap { splitByTokenBudget(it, 0, tokenBudget, tokenCount) }

    /**
     * Re-splits [text] until it fits [tokenBudget].
     *
     * For a given text the tokens/char ratio is roughly constant, so rescaling the character budget
     * by the ratio observed here converges in a single level. [TOKEN_RESCALE_NUMERATOR] absorbs
     * [splitByCharBudget]'s 8% hard-limit overshoot, which would otherwise push the rescaled chunk
     * back over budget (measured: a 162-character chunk of rare glyphs still cost 472 tokens).
     */
    private fun splitByTokenBudget(
        text: String,
        depth: Int,
        tokenBudget: Int,
        tokenCount: (String) -> Int,
    ): List<String> {
        val tokens = tokenCount(text)
        if (tokens == 0 || tokens <= tokenBudget) return listOf(text)
        if (depth >= MAX_TOKEN_SPLIT_DEPTH || text.length <= MIN_TOKEN_SPLIT_CHARS) return listOf(text)

        val safeChars = (text.length.toLong() * tokenBudget / tokens *
            TOKEN_RESCALE_NUMERATOR / TOKEN_RESCALE_DENOMINATOR)
            .toInt()
            .coerceIn(MIN_TOKEN_SPLIT_CHARS, text.length - 1)

        val pieces = splitByCharBudget(text, safeChars)
        // No progress means no clause boundary to cut at; stop rather than recurse forever.
        if (pieces.size <= 1 && pieces.firstOrNull() == text) return listOf(text)

        return pieces.flatMap { splitByTokenBudget(it, depth + 1, tokenBudget, tokenCount) }
    }

    /**
     * Splits one paragraph into chunks of at most ~[budget] characters.
     *
     * Only sentence terminators end a chunk ('；'/';' separate clauses in Chinese and used to cut
     * subject-verb pairs apart), and an over-long run-on backs off to the nearest clause boundary
     * instead of being sliced at an arbitrary character offset.
     */
    private fun splitByCharBudget(text: String, budget: Int): List<String> {
        if (text.length <= budget) return listOf(text)

        val result = ArrayList<String>(text.length / budget + 1)
        val buffer = StringBuilder(budget + 32)
        val hardLimit = (budget * HARD_LIMIT_FACTOR).toInt().coerceAtLeast(budget)

        for (ch in text) {
            buffer.append(ch)
            when {
                ch in SENTENCE_ENDERS && buffer.length >= MIN_SOFT_SPLIT_CHARS -> {
                    result.add(buffer.toString())
                    buffer.clear()
                }
                buffer.length >= hardLimit -> {
                    val joined = buffer.toString()
                    val cut = joined.lastIndexOfAny(CLAUSE_BOUNDARIES)
                    if (cut >= MIN_SOFT_SPLIT_CHARS / 2) {
                        result.add(joined.substring(0, cut + 1))
                        buffer.clear()
                        buffer.appendRange(joined, cut + 1, joined.length)
                    } else {
                        result.add(joined)
                        buffer.clear()
                    }
                }
            }
        }
        if (buffer.isNotEmpty()) result.add(buffer.toString())
        return result
    }
}
