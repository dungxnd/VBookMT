package vn.dungxnd.vbookmt.service

import com.vbookmt.core.processor.ChunkResolver
import com.vbookmt.core.processor.TextProcessor
import com.vbookmt.core.processor.TranslationCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Turns a chapter's chunks into translated text, spending engine time only where it can help.
 *
 * The whole chapter is resolved in one pass ([ChunkResolver]) and only the surviving misses are
 * batched: chunks are grouped *after* the cache and passthrough stages, not before. Slicing first
 * (the old order) turned a re-read of a 7 000-character chapter into three one-sequence native
 * calls instead of one full batch, and it made batching depend on chapter layout - a miss near the
 * start and a miss near the end could never share a batch. Resolving first also lets repeat lines
 * collapse into one decode and lets each batch be length-homogeneous, which is where the win on a
 * cold read comes from.
 *
 * Cache access is serialized by this object's monitor and the monitor is never held across a native
 * call or a suspension point, so `POST /config` can clear the cache while a chapter is in flight
 * without tearing the LRU's index and its recency list apart.
 */
object ChunkTranslator {

    /**
     * Exact-match LRU of finished chunk translations, namespaced by model + beam in the key so one
     * cache can serve every configuration without returning another model's output.
     */
    private val cache = TranslationCache()

    /** Toggleable so a device test can measure with and without caching (`POST /config`). */
    @Volatile
    var cacheEnabled: Boolean = true

    @Synchronized
    fun cacheStats(): TranslationCache.Stats = cache.stats()

    /**
     * Drops both tiers. The durable tier goes too: a clear that only empties memory would come back
     * from the journal on the next start, which is not what "clear" means to the person asking.
     */
    fun clearCache() {
        clearMemory()
        CacheStore.clear()
    }

    @Synchronized
    private fun clearMemory() = cache.clear()

    /** Memo contents, most recently used first, for the durable tier to rewrite. */
    @Synchronized
    fun snapshotForDisk(): List<Pair<String, String>> = cache.snapshot()

    /**
     * Replaces the memo with what the durable tier replayed, **least recently used first**.
     *
     * Entries produced by a model that is no longer active simply never match a lookup again and age
     * out through normal eviction; filtering them on load would need the engine to be up first, and a
     * few hundred dead entries cost less than the complexity.
     */
    @Synchronized
    fun restoreFromDisk(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        cache.restore(entries)
    }

    /**
     * Translates [pieces] and returns one entry per input, in input order.
     *
     * Never throws and never returns Han text: anything the engine fails to translate falls back to
     * Sino-Vietnamese for that item only, so one bad chunk cannot degrade a chapter.
     *
     * `suspend` on purpose - it yields between engine groups so the caller's `withTimeout` can fire.
     * A fully blocking implementation silently ignores its own timeout.
     */
    suspend fun translateChunks(pieces: List<String>, numBeams: Int = 1): List<String> {
        if (pieces.isEmpty()) return emptyList()

        // Read the model once for the whole request: /config can reload the model mid-chapter, and
        // re-deriving the key per chunk would then mix two models into one output.
        val model = Ct2Translator.getActiveModelPath() ?: "-"

        val plan = resolveLocked(pieces, model, numBeams)
        val resolved: MutableList<String?> = plan.resolved.toMutableList()

        if (plan.misses.isEmpty()) {
            logStages(plan, groups = 0)
            return finish(resolved)
        }

        // Never hand the reader raw Han text: fall back per item when the engine is cold.
        if (!Ct2Translator.isReady()) {
            ServiceState.log(
                "THÔNG BÁO: CT2 chưa sẵn sàng -> dùng Hán-Việt fallback cho ${plan.misses.size} đoạn"
            )
            fillFallback(resolved, plan.misses)
            return finish(resolved)
        }

        val groups = ChunkResolver.batches(
            misses = plan.misses,
            tokenCount = Ct2Translator::tokenCount,
            batchSize = Ct2Translator.maxBatchSize,
        )
        var fallbackPositions = 0

        for (group in groups) {
            currentCoroutineContext().ensureActive()

            val translated = Ct2Translator.rawTranslateBatch(group.map { it.text }, numBeams)
            if (translated == null) {
                ServiceState.log("CẢNH BÁO: CT2 batch thất bại -> dùng Hán-Việt fallback")
                // Only the positions still unresolved fall back; earlier groups are already done.
                fillFallback(resolved, plan.misses)
                return finish(resolved)
            }

            val store = ArrayList<Pair<String, String>>(group.size)
            for (k in group.indices) {
                val miss = group[k]
                val item = translated[k]
                if (item.isBlank()) {
                    fallbackPositions += miss.positions.size
                    scatter(resolved, miss, TextProcessor.toSinoVietnamese(miss.text))
                } else {
                    scatter(resolved, miss, item)
                    // Only model output is cached: a Sino-Vietnamese fallback is a degraded result
                    // and must not be replayed once the engine recovers.
                    store.add(miss.text to item)
                }
            }
            // Persist outside the memo monitor: flash is slower than RAM, and /health must never wait
            // on a disk write.
            CacheStore.append(storeLocked(store, model, numBeams))
        }

        if (fallbackPositions > 0) {
            ServiceState.log("CẢNH BÁO: $fallbackPositions/${pieces.size} đoạn rơi vào Hán-Việt fallback")
        }
        logStages(plan, groups = groups.size)
        return finish(resolved)
    }

