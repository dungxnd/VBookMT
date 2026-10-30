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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.service.Ct2Translator
import vn.dungxnd.vbookmt.service.ServiceState
import vn.dungxnd.vbookmt.util.ModelDownloader
import java.io.File

/**
 * The model chooser: section header plus one [ModelCard] per bundled model.
 *
 * The install flow for both models is the same six steps (log, download with progress, update the
 * two pieces of state, select the model, preload the engine, toast), so it lives in [install] once
 * instead of being copy-pasted per card. Installed/downloading flags are local UI state - nothing
 * outside this section reads them.
 */
@Composable
fun ModelSection(
    selectedModel: ModelSpec,
    onSelectModel: (ModelSpec) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var hachimiInstalled by remember {
        mutableStateOf(ModelDownloader.isModelReady(context, ModelRegistry.HACHIMI_60_QT))
    }
    var moxhiInstalled by remember {
        mutableStateOf(ModelDownloader.isModelReady(context, ModelRegistry.MOXHI_30_QT))
    }
    var hachimiDownloading by remember { mutableStateOf(false) }
    var moxhiDownloading by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionHeader(title = "Mô hình Dịch thuật", badge = "CTranslate2 INT8")

        ModelCard(
            model = ModelRegistry.HACHIMI_60_QT,
            isSelected = selectedModel.id == ModelRegistry.HACHIMI_60_QT.id,
            badge = "Mặc định • 55MB • BLEU 53.0",
            isInstalled = hachimiInstalled,
            isDownloading = hachimiDownloading,
            onSelect = { onSelectModel(ModelRegistry.HACHIMI_60_QT) },
            onDownload = {
                install(
                    context = context,
                    scope = coroutineScope,
                    spec = ModelRegistry.HACHIMI_60_QT,
                    onDownloadingChange = { hachimiDownloading = it },
                    onInstalledChange = { hachimiInstalled = it },
                    onSelectModel = onSelectModel,
                )
            }
        )

        ModelCard(
            model = ModelRegistry.MOXHI_30_QT,
            isSelected = selectedModel.id == ModelRegistry.MOXHI_30_QT.id,
            badge = "Siêu nhẹ • 36MB • Tiết kiệm RAM",
            isInstalled = moxhiInstalled,
            isDownloading = moxhiDownloading,
            onSelect = { onSelectModel(ModelRegistry.MOXHI_30_QT) },
            onDownload = {
                install(
                    context = context,
                    scope = coroutineScope,
                    spec = ModelRegistry.MOXHI_30_QT,
                    onDownloadingChange = { moxhiDownloading = it },
                    onInstalledChange = { moxhiInstalled = it },
                    onSelectModel = onSelectModel,
                )
            }
        )
    }
}

/**
 * Downloads one model, then selects it and preloads the engine.
 *
 * Progress is logged every 25% rather than per file chunk: the debug console keeps only the last
 * 150 lines and per-chunk logging pushed everything else out.
 */
private fun install(
    context: Context,
    scope: CoroutineScope,
    spec: ModelSpec,
    onDownloadingChange: (Boolean) -> Unit,
    onInstalledChange: (Boolean) -> Unit,
    onSelectModel: (ModelSpec) -> Unit,
) {
    // "ngocdang83/HachimiMT-60-QT" -> "HachimiMT-60-QT" / "HachimiMT" for logs and toasts, so the
    // wording matches what the card and the releases page call the model.
    val label = spec.repoId.substringAfter('/')
    val shortLabel = label.substringBefore('-')

    onDownloadingChange(true)
    ServiceState.log("Bắt đầu tải $label (${spec.sizeMb}MB) từ HuggingFace...")
    scope.launch {
        val ok = ModelDownloader.downloadModel(context, spec) { progress ->
            if (progress.percent % 25 == 0) {
                ServiceState.log(
                    "Đang tải ${progress.currentFile}: ${progress.percent}% " +
                        "(${progress.fileIndex}/${progress.totalFiles})"
                )
            }
        }
        onDownloadingChange(false)
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
        ServiceState.log("Đã tải xong $label thành công!")
        Toast.makeText(context, "Đã tải xong $label!", Toast.LENGTH_SHORT).show()
        withContext(Dispatchers.IO) {
            Ct2Translator.init(File(context.filesDir, "models/${spec.id}").absolutePath)
        }
    }
}
