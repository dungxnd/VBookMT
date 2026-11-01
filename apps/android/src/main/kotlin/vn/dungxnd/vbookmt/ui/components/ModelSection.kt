package vn.dungxnd.vbookmt.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import vn.dungxnd.vbookmt.service.Engines
import vn.dungxnd.vbookmt.service.ServiceState
import vn.dungxnd.vbookmt.util.ModelDownloader
import java.io.File

/**
 * The model chooser: section header plus one [ModelCard] per shipped model.
 *
 * The install flow is persistent and survives Activity destruction/rotation via [ModelDownloader.downloadState].
 */
@Composable
fun ModelSection(
    selectedModel: ModelSpec,
    onSelectModel: (ModelSpec) -> Unit,
) {
    val context = LocalContext.current
    val downloadState by ModelDownloader.downloadState.collectAsState()
    val downloadingModelId = downloadState?.takeIf { it.isDownloading }?.modelId
    val downloadPercent = if (downloadingModelId != null) downloadState?.percent else null
    val isAnyDownloading = downloadState?.isDownloading == true

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(title = "Mô hình Dịch thuật", badge = "ONNX · FP16 / Q8")

        for (model in ModelRegistry.ALL_MODELS) {
            // Which precision this card is set to. Kept per-model so switching cards does not carry a
            // choice across to a different artifact, and seeded from what is already on disk so a
            // model installed at Q8 does not read as "not installed" because the picker says FP16.
            var variant by remember(model.id) {
                mutableStateOf(ModelDownloader.installedVariant(context, model))
            }
            val isInstalled = remember(model.id, variant, downloadState) {
                ModelDownloader.isModelReady(context, model, variant)
            }
            val isDownloading = downloadingModelId == model.id

            val shownVariant = if (model.hasVariants) variant else null
            // Every shipped artifact is ONNX with two quant variants (FP16 / Q8), so the badge always names the
            // picked variant and its real download size.
            val badge = buildString {
                if (model.isDefault) append("Mặc định • ")
                append("ONNX ")
                append(shownVariant?.label ?: "FP16")
                append(" • ")
                append(model.sizeMbFor(shownVariant))
                append("MB • gọt 6k")
            }

            ModelCard(
                model = model,
                isSelected = selectedModel.id == model.id,
                badge = badge,
                isInstalled = isInstalled,
                isDownloading = isDownloading,
                isAnyDownloading = isAnyDownloading,
                downloadPercent = if (isDownloading) downloadPercent else null,
                variants = model.variants,
                selectedVariant = shownVariant,
                onSelectVariant = { picked ->
                    variant = picked
                },
                onSelect = { onSelectModel(model) },
                onDownload = {
                    if (isAnyDownloading) return@ModelCard
                    install(
                        context = context,
                        spec = model,
                        variant = shownVariant,
                        onSelectModel = onSelectModel,
                    )
                }
            )
        }
    }
}

/**
 * Initiates persistent background model download at [variant], then selects it and preloads the engine.
 */
private fun install(
    context: Context,
    spec: ModelSpec,
    onSelectModel: (ModelSpec) -> Unit,
    variant: QuantVariant? = null,
) {
    val label = spec.repoId.substringAfter('/')
    val shortLabel = label.substringBefore('-')
    val sizeMb = spec.sizeMbFor(variant)
    val variantLabel = variant?.let { " ${it.label}" } ?: ""

    ServiceState.log("Bắt đầu tải $label$variantLabel (${sizeMb}MB) từ HuggingFace...")
    ModelDownloader.startDownload(
        context = context,
        modelSpec = spec,
        variant = variant,
        onComplete = { ok ->
            if (!ok) {
                ServiceState.log("LỖI: Tải $shortLabel thất bại. Vui lòng kiểm tra mạng.")
                Toast.makeText(
                    context,
                    "Tải mô hình thất bại, vui lòng thử lại!",
                    Toast.LENGTH_SHORT
                ).show()
                return@startDownload
            }

            onSelectModel(spec)
            ServiceState.log("Đã tải xong $label$variantLabel thành công!")
            Toast.makeText(context, "Đã tải xong $label!", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                Engines.ensureActiveLoaded(File(context.filesDir, "models/${spec.id}"))
            }
        }
    )
}
