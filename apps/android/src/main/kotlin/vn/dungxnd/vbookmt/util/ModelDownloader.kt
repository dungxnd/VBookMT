package vn.dungxnd.vbookmt.util

import android.content.Context
import android.util.Log
import com.vbookmt.core.download.ModelDownloader as CoreDownloader
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    typealias DownloadProgress = CoreDownloader.DownloadProgress

    data class DownloadState(
        val modelId: String,
        val variant: QuantVariant,
        val percent: Int = 0,
        val currentFile: String = "",
        val isDownloading: Boolean = true
    )

    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    private var activeJob: Job? = null

    fun installedVariant(context: Context, modelSpec: ModelSpec): QuantVariant {
        val modelDir = File(context.filesDir, "models/${modelSpec.id}")
        val diskVariant = File(modelDir, ".variant").takeIf { it.exists() }?.readText()?.trim()
            ?.let { QuantVariant.parse(it) }
        if (diskVariant != null) return diskVariant

        return context.getSharedPreferences("vbookmt_models", Context.MODE_PRIVATE)
            .getString("variant_${modelSpec.id}", null)
            ?.let { QuantVariant.parse(it) }
            ?: QuantVariant.DEFAULT
    }

    fun setInstalledVariant(context: Context, modelSpec: ModelSpec, variant: QuantVariant) {
        val modelDir = File(context.filesDir, "models/${modelSpec.id}")
        if (modelDir.exists()) {
            runCatching { File(modelDir, ".variant").writeText(variant.folder) }
        }
        context.getSharedPreferences("vbookmt_models", Context.MODE_PRIVATE)
            .edit()
            .putString("variant_${modelSpec.id}", variant.folder)
            .apply()
    }

    fun firstInstalled(context: Context): ModelSpec? =
        ModelRegistry.ALL_MODELS.firstOrNull { isModelReady(context, it) }

    fun installedModels(context: Context): List<ModelSpec> =
        ModelRegistry.ALL_MODELS.filter { isModelReady(context, it) }

    fun isModelReady(context: Context, modelSpec: ModelSpec, variant: QuantVariant? = null): Boolean {
        val modelDir = File(context.filesDir, "models/${modelSpec.id}")
        return CoreDownloader.isModelReady(
            modelDir = modelDir,
            modelSpec = modelSpec,
            variant = variant,
            currentInstalledVariant = installedVariant(context, modelSpec)
        )
    }

    fun startDownload(
        context: Context,
        modelSpec: ModelSpec,
        variant: QuantVariant? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (_downloadState.value?.isDownloading == true) return
        val targetVariant = variant ?: QuantVariant.DEFAULT

        _downloadState.value = DownloadState(
            modelId = modelSpec.id,
            variant = targetVariant,
            percent = 0,
            isDownloading = true
        )

        activeJob = downloadScope.launch {
            val ok = downloadModel(
                context = context,
                modelSpec = modelSpec,
                variant = targetVariant,
                onProgress = { p ->
                    _downloadState.value = DownloadState(
                        modelId = modelSpec.id,
                        variant = targetVariant,
                        percent = p.percent,
                        currentFile = p.currentFile,
                        isDownloading = true
                    )
                }
            )
            _downloadState.value = null
            withContext(Dispatchers.Main) {
                onComplete?.invoke(ok)
            }
        }
    }

    suspend fun downloadModel(
        context: Context,
        modelSpec: ModelSpec,
        variant: QuantVariant? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Boolean {
        val targetDir = File(context.filesDir, "models/${modelSpec.id}")
        val targetVariant = variant ?: QuantVariant.DEFAULT
        val ok = CoreDownloader.downloadModel(
            targetDir = targetDir,
            modelSpec = modelSpec,
            variant = targetVariant,
            currentInstalledVariant = installedVariant(context, modelSpec),
            userAgent = "VBookMT-Android/1.2.1",
            log = { Log.i(TAG, it) },
            onProgress = onProgress
        )
        if (ok) {
            setInstalledVariant(context, modelSpec, targetVariant)
        }
        return ok
    }

    fun deleteModel(context: Context, modelSpec: ModelSpec): Boolean {
        val modelDir = File(context.filesDir, "models/${modelSpec.id}")
        return CoreDownloader.deleteModel(modelDir) { Log.i(TAG, it) }
    }
}
