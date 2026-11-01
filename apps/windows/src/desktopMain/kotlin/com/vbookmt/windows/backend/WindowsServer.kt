package com.vbookmt.windows.backend

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.processor.TranslationCache
import com.vbookmt.core.server.BaseTranslatorRpcService
import com.vbookmt.core.server.KtorTranslatorServer
import com.vbookmt.windows.backend.onnx.WindowsOnnxEngine
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import java.io.File

/**
 * The Windows host's loopback surface: gRPC-Web only, delegating to [KtorTranslatorServer] in `core`.
 */
object WindowsServer {

    const val PORT = 8765

    val currentHost: String
        get() = if (WindowsState.lanDiscoveryEnabled.value) "0.0.0.0" else "127.0.0.1"

    @Volatile
    private var serverEngine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /** Adapts the desktop backend to the shared RPC contract. */
    private object Service : BaseTranslatorRpcService(
        hostName = "windows",
        port = PORT,
        maxTranslateTimeoutMs = 110_000L,
    ) {
        override val activeModelId: String get() = WindowsState.activeModel.value
        override val isEngineReady: Boolean get() = WindowsTranslator.isReady()
        override val activeThreads: Int get() = WindowsTranslator.activeThreads
        override val maxBatchSize: Int get() = WindowsTranslator.maxBatchSize
        override val providerLabel: String get() = WindowsOnnxEngine.providerLabel
        override var chunkChars: Int
            get() = WindowsTranslator.chunkChars
            set(value) { WindowsTranslator.setChunkChars(value) }
        override var cacheEnabled: Boolean
            get() = WindowsTranslator.cacheEnabled
            set(value) { WindowsTranslator.cacheEnabled = value }

        override fun cacheStats(): TranslationCache.Stats = WindowsTranslator.cacheStats()
        override fun diskCacheBytes(): Long = WindowsCacheStore.diskBytes()
        override fun restoredCacheEntries(): Int = WindowsCacheStore.restoredEntries()
        override fun requestCount(): Int = WindowsState.requestCount.value
        override fun inFlightCount(): Int = WindowsState.inFlight.get()

        override suspend fun ensureReady(): Boolean =
            WindowsTranslator.isReady() || WindowsTranslator.init()

        override suspend fun executeTranslate(text: String): String =
            WindowsTranslator.translate(text)

        override fun onNoteRequestStarted() {
            WindowsState.noteRequestStarted()
        }

        override fun onInFlightChanged(delta: Int) {
            WindowsState.changeInFlight(delta)
        }

        override fun onRecordLatency(latencyMs: Long) {
            WindowsState.setLastLatency(latencyMs)
        }

        override fun log(message: String) {
            WindowsState.log(message)
        }

        override fun onApplyBatchSize(batchSize: Int) {
            WindowsTranslator.setBatchSize(batchSize)
        }

        override suspend fun onReloadThreads(threads: Int) {
            WindowsTranslator.init(threads = threads)
        }

        override fun onClearCache() {
            WindowsTranslator.clearCache()
        }
    }

    fun start() {
        if (serverEngine != null) return
        var attempts = 0
        while (attempts < 3) {
            try {
                val engine = KtorTranslatorServer.create(
                    port = PORT,
                    host = currentHost,
                    serviceTitle = "VBookMT Desktop 1.2.1",
                    service = Service
                )
                engine.start(wait = false)
                serverEngine = engine
                WindowsState.setRunning(true)
                return
            } catch (e: Exception) {
                attempts++
                if (attempts < 3) {
                    Thread.sleep(150)
                } else {
                    WindowsState.log("Không thể khởi động server: ${e.message}")
                    WindowsState.setRunning(false)
                }
            }
        }
    }

    fun stop() {
        try {
            serverEngine?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        } catch (_: Exception) {
        }
        serverEngine = null
        WindowsState.setRunning(false)
    }

    fun restart() {
        val wasRunning = serverEngine != null
        stop()
        if (wasRunning) start()
    }

    suspend fun warmUp() {
        WindowsCacheStore.attach()
        val spec = ModelRegistry.getById(WindowsState.activeModel.value)
        if (WindowsModelDownloader.isModelReady(spec)) {
            WindowsTranslator.init(
                modelId = spec.id,
                threads = WindowsState.savedThreads.value,
            )
        } else {
            WindowsState.log(
                "Mô hình ${spec.name} chưa tải về. Mở mục Mô hình để tải (${spec.sizeMb} MB)."
            )
        }
    }

    fun nativeDir(): File = WindowsNativeTokenizer.nativeDir()
}
