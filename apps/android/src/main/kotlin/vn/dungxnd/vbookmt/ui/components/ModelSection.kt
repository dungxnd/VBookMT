package vn.dungxnd.vbookmt.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.service.Engines
import vn.dungxnd.vbookmt.service.ServiceState
import vn.dungxnd.vbookmt.util.ModelDownloader
import java.io.File

/**
 * The model chooser: section header plus one [ModelCard] per shipped model.
 *
 * The install flow is the same for both models (log, download with progress, update state, select,
 * preload the engine, toast), so it lives in [install] once instead of being copy-pasted per card.
 *
 * **Install state is per precision, not per model.** The three builds are separate folder downloads, so
 * a model sitting on disk as FP16 is *not* installed as Q8. [installed] therefore tracks the variant the
 * download actually wrote, and the card asks for a re-download when the user picks a different one —
 * rather than silently running the old weights under the new label.
 */
@Composable
fun ModelSection(
    selectedModel: ModelSpec,
    onSelectModel: (ModelSpec) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var downloadPercent by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(title = "Mô hình Dịch thuật", badge = "ONNX · FP16 / Q16 / Q8")

        for (model in ModelRegistry.ALL_MODELS) {
            // Which precision this card is set to. Kept per-model so switching cards does not carry a
            // choice across to a different artifact, and seeded from what is already on disk so a
            // model installed at Q8 does not read as "not installed" because the picker says FP16.
            var variant by remember(model.id) {
                mutableStateOf(ModelDownloader.installedVariant(context, model))
            }
            var isInstalled by remember(model.id) {
                mutableStateOf(ModelDownloader.isModelReady(context, model, variant))
            }
            val isDownloading = downloadingModelId == model.id
            val isAnyDownloading = downloadingModelId != null

            val shownVariant = if (model.hasVariants) variant else null
            // Every shipped artifact is ONNX with three quant variants, so the badge always names the
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
                    // A different precision is a different set of files, so re-check rather than
                    // assume: this is what makes the button reappear for an un-downloaded variant.
                    isInstalled = ModelDownloader.isModelReady(context, model, picked)
                },
                onSelect = { onSelectModel(model) },
                onDownload = {
                    if (isAnyDownloading) return@ModelCard
                    install(
                        context = context,
                        scope = coroutineScope,
                        spec = model,
                        variant = shownVariant,
                        onProgress = { downloadPercent = it },
                        onDownloadingChange = { downloading ->
                            downloadingModelId = if (downloading) model.id else null
                            if (!downloading) downloadPercent = null
                        },
                        onInstalledChange = { isInstalled = it },
                        onSelectModel = onSelectModel,
                    )
                }
            )
        }
    }
}

/**
 * Downloads one model at [variant], then selects it and preloads the engine.
 *
 * Progress is logged every 25% rather than per file chunk: the debug console keeps only the last
 * 150 lines and per-chunk logging pushed everything else out. The UI gets the raw percentage so the
 * card can show a real progress bar.
 */
private fun install(
    context: Context,
    scope: CoroutineScope,
    spec: ModelSpec,
    onDownloadingChange: (Boolean) -> Unit,
    onInstalledChange: (Boolean) -> Unit,
    onSelectModel: (ModelSpec) -> Unit,
    variant: QuantVariant? = null,
    onProgress: (Int?) -> Unit = {},
) {
    // "dungxnd/HachimiMT-60-QT-ONNX" -> "HachimiMT-60-QT-ONNX" / "HachimiMT" for logs and toasts, so
    // the wording matches what the card and the releases page call the model.
    val label = spec.repoId.substringAfter('/')
    val shortLabel = label.substringBefore('-')
    val sizeMb = spec.sizeMbFor(variant)
    val variantLabel = variant?.let { " ${it.label}" } ?: ""

    onDownloadingChange(true)
    onProgress(0)
    ServiceState.log("Bắt đầu tải $label$variantLabel (${sizeMb}MB) từ HuggingFace...")
    scope.launch {
        var lastLoggedPercent = -1
        val ok = ModelDownloader.downloadModel(context, spec, variant) { progress ->
            onProgress(progress.percent)
            if (progress.percent % 25 == 0 && progress.percent != lastLoggedPercent) {
                lastLoggedPercent = progress.percent
                ServiceState.log(
                    "Đang tải ${progress.currentFile}: ${progress.percent}% " +
                        "(${progress.fileIndex}/${progress.totalFiles})"
                )
            }
        }
        onDownloadingChange(false)
        onProgress(null)
        onInstalledChange(ok)

        if (!ok) {
            ServiceState.log("LỖI: Tải $shortLabel thất bại. Vui lòng kiểm tra mạng.")
            Toast.makeText(
                context,
                "Tải mô hình thất bại, vui lòng thử lại!",
                Toast.LENGTH_SHORT
            ).show()
            return@launch
        }

        onSelectModel(spec)
        ServiceState.log("Đã tải xong $label$variantLabel thành công!")
        Toast.makeText(context, "Đã tải xong $label!", Toast.LENGTH_SHORT).show()
        withContext(Dispatchers.IO) {
            Engines.ensureActiveLoaded(File(context.filesDir, "models/${spec.id}"))
        }
    }
}
