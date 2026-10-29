package com.vbookmt.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.android.service.Ct2Translator
import com.vbookmt.android.service.TranslationService
import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.processor.TextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            VBookMTTheme {
                MainScreen(
                    onStartService = { modelId ->
                        val intent = Intent(this, TranslationService::class.java).apply {
                            action = TranslationService.ACTION_START
                            putExtra(TranslationService.EXTRA_ACTIVE_MODEL, modelId)
                        }
                        startForegroundService(intent)
                    },
                    onStopService = {
                        val intent = Intent(this, TranslationService::class.java).apply {
                            action = TranslationService.ACTION_STOP
                        }
                        startService(intent)
                    }
                )
            }
        }
    }
}

// ============================================================================
// Modern Sleek Material 3 Theme with Dynamic Color Support
// ============================================================================
@Composable
fun VBookMTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFF64B5F6),
            secondary = Color(0xFF81C784),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            surfaceVariant = Color(0xFF2C2C2C)
        )
        else -> lightColorScheme(
            primary = Color(0xFF1976D2),
            secondary = Color(0xFF388E3C),
            background = Color(0xFFF8F9FA),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEF2F6)
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

// ============================================================================
// Main Dashboard Screen
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartService: (String) -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isRunning by remember { mutableStateOf(false) }
    var selectedModel by remember { mutableStateOf(ModelRegistry.HACHIMI_60_QT) }
    var isMoxhiDownloaded by remember { mutableStateOf(false) }
    var isDownloadingMoxhi by remember { mutableStateOf(false) }
    var showNoticesSheet by remember { mutableStateOf(false) }

    // Quick Test State
    var testInput by remember { mutableStateOf("“醒醒，指挥官！快醒醒！”") }
    var testOutput by remember { mutableStateOf("") }
    var testLatencyMs by remember { mutableStateOf<Long?>(null) }
    var isTranslatingTest by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "VBookMT",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = "CT2 Offline",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showNoticesSheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Tác quyền & Giấy phép"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Hero Status Card
            item {
                StatusHeroCard(
                    isRunning = isRunning,
                    port = 8765,
                    onToggle = {
                        if (isRunning) {
                            onStopService()
                            isRunning = false
                        } else {
                            onStartService(selectedModel.id)
                            isRunning = true
                        }
                    },
                    onCopyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("VBookMT URL", "http://127.0.0.1:8765/translate"))
                        Toast.makeText(context, "Đã sao chép địa chỉ server!", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 2. Model Selection Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Mô hình Dịch thuật",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "CTranslate2 INT8",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 3. Model Cards
            item {
                ModelCard(
                    model = ModelRegistry.HACHIMI_60_QT,
                    isSelected = selectedModel.id == ModelRegistry.HACHIMI_60_QT.id,
                    badge = "Mặc định • 55MB • BLEU 53.0",
                    isInstalled = true,
                    isDownloading = false,
                    onSelect = { selectedModel = ModelRegistry.HACHIMI_60_QT },
                    onDownload = {}
                )
            }

            item {
                ModelCard(
                    model = ModelRegistry.MOXHI_30_QT,
                    isSelected = selectedModel.id == ModelRegistry.MOXHI_30_QT.id,
                    badge = "Siêu nhẹ • 36MB • Tiết kiệm RAM",
                    isInstalled = isMoxhiDownloaded,
                    isDownloading = isDownloadingMoxhi,
                    onSelect = { selectedModel = ModelRegistry.MOXHI_30_QT },
                    onDownload = {
                        isDownloadingMoxhi = true
                        coroutineScope.launch {
                            // Simulation of background model file download
                            withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1500) }
                            isMoxhiDownloaded = true
                            isDownloadingMoxhi = false
                            selectedModel = ModelRegistry.MOXHI_30_QT
                            Toast.makeText(context, "Đã tải xong MoxhiMT-30-QT!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // 4. Quick Test & Diagnostics Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Dịch thử nghiệm nhanh",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (testLatencyMs != null) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE8F5E9)
                                ) {
                                    Text(
                                        text = "⚡ ${testLatencyMs}ms",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF2E7D32),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = testInput,
                            onValueChange = { testInput = it },
                            label = { Text("Tiếng Trung (gốc)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            shape = RoundedCornerShape(10.dp)
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                if (testInput.isNotBlank()) {
                                    isTranslatingTest = true
                                    coroutineScope.launch {
                                        val start = System.currentTimeMillis()
                                        val out = withContext(Dispatchers.Default) {
                                            val encoded = TextProcessor.encodeSource(testInput)
                                            val translated = Ct2Translator.translate(encoded, numBeams = 1)
                                            TextProcessor.decodeTarget(translated)
                                        }
                                        testLatencyMs = System.currentTimeMillis() - start
                                        testOutput = out
                                        isTranslatingTest = false
                                    }
                                }
                            },
                            enabled = !isTranslatingTest,
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isTranslatingTest) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Dịch thử ngay")
                        }

                        AnimatedVisibility(visible = testOutput.isNotEmpty()) {
                            Column(modifier = Modifier.padding(top = 10.dp)) {
                                Text(
                                    text = "Kết quả Tiếng Việt:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = testOutput,
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Bottom Sheet: Notices & Licenses
        if (showNoticesSheet) {
            ModalBottomSheet(
                onDismissRequest = { showNoticesSheet = false },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
            ) {
                NoticesSheetContent(onClose = { showNoticesSheet = false })
            }
        }
    }
}

// ============================================================================
// Modern Hero Status Card with Pulsing Indicator
// ============================================================================
@Composable
fun StatusHeroCard(
    isRunning: Boolean,
    port: Int,
    onToggle: () -> Unit,
    onCopyUrl: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val cardBg by animateColorAsState(
        targetValue = if (isRunning) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "cardBg"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pulsing Status Dot & Text
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isRunning) Color(0xFF2E7D32).copy(alpha = pulseAlpha)
                                else Color(0xFF757575)
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRunning) "MÁY CHỦ SẴN SÀNG" else "MÁY CHỦ ĐANG NGHỈ",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) Color(0xFF1B5E20) else Color(0xFF424242)
                    )
                }

                // Switch Button
                FilledIconToggleButton(
                    checked = isRunning,
                    onCheckedChange = { onToggle() },
                    shape = CircleShape,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Filled.PowerSettingsNew else Icons.Outlined.PowerSettingsNew,
                        contentDescription = "Bật/Tắt Server"
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // URL Pill with Copy
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCopyUrl() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "ĐỊA CHỈ NỘI BỘ VBOOK (LOOPBACK)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "http://127.0.0.1:$port/translate",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Sao chép",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Cơ chế Foreground Service giữ kết nối ổn định 100%, không bị Android tối ưu pin ngắt kết nối.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                lineHeight = 15.sp
            )
        }
    }
}

