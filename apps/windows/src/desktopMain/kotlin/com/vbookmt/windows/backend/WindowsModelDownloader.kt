package com.vbookmt.windows.backend

import com.vbookmt.core.download.ModelDownloader as CoreDownloader
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import java.io.File

object WindowsModelDownloader {

    typealias DownloadProgress = CoreDownloader.DownloadProgress

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
            userAgent = "VBookMT-Desktop/1.2.0",
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
