package vn.dungxnd.vbookmt.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.core.processor.TextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.dungxnd.vbookmt.service.ChunkTranslator
import vn.dungxnd.vbookmt.service.Ct2Translator
import vn.dungxnd.vbookmt.service.ServiceState
import java.io.File

/**
 * One-shot translation playground so the reader can confirm the engine works before opening vBook.
 *
 * Owns its own input/output state: nothing here is needed by the rest of the screen, and keeping it
 * local means the dashboard does not re-render when the user types a test sentence.
 */
@Composable
fun QuickTestCard(modelId: String) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var testInput by remember { mutableStateOf("“醒醒，指挥官！快醒醒！”") }
    var testOutput by remember { mutableStateOf("") }
    var testLatencyMs by remember { mutableStateOf<Long?>(null) }
    var isTranslatingTest by remember { mutableStateOf(false) }

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
                        ServiceState.log("Bấm dịch thử: ${testInput.length} ký tự...")
                        coroutineScope.launch {
                            val start = System.currentTimeMillis()
                            try {
                                val out = withContext(Dispatchers.Default) {
                                    if (!Ct2Translator.isReady()) {
                                        val modelDir = File(context.filesDir, "models/$modelId")
                                        if (!modelDir.exists()) modelDir.mkdirs()
                                        Ct2Translator.init(modelDir.absolutePath)
                                    }
                                    val encoded = TextProcessor.encodeSource(testInput)
                                    val translated = ChunkTranslator.translate(encoded, numBeams = 1)
                                    TextProcessor.decodeTarget(translated)
                                }
                                val elapsed = System.currentTimeMillis() - start
                                testLatencyMs = elapsed
                                testOutput = out
                                ServiceState.log("Dịch thử hoàn tất (${elapsed}ms)")
                            } catch (e: Throwable) {
                                testLatencyMs = null
                                testOutput = "Lỗi khi dịch: ${e.message ?: e.toString()}"
                                ServiceState.log("LỖI dịch thử: ${e.message}")
                            } finally {
                                isTranslatingTest = false
                            }
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