    /** Single-text convenience wrapper, used by the in-app quick test. */
    suspend fun translate(text: String, numBeams: Int = 1): String {
        if (text.isBlank()) return ""
        return translateChunks(listOf(text), numBeams).firstOrNull() ?: ""
    }

    // -----------------------------------------------------------------------------------------
    // Cache-guarded stages (each critical section is a few hash lookups; no native work inside)
    // -----------------------------------------------------------------------------------------

    @Synchronized
    private fun resolveLocked(pieces: List<String>, model: String, numBeams: Int): ChunkResolver.Result =
        ChunkResolver.resolve(pieces) { text ->
            if (cacheEnabled) cache.get(TranslationCache.key(model, numBeams, text)) else null
        }

    /**
     * Inserts finished translations and hands back the records the durable tier should keep.
     *
     * Returning them (rather than letting the store read the memo back) keeps the two locks apart:
     * persistence never has to acquire the memo's monitor. See CacheStore's lock-order note.
     */
    @Synchronized
    private fun storeLocked(
        entries: List<Pair<String, String>>,
        model: String,
        numBeams: Int,
    ): List<Pair<String, String>> {
        if (entries.isEmpty() || !cacheEnabled) return emptyList()

        val persisted = ArrayList<Pair<String, String>>(entries.size)
        for ((text, translation) in entries) {
            val key = TranslationCache.key(model, numBeams, text)
            cache.put(key, translation)
            persisted.add(key to translation)
        }
        return persisted
    }

    // -----------------------------------------------------------------------------------------
    // Pure helpers - operate on the caller's local array, so they need no lock
    // -----------------------------------------------------------------------------------------

    private fun scatter(resolved: MutableList<String?>, miss: ChunkResolver.Miss, value: String) {
        for (position in miss.positions) resolved[position] = value
    }

    /**
     * Sino-Vietnamese fallback for every position that is still unresolved, so a mid-chapter engine
     * failure keeps the work already done instead of discarding it.
     */
    private fun fillFallback(resolved: MutableList<String?>, misses: List<ChunkResolver.Miss>) {
        for (miss in misses) {
            val fallback = TextProcessor.toSinoVietnamese(miss.text)
            for (position in miss.positions) {
                if (resolved[position] == null) resolved[position] = fallback
            }
        }
    }

    private fun finish(resolved: List<String?>): List<String> = resolved.map { it ?: "" }

    /** One line per request, and only when something was actually skipped. */
    private fun logStages(plan: ChunkResolver.Result, groups: Int) {
        val skipped = plan.cacheHits + plan.passthrough + plan.duplicates
        if (plan.misses.isEmpty()) {
            if (skipped > 0) {
                ServiceState.log(
                    "Không cần engine: ${plan.cacheHits} đoạn lấy từ cache, " +
                        "${plan.passthrough} bỏ qua"
                )
            }
            return
        }
        if (skipped == 0) return
        ServiceState.log(
            "Lọc: ${plan.cacheHits} từ cache, ${plan.passthrough} bỏ qua (không Hán tự), " +
                "${plan.duplicates} trùng lặp -> ${plan.misses.size} đoạn tới engine trong $groups batch"
        )
    }
}
