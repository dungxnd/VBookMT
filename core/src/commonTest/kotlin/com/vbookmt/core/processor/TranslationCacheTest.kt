package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranslationCacheTest {

    @Test
    fun missThenHitIsCounted() {
        val cache = TranslationCache()
        assertNull(cache.get("k1"))
        assertEquals(0L, cache.stats().hits)
        assertEquals(1L, cache.stats().misses)

        cache.put("k1", "v1")
        assertEquals("v1", cache.get("k1"))
        assertEquals(1L, cache.stats().hits)
        assertEquals(1L, cache.stats().misses)
        // One hit and one miss so far: (1 * 100) / 2.
        assertEquals(50, cache.stats().hitRatePercent)
    }

    @Test
    fun evictsLeastRecentlyUsedNotOldestInserted() {
        val cache = TranslationCache(maxEntries = 3, maxPayloadChars = 1_000_000)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")

        // Touch "a" so "b" becomes the least recently used entry.
        assertEquals("1", cache.get("a"))

        cache.put("d", "4")
        assertEquals(3, cache.size)
        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
        assertEquals("4", cache.get("d"))
        // "b" was evicted: a miss from here on.
        assertNull(cache.get("b"))
    }

    @Test
    fun respectsPayloadCharBound() {
        // Keys and values are 1 char each => 2 payload chars per entry.
        val cache = TranslationCache(maxEntries = 100, maxPayloadChars = 6)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals(3, cache.size)
        assertEquals(6L, cache.stats().payloadChars)

        cache.put("d", "D")
        assertEquals(3, cache.size)
        assertTrue(cache.stats().payloadChars <= 6)
        assertNull(cache.get("a"))
        assertEquals("D", cache.get("d"))
    }

    @Test
    fun rejectsEntryLargerThanTheWholeCache() {
        val cache = TranslationCache(maxEntries = 10, maxPayloadChars = 10)
        cache.put("k", "0123456789ABCDEF")
        assertEquals(0, cache.size)
    }

    @Test
    fun updatingSameKeyReplacesValueWithoutGrowing() {
        val cache = TranslationCache()
        cache.put("k", "old")
        cache.put("k", "new")
        assertEquals(1, cache.size)
        assertEquals("new", cache.get("k"))
        // One entry: key "k" (1) + value "new" (3). Replacing must not double-count.
        assertEquals(4L, cache.stats().payloadChars)
    }

    @Test
    fun clearDropsEntriesAndStats() {
        val cache = TranslationCache()
        cache.put("k", "v")
        cache.get("k")
        cache.get("missing")
        cache.clear()
        assertEquals(0, cache.size)
        assertEquals(0L, cache.stats().hits)
        assertEquals(0L, cache.stats().misses)
        assertNull(cache.get("k"))
    }

    @Test
    fun snapshotListsMostRecentlyUsedFirst() {
        val cache = TranslationCache(maxEntries = 10, maxPayloadChars = 1_000_000)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertEquals(listOf("c", "b", "a"), cache.snapshot().map { it.first })

        cache.get("a")
        assertEquals(listOf("a", "c", "b"), cache.snapshot().map { it.first })
    }

    @Test
    fun snapshotThenRestoreRoundTripsTheWholeState() {
        val cache = TranslationCache(maxEntries = 3, maxPayloadChars = 1_000_000)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        cache.get("b") // b becomes most recent, so a is now the least recently used
        val before = cache.snapshot()
        assertEquals(listOf("b", "c", "a"), before.map { it.first })

        val reloaded = TranslationCache(maxEntries = 3, maxPayloadChars = 1_000_000)
        // The journal stores records oldest-first, so the durable tier reverses on write.
        reloaded.restore(before.asReversed())
        assertEquals(before, reloaded.snapshot())

        // Recency survived the round trip: the next insert evicts a, not b.
        reloaded.put("d", "4")
        assertNull(reloaded.get("a"))
        assertEquals("2", reloaded.get("b"))
    }

    @Test
    fun restoreKeepsOnlyWhatTheCacheCanHold() {
        val cache = TranslationCache(maxEntries = 2, maxPayloadChars = 1_000_000)
        // Least recently used first: "a" is the oldest and must be evicted first.
        cache.restore(listOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(2, cache.size)
        assertEquals(listOf("c", "b"), cache.snapshot().map { it.first })
    }

    @Test
    fun restoreRefusesAnEntryLargerThanTheWholeCache() {
        val cache = TranslationCache(maxEntries = 10, maxPayloadChars = 10)
        cache.restore(listOf("k" to "0123456789ABCDEF"))
        assertEquals(0, cache.size)
    }

    @Test
    fun restoreIsNotCountedAsHitsOrMisses() {
        val cache = TranslationCache()
        cache.put("k", "v")
        cache.get("k")
        cache.get("missing")
        cache.restore(listOf("k" to "v"))

        // A replay is not a burst of cache hits: counting it would make the hit-rate metric
        // meaningless on the first chapter of every session.
        assertEquals(0L, cache.stats().hits)
        assertEquals(0L, cache.stats().misses)
        assertEquals(1, cache.stats().entries)
    }

    @Test
    fun keyIsNamespacedByModelAndBeams() {
        assertEquals(TranslationCache.key("m1", 1, "text"), TranslationCache.key("m1", 1, "text"))
        assertNotEquals(TranslationCache.key("m1", 1, "text"), TranslationCache.key("m2", 1, "text"))
        assertNotEquals(TranslationCache.key("m1", 1, "text"), TranslationCache.key("m1", 2, "text"))
        // The \u0000 separator prevents boundary-collision between model name and text.
        assertNotEquals(
            TranslationCache.key("ab", 1, "c"),
            TranslationCache.key("a", 1, "bc"),
        )
    }
}
