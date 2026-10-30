package vn.dungxnd.vbookmt.service

import android.content.Context
import android.util.Log
import com.vbookmt.core.processor.TranslationCacheJournal
import java.io.File
import java.io.IOException

/**
 * Durable tier for the chunk memo: an append-only journal, replayed at startup.
 *
 * Why this exists: the in-memory memo dies with the process, and the process dies whenever Android
 * feels like it. Without a durable tier, "re-opening a chapter costs nothing" was true only inside a
 * single app lifetime - precisely the window in which a reader is least likely to re-open it.
 *
 * The trade-offs, all deliberate:
 *  - **Append per finished batch; never rewrite per chapter.** Rewriting a few megabytes after every
 *    chapter is measurable flash wear on a phone. Appending a few kilobytes is not.
 *  - **Replay is sequential and stops at the first bad record**, so a kill mid-write costs one batch
 *    instead of the file.
 *  - **Compaction** rewrites only live entries once the journal outgrows what it needs to hold, so it
 *    cannot grow without bound.
 *  - **No database.** The payload is regenerable, so there is no schema, no migration and no
 *    transaction here. Losing the file costs time, never correctness.
 *
 * Lives in the host rather than in core because it is the only part that touches a filesystem; the
 * record format itself is core's.
 *
 * **Lock order.** The memo's monitor may be acquired while holding [lock] (compaction snapshots the
 * memo), but never the other way round: callers must hand their entries over *after* releasing the
 * memo monitor. That is why [append] takes plain pairs rather than reading the memo itself.
 */
object CacheStore {

    private const val TAG = "CacheStore"

    private const val DIR_NAME = "translation-cache"
    private const val FILE_NAME = "journal.log"

    /** Rewrite the journal once it exceeds this multiple of what it actually needs to hold. */
    private const val COMPACT_RATIO = 2L

    /** Hard ceiling, whatever the entries cost. */
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024

    /** Below this a rewrite costs more flash than it saves. */
    private const val MIN_COMPACT_BYTES = 512L * 1024

    /** Stat'ing the file on every batch is pointless; check this often instead. */
    private const val SIZE_CHECK_INTERVAL = 32

    private val lock = Any()
    private var journal: File? = null
    private var appendsSinceCheck = 0
    private var failureLogged = false

    @Volatile
    private var restoredCount = 0

    /** Entries recovered by the last [attach]; 0 when there was nothing to replay. */
    fun restoredEntries(): Int = restoredCount

    /** Journal size in bytes - the cheap, honest signal that persistence is actually working. */
    fun diskBytes(): Long = synchronized(lock) {
        val file = journal ?: return 0L
        if (file.exists()) file.length() else 0L
    }

    /**
     * Replays the journal into the memo, creating the file if missing. Idempotent.
     *
     * Blocking and proportional to the file size: call from an IO dispatcher, never the main thread.
     * Nothing depends on it having finished - a request that arrives first simply sees a cold memo.
     */
    fun attach(context: Context) {
        synchronized(lock) {
            if (journal != null) return
            // noBackupFilesDir, not cacheDir: Android may purge cacheDir under storage pressure, which
            // would quietly undo the point of persisting. Not the ordinary files dir either, because a
            // few megabytes of regenerable cache has no business inflating a cloud backup.
            val dir = File(context.noBackupFilesDir, DIR_NAME)
            val file = File(dir, FILE_NAME)
            journal = file
            dir.mkdirs()
            if (file.exists()) replayLocked(file)
        }
    }

    /** Appends finished translations. Best-effort: a failed write never changes a response. */
    fun append(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        synchronized(lock) {
            val file = journal ?: return
            try {
                val text = StringBuilder(entries.size * 320)
                for ((key, value) in entries) {
                    text.append(TranslationCacheJournal.encode(key, value)).append('\n')
                }
                file.appendText(text.toString(), Charsets.UTF_8)
                failureLogged = false

                if (++appendsSinceCheck >= SIZE_CHECK_INTERVAL) {
                    appendsSinceCheck = 0
                    if (needsCompactionLocked(file)) rewriteLocked(file)
                }
            } catch (e: IOException) {
                // A full or read-only disk must not take translation down with it. The memo still
                // works for this session; it just will not survive the next restart.
                if (!failureLogged) {
                    failureLogged = true
                    Log.w(TAG, "Không ghi được journal: ${e.message}")
                    ServiceState.log("CẢNH BÁO: không ghi được cache xuống đĩa (${e.message})")
                }
            }
        }
    }

    /** Drops the persisted tier; called together with clearing the in-memory memo. */
    fun clear() {
        synchronized(lock) {
            appendsSinceCheck = 0
            restoredCount = 0
            val file = journal ?: return
            if (file.exists() && !file.delete()) Log.w(TAG, "Không xoá được journal")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Replay
    // -----------------------------------------------------------------------------------------

    private fun replayLocked(file: File) {
        val entries = ArrayList<Pair<String, String>>(1024)
        var corrupt = false
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                if (reader.readLine() != TranslationCacheJournal.HEADER) {
                    corrupt = true
                    return@use
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    val record = TranslationCacheJournal.decode(line)
                    if (record == null) {
                        corrupt = true
                        break
                    }
                    entries.add(record)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Không đọc được journal: ${e.message}")
            corrupt = true
        }

        if (entries.isEmpty()) {
            // Nothing usable. A cache file is always safe to discard - that is exactly what makes it a
            // cache - so do not attempt a salvage.
            if (corrupt) file.delete()
            return
        }

        ChunkTranslator.restoreFromDisk(entries)
        restoredCount = entries.size
        ServiceState.log("Đã nhớ lại $restoredCount đoạn đã dịch từ lần chạy trước")

        if (corrupt || needsCompactionLocked(file)) rewriteLocked(file)
    }

    // -----------------------------------------------------------------------------------------
    // Compaction
    // -----------------------------------------------------------------------------------------

    private fun needsCompactionLocked(file: File): Boolean {
        val size = file.length()
        if (size > MAX_FILE_BYTES) return true
        if (size <= MIN_COMPACT_BYTES) return false
        val live = liveBytesLocked()
        return live == 0L || size > live * COMPACT_RATIO
    }

    /** Exactly what a freshly compacted journal would weigh. */
    private fun liveBytesLocked(): Long {
        var bytes = TranslationCacheJournal.HEADER.length + 1L
        for ((key, value) in ChunkTranslator.snapshotForDisk()) {
            bytes += TranslationCacheJournal.encode(key, value).toByteArray(Charsets.UTF_8).size + 1L
        }
        return bytes
    }

    /**
     * Rewrites the journal from the live memo through a temp file and a rename, so an interruption
     * leaves either the old file or the new one - never half of each.
     */
    private fun rewriteLocked(file: File) {
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        try {
            temp.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(TranslationCacheJournal.HEADER)
                writer.newLine()
                // Oldest first: replaying the file must rebuild the same recency order.
                for ((key, value) in ChunkTranslator.snapshotForDisk().asReversed()) {
                    writer.write(TranslationCacheJournal.encode(key, value))
                    writer.newLine()
                }
            }
            if (file.exists() && !file.delete()) throw IOException("không xoá được journal cũ")
            if (!temp.renameTo(file)) throw IOException("không đổi tên được journal mới")
            Log.i(TAG, "Đã nén journal xuống ${file.length()} byte")
        } catch (e: IOException) {
            Log.w(TAG, "Nén journal thất bại: ${e.message}")
            temp.delete()
        }
    }
}
