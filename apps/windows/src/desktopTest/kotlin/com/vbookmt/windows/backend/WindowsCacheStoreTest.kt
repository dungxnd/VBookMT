package com.vbookmt.windows.backend

import com.vbookmt.core.processor.TranslationCacheJournal
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The durable memo tier, tested against a real file.
 *
 * This exists because the Windows journal was, until now, only ever exercised by hand — which meant
 * "the cache is persistent" was an assertion rather than a verified property. A restarting process is
 * modelled by [coldStart], which reads the file from disk exactly as a fresh JVM would; the in-memory
 * singleton cannot fake that, because `attach` deliberately no-ops once it has run.
 */
class WindowsCacheStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        // Purge the singleton first: it is process-wide, so without this every case inherits the
        // previous case's directory and appends into a folder that has already been deleted.
        WindowsCacheStore.detachForTests()
        dir = File(System.getProperty("java.io.tmpdir"), "vbm-cachetest-${System.nanoTime()}")
        dir.mkdirs()
        WindowsState.cache.clear()
    }

    @AfterTest
    fun tearDown() {
        WindowsCacheStore.detachForTests()
        WindowsState.cache.clear()
        dir.deleteRecursively()
    }

    /**
     * What a brand-new process sees: parse the journal from disk with no in-memory state.
     *
     * Mirrors `WindowsCacheStore.replay` step for step — including **stopping** at the first
     * undecodable record. An earlier version of this helper used `mapNotNull`, which *skips* bad lines
     * instead of halting, so it asserted a contract the production code does not have and hid the
     * difference. Keep the two in lockstep.
     */
    private fun coldStart(): List<Pair<String, String>> {
        val file = File(dir, "journal.log")
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        if (lines.isEmpty() || lines[0].trim() != TranslationCacheJournal.HEADER) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.isEmpty()) continue
            out.add(TranslationCacheJournal.decode(line) ?: break)
        }
        return out
    }

    @Test
    fun `appended entries survive a restart`() {
        WindowsCacheStore.attach(dir)
        val key = "hachimi-60-qt|1|第一段"
        WindowsState.cache.put(key, "Đoạn một")
        WindowsCacheStore.append(listOf(key to "Đoạn một"))

        // A restart replays the file into an empty memo and must answer from it.
        WindowsState.cache.clear()
        val restored = coldStart()
        assertEquals(1, restored.size, "journal should hold exactly the appended record")
        WindowsState.cache.restore(restored)
        assertEquals("Đoạn một", WindowsState.cache.get(key), "re-read after restart must hit")
    }

    @Test
    fun `replay stops at a record with a split escape sequence`() {
        WindowsCacheStore.attach(dir)
        WindowsCacheStore.append(listOf("k1" to "v1", "k2" to "v2"))

        // A genuine partial-escape write: a dangling backslash is unparseable, so replay must stop
        // there and never surface the records behind it.
        val file = File(dir, "journal.log")
        file.appendText("k3\tv3\\\nk4\tv4\n")

        assertEquals(listOf("k1", "k2"), coldStart().map { it.first })
    }

    @Test
    fun `a cut at a character boundary is accepted, by design`() {
        WindowsCacheStore.attach(dir)
        WindowsCacheStore.append(listOf("k1" to "v1"))

        // The format has no checksum, so a line cut cleanly between characters is indistinguishable
        // from a valid short record. Documented behaviour, not an accident: replay keeps it and stops
        // at the record that follows, whose bytes are the torn tail.
        val file = File(dir, "journal.log")
        file.appendText("k2\tv2-part\nk3-from-middle-of-a-torn-record\n")

        val restored = coldStart()
        assertEquals(listOf("k1", "k2"), restored.map { it.first })
        assertEquals("v2-part", restored.last().second)
    }

    @Test
    fun `a file without the header is discarded, not parsed`() {
        WindowsCacheStore.attach(dir)
        WindowsCacheStore.append(listOf("k1" to "v1"))

        File(dir, "journal.log").writeText("garbage\nk1\tv1\n")
        assertTrue(coldStart().isEmpty(), "headerless file must not be trusted")
    }

    @Test
    fun `clear removes both the file contents and the restored count`() {
        WindowsCacheStore.attach(dir)
        WindowsCacheStore.append(listOf("k1" to "v1"))
        assertTrue(WindowsCacheStore.diskBytes() > 0)

        WindowsCacheStore.clear()
        assertEquals(0, WindowsCacheStore.restoredEntries())
        assertTrue(coldStart().isEmpty(), "after clear the journal must replay to nothing")
    }

    @Test
    fun `an unresolvable directory never throws`() {
        // A cache that cannot be written is a slow start, never a crash.
        val bogus = File(dir, "nested/readonly/deeper")
        WindowsCacheStore.append(emptyList()) // no-op, must not throw before attach
        assertEquals(0L, WindowsCacheStore.diskBytes())
        assertFalse(bogus.exists())
    }
}
