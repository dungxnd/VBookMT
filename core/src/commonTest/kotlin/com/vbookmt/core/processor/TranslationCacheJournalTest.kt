package com.vbookmt.core.processor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationCacheJournalTest {

    private fun roundTrip(key: String, value: String) {
        val line = TranslationCacheJournal.encode(key, value)
        // A record must stay on one line: that is the whole framing contract.
        assertEquals(false, line.contains('\n'), "record contained a raw newline: $line")
        assertEquals(key to value, TranslationCacheJournal.decode(line))
    }

    @Test
    fun plainRecordSurvivesARoundTrip() {
        roundTrip("m1\u00001\u0000他点了点头。", "Hắn gật đầu.")
    }

    @Test
    fun escapedCharactersSurviveARoundTrip() {
        // Real payloads can carry any of these: newline markers in decoded output, a backslash in a
        // name, and the NUL separators that TranslationCache.key uses between its fields.
        roundTrip("k", "line one\nline two")
        roundTrip("k", "tab\there")
        roundTrip("k", "back\\slash")
        roundTrip("k", "carriage\rreturn")
        roundTrip("k", "nul\u0000inside")
        roundTrip("k", "\\n is not a newline")
        roundTrip("key\twith\ttabs", "value")
    }

    @Test
    fun anEmptyTranslationIsStillAValidRecord() {
        roundTrip("k\u00001\u0000text", "")
    }

    @Test
    fun aRealCacheKeyRoundTripsIncludingItsSeparators() {
        val key = TranslationCache.key("/data/user/0/app/files/models/hachimi-60-qt", 2, "他抬头看向远处的山门。")
        roundTrip(key, "Hắn ngẩng đầu nhìn về phía sơn môn.")
        // The decoded key must still hash to the same cache slot, separators included.
        assertEquals(key, TranslationCacheJournal.decode(TranslationCacheJournal.encode(key, "v"))?.first)
    }

    @Test
    fun corruptRecordsAreRejectedInsteadOfGuessed() {
        // No field separator at all.
        assertNull(TranslationCacheJournal.decode("no-separator-here"))
        // Separator at position 0 means an empty key, which a real record can never have.
        assertNull(TranslationCacheJournal.decode("\tvalue"))
        // Truncated write: the record ends mid-escape.
        assertNull(TranslationCacheJournal.decode("key\tvalue\\"))
        // Unknown escape sequence: written by a newer format or corrupted bytes.
        assertNull(TranslationCacheJournal.decode("key\tvalue\\q"))
        assertNull(TranslationCacheJournal.decode("key\\q\tvalue"))
    }

    @Test
    fun theHeaderIsStableAndVersioned() {
        // Changing this discards every existing journal, so it must be a deliberate act.
        assertEquals("vbookmt-cache/1", TranslationCacheJournal.HEADER)
    }


    // -----------------------------------------------------------------------------------------
    // The on-disk contract, exercised the way the host uses it: header, records oldest-first, then
    // a replay into a fresh cache.
    // -----------------------------------------------------------------------------------------

    @Test
    fun aWrittenJournalRebuildsTheCacheExactly() {
        val original = TranslationCache(maxEntries = 8, maxPayloadChars = 100_000)
        val keyOne = TranslationCache.key("/models/m", 1, "一。")
        val keyTwo = TranslationCache.key("/models/m", 1, "二。")
        val keyThree = TranslationCache.key("/models/m", 1, "三。")
        original.put(keyOne, "Một.")
        original.put(keyTwo, "Hai.")
        original.put(keyThree, "Ba.")
        original.get(keyOne) // touches recency, so order is not just insertion order

        // What the durable tier writes: the header, then records oldest-first.
        val file = buildString {
            append(TranslationCacheJournal.HEADER).append('\n')
            for ((key, value) in original.snapshot().asReversed()) {
                append(TranslationCacheJournal.encode(key, value)).append('\n')
            }
        }

        // What the durable tier replays.
        val lines = file.split('\n').filter { it.isNotEmpty() }
        assertEquals(TranslationCacheJournal.HEADER, lines.first())
        val records = lines.drop(1).mapNotNull { TranslationCacheJournal.decode(it) }

        val reloaded = TranslationCache(maxEntries = 8, maxPayloadChars = 100_000)
        reloaded.restore(records)

        // Same contents, same recency order, same payload accounting: a restart is invisible.
        assertEquals(original.snapshot(), reloaded.snapshot())
        assertEquals(original.stats().payloadChars, reloaded.stats().payloadChars)
    }

    @Test
    fun aTruncatedTailKeepsEveryCompleteRecordBeforeIt() {
        val first = "k1\u00001\u0000一。"
        val second = "k2\u00001\u0000二。"
        val complete = listOf(first to "Một.", second to "Hai.")
        // A write cut mid-record: the value ends on a lone backslash, i.e. inside an escape sequence.
        // Dropping the last character of a *well-formed* record would not do: "…\\b" minus its "b" is
        // still a perfectly valid record, which is exactly why the framing has to be checked this way.
        val truncated = "k3\ta\\"
        assertNull(TranslationCacheJournal.decode(truncated))

        val replayed = ArrayList<Pair<String, String>>()
        for (line in complete.map { (k, v) -> TranslationCacheJournal.encode(k, v) } + truncated) {
            val record = TranslationCacheJournal.decode(line) ?: break
            replayed.add(record)
        }
        // Losing the interrupted batch is the whole cost, which is what the design promises.
        assertEquals(complete, replayed)
    }
}
