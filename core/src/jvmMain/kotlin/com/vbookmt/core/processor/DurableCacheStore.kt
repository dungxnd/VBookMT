package com.vbookmt.core.processor

import java.io.File

/**
 * Shared durable tier for the translation memo: an append-only journal replayed at startup.
 * Thread-safe across appends and compactions.
 */
class DurableCacheStore(
    private val maxFileBytes: Long = 32L * 1024 * 1024,
    private val minCompactBytes: Long = 512L * 1024,
    private val compactRatio: Long = 2L,
    private val sizeCheckInterval: Int = 32
) {
    private val lock = Any()
    private var journal: File? = null
    private var appendsSinceCheck = 0
    private var failureLogged = false

    @Volatile
    private var restoredCount = 0

    fun restoredEntries(): Int = restoredCount

    fun diskBytes(): Long = synchronized(lock) {
        val file = journal ?: return 0L
        if (file.exists()) file.length() else 0L
    }

    fun attach(
        file: File,
        onRestored: (List<Pair<String, String>>) -> Unit,
        onLog: (String) -> Unit = {}
    ) {
        synchronized(lock) {
            if (journal != null) return
            try {
                file.parentFile?.mkdirs()
                journal = file

                val restored = replay(file)
                restoredCount = restored.size
                if (restored.isNotEmpty()) {
                    onRestored(restored)
                    onLog("Bộ nhớ đệm: khôi phục ${restored.size} đoạn từ lần chạy trước")
                }
            } catch (e: Exception) {
                onLog("Bộ nhớ đệm: không đọc được nhật ký (${e.message}) - bỏ qua")
            }
        }
    }

    fun detachForTests() {
        synchronized(lock) {
            journal = null
            restoredCount = 0
            failureLogged = false
            appendsSinceCheck = 0
        }
    }

    private fun replay(file: File): List<Pair<String, String>> {
        if (!file.exists() || file.length() == 0L) {
            file.parentFile?.mkdirs()
            file.writeText(TranslationCacheJournal.HEADER + "\n")
            return emptyList()
        }

        val lines = file.readLines()
        if (lines.isEmpty() || lines[0].trim() != TranslationCacheJournal.HEADER) {
            file.delete()
            file.writeText(TranslationCacheJournal.HEADER + "\n")
            return emptyList()
        }

        val restored = ArrayList<Pair<String, String>>(lines.size)
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val entry = TranslationCacheJournal.decode(line) ?: break
            restored.add(entry)
        }
        return restored
    }

    fun append(
        entries: List<Pair<String, String>>,
        snapshotProvider: (() -> List<Pair<String, String>>)? = null,
        onLog: (String) -> Unit = {}
    ) {
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

                if (snapshotProvider != null) {
                    appendsSinceCheck++
                    if (appendsSinceCheck >= sizeCheckInterval) {
                        appendsSinceCheck = 0
                        maybeCompactLocked(file, snapshotProvider, onLog)
                    }
                }
            } catch (e: Exception) {
                if (!failureLogged) {
                    onLog("Lỗi ghi bộ nhớ đệm (${e.message})")
                    failureLogged = true
                }
            }
        }
    }

    private fun maybeCompactLocked(
        file: File,
        snapshotProvider: () -> List<Pair<String, String>>,
        onLog: (String) -> Unit
    ) {
        val fileBytes = file.length()
        if (fileBytes < minCompactBytes) return

        val snapshot = snapshotProvider()
        val neededBytes = snapshot.sumOf { (k, v) -> k.length + v.length + 8L }
        val triggerBytes = (neededBytes * compactRatio).coerceAtLeast(minCompactBytes)

        if (fileBytes > triggerBytes || fileBytes > maxFileBytes) {
            compactLocked(file, snapshot, onLog)
        }
    }

    private fun compactLocked(
        file: File,
        entries: List<Pair<String, String>>,
        onLog: (String) -> Unit
    ) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            val beforeBytes = file.length()
            val text = StringBuilder(entries.size * 320 + 32)
            text.append(TranslationCacheJournal.HEADER).append('\n')
            for ((key, value) in entries) {
                text.append(TranslationCacheJournal.encode(key, value)).append('\n')
            }
            temp.writeText(text.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
            val afterBytes = file.length()
            onLog("Nén nhật ký cache: $beforeBytes -> $afterBytes bytes (${entries.size} mục)")
        } catch (e: Exception) {
            temp.delete()
            onLog("Nén cache thất bại (${e.message})")
        }
    }

    fun compact(
        snapshotProvider: () -> List<Pair<String, String>>,
        onLog: (String) -> Unit = {}
    ) {
        synchronized(lock) {
            val file = journal ?: return
            compactLocked(file, snapshotProvider(), onLog)
        }
    }

    fun clear(onLog: (String) -> Unit = {}) {
        synchronized(lock) {
            val file = journal ?: return
            try {
                file.delete()
                file.writeText(TranslationCacheJournal.HEADER + "\n")
                restoredCount = 0
            } catch (e: Exception) {
                onLog("Lỗi làm mới cache (${e.message})")
            }
        }
    }

    fun forget(
        keys: Collection<String>,
        snapshotProvider: () -> List<Pair<String, String>>,
        onLog: (String) -> Unit = {}
    ) {
        if (keys.isEmpty()) return
        synchronized(lock) {
            val file = journal ?: return
            val keySet = if (keys is Set<String>) keys else keys.toSet()
            val surviving = snapshotProvider().filter { (k, _) -> k !in keySet }
            compactLocked(file, surviving, onLog)
        }
    }
}
