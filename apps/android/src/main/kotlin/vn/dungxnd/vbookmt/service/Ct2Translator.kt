package vn.dungxnd.vbookmt.service

import android.util.Log

object Ct2Translator {
    private const val TAG = "Ct2Translator"
    private var isNativeLoaded = false
    private var isModelInitialized = false
    private var activeModelPath: String? = null

    init {
        try {
            System.loadLibrary("vbookmt_native")
            isNativeLoaded = true
            Log.i(TAG, "Native library 'vbookmt_native' loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            Log.e(TAG, "Failed to load 'vbookmt_native': ${e.message}")
        } catch (e: Exception) {
            isNativeLoaded = false
            Log.e(TAG, "Unexpected error loading native library: ${e.message}")
        }
    }

    private external fun nativeInit(modelDir: String, numThreads: Int): Boolean
    private external fun nativeTranslate(inputText: String, numBeams: Int): String

    fun isReady(): Boolean = isNativeLoaded && isModelInitialized

    fun getActiveModelPath(): String? = activeModelPath

    @Synchronized
    fun init(modelPath: String, threads: Int = 2): Boolean {
        if (!isNativeLoaded) {
            Log.w(TAG, "Cannot init model: Native library is not loaded.")
            return false
        }
        return try {
            val success = nativeInit(modelPath, threads)
            isModelInitialized = success
            if (success) {
                activeModelPath = modelPath
                Log.i(TAG, "Model successfully initialized at: $modelPath")
            } else {
                Log.e(TAG, "nativeInit returned false for path: $modelPath")
            }
            success
        } catch (e: Throwable) {
            isModelInitialized = false
            Log.e(TAG, "Error initializing CT2 model: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun translate(text: String, numBeams: Int = 1): String {
        if (text.isBlank()) {
            return ""
        }

        // If native library or neural model is not initialized yet,
        // translate via Sino-Vietnamese convert so user NEVER receives raw Chinese!
        if (!isNativeLoaded || !isModelInitialized) {
            return com.vbookmt.core.processor.TextProcessor.toSinoVietnamese(text)
        }

        return try {
            val res = nativeTranslate(text, numBeams)
            // If native layer is currently a stub or returns mock prefix,
            // immediately produce real Vietnamese convert output
            if (res.isBlank() || res.startsWith("[CT2 Native]")) {
                com.vbookmt.core.processor.TextProcessor.toSinoVietnamese(text)
            } else {
                res
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Exception during native translate: ${e.message}", e)
            com.vbookmt.core.processor.TextProcessor.toSinoVietnamese(text)
        }
    }
}