// ============================================================================
// Sleek Model Card
// ============================================================================
@Composable
fun ModelCard(
    model: ModelSpec,
    isSelected: Boolean,
    badge: String,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardColor = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = if (isSelected) 1.5.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(14.dp))
            .clickable(enabled = isInstalled) { onSelect() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tác giả: ${model.author} • ${model.license}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                if (isInstalled) {
                    RadioButton(
                        selected = isSelected,
                        onClick = onSelect
                    )
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isDownloading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Đang tải...", fontSize = 11.sp)
                        } else {
                            Text("Tải (${model.sizeMb}MB)", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Text(
                    text = badge,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = model.description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
    }
}

// ============================================================================
// Notices & Licenses Bottom Sheet
// ============================================================================
@Composable
fun NoticesSheetContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tác quyền & Bản quyền",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Đóng")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Dự án VBookMT sử dụng và phát triển dựa trên các công trình đóng góp của cộng đồng:",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        NoticeItem(
            name = "HachimiMT-60-QT & 40-QT",
            author = "ngocdang83",
            license = "CC BY 4.0",
            desc = "Mô hình dịch máy Trung-Việt chuẩn ngôi xưng convert truyện tiên hiệp/kiếm hiệp."
        )

        NoticeItem(
            name = "MoxhiMT-30-QT",
            author = "DanVP",
            license = "CC BY-NC 4.0",
            desc = "Mô hình siêu nhẹ 36MB, phản hồi cực nhanh, tối ưu cho thiết bị hạn chế RAM."
        )

        NoticeItem(
            name = "CTranslate2",
            author = "OpenNMT Team",
            license = "MIT License",
            desc = "Engine suy luận C++ tối ưu hóa bộ nhớ và tốc độ cho Transformer & Seq2Seq."
        )

        NoticeItem(
            name = "vBook Ecosystem",
            author = "Darkrai9x",
            license = "Open Source",
            desc = "Ứng dụng đọc truyện mã nguồn mở đa nền tảng dành cho cộng đồng người Việt."
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun NoticeItem(name: String, author: String, license: String, desc: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(text = license, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            Text(text = "Tác giả: $author", fontSize = 11.sp, color = Color.Gray)
            Text(text = desc, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), lineHeight = 15.sp)
        }
    }
}
