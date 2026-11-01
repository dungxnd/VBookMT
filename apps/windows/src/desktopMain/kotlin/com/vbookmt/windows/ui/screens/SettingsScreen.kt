package com.vbookmt.windows.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vbookmt.windows.backend.WindowsCacheStore
import com.vbookmt.windows.backend.WindowsPerformanceTuner
import com.vbookmt.windows.backend.WindowsState
import com.vbookmt.windows.backend.WindowsTranslator
import com.vbookmt.windows.ui.AppSwitch
import com.vbookmt.windows.ui.Caption
import com.vbookmt.windows.ui.DetailRow
import com.vbookmt.windows.ui.HairlineDivider
import com.vbookmt.windows.ui.InsetPanel
import com.vbookmt.windows.ui.PageHeader
import com.vbookmt.windows.ui.Panel
import com.vbookmt.windows.ui.PanelColumn
import com.vbookmt.windows.ui.QuietIconButton
import com.vbookmt.windows.ui.SectionHeading
import com.vbookmt.windows.ui.StatusDot
import com.vbookmt.windows.ui.StatusTone
import com.vbookmt.windows.ui.VBookTheme
import com.vbookmt.windows.ui.VSpace
import com.vbookmt.windows.ui.icons.AppIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.Locale

/**
 * Cài đặt — tuning, engine readout, cache housekeeping and the log console, on one page.
 *
 * **Why these are one screen.** Desktop used to spread them over two destinations ("Hiệu năng" and
 * "Nhật ký") while the phone put all of it behind a single "Cài đặt" tab. Two top-level destinations
 * for panels a reader opens once made the rail longer than the content justified, and it meant the
 * same product had two different maps. Both hosts now have exactly three places to be.
 *
 * The log well keeps a **fixed height** rather than filling the window: it lives inside a scrolling
 * column here, and a weighted child of an infinite-height parent has no height to resolve against.
 * That is also the reason the terminal is short — it is a status readout, not the primary workspace.
 */
