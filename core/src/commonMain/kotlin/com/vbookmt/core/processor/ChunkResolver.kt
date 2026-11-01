package com.vbookmt.core.processor

/**
 * Splits one request's chunks into "already answered" and "must ask the engine" *before* batching.
 *
 * Why this exists: batching only pays off when a native call carries a full batch. Deciding what to
 * skip *after* the chapter has been sliced into batches of N throws that away - a slice where 15 of
 * 16 chunks are cached sends a 1-sequence batch. Re-reading a mostly cached chapter therefore cost
 * N calls of one sequence instead of one call of N, which is the same fixed-per-call penalty that
 * batching was introduced to remove. Resolving first spends the batch budget on genuine misses.
 *
 * Three stages, cheapest first:
 *  1. blank / no Han character -> returned untouched ([ScriptClassifier]). This is a quality win as
 *     well as a speed one: the decoder tends to invent text rather than copy input it cannot align.
 *  2. cache replay -> previously produced model output, returned verbatim.
 *  3. everything else, deduplicated: the same line often repeats inside one chapter (dialogue,
 *     chants, recap) and the model is deterministic per (text, beams), so asking once and scattering
 *     the answer cannot change what any occurrence would have produced.
 *
 * Pure: the cache probe and the tokenizer are injected, so this is testable without a native
 * library, and reusable by a future desktop server.
 */
object ChunkResolver {

    /** One distinct text the engine still has to translate, plus every input index it answers. */
    class Miss(val text: String, val positions: List<Int>)

    /**
     * @property resolved per input index: the final text, or null when the engine is still needed.
     * @property misses distinct engine work, in first-appearance order.
     * @property passthrough chunks with no Han character (returned as-is).
     * @property cacheHits chunks replayed from the cache.
     * @property duplicates repeat occurrences merged into an existing miss.
     */
    class Result(
        val resolved: List<String?>,
        val misses: List<Miss>,
        val passthrough: Int,
        val cacheHits: Int,
        val duplicates: Int,
    ) {
        /** Distinct texts that will reach the engine. */
        val engineChunks: Int get() = misses.size
    }

    /**
     * Resolves as much of [pieces] as the cache allows.
     *
     * @param lookup cache probe: returns the stored translation for a chunk, or null on a miss.
     *   Called once per non-blank, Han-bearing chunk, and never for passthrough chunks.
     */
    fun resolve(pieces: List<String>, lookup: (String) -> String?): Result {
        val resolved = arrayOfNulls<String>(pieces.size)
        val missTexts = ArrayList<String>(pieces.size)
        val missPositions = ArrayList<MutableList<Int>>(pieces.size)
        // Chunk text -> index in missTexts. Identical text is a single unit of engine work.
        val missIndex = HashMap<String, Int>(pieces.size)
        var passthrough = 0
        var cacheHits = 0
        var duplicates = 0

        for (i in pieces.indices) {
            val text = pieces[i]
            when {
                text.isBlank() -> resolved[i] = ""
                !ScriptClassifier.needsTranslation(text) -> {
                    resolved[i] = text
                    passthrough++
                }
                else -> {
                    val cached = lookup(text)
                    if (cached != null) {
                        resolved[i] = cached
                        cacheHits++
                    } else {
                        val known = missIndex[text]
                        if (known != null) {
                            missPositions[known].add(i)
                            duplicates++
                        } else {
                            missIndex[text] = missTexts.size
                            missTexts.add(text)
                            missPositions.add(mutableListOf(i))
                        }
                    }
                }
            }
        }

        val misses = ArrayList<Miss>(missTexts.size)
        for (k in missTexts.indices) {
            misses.add(Miss(missTexts[k], missPositions[k]))
        }
        return Result(resolved.toList(), misses, passthrough, cacheHits, duplicates)
    }

    /**
     * Groups [misses] into the sequence lists handed to the engine, smallest work first.
     *
     * CTranslate2 pads a batch to its longest sequence and does not shrink the batch as sequences
     * finish, so one 400-token rare-glyph chunk sitting in a group of short dialogue lines makes
     * every member of that group pay for the longest one. Sorting is a pure reordering of
     * independent work - each chunk still gets its own decode, and every result is scattered back
     * through [Miss.positions], so chapter layout is unaffected - but it keeps each group's maximum
     * close to its mean.
     *
     * @param tokenCount SentencePiece length of a chunk; 0 (engine unavailable) degenerates safely to
     *   chapter order, because the sort is stable.
     */
    fun batches(
        misses: List<Miss>,
        tokenCount: (String) -> Int,
        batchSize: Int,
    ): List<List<Miss>> {
        if (misses.isEmpty()) return emptyList()
        val size = batchSize.coerceAtLeast(1)
        val tokenCounts = misses.associateWith { tokenCount(it.text) }
        return misses.sortedBy { tokenCounts[it] ?: 0 }.chunked(size)
    }
}
