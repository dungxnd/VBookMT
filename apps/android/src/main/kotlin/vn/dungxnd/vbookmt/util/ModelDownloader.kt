package vn.dungxnd.vbookmt.util

import android.content.Context
import android.util.Log
import com.vbookmt.core.model.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelDownloader {

    private const val TAG = "ModelDownloader"

    data class DownloadProgress(
        val currentFile: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    )

    private val REQUIRED_FILES = listOf(
        "config.json",
        "model.bin",
        "shared_vocabulary.json",
        "source.spm",
        "target.spm"
    )

    fun isModelReady(context: Context, modelSpec: ModelSpec): Boolean {
        val modelDir = File(context.filesDir, "models/${modelSpec.id}")
        if (!modelDir.exists() || !modelDir.isDirectory) return false
        val hasConfig = (File(modelDir, "config.json").exists() && File(modelDir, "config.json").length() > 20) ||
                (File(modelDir, "ct2-int8_float32/config.json").exists() && File(modelDir, "ct2-int8_float32/config.json").length() > 20)
        val hasBin = (File(modelDir, "model.bin").exists() && File(modelDir, "model.bin").length() > 500_000) ||
                (File(modelDir, "ct2-int8_float32/model.bin").exists() && File(modelDir, "ct2-int8_float32/model.bin").length() > 500_000)
        val hasSrcSpm = (File(modelDir, "source.spm").exists() && File(modelDir, "source.spm").length() > 10_000) ||
                (File(modelDir, "ct2-int8_float32/source.spm").exists() && File(modelDir, "ct2-int8_float32/source.spm").length() > 10_000)
        val hasTgtSpm = (File(modelDir, "target.spm").exists() && File(modelDir, "target.spm").length() > 10_000) ||
                (File(modelDir, "ct2-int8_float32/target.spm").exists() && File(modelDir, "ct2-int8_float32/target.spm").length() > 10_000)
        val hasVocab = (File(modelDir, "shared_vocabulary.json").exists() && File(modelDir, "shared_vocabulary.json").length() > 1_000) ||
                (File(modelDir, "ct2-int8_float32/shared_vocabulary.json").exists() && File(modelDir, "ct2-int8_float32/shared_vocabulary.json").length() > 1_000)
        return hasConfig && hasBin && hasSrcSpm && hasTgtSpm && hasVocab
    }

    suspend fun downloadModel(
        context: Context,
        modelSpec: ModelSpec,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "models/${modelSpec.id}")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val baseUrl = "https://huggingface.co/${modelSpec.repoId}/resolve/main/ct2-int8_float32"

        for ((index, fileName) in REQUIRED_FILES.withIndex()) {
            val destinationFile = File(targetDir, fileName)
            if (destinationFile.exists() && destinationFile.length() > 1000) {
                // Already downloaded this file
                continue
            }

            val fileUrl = "$baseUrl/$fileName"
            var connection: HttpURLConnection? = null
            try {
                Log.i(TAG, "Downloading $fileName from $fileUrl ...")
                val url = URL(fileUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 25_000
                    readTimeout = 60_000
                    setRequestProperty("User-Agent", "VBookMT-Android/1.0.0")
                    connect()
                }

                var code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    connection = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 25_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "VBookMT-Android/1.0.0")
                        connect()
                    }
                }

                if (connection.responseCode !in 200..299) {
                    // Try alternative direct root URL without ct2-int8_float32
                    connection.disconnect()
                    val altUrl = "https://huggingface.co/${modelSpec.repoId}/resolve/main/$fileName"
                    connection = (URL(altUrl).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        connectTimeout = 25_000
                        readTimeout = 60_000
                        setRequestProperty("User-Agent", "VBookMT-Android/1.0.0")
                        connect()
                    }
                }

                val totalLength = connection.contentLengthLong
                val tempFile = File(targetDir, "$fileName.tmp")

                connection.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val pct = if (totalLength > 0) {
                                ((totalRead * 100) / totalLength).toInt().coerceIn(0, 100)
                            } else {
                                50
                            }

                            onProgress(
                                DownloadProgress(
                                    currentFile = fileName,
                                    fileIndex = index + 1,
                                    totalFiles = REQUIRED_FILES.size,
                                    percent = pct,
                                    bytesDownloaded = totalRead,
                                    totalBytes = totalLength
                                )
                            )
                        }
                    }
                }

                if (tempFile.exists()) {
                    if (destinationFile.exists()) destinationFile.delete()
                    tempFile.renameTo(destinationFile)
                    Log.i(TAG, "Saved $fileName (${destinationFile.length()} bytes)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed downloading $fileName: ${e.message}", e)
                return@withContext false
            } finally {
                connection?.disconnect()
            }
        }

        context.getSharedPreferences("vbookmt_models", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("downloaded_${modelSpec.id}", true)
            .apply()

        true
    }
}
