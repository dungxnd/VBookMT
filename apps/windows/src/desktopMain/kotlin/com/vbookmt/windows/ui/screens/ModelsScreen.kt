package com.vbookmt.windows.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.windows.backend.WindowsModelDownloader
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.backend.WindowsTranslator
import com.vbookmt.windows.ui.Caption
import com.vbookmt.windows.ui.HairlineDivider
import com.vbookmt.windows.ui.PageHeader
import com.vbookmt.windows.ui.Panel
import com.vbookmt.windows.ui.PanelColumn
import com.vbookmt.windows.ui.SectionHeading
import com.vbookmt.windows.ui.StatusDot
import com.vbookmt.windows.ui.StatusTone
import com.vbookmt.windows.ui.Tag
import com.vbookmt.windows.ui.VBookTheme
import com.vbookmt.windows.ui.VSpace
import com.vbookmt.windows.ui.icons.AppIcons
import kotlinx.coroutines.launch

/**
 * Screen 2 — Mô hình.
 *
 * One row per model, no cards: the previous version stacked a bordered card inside a bordered card
 * *for each* model, so two models filled the window and the download control sat at a different
 * height in each. Here the layout is a list where the name, metadata, status and the single available
 * action all sit on one line, so comparing two models is a vertical scan.
 */
@Composable
fun ModelsScreen() {
    val activeModelId by WindowsState.activeModel.collectAsState()
    val scroll = rememberScrollState()
    var downloadingModelId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
        PageHeader(
            title = "Mô hình",
            subtitle = "Mô hình chạy hoàn toàn trên máy này. Tải một lần, dùng mãi.",
        )

        PanelColumn {
            for (model in ModelRegistry.ALL_MODELS) {
                ModelRow(
                    model = model,
                    isActive = model.id == activeModelId,
                    downloadingModelId = downloadingModelId,
                    onDownloadingChange = { isDownloading ->
                        downloadingModelId = if (isDownloading) model.id else null
                    },
                )
            }

            Panel {
                SectionHeading("Về các mô hình")
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "HachimiMT-60-QT là bản mặc định: chất lượng cao nhất, giữ đúng đại từ xưng hô " +
                        "của văn phong convert. MoxhiMT-30-QT nhẹ hơn khoảng 20 MB, phù hợp máy yếu " +
                        "hoặc khi cần tiết kiệm bộ nhớ."
                )
                VSpace(VBookTheme.spacing.sm)
                HairlineDivider()
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "Cả hai mô hình đều được tác giả phát hành công khai trên Hugging Face. " +
                        "Bản quyền và giấy phép thuộc về tác giả gốc."
                )
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: ModelSpec,
    isActive: Boolean,
    downloadingModelId: String? = null,
    onDownloadingChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val downloading = downloadingModelId == model.id
    val anyDownloading = downloadingModelId != null

    // Which precision this row is set to. Seeded from what is actually on disk so a model installed at
    // Q8 does not read as "not installed" because the picker defaults to FP16.
    var variant by remember(model.id) {
        mutableStateOf(WindowsModelDownloader.installedVariant(model))
    }
    var installed by remember(model.id) {
        mutableStateOf(WindowsModelDownloader.isModelReady(model, variant))
    }
    var switching by remember(model.id) { mutableStateOf(false) }
    var progress by remember(model.id) { mutableStateOf<WindowsModelDownloader.DownloadProgress?>(null) }

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = VBookTheme.palette.onSurface,
                    )
                    Spacer(Modifier.width(VBookTheme.spacing.sm))
                    Tag("${model.sizeMbFor(variant)} MB")
                    if (model.isDefault) {
                        Spacer(Modifier.width(6.dp))
                        Tag("Mặc định", StatusTone.Ok)
                    }
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Tag("Đang dùng", StatusTone.Ok)
                    }
                }
                VSpace(3.dp)
                Caption(model.description)
                VSpace(6.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(VBookTheme.spacing.md)) {
                    Caption("Tác giả: ${model.author}")
                    Caption("Giấy phép: ${model.license}")
                }

                // Precision picker. Every artifact publishes fp16 / q16 / q8 as separate folder builds,
                // so this changes both what gets downloaded and what runs. The phone has had this since
                // the variants shipped; desktop did not, which meant a desktop user silently got FP16
                // with no way to choose — the largest download of the three.
                if (model.hasVariants) {
                    VSpace(VBookTheme.spacing.sm)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (v in model.variants) {
                            val picked = v == variant
                            Surface(
                                shape = VBookTheme.radii.smShape,
                                color = if (picked) VBookTheme.palette.accent
                                else VBookTheme.palette.surfaceMuted,
                                modifier = Modifier.clickable(enabled = !anyDownloading) {
                                    variant = v
                                    // A different precision is a different set of files, so re-check
                                    // rather than assume — this is what makes the button reappear.
                                    installed = WindowsModelDownloader.isModelReady(model, v)
                                },
                            ) {
                                Text(
                                    text = "${v.label} · ${model.sizeMbFor(v)}MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (picked) VBookTheme.palette.onAccent
                                    else VBookTheme.palette.onSurfaceMuted,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    VSpace(4.dp)
                    Caption(variant.hint)
                }
            }

            Spacer(Modifier.width(VBookTheme.spacing.lg))

            // Exactly one action per row, chosen by state. A row never shows two competing buttons.
            when {
                downloading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { (progress?.percent ?: 0) / 100f },
                            modifier = Modifier.width(96.dp).height(4.dp),
                            color = VBookTheme.palette.accent,
                            trackColor = VBookTheme.palette.surfaceMuted,
                        )
                        Spacer(Modifier.width(VBookTheme.spacing.sm))
                        Caption("${progress?.percent ?: 0}%")
                    }
                }

                !installed -> {
                    Button(
                        onClick = {
                            if (anyDownloading) return@Button
                            onDownloadingChange(true)
                            scope.launch {
                                // Pass the picked precision: the three builds are separate folder
                                // downloads, so omitting it would silently always fetch FP16 and make
                                // the chip row above cosmetic.
                                val ok = WindowsModelDownloader.downloadModel(model, variant) { p ->
                                    progress = p
                                }
                                onDownloadingChange(false)
                                progress = null
                                if (ok) {
                                    installed = true
                                    // Downloading a model the user asked for is an implicit "use it".
                                    WindowsTranslator.init(
                                        model.id,
                                        WindowsState.savedThreads.value,
                                    )
                                }
                            }
                        },
                        enabled = !anyDownloading,
                        shape = VBookTheme.radii.mdShape,
                    ) {
                        Icon(AppIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tải về")
                    }
                }

                isActive -> StatusDot("Đang hoạt động", StatusTone.Ok)

                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                WindowsModelDownloader.deleteModel(model)
                                installed = false
                            },
                            enabled = !anyDownloading,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                AppIcons.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = VBookTheme.palette.danger,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Xoá", style = MaterialTheme.typography.labelMedium, color = VBookTheme.palette.danger)
                        }
                        Spacer(Modifier.width(VBookTheme.spacing.xs))
                        Button(
                            onClick = {
                                switching = true
                                scope.launch {
                                    WindowsTranslator.init(
                                        model.id,
                                        WindowsState.savedThreads.value,
                                    )
                                    switching = false
                                }
                            },
                            enabled = !switching && !anyDownloading,
                            shape = VBookTheme.radii.mdShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VBookTheme.palette.accentTonal,
                                contentColor = VBookTheme.palette.onAccentTonal,
                            ),
                        ) {
                            if (switching) {
                                Text("Đang nạp…", style = MaterialTheme.typography.labelLarge)
                            } else {
                                Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Dùng mô hình này")
                            }
                        }
                    }
                }
            }
        }
    }
}
