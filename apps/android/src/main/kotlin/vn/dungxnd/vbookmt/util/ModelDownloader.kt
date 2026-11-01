package vn.dungxnd.vbookmt.util

import android.content.Context
import android.util.Log
import com.vbookmt.core.download.ModelDownloader as CoreDownloader
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import java.io.File

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    typealias DownloadProgress = CoreDownloader.DownloadProgress

    fun installedVariant(context: Context, modelSpec: ModelSpec): QuantVariant =
        context.getSharedPreferences("vbookmt_models", Context.MODE_PRIVATE)
            .getString("variant_${modelSpec.id}", null)
            ?.let { QuantVariant.parse(it) }
            ?: QuantVariant.DEFAULT

    fun setInstalledVariant(context: Context, modelSpec: ModelSpec, variant: QuantVariant) {
        context.getSharedPreferences("vbookmt_models", Context.MODE_PRIVATE)
            .edit()
            .putString("variant_${modelSpec.id}", variant.name)
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

    suspend fun downloadModel(
        context: Context,
        modelSpec: ModelSpec,
        variant: QuantVariant? = null,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean {
        val targetDir = File(context.filesDir, "models/${modelSpec.id}")
        val targetVariant = variant ?: QuantVariant.DEFAULT
        val ok = CoreDownloader.downloadModel(
            targetDir = targetDir,
            modelSpec = modelSpec,
            variant = targetVariant,
            currentInstalledVariant = installedVariant(context, modelSpec),
            userAgent = "VBookMT-Android/1.2.0",
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
