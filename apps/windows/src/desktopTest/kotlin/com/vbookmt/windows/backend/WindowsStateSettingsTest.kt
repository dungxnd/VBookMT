package com.vbookmt.windows.backend

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Settings round-trip: what the performance sweep saves must be what the next launch applies.
 *
 * This is the regression test for the bug where the benchmark picked a thread count, applied it for
 * the session only, and silently reverted on restart — the screen promised "ghi nhớ kết quả" and did
 * not keep it, because nothing called `setSavedTuning`.
 *
 * `WindowsState` persists to its own file under `LOCALAPPDATA` and has no injectable path, so these
 * cases snapshot and restore that file. Without the snapshot they leak state into each other: a case
 * that leaves `threads=2` on disk makes the next case's reload legitimately answer 2.
 */
class WindowsStateSettingsTest {

    /**
     * The file `WindowsState` actually writes: `<LOCALAPPDATA>/VBookMT/settings.properties`.
     *
     * Mirrored here rather than exposed from production — the store resolves its own location and
     * should keep doing so. Getting this path wrong is what made the first version of this test
     * assert against a file nothing ever wrote, so it is spelled out with the same fallback.
     */
    private val settingsFile: java.io.File
        get() {
            val localAppData = System.getenv("LOCALAPPDATA")
            val root = if (!localAppData.isNullOrBlank()) {
                java.io.File(localAppData, "VBookMT")
            } else {
                java.io.File(System.getProperty("user.home") ?: ".", ".vbookmt")
            }
            return java.io.File(root, "settings.properties")
        }

    private var backup: String? = null

    @BeforeTest
    fun snapshot() {
        val f = settingsFile
        backup = if (f.exists()) f.readText() else null
    }

    @AfterTest
    fun restore() {
        val f = settingsFile
        val saved = backup
        if (saved != null) f.parentFile?.mkdirs().also { f.writeText(saved) } else f.delete()
    }

    @Test
    fun `setSavedTuning records the winning configuration in memory`() {
        WindowsState.setSavedTuning(threads = 6, computeType = "int8")
        assertEquals(6, WindowsState.savedThreads.value)
        assertEquals("int8", WindowsState.savedComputeType.value)
    }

    @Test
    fun `a non-positive thread count is ignored rather than overwriting a real choice`() {
        WindowsState.setSavedTuning(threads = 6, computeType = "int8")
        WindowsState.setSavedTuning(threads = 0, computeType = "int8")
        // 0 means "not measured", not "use zero threads".
        assertEquals(6, WindowsState.savedThreads.value)
    }

    @Test
    fun `save then load restores the tuning the sweep chose`() {
        WindowsState.setSavedTuning(threads = 5, computeType = "int8")

        // The sweep's choice must actually reach disk -- that is the whole contract.
        assertTrue(settingsFile.exists(), "setSavedTuning must write the settings file")
        assertTrue(
            settingsFile.readText().contains("threads=5"),
            "settings file should record threads=5, was: ${settingsFile.readText()}",
        )

        // Loading reads the file rather than trusting whatever is in memory: change it underneath and
        // the next load must follow the file.
        settingsFile.writeText(settingsFile.readText().replace("threads=5", "threads=7"))
        WindowsState.loadSettings()
        assertEquals(7, WindowsState.savedThreads.value, "loadSettings must read the file, not memory")
        assertEquals("int8", WindowsState.savedComputeType.value)
    }

    @Test
    fun `a benchmark result is what the next launch applies`() {
        // Mirrors the real sequence the performance screen performs after a sweep.
        val measured = 6
        WindowsState.setSavedTuning(measured, "int8")
        WindowsState.loadSettings()
        assertEquals(
            measured,
            WindowsState.savedThreads.value,
            "warmUp() reads savedThreads, so this is what the next session configures the engine with",
        )
    }
}
