package com.vbookmt.windows.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.windows.backend.WindowsModelDownloader
import com.vbookmt.windows.backend.WindowsServer
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.backend.WindowsTranslator
import com.vbookmt.windows.ui.AppSwitch
import com.vbookmt.windows.ui.Caption
import com.vbookmt.windows.ui.DetailRow
import com.vbookmt.windows.ui.HairlineDivider
import com.vbookmt.windows.ui.InsetPanel
import com.vbookmt.windows.ui.MetricBlock
import com.vbookmt.windows.ui.PageHeader
import com.vbookmt.windows.ui.Panel
import com.vbookmt.windows.ui.PanelColumn
import com.vbookmt.windows.ui.QuietIconButton
import com.vbookmt.windows.ui.SectionHeading
import com.vbookmt.windows.ui.StatusDot
import com.vbookmt.windows.ui.StatusTone
import com.vbookmt.windows.ui.Tag
import com.vbookmt.windows.ui.VBookTheme
import com.vbookmt.windows.ui.VSpace
import com.vbookmt.windows.ui.icons.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import kotlin.system.measureTimeMillis

/**
 * Screen 1 — Tổng quan.
 *
 * The reader's question is "can I read right now, and how fast?", so the screen answers that first
 * and buries nothing else in the way:
 *
 *  - **One primary action**: start or stop the server. It is the only filled button on the screen.
 *  - **Three numbers**: characters/second, requests served, last chapter latency. These are the
 *    only figures a reader can act on.
 *  - **Everything else** is a panel below the fold: connection details, the translation playground.
 *
 * 1.1.0 spread these across a hero card, a model card, a performance card, a test card and a logs
 * card, every one of them equally loud and equally bordered, so the answer to "is it working" was
 * three scrolls down.
 */
@Composable
fun OverviewScreen(onOpenModels: () -> Unit) {
    val scope = rememberCoroutineScope()
    val isRunning by WindowsState.isRunning.collectAsState()
    val activeModelId by WindowsState.activeModel.collectAsState()
    val requestCount by WindowsState.requestCount.collectAsState()
    val lastLatency by WindowsState.lastLatencyMs.collectAsState()
    val lanEnabled by WindowsState.lanDiscoveryEnabled.collectAsState()
    val isReady = WindowsTranslator.isReady()

    val spec = remember(activeModelId) { ModelRegistry.getById(activeModelId) }
    val modelInstalled = remember(activeModelId) { WindowsModelDownloader.isModelReady(spec) }

    // Throughput is measured, not guessed: the tuner's last result is the only honest number, and
    // showing a placeholder is better than inventing one.
    val tuning by com.vbookmt.windows.backend.WindowsPerformanceTuner.tuning.collectAsState()

    val scroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
        PageHeader(
            title = "Tổng quan",
            subtitle = when {
                isRunning && isReady -> "Máy chủ đang chạy và sẵn sàng dịch."
                isRunning && !modelInstalled -> "Máy chủ đang chạy nhưng chưa có mô hình."
                isRunning -> "Máy chủ đang chạy, đang nạp mô hình…"
                else -> "Máy chủ đang tắt."
            },
        ) {
            Button(
                onClick = {
                    if (isRunning) {
                        WindowsServer.stop()
                    } else {
                        WindowsServer.start()
                    }
                },
                shape = VBookTheme.radii.mdShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) VBookTheme.palette.surfaceMuted
                    else VBookTheme.palette.accent,
                    contentColor = if (isRunning) VBookTheme.palette.onSurface
                    else VBookTheme.palette.onAccent,
                ),
            ) {
                Text(if (isRunning) "Dừng máy chủ" else "Bật máy chủ")
            }
        }

        PanelColumn {
            // ---- The three numbers ------------------------------------------
            Panel {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(VBookTheme.spacing.xl),
                ) {
                    MetricBlock(
                        value = tuning?.speedCharsPerSec?.let { "${it.toInt()}" } ?: "—",
                        unit = "ký tự/giây",
                        label = "Tốc độ đo được",
                        emphasis = true,
                        modifier = Modifier.weight(1f),
                    )
                    MetricBlock(
                        value = "$requestCount",
                        unit = "yêu cầu",
                        label = "Đã phục vụ",
                        modifier = Modifier.weight(1f),
                    )
                    MetricBlock(
                        value = if (lastLatency > 0) "$lastLatency" else "—",
                        unit = "ms",
                        label = "Chương gần nhất",
                        modifier = Modifier.weight(1f),
                    )
                }

                if (tuning == null) {
                    VSpace(VBookTheme.spacing.md)
                    Caption("Chưa đo hiệu năng. Mở mục Hiệu năng để đo tốc độ thật của máy này.")
                }
            }

            // ---- Connection --------------------------------------------------
            Panel {
                SectionHeading("Kết nối") {
                    StatusDot(
                        text = if (isReady) "Sẵn sàng" else "Chưa nạp mô hình",
                        tone = if (isReady) StatusTone.Ok else StatusTone.Warn,
                    )
                }
                VSpace(VBookTheme.spacing.md)

                ConnectionRow(
                    label = "Máy này (loopback)",
                    url = "http://127.0.0.1:${WindowsServer.PORT}",
                    onOpen = {
                        runCatching {
                            if (Desktop.isDesktopSupported() &&
                                Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
                            ) {
                                Desktop.getDesktop().browse(URI("http://127.0.0.1:${WindowsServer.PORT}/"))
                            }
                        }
                    },
                )

                VSpace(VBookTheme.spacing.md)
                HairlineDivider()
                VSpace(VBookTheme.spacing.md)

                // LAN mode: a phone on the same Wi-Fi can point at this machine.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cho phép điện thoại trong cùng Wi-Fi",
                            style = MaterialTheme.typography.titleSmall,
                            color = VBookTheme.palette.onSurface,
                        )
                        Caption(
                            if (lanEnabled) {
                                "Đang mở cổng ${WindowsServer.PORT} cho mạng nội bộ."
                            } else {
                                "Mặc định chỉ nhận kết nối trên chính máy này."
                            }
                        )
                    }
                    AppSwitch(
                        checked = lanEnabled,
                        onCheckedChange = {
                            WindowsState.setLanDiscoveryEnabled(it)
                            // Rebinding is required: the listener has to move between 127.0.0.1 and
                            // 0.0.0.0, which a live socket cannot do.
                            if (isRunning) WindowsServer.restart()
                        },
                    )
                }

                if (lanEnabled) {
                    VSpace(VBookTheme.spacing.md)
                    InsetPanel {
                        Caption("Địa chỉ cho điện thoại")
                        VSpace(4.dp)
                        SelectionContainer {
                            Text(
                                text = "http://${WindowsState.getLocalLanIp()}:${WindowsServer.PORT}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = VBookTheme.palette.onSurface,
                            )
                        }
                    }
                }
            }

            // ---- Model summary ----------------------------------------------
            Panel {
                SectionHeading("Mô hình đang dùng") {
                    TextButton(onClick = onOpenModels) { Text("Đổi mô hình") }
                }
                VSpace(VBookTheme.spacing.sm)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = spec.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = VBookTheme.palette.onSurface,
                    )
                    Spacer(Modifier.width(VBookTheme.spacing.sm))
                    Tag("${spec.sizeMb} MB")
                    Spacer(Modifier.width(6.dp))
                    // Every shipped artifact is ONNX with three precisions in the same repo. The price
                    // tag above already shows the size for the chosen variant.
                    Tag("ONNX", StatusTone.Idle)
                }
                VSpace(4.dp)
                Caption(spec.description)

                if (!modelInstalled) {
                    VSpace(VBookTheme.spacing.md)
                    InsetPanel(background = VBookTheme.palette.warnTonal) {
                        Text(
                            text = "Mô hình chưa tải về máy. Vào mục Mô hình để tải trước khi dịch.",
                            style = MaterialTheme.typography.bodySmall,
                            color = VBookTheme.palette.warn,
                        )
                    }
                }
            }

            PlaygroundPanel()

            VSpace(VBookTheme.spacing.sm)
        }
    }
}

