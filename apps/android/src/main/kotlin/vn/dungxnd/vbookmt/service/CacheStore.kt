package vn.dungxnd.vbookmt.service

import android.content.Context
import android.util.Log
import com.vbookmt.core.processor.DurableCacheStore
import java.io.File

object CacheStore {

    private const val TAG = "CacheStore"
    private const val DIR_NAME = "translation-cache"
    private const val FILE_NAME = "journal.log"

    private val delegate = DurableCacheStore(
        maxFileBytes = 8L * 1024 * 1024,
        minCompactBytes = 512L * 1024,
        compactRatio = 2L,
        sizeCheckInterval = 32
    )

    fun restoredEntries(): Int = delegate.restoredEntries()

    fun diskBytes(): Long = delegate.diskBytes()

    fun attach(context: Context) {
        val dir = File(context.noBackupFilesDir, DIR_NAME)
        val file = File(dir, FILE_NAME)
        delegate.attach(
            file = file,
            onRestored = { ChunkTranslator.restoreFromDisk(it) },
            onLog = { Log.i(TAG, it) }
        )
    }

    fun append(entries: List<Pair<String, String>>) {
        delegate.append(
            entries = entries,
            snapshotProvider = { ChunkTranslator.snapshotForDisk() },
            onLog = { Log.w(TAG, it) }
        )
    }

    fun compact() {
        delegate.compact(
            snapshotProvider = { ChunkTranslator.snapshotForDisk() },
            onLog = { Log.i(TAG, it) }
        )
    }

    fun clear() {
        delegate.clear { Log.w(TAG, it) }
    }
}
