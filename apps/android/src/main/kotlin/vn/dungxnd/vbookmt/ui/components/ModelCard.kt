package vn.dungxnd.vbookmt.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant

/**
 * One model, with its precision picker and install state.
 *
 * The card shows three precisions because every artifact publishes all three in the same repo, and
 * choosing one changes *both* what gets downloaded and what runs afterwards. Two things are therefore
 * driven by [selectedVariant] rather than by the model's defaults:
 *
 *  - the size on the download button, which used to read `model.sizeMb` — the FP16 size — even when
 *    the user had picked Q8, overstating the download by 60%;
 *  - whether the button appears at all, because a model installed at Q8 is *not* installed at FP16.
 *
 * @param isInstalled whether [selectedVariant] specifically is on disk.
 * @param downloadPercent 0..100 while downloading, null otherwise.
 */
@Composable
fun ModelCard(
    model: ModelSpec,
    isSelected: Boolean,
    badge: String,
    isInstalled: Boolean,
    isDownloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    variants: List<QuantVariant> = emptyList(),
    selectedVariant: QuantVariant? = null,
    onSelectVariant: (QuantVariant) -> Unit = {},
    downloadPercent: Int? = null,
    isAnyDownloading: Boolean = false,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardColor = if (isSelected) MaterialTheme.colorScheme.surface
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(enabled = isInstalled && !isAnyDownloading) { onSelect() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // --- title row ----------------------------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
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
                        onClick = onSelect,
                        enabled = !isAnyDownloading,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- precision chips ----------------------------------------------------------
            if (variants.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    variants.forEach { v ->
                        val picked = v == selectedVariant
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (picked) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.clickable(enabled = !isAnyDownloading) { onSelectVariant(v) }
                        ) {
                            Text(
                                text = "${v.label} · ${model.sizeMbFor(v)}MB",
                                fontSize = 10.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                                color = if (picked) MaterialTheme.colorScheme.onPrimary else Color.Gray,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // The trade-off for whichever precision is picked, so the choice is understandable
                // without opening the model card on Hugging Face.
                selectedVariant?.hint?.let { hint ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = hint,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // --- badge ---------------------------------------------------------------------
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

            // --- action --------------------------------------------------------------------
            if (!isInstalled) {
                Spacer(modifier = Modifier.height(10.dp))
                if (isDownloading) {
                    // Real progress, not an indeterminate spinner: the download is 88–205 MB and an
                    // indeterminate bar over several minutes tells the reader nothing.
                    val percent = downloadPercent
                    if (percent != null) {
                        LinearProgressIndicator(
                            progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Đang tải $percent%", fontSize = 10.sp, color = Color.Gray)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Đang tải...", fontSize = 11.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isAnyDownloading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        // The picked precision's size, so switching FP16 -> Q8 changes the number the
                        // button promises.
                        Text(
                            text = "Tải ${selectedVariant?.label ?: ""} (${model.sizeMbFor(selectedVariant)}MB)",
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
