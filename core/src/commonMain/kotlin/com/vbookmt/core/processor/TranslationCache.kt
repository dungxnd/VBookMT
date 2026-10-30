package com.vbookmt.core.processor

/**
 * Bounded LRU cache for finished translations.
 *
 * Why this exists: web-novel readers re-open chapters, scroll back, and re-read; vBook re-requests
 * a chapter whenever the reader leaves and returns. Chunking is deterministic, so the same chapter
 * always produces the same chunks - every one of those round trips currently pays full neural cost
 * for text the engine has already translated. Exact-match caching is the only speed-up in this
 * project that cannot degrade quality, because it returns previously produced model output verbatim.
 *
 * Two independent bounds keep it phone-friendly ([maxEntries] and [maxPayloadChars]): a chapter is
 * a few hundred chunks, so [DEFAULT_MAX_ENTRIES] covers roughly ten chapters of history at well
 * under 10 MB of UTF-16.
 *
 * **Not thread-safe.** Callers must serialize access (the Android service already does: every
 * translation runs under a single permit). Keeping the lock out of here is deliberate - common code
 * cannot use `synchronized`, and a future Linux/Windows server will want its own strategy.
 */
class TranslationCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxPayloadChars: Int = DEFAULT_MAX_PAYLOAD_CHARS,
) {

    /** A node in both the hash index and the recency list. */
    private class Node(
        val key: String,
        val value: String,
        /** Toward the head, i.e. the next *more* recently used entry. Null on the head. */
        var newer: Node? = null,
        /** Toward the tail, i.e. the next *less* recently used entry. Null on the tail. */
        var older: Node? = null,
    )

    private val index = HashMap<String, Node>()

    /** Most recently used. */
    private var head: Node? = null

    /** Least recently used; evicted first. */
    private var tail: Node? = null

    private var payloadChars = 0L
    private var hits = 0L
    private var misses = 0L

    val size: Int get() = index.size

    /** Cached translation, or null on a miss. Counts both outcomes for [stats]. */
    fun get(key: String): String? {
        val node = index[key]
        if (node == null) {
            misses++
            return null
        }
        hits++
        if (node !== head) {
            unlink(node)
            linkNewest(node)
        }
        return node.value
    }

    /**
     * Stores [value] and evicts least-recently-used entries until both bounds hold.
     *
     * An entry costing more than [maxPayloadChars] is rejected rather than stored and immediately
     * evicted, which would otherwise flush the whole cache for one pathological chunk.
     */
    fun put(key: String, value: String) {
        val cost = key.length + value.length
        if (cost > maxPayloadChars) return

        val existing = index.remove(key)
        if (existing != null) {
            unlink(existing)
            payloadChars -= (existing.key.length + existing.value.length)
        }

        val node = Node(key, value)
        index[key] = node
        linkNewest(node)
        payloadChars += cost

        while ((index.size > maxEntries || payloadChars > maxPayloadChars) && tail != null) {
            val victim = tail ?: break
            unlink(victim)
            index.remove(victim.key)
            payloadChars -= (victim.key.length + victim.value.length)
        }
    }

    fun clear() {
        index.clear()
        head = null
        tail = null
        payloadChars = 0
        hits = 0
        misses = 0
    }

    fun stats(): Stats = Stats(
        entries = index.size,
        payloadChars = payloadChars,
        hits = hits,
        misses = misses,
    )

    /**
     * Every entry, most recently used first.
     *
     * This is the shape a durable tier needs: re-inserting the list in reverse rebuilds exactly the
     * same recency order, so a restart does not throw away what the reader had already earned.
     */
    fun snapshot(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(index.size)
        var node = head
        while (node != null) {
            out.add(node.key to node.value)
            node = node.older
        }
        return out
    }

    /**
     * Replaces the contents with [entries], **least recently used first**, as a restart would.
     *
     * Counters reset deliberately: replaying a file is not a burst of cache hits, and counting it as
     * such would make the hit-rate metric meaningless on the first chapter of every session.
     */
    fun restore(entries: List<Pair<String, String>>) {
        clear()
        for ((key, value) in entries) put(key, value)
    }

    data class Stats(
        val entries: Int,
        val payloadChars: Long,
        val hits: Long,
        val misses: Long,
    ) {
        /** Integer percent (0-100) so it can be dropped into a hand-built JSON body. */
        val hitRatePercent: Int
            get() {
                val total = hits + misses
                return if (total == 0L) 0 else ((hits * 100) / total).toInt()
            }
    }

    /** Links [node] as the most recently used entry. */
    private fun linkNewest(node: Node) {
        node.newer = null
        node.older = head
        head?.newer = node
        head = node
        if (tail == null) tail = node
    }

    /** Removes [node] from the recency list only; the caller owns the index entry. */
    private fun unlink(node: Node) {
        node.older?.newer = node.newer
        node.newer?.older = node.older
        if (head === node) head = node.older
        if (tail === node) tail = node.newer
        node.newer = null
        node.older = null
    }

    companion object {
        /**
         * Chunk history. A 3 000-character chapter is ~20-40 chunks at the measured 150-character
         * budget, so this holds on the order of a hundred chapters - far more than a reader re-opens
         * inside one app lifetime.
         */
        const val DEFAULT_MAX_ENTRIES = 4096

        /**
         * Hard ceiling on retained source + target characters.
         *
         * At the default entry count a full cache costs ~400 characters per entry (~1.6 M), so this
         * bound only engages when [maxEntries] is raised or chunks grow much larger. It is a ceiling
         * on RAM, not the working limit - do not size the cache by it.
         */
        const val DEFAULT_MAX_PAYLOAD_CHARS = 3_000_000

        /**
         * Cache keys are namespaced by everything that changes the output: the model on disk and
         * the beam count. '\u0000' cannot appear in novel text, so it is collision-proof as a
         * separator (unlike ':' or '|').
         */
        fun key(model: String, beams: Int, text: String): String =
            buildString(model.length + text.length + 8) {
                append(model)
                append('\u0000')
                append(beams)
                append('\u0000')
                append(text)
            }
    }
}
