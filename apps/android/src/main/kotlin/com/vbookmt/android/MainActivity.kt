package com.vbookmt.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.android.service.TranslationService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServerControlScreen(
                        onStartService = {
                            val intent = Intent(this, TranslationService::class.java).apply {
                                action = TranslationService.ACTION_START
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
fun ServerControlScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "VBookMT",
            fontSize = 32.sp,
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = "Máy chủ dịch AI Offline cho vBook",
            fontSize = 16.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isRunning) "Trạng thái: ĐANG CHẠY" else "Trạng thái: ĐÃ TẮT",
                    color = if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontSize = 18.sp
                )
                Text(
                    text = if (isRunning) "Địa chỉ: http://127.0.0.1:8765" else "Chưa khởi động",
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (isRunning) {
                    onStopService()
                    isRunning = false
                } else {
                    onStartService()
                    isRunning = true
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFFD32F2F) else Color(0xFF1976D2)
            )
        ) {
            Text(
                text = if (isRunning) "DỪNG SERVER" else "BẬT DỊCH OFFLINE",
                fontSize = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Chế độ Foreground Service giữ kết nối luôn ổn định, không bị hệ thống tối ưu pin làm gián đoạn.",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
