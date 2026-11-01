package vn.dungxnd.vbookmt.service.onnx

import android.util.Log
import com.vbookmt.core.onnx.DelegateOnnxTokenizer
import com.vbookmt.core.onnx.ort.OnnxProvider
import com.vbookmt.core.onnx.ort.OrtEngineHost
import java.io.File
import vn.dungxnd.vbookmt.service.NativeTokenizer
import vn.dungxnd.vbookmt.service.ServiceState

object OnnxTranslator {

    private const val TAG = "OnnxTranslator"

    const val DEFAULT_MAX_BATCH_SIZE = OrtEngineHost.DEFAULT_MAX_BATCH_SIZE
    const val MAX_STEPS = OrtEngineHost.MAX_STEPS

    private val host = OrtEngineHost()

    val maxBatchSize: Int get() = host.maxBatchSize
    val providerLabel: String get() = host.providerLabel
    val activeThreads: Int get() = host.activeThreads

    fun setProviderOverride(id: String) = host.setProviderOverride(id)
    fun setThreads(value: Int) = host.setThreads(value)
    fun isReady(): Boolean = host.isReady()
    fun getActiveModelPath(): String? = host.getActiveModelPath()
    fun setBatchSize(value: Int) = host.setBatchSize(value)

    @Synchronized
    fun init(modelDir: File, provider: OnnxProvider? = null): Boolean {
        if (!NativeTokenizer.load(modelDir.absolutePath)) {
            ServiceState.log("ONNX: không nạp được tokenizer (thiếu .spm hoặc thư viện native)")
            return false
        }
        val tokenizer = DelegateOnnxTokenizer(
            encodeFn = { NativeTokenizer.encode(it) },
            decodeFn = { NativeTokenizer.decode(it) }
        )

        val markerDir = modelDir.parentFile?.parentFile
        val threads = host.resolveThreads(NativeTokenizer.recommendedThreadCount(), markerDir)
        val targetProvider = host.resolveProvider(provider ?: OnnxProvider.defaultForPlatform(), markerDir)

        val ok = host.init(
            modelDir = modelDir,
            tokenizer = tokenizer,
            threads = threads,
            provider = targetProvider,
            onLog = { ServiceState.log(it) }
        )
        if (ok) {
            Log.i(TAG, "ONNX model loaded at ${modelDir.absolutePath} on $providerLabel")
        }
        return ok
    }

    fun closeQuietly() = host.closeQuietly()

    fun rawTranslateBatch(pieces: List<String>, numBeams: Int = 1): List<String>? =
        host.rawTranslateBatch(pieces, numBeams) { ServiceState.log(it) }

    fun benchmarkMs(pieces: List<String>, numBeams: Int = 1): Long =
        host.benchmarkMs(pieces) { ServiceState.log(it) }

    fun tokenCount(text: String): Int = NativeTokenizer.tokenCount(text)
}