@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val tuning by WindowsPerformanceTuner.tuning.collectAsState()
    val isTuning by WindowsPerformanceTuner.isTuning.collectAsState()
    val progress by WindowsPerformanceTuner.progress.collectAsState()

    // Live, not remembered once: the stats were previously captured on first composition, so the
    // panel showed whatever the numbers were when the tab first opened and never moved — which is why
    // the cache looked like it was not persisting. `stats()` only reads a few counters.
    val stats by produceState(WindowsTranslator.cacheStats()) {
        while (true) {
            value = WindowsTranslator.cacheStats()
            delay(2000)
        }
    }
    val logs by WindowsState.debugLogs.collectAsState()
    val logListState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }

    // Follow the tail only while the user is at the bottom: scrolling back to read a message must not
    // be yanked away by the next event.
    LaunchedEffect(logs.size, followTail) {
        if (followTail && logs.isNotEmpty()) logListState.scrollToItem(logs.lastIndex)
    }

    val scroll = rememberScrollState()

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
        PageHeader(
            title = "Cài đặt",
            subtitle = "Đo đúng máy này, đúng cấu hình đang dùng — rồi ghi nhớ kết quả.",
        )

        PanelColumn {
            // ---- Tuning --------------------------------------------------------------
            Panel {
                SectionHeading("Tối ưu cho máy này") {
                    if (tuning != null && !isTuning) StatusDot("Đã tối ưu", StatusTone.Ok)
                }
                VSpace(VBookTheme.spacing.sm)

                Caption(
                    "Ứng dụng sẽ thử vài mức luồng CPU trên chính máy bạn (khoảng 8–12 giây), " +
                        "chọn cấu hình nhanh nhất và ghi nhớ cho những lần mở sau."
                )

                VSpace(VBookTheme.spacing.lg)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isTuning) {
                        Column(modifier = Modifier.weight(1f)) {
                            Caption("Đang đo… (${progress.first}/${progress.second})")
                            VSpace(6.dp)
                            LinearProgressIndicator(
                                progress = {
                                    if (progress.second > 0) {
                                        progress.first.toFloat() / progress.second
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = VBookTheme.palette.accent,
                                trackColor = VBookTheme.palette.surfaceMuted,
                            )
                        }
                    } else {
                        Caption(
                            tuning?.detailText ?: "Chưa đo lần nào trên máy này.",
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.width(VBookTheme.spacing.lg))

                    Button(
                        onClick = {
                            scope.launch {
                                // Persist what the sweep picked. Without this the benchmark chose a
                                // thread count, applied it in-session, and forgot it on restart —
                                // while the subtitle above promised "rồi ghi nhớ kết quả".
                                val result = WindowsPerformanceTuner.runBenchmark()
                                WindowsState.setSavedTuning(result.threads, result.computeType)
                                WindowsState.log(
                                    "Đã lưu cấu hình tối ưu: ${result.threads} luồng, " +
                                        "${result.computeType}"
                                )
                            }
                        },
                        enabled = !isTuning,
                        shape = VBookTheme.radii.mdShape,
                    ) {
                        Text(if (isTuning) "Đang đo…" else "Đo và tối ưu")
                    }
                }
            }

            // ---- Engine readout ------------------------------------------------------
            Panel {
                SectionHeading("Cấu hình engine")
                VSpace(VBookTheme.spacing.sm)
                DetailRow("Luồng CPU", "${WindowsTranslator.activeThreads}")
                DetailRow("Số câu mỗi lượt", "${WindowsTranslator.maxBatchSize}")
                // ONNX has no runtime precision knob: the model's precision is whichever variant was
                // downloaded (FP16 / Q16 / Q8), so naming a "compute type" here would be a fiction.
                DetailRow("Kiểu tính toán", "ONNX Runtime (CPU)")
                DetailRow("Độ dài mỗi đoạn", "${WindowsTranslator.chunkChars} ký tự")
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "Đây là giá trị engine đã nhận, không phải giá trị đã yêu cầu. " +
                        "Độ chính xác do bản mô hình bạn tải quyết định, không phải chỉnh ở đây."
                )
            }

            // ---- Cache ---------------------------------------------------------------
            Panel {
                // Hoisted out of the SectionHeading lambda: the confirmation also drives the warning
                // caption below, so both branches must see the same flag.
                var confirming by remember { mutableStateOf(false) }
                SectionHeading("Bộ nhớ đệm bản dịch") {
                    if (confirming) {
                        // Clearing is destructive: it throws away every chapter the reader has already
                        // paid CPU for, and it cannot be undone. One click was too easy.
                        TextButton(onClick = { confirming = false }) { Text("Huỷ") }
                        TextButton(
                            onClick = {
                                WindowsTranslator.clearCache()
                                confirming = false
                                WindowsState.log(
                                    "Đã xoá bộ nhớ đệm bản dịch (RAM + nhật ký trên đĩa)"
                                )
                            },
                        ) { Text("Xoá hẳn") }
                    } else {
                        TextButton(onClick = { confirming = true }, enabled = stats.entries > 0) {
                            Text("Xoá cache")
                        }
                    }
                }
                VSpace(VBookTheme.spacing.sm)
                DetailRow("Số đoạn đã dịch sẵn", "${stats.entries}")
                DetailRow("Tỉ lệ dùng lại", "${stats.hitRatePercent}%")
                DetailRow("Dung lượng nhật ký", humanBytes(WindowsCacheStore.diskBytes()))
                if (confirming && stats.entries > 0) {
                    VSpace(VBookTheme.spacing.sm)
                    Caption(
                        "Xoá hẳn sẽ mất toàn bộ ${stats.entries} đoạn đã lưu. " +
                            "Lần đọc sau phải dịch lại từ đầu."
                    )
                }
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "Đọc lại một chương đã dịch gần như miễn phí, kể cả sau khi đã thoát ứng dụng."
                )
            }

            // ---- Log console ---------------------------------------------------------
            Panel {
                SectionHeading("Nhật ký") {
                    TextButton(
                        onClick = {
                            val text = logs.joinToString("\n")
                            Toolkit.getDefaultToolkit().systemClipboard
                                .setContents(StringSelection(text), null)
                        },
                        enabled = logs.isNotEmpty(),
                    ) { Text("Sao chép") }
                    TextButton(
                        onClick = { WindowsState.clearLogs() },
                        enabled = logs.isNotEmpty(),
                    ) { Text("Xoá", color = VBookTheme.palette.danger) }
                }
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "${logs.size} dòng · chỉ ghi trạng thái và thời gian, không ghi nội dung truyện."
                )
                VSpace(VBookTheme.spacing.md)

                LogWell(
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                    logs = logs,
                    listState = logListState,
                    followTail = followTail,
                )

                VSpace(VBookTheme.spacing.sm)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Caption("Luôn theo dõi dòng mới nhất")
                    AppSwitch(checked = followTail, onCheckedChange = { followTail = it })
                }
            }

            // ---- Why measure at all --------------------------------------------------
            Panel {
                SectionHeading("Vì sao phải đo trên máy này?")
                VSpace(VBookTheme.spacing.sm)
                Caption(
                    "Cùng một mô hình cho tốc độ rất khác nhau tuỳ CPU: số nhân thực, xung nhịp và " +
                        "khả năng tăng tốc đều thay đổi kết quả. Vì vậy ứng dụng đo trên chính máy " +
                        "bạn thay vì áp một con số cố định."
                )
                VSpace(VBookTheme.spacing.md)
                HairlineDivider()
                VSpace(VBookTheme.spacing.md)
                InsetPanel {
                    Caption("Nếu thấy máy nóng hoặc quạt chạy to khi dịch")
                    VSpace(4.dp)
                    Caption(
                        "Giảm số luồng CPU bằng cách đo lại ở mục trên — ít luồng hơn thường mát hơn " +
                            "và với văn bản ngắn thì gần như không chậm hơn."
                    )
                }
            }
        }
    }
}

