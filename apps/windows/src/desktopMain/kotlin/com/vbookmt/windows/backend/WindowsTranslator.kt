package com.vbookmt.windows.backend

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.processor.ChapterChunker
import com.vbookmt.core.processor.ChapterTranslationPipeline
import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.windows.backend.onnx.WindowsOnnxEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Translates one chapter on Windows through ONNX Runtime, delegating the pipeline to
 * [ChapterTranslationPipeline] in `core`.
 */
object WindowsTranslator {

    const val MIN_CHUNK_CHARS = ChapterChunker.MIN_CHUNK_CHARS
    const val MAX_CHUNK_CHARS = ChapterChunker.MAX_CHUNK_CHARS

    private val gate = Mutex()

    @Volatile
    private var chunkCharsValue: Int = ChapterChunker.DEFAULT_CHUNK_CHARS

    @Volatile
    var cacheEnabled: Boolean = true

    val activeThreads: Int get() = WindowsNativeTokenizer.recommendedThreadCount()

    val maxBatchSize: Int get() = WindowsOnnxEngine.maxBatchSize

    val chunkChars: Int get() = chunkCharsValue

    fun isReady(): Boolean = WindowsOnnxEngine.isReady()

    fun setChunkChars(value: Int) {
        chunkCharsValue = value.coerceIn(MIN_CHUNK_CHARS, MAX_CHUNK_CHARS)
    }

    fun setBatchSize(value: Int) = WindowsOnnxEngine.setBatchSize(value)

    fun setThreads(value: Int) {
        if (value > 0) WindowsOnnxEngine.setThreads(value)
    }

    fun clearCache() {
        WindowsState.cache.clear()
        WindowsCacheStore.clear()
    }

    fun cacheStats(): TranslationCache.Stats = WindowsState.cache.stats()

    suspend fun init(
        modelId: String = WindowsState.activeModel.value,
        threads: Int = 0,
    ): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            val spec = ModelRegistry.getById(modelId)
            val modelDir = File(WindowsState.modelsDir, spec.id)

            if (!WindowsModelDownloader.isModelReady(spec)) {
                WindowsState.log("Mô hình ${spec.name} chưa tải xong trên máy")
                return@withLock false
            }

            val native = WindowsNativeTokenizer.nativeDir()
            if (!WindowsNativeTokenizer.ensureLoaded(native)) {
                WindowsState.log(
                    "LỖI: ${WindowsNativeTokenizer.loadError() ?: "không nạp được thư viện native"}"
                )
                return@withLock false
            }

            if (threads > 0) WindowsOnnxEngine.setThreads(threads)
            WindowsState.log("Nạp ONNX: ${spec.name}")
            val ok = WindowsOnnxEngine.init(modelDir)
            if (ok) {
                WindowsState.setActiveModel(spec.id)
                WindowsState.log("Sẵn sàng: ${spec.name} (ONNX, ${WindowsOnnxEngine.providerLabel})")
            } else {
                WindowsState.log("LỖI: ONNX không nạp được mô hình ${spec.name}")
            }
            ok
        }
    }

    suspend fun translate(rawText: String, numBeams: Int = 1): String = withContext(Dispatchers.IO) {
        if (rawText.isBlank()) return@withContext ""

        val activeTarget = File(WindowsState.modelsDir, WindowsState.activeModel.value).absolutePath
        if (!isReady() || WindowsOnnxEngine.getActiveModelPath() != activeTarget) {
            init()
            if (!isReady()) {
                throw IllegalStateException("Mô hình chưa sẵn sàng hoặc chưa được tải về.")
            }
        }

        val engineDelegate = object : ChapterTranslationPipeline.EngineDelegate {
            override fun isReady(): Boolean = WindowsOnnxEngine.isReady()
            override fun getActiveModelPath(): String? = WindowsOnnxEngine.getActiveModelPath()
            override val maxBatchSize: Int get() = WindowsOnnxEngine.maxBatchSize
            override fun tokenCount(text: String): Int = WindowsNativeTokenizer.tokenCount(text)
            override suspend fun rawTranslateBatch(texts: List<String>, numBeams: Int): List<String>? =
                WindowsOnnxEngine.rawTranslateBatch(texts, numBeams)
        }

        val cacheDelegate = object : ChapterTranslationPipeline.CacheDelegate {
            override val cacheEnabled: Boolean get() = this@WindowsTranslator.cacheEnabled
            override fun get(key: String): String? = WindowsState.cache.get(key)
            override fun put(key: String, value: String) { WindowsState.cache.put(key, value) }
            override fun onPersist(entries: List<Pair<String, String>>) { WindowsCacheStore.append(entries) }
        }

        val res = gate.withLock {
            ChapterTranslationPipeline.translate(
                rawText = rawText,
                chunkChars = chunkChars,
                tokenBudget = WindowsNativeTokenizer.TOKEN_BUDGET,
                numBeams = numBeams,
                engine = engineDelegate,
                cache = cacheDelegate,
                logger = { WindowsState.log(it) }
            )
        }

        WindowsState.log(
            "Dịch xong: ${res.paragraphCount} đoạn, ${res.chunkCount} chunks, " +
                "${res.translatedText.length} ký tự (chunk $chunkChars ký tự)"
        )
        res.translatedText
    }
}
