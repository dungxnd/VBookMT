package com.vbookmt.windows.backend

import com.vbookmt.core.processor.DurableCacheStore
import java.io.File

object WindowsCacheStore {

    private const val DIR_NAME = "translation-cache"
    private const val FILE_NAME = "journal.log"

    private val delegate = DurableCacheStore(
        maxFileBytes = 32L * 1024 * 1024,
        minCompactBytes = 512L * 1024,
        compactRatio = 2L,
        sizeCheckInterval = 32
    )

    fun restoredEntries(): Int = delegate.restoredEntries()

    fun diskBytes(): Long = delegate.diskBytes()

    private fun defaultDir(): File {
        val localAppData = System.getenv("LOCALAPPDATA")
        val root = if (!localAppData.isNullOrBlank()) {
            File(localAppData, "VBookMT")
        } else {
            File(System.getProperty("user.home") ?: ".", ".vbookmt")
        }
        return File(root, DIR_NAME)
    }

    fun attach(dir: File = defaultDir()) {
        val file = File(dir, FILE_NAME)
        delegate.attach(
            file = file,
            onRestored = { entries ->
                synchronized(WindowsState.cacheLock) {
                    WindowsState.cache.restore(entries)
                }
            },
            onLog = { WindowsState.log(it) }
        )
    }

    fun append(entries: List<Pair<String, String>>) {
        delegate.append(
            entries = entries,
            snapshotProvider = {
                synchronized(WindowsState.cacheLock) {
                    WindowsState.cache.snapshot()
                }
            },
            onLog = { WindowsState.log(it) }
        )
    }

    fun compact() {
        delegate.compact(
            snapshotProvider = {
                synchronized(WindowsState.cacheLock) {
                    WindowsState.cache.snapshot()
                }
            },
            onLog = { WindowsState.log(it) }
        )
    }

    fun clear() {
        delegate.clear { WindowsState.log(it) }
    }

    fun forget(keys: Collection<String>) {
        delegate.forget(
            keys = keys,
            snapshotProvider = {
                synchronized(WindowsState.cacheLock) {
                    WindowsState.cache.snapshot()
                }
            },
            onLog = { WindowsState.log(it) }
        )
    }

    fun detachForTests() {
        delegate.detachForTests()
    }
}
