package vn.dungxnd.vbookmt.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
// Notices & Licenses Bottom Sheet
// ============================================================================
@Composable
fun NoticesSheetContent(onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tác quyền & Đóng góp",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            IconButton(onClick = onClose) {
                Icon(imageVector = Icons.Default.Close, contentDescription = "Đóng")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Dự án VBookMT được phát triển bởi dungxnd và tích hợp các công trình xuất sắc từ cộng đồng:",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        NoticeItem(
            name = "VBookMT App & Ecosystem",
            author = "dungxnd (@dungxnd)",
            license = "MIT License",
            desc = "Tác giả kiến trúc & phát triển: Android companion app, Windows native CLI server và extension vBook."
        )

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
            name = "ONNX Runtime",
            author = "Microsoft",
            license = "MIT License",
            desc = "Engine suy luận thứ hai, dùng cho các mô hình ONNX và tăng tốc GPU/NPU khi thiết bị hỗ trợ."
        )

        NoticeItem(
            name = "vBook Ecosystem",
            author = "Darkrai9x",
            license = "Open Source",
            desc = "Ứng dụng đọc truyện mã nguồn mở đa nền tảng dành cho cộng đồng người Việt."
        )

        Spacer(modifier = Modifier.height(24.dp))
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