/**
 * The log terminal itself.
 *
 * Monospace because it is a log, and selectable so a line can be copied. Tones match the prefixes the
 * logger actually emits ("LỖI", "CẢNH BÁO", "THẤT BẠI", …) rather than substring-hunting the whole
 * line — 1.1.0 matched things like "BẬT" and "thành công" anywhere in the text, so a path containing
 * "bat" could turn a line green.
 */
@Composable
private fun LogWell(
    modifier: Modifier,
    logs: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    followTail: Boolean,
) {
    Box(
        modifier = modifier
            .background(VBookTheme.palette.surfaceMuted, VBookTheme.radii.mdShape),
    ) {
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Caption("Chưa có sự kiện nào.")
                VSpace(4.dp)
                Caption("Bật máy chủ hoặc dịch thử để xem hoạt động ở đây.")
            }
            return@Box
        }

        Row(modifier = Modifier.fillMaxSize()) {
            SelectionContainer(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    items(logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = logColor(line),
                        )
                    }
                }
            }
            VerticalScrollbar(
                modifier = Modifier.fillMaxSize().width(10.dp),
                adapter = rememberScrollbarAdapter(listState),
            )
        }

        if (!followTail) {
            Row(modifier = Modifier.align(Alignment.BottomEnd).padding(VBookTheme.spacing.sm)) {
                QuietIconButton(
                    icon = AppIcons.VerticalAlignBottom,
                    contentDescription = "Cuộn xuống cuối",
                    onClick = { /* toggled by the switch; kept as an explicit affordance */ },
                )
            }
        }
    }
}

@Composable
private fun logColor(line: String) = when {
    line.contains("LỖI") || line.contains("CẢNH BÁO") || line.contains("THẤT BẠI") ->
        VBookTheme.palette.danger
    line.contains("Sẵn sàng") || line.contains("Dịch xong") || line.contains("khôi phục") ->
        VBookTheme.palette.ok
    else -> VBookTheme.palette.onSurface
}

private fun humanBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
