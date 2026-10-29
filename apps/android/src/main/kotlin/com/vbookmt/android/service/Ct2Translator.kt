package com.vbookmt.android.service

object Ct2Translator {
    init {
        try {
            System.loadLibrary("vbookmt_native")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    external fun nativeInit(modelDir: String, numThreads: Int): Boolean
    external fun nativeTranslate(inputText: String, numBeams: Int): String

    fun init(modelPath: String, threads: Int = 2): Boolean {
        return try {
            nativeInit(modelPath, threads)
        } catch (e: Exception) {
            false
        }
    }

    fun translate(text: String, numBeams: Int = 1): String {
        return try {
            nativeTranslate(text, numBeams)
        } catch (e: Exception) {
            "Lỗi dịch CT2: ${e.message}"
        }
    }
}
