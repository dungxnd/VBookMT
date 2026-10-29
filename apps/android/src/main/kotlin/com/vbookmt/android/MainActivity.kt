package com.vbookmt.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.android.service.TranslationService
import com.vbookmt.core.model.ModelRegistry

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VBookMTDashboard(
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
}

@Composable
fun VBookMTDashboard(
    onStartService: (String) -> Unit,
    onStopService: () -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf(ModelRegistry.HACHIMI_60_QT.id) }
    var isMoxhiDownloaded by remember { mutableStateOf(false) }
    var isDownloadingMoxhi by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "VBookMT",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge
            )
            Text(
                text = "Máy chủ dịch AI Offline cho vBook (CTranslate2)",
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Status Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isRunning) "Trạng thái: ĐANG CHẠY" else "Trạng thái: ĐÃ TẮT",
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828),
                        fontSize = 17.sp
                    )
                    Text(
                        text = if (isRunning) "Cổng dịch: http://127.0.0.1:8765/translate" else "Bật service để vBook kết nối",
                        fontSize = 13.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Power Switch Button
        item {
            Button(
                onClick = {
                    if (isRunning) {
                        onStopService()
                        isRunning = false
                    } else {
                        onStartService(selectedModelId)
                        isRunning = true
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF1976D2)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    text = if (isRunning) "DỪNG DỊCH THUẬT" else "BẬT DỊCH OFFLINE",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Model Management Section
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Quản lý Mô hình AI (CTranslate2 INT8)",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Model 1: HachimiMT-60-QT (Default)
        item {
            val isSelected = selectedModelId == ModelRegistry.HACHIMI_60_QT.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "HachimiMT 60 QT (Mặc định)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Tác giả: ngocdang83 • CC BY 4.0 • ~55 MB",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        RadioButton(
                            selected = isSelected,
                            onClick = { selectedModelId = ModelRegistry.HACHIMI_60_QT.id }
                        )
                    }
                    Text(
                        text = "Chất lượng cao nhất, ổn định ngôi xưng hô convert tuyệt đối. Đã cài đặt sẵn.",
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Model 2: MoxhiMT-30-QT (Optional download)
        item {
            val isSelected = selectedModelId == ModelRegistry.MOXHI_30_QT.id
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "MoxhiMT 30 QT (Tùy chọn)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Text(
                                text = "Tác giả: DanVP • CC BY-NC 4.0 • ~36 MB",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        if (isMoxhiDownloaded) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { selectedModelId = ModelRegistry.MOXHI_30_QT.id }
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    isDownloadingMoxhi = true
                                    // Simulation of download
                                    isMoxhiDownloaded = true
                                    isDownloadingMoxhi = false
                                },
                                enabled = !isDownloadingMoxhi,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(if (isDownloadingMoxhi) "Đang tải..." else "Tải (36MB)", fontSize = 12.sp)
                            }
                        }
                    }
                    Text(
                        text = "Bản siêu nhẹ 36MB, tốc độ cao, tiết kiệm RAM tối đa cho máy cấu hình thấp.",
                        fontSize = 12.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // Author Attribution Card (NOTICE)
        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Ghi nhận Tác quyền & Bản quyền (Notice)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• HachimiMT: Bản quyền thuộc về tác giả ngocdang83 (Giấy phép CC BY 4.0).\n" +
                               "• MoxhiMT: Bản quyền thuộc về tác giả DanVP (Giấy phép CC BY-NC 4.0).\n" +
                               "• CTranslate2: Dự án mã nguồn mở OpenNMT (MIT License).\n" +
                               "• vBook: Ứng dụng đọc sách mã nguồn mở của tác giả Darkrai9x.",
                        fontSize = 11.sp,
                        color = Color(0xFF616161),
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
