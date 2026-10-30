package vn.dungxnd.vbookmt.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.dungxnd.vbookmt.util.PerformanceTuner

/**
 * "Hiệu năng" card: the only performance surface an end user sees.
 *
 * The app already picks sensible settings by itself (big-core thread count, batch 16, the model's
 * own compute type). This card exists so the reader can (a) see the result in words they actually
 * understand and (b) optionally let the app measure this phone once. No thread counts, batch sizes
 * or compute types unless they open "Chi tiết".
 */
@Composable
fun PerformanceCard(
    tuning: PerformanceTuner.Tuning?,
    isTuning: Boolean,
    progress: Pair<Int, Int>,
    cacheText: String?,
    onTune: () -> Unit
) {
    var showDetail by remember { mutableStateOf(false) }
    val (done, total) = progress

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text("Hiệu năng", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = if (tuning?.measured == true) "Đã tối ưu" else "Tự động",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = tuning?.let { "${it.speedText} • ${it.chapterText}" }
                    ?: "Đang dùng cấu hình phù hợp với máy của bạn",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (cacheText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cacheText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            if (isTuning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (total > 0) "Đang đo máy… ($done/$total)" else "Đang đo máy…",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Button(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (tuning?.measured == true) "Đo lại cho máy này" else "Đo & tối ưu cho máy này"
                    )
                }
            }

            Text(
                text = "App tự chọn cấu hình phù hợp. Bạn không cần chỉnh gì cả.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            TextButton(onClick = { showDetail = !showDetail }) {
                Text(if (showDetail) "Ẩn chi tiết" else "Chi tiết kỹ thuật", fontSize = 12.sp)
            }
            AnimatedVisibility(visible = showDetail) {
                Column {
                    Text(
                        text = tuning?.detailText ?: "—",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "Batch 16 là điểm tối ưu đã đo; số luồng lấy theo số nhân lớn của CPU.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