/** A read-only URL with copy and open actions. */
@Composable
private fun ConnectionRow(label: String, url: String, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    fun copy() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
        copied = true
        scope.launch {
            delay(1600)
            copied = false
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Caption(label)
            VSpace(2.dp)
            SelectionContainer {
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = VBookTheme.palette.onSurface,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietIconButton(
                icon = AppIcons.ContentCopy,
                contentDescription = "Sao chép địa chỉ",
                onClick = { copy() },
            )
            QuietIconButton(
                icon = AppIcons.OpenInBrowser,
                contentDescription = "Mở trong trình duyệt",
                onClick = onOpen,
            )
        }
    }
}

/**
 * The quick test.
 *
 * Kept because it is the only way a user can answer "is the output actually good on my machine"
 * without opening vBook. Presented as a two-column result rather than a card full of chrome: source
 * on the left, translation on the right, latency underneath.
 */
@Composable
private fun PlaygroundPanel() {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(com.vbookmt.core.tuner.BenchmarkProse.QUICK_TEST_SAMPLE) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var latency by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }

    val samples = listOf(
        "Thoại" to com.vbookmt.core.tuner.BenchmarkProse.QUICK_TEST_SAMPLE,
        "Tiên hiệp" to "陆沉手握青峰古剑，眼神凌厉地看向虚空深处：“今日纵然身死道消，亦要斩破这天道樊笼！”",
        "Văn xuôi" to "夜色深沉，万籁俱寂。李青缓缓推开陈旧的木门，冷冽的夜风夹杂着草木清香扑面而来。",
    )

    Panel {
        SectionHeading("Dịch thử") {
            Row(horizontalArrangement = Arrangement.spacedBy(VBookTheme.spacing.xs)) {
                for ((label, text) in samples) {
                    TextButton(onClick = { input = text }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        VSpace(VBookTheme.spacing.sm)

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            shape = VBookTheme.radii.mdShape,
            label = { Text("Đoạn văn tiếng Trung", style = MaterialTheme.typography.bodySmall) },
        )

        VSpace(VBookTheme.spacing.md)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Caption(
                buildString {
                    append("${input.length} ký tự")
                    latency?.let { append(" · $it ms") }
                    if (latency != null && latency!! > 0) {
                        append(" · ~${(input.length * 1000L / latency!!)} ký tự/giây")
                    }
                }
            )
            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            var result = ""
                            val ms = measureTimeMillis { result = WindowsTranslator.translate(input) }
                            output = result
                            latency = ms
                        } catch (e: Exception) {
                            error = e.message ?: "Không dịch được"
                            output = ""
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && input.isNotBlank(),
                shape = VBookTheme.radii.mdShape,
            ) {
                Icon(AppIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Đang dịch…" else "Dịch")
            }
        }

        val shown = error ?: output
        if (shown.isNotBlank()) {
            VSpace(VBookTheme.spacing.md)
            InsetPanel(
                background = if (error != null) VBookTheme.palette.dangerTonal
                else VBookTheme.palette.surfaceMuted,
            ) {
                SelectionContainer {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (error != null) VBookTheme.palette.danger else VBookTheme.palette.onSurface,
                    )
                }
            }
        }
    }
}
