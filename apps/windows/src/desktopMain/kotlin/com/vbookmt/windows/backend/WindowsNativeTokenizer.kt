package com.vbookmt.windows.backend

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Windows host's handle on the shared SentencePiece tokenizer (`native/`, compiled by
 * `native/windows/CMakeLists.txt` into `vbookmt_native.dll`).
 *
 * **Why this file is not called WindowsCt2Engine any more.** It used to be, because CTranslate2 owned
 * both translation and the two `.spm` tables. Translation now runs on ONNX Runtime, so what remains is
 * the tokenizer — and the class name is part of the JNI contract: `jni_bridge.cpp` exports
 * `Java_com_vbookmt_windows_backend_WindowsNativeTokenizer_*`, so renaming this class without renaming
 * the bridge (or the reverse) fails every native call with UnsatisfiedLinkError.
 *
 * **Loading.** `vbookmt_native.dll` no longer imports `ctranslate2.dll` — the CT2 linkage is gone with
 * the engine — so the DLL stands alone and can be loaded by absolute path.
 *
 * **Threading.** SentencePiece processors are not re-entrant, so every entry point locks.
 */
object WindowsNativeTokenizer {

    /** SentencePiece budget per chunk; the single source of truth is `ChapterChunker`. */
    const val TOKEN_BUDGET = 280

    private val loadState = AtomicBoolean(false)

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var tablesLoaded = false

    @Volatile
    private var loadedDir: String? = null

    /** True once the library is loaded and the `.spm` tables are in memory. */
    fun isReady(): Boolean = tablesLoaded

    /** Directory the tables came from; null until [load]. */
    fun getActiveModelPath(): String? = loadedDir

    /** Why the native library could not be loaded, or null. Shown verbatim in the diagnostics card. */
    fun loadError(): String? = loadError

    /**
     * Directory holding `vbookmt_native.dll`.
     *
     * Resolution order, most specific first:
     *  1. `-Dvbookmt.nativeDir=…` — set by the packaged launcher and by CI.
     *  2. `VBOOKMT_NATIVE_DIR` — used by `./gradlew run` during development.
     *  3. The directory containing this class's jar/classes — the normal install location, where the
     *     DLL sits beside the executable.
     *  4. The working directory, as a last resort.
     */
    fun nativeDir(): File {
        System.getProperty("vbookmt.nativeDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        System.getenv("VBOOKMT_NATIVE_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val codeLocation = runCatching {
            File(object {}.javaClass.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        val base = if (codeLocation?.isFile == true) codeLocation.parentFile else codeLocation
        return base ?: File(".").absoluteFile
    }

    /**
     * Loads `vbookmt_native.dll` from [nativeDir].
     *
     * Idempotent: a second call is a no-op, which matters because the UI may retry after a model
     * download without restarting the process.
     */
    fun ensureLoaded(nativeDir: File): Boolean {
        if (loadState.get()) return loadError == null
        synchronized(this) {
            if (loadState.get()) return loadError == null
            try {
                val engine = File(nativeDir, "vbookmt_native.dll")
                if (!engine.exists()) {
                    loadError = "Thiếu vbookmt_native.dll trong ${nativeDir.absolutePath}"
                    loadState.set(true)
                    return false
                }
                System.load(engine.absolutePath)
                loadError = null
            } catch (e: UnsatisfiedLinkError) {
                loadError = "Không nạp được thư viện native: ${e.message}"
            } catch (e: Throwable) {
                loadError = "Lỗi nạp thư viện native: ${e.message}"
            }
            loadState.set(true)
            return loadError == null
        }
    }

    // --- JNI surface, mirrored 1:1 from native/jni_bridge.cpp -------------------------------

    private external fun nativeLoad(modelDir: String): Boolean

    private external fun nativeTokenCount(text: String): Int

    private external fun nativeEncode(text: String): IntArray

    private external fun nativeDecode(ids: IntArray): String

    /**
     * Loads the SentencePiece tables for a model directory.
     *
     * @return false when the library is missing or `modelDir` has no `source.spm`/`target.spm`.
     */
    @Synchronized
    fun load(modelDir: String): Boolean {
        if (loadError != null) return false
        // Same directory already loaded: reloading resets the processors for no benefit, and init
        // runs on every model switch.
        if (tablesLoaded && loadedDir == modelDir) return true
        return try {
            nativeLoad(modelDir).also { ok ->
                tablesLoaded = ok
                loadedDir = if (ok) modelDir else null
            }
        } catch (e: Throwable) {
            WindowsState.log("LỖI nạp tokenizer: ${e.message}")
            tablesLoaded = false
            loadedDir = null
            false
        }
    }

    /** SentencePiece length of [text] plus the trailing `</s>`, or 0 when the tables are unavailable. */
    fun tokenCount(text: String): Int {
        if (text.isEmpty() || !tablesLoaded) return 0
        return try {
            nativeTokenCount(text)
        } catch (e: Throwable) {
            0
        }
    }

    /**
     * Source text to model ids, including the trailing `</s>`.
     *
     * @return empty only when the tokenizer is unavailable; callers treat that as "cannot encode"
     *   rather than as an empty sentence.
     */
    fun encode(text: String): LongArray {
        if (text.isEmpty() || !tablesLoaded) return LongArray(0)
        return try {
            nativeEncode(text).let { ints -> LongArray(ints.size) { ints[it].toLong() } }
        } catch (e: Throwable) {
            LongArray(0)
        }
    }

    /** Model ids back to text. Ids outside the target vocabulary are skipped, never fatal. */
    fun decode(ids: LongArray): String {
        if (ids.isEmpty() || !tablesLoaded) return ""
        return try {
            nativeDecode(IntArray(ids.size) { ids[it].toInt() })
        } catch (e: Throwable) {
            ""
        }
    }

    /**
     * Half the logical cores clamped to 2..8.
     *
     * ONNX Runtime does not manage its own pool, and intra-op threads beyond the physical core count
     * schedule onto SMT siblings, which on a laptop costs throughput and adds heat for no gain; the
     * performance screen can still override it with a measurement.
     */
    fun recommendedThreadCount(): Int =
        (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 8)
}
