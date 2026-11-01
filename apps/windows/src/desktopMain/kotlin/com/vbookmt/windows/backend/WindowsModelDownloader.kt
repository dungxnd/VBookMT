package com.vbookmt.windows.backend

import com.vbookmt.core.download.ModelDownloader as CoreDownloader
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

object WindowsModelDownloader {

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

    fun isModelReady(modelSpec: ModelSpec, variant: QuantVariant? = null): Boolean {
        val modelDir = File(WindowsState.modelsDir, modelSpec.id)
        return CoreDownloader.isModelReady(
            modelDir = modelDir,
            modelSpec = modelSpec,
            variant = variant,
            currentInstalledVariant = installedVariant(modelSpec)
        )
    }

    fun installedVariant(modelSpec: ModelSpec): QuantVariant =
        WindowsState.installedVariant(modelSpec.id)

    fun startDownload(
        modelSpec: ModelSpec,
        variant: QuantVariant? = QuantVariant.DEFAULT,
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
            if (ok) {
                runCatching {
                    WindowsTranslator.init(
                        modelSpec.id,
                        WindowsState.savedThreads.value
                    )
                }
            }
            _downloadState.value = null
            withContext(Dispatchers.Main) {
                onComplete?.invoke(ok)
            }
        }
    }

    suspend fun downloadModel(
        modelSpec: ModelSpec,
        variant: QuantVariant? = QuantVariant.DEFAULT,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Boolean {
        val targetDir = File(WindowsState.modelsDir, modelSpec.id)
        val targetVariant = variant ?: QuantVariant.DEFAULT
        val ok = CoreDownloader.downloadModel(
            targetDir = targetDir,
            modelSpec = modelSpec,
            variant = targetVariant,
            currentInstalledVariant = installedVariant(modelSpec),
            userAgent = "VBookMT-Desktop/1.2.1",
            log = { WindowsState.log(it) },
            onProgress = onProgress
        )
        if (ok) {
            WindowsState.setInstalledVariant(modelSpec.id, targetVariant)
        }
        return ok
    }

    fun deleteModel(modelSpec: ModelSpec): Boolean {
        val modelDir = File(WindowsState.modelsDir, modelSpec.id)
        return CoreDownloader.deleteModel(modelDir) { WindowsState.log(it) }
    }
}
