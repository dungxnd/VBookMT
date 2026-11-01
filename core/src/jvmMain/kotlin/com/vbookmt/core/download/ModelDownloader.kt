package com.vbookmt.core.download

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import com.vbookmt.core.model.hasUsableVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Shared model downloading and artifact verification logic for all JVM-based targets (Android & Windows).
 */
object ModelDownloader {

    data class DownloadProgress(
        val currentFile: String,
        val fileIndex: Int,
        val totalFiles: Int,
        val percent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long
    )

    fun isModelReady(
        modelDir: File,
        modelSpec: ModelSpec,
        variant: QuantVariant? = null,
        currentInstalledVariant: QuantVariant? = null
    ): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false

        val hasEncoder = File(modelDir, "encoder_model.onnx").length() > 500_000
        val hasDecoder = File(modelDir, "decoder_model.onnx").length() > 500_000
        val hasPast = File(modelDir, "decoder_with_past_model.onnx").length() > 500_000
        val hasTrim = File(modelDir, "trim_meta.json").length() > 100
        val hasSrcSpm = File(modelDir, "source.spm").length() > 10_000
        val hasTgtSpm = File(modelDir, "target.spm").length() > 10_000

        val names = (modelDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()).toSet()
        val filesOk = hasEncoder && hasDecoder && hasPast && hasTrim && hasSrcSpm && hasTgtSpm &&
            hasUsableVocabulary(names)
        val wanted = variant ?: return filesOk
        return filesOk && currentInstalledVariant == wanted
    }

    suspend fun downloadModel(
        targetDir: File,
        modelSpec: ModelSpec,
        variant: QuantVariant? = QuantVariant.DEFAULT,
        currentInstalledVariant: QuantVariant? = null,
        userAgent: String = "VBookMT",
        log: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) targetDir.mkdirs()

        val files = modelSpec.filesFor(variant).map { it.substringAfterLast('/') }
        val totalFiles = files.size
        val targetVariant = variant ?: QuantVariant.DEFAULT
        val isSameVariant = currentInstalledVariant == targetVariant
        val totalExpectedBytes = (modelSpec.sizeMbFor(variant) * 1024L * 1024L).coerceAtLeast(1L)
        var totalBytesDownloaded = 0L

        if (isSameVariant) {
            for (fileName in files) {
                val f = File(targetDir, fileName)
                if (f.exists() && f.length() > 1000) {
                    totalBytesDownloaded += f.length()
                }
            }
        }

        var lastReportedPercent = -1

        log("Bắt đầu tải model: ${modelSpec.name} (${variant?.label ?: "mặc định"}, $totalFiles tệp)...")

        for ((index, fileName) in files.withIndex()) {
            val destinationFile = File(targetDir, fileName)
            if (isSameVariant && destinationFile.exists() && destinationFile.length() > 1000) {
                continue
            }

            val fileUrl = ModelRegistry.getDownloadUrl(modelSpec, fileName, variant)
            val tempFile = File(targetDir, "$fileName.tmp")

            var success = false
            for (attempt in 1..3) {
                var conn: HttpURLConnection? = null
                try {
                    var currentUrl = fileUrl
                    var redirects = 0
                    while (redirects < 5) {
                        val uri = URI.create(currentUrl)
                        val candidateConn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                            connectTimeout = 30_000
                            readTimeout = 60_000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", userAgent)
                        }

                        val responseCode = candidateConn.responseCode
                        if (responseCode in listOf(
                                HttpURLConnection.HTTP_MOVED_PERM,
                                HttpURLConnection.HTTP_MOVED_TEMP,
                                307,
                                308
                            )
                        ) {
                            val location = candidateConn.getHeaderField("Location")
                            candidateConn.disconnect()
                            if (location != null) {
                                currentUrl = uri.resolve(location).toString()
                                redirects++
                                continue
                            }
                        }
                        conn = candidateConn
                        break
                    }

                    val activeConn = conn ?: continue
                    val responseCode = activeConn.responseCode
                    if (responseCode !in 200..299) {
                        activeConn.disconnect()
                        continue
                    }

                    activeConn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalBytesDownloaded += bytesRead

                                val overallPercent = ((totalBytesDownloaded * 100) / totalExpectedBytes)
                                    .toInt()
                                    .coerceIn(0, 99)

                                if (overallPercent != lastReportedPercent) {
                                    lastReportedPercent = overallPercent
                                    onProgress(
                                        DownloadProgress(
                                            currentFile = fileName,
                                            fileIndex = index + 1,
                                            totalFiles = totalFiles,
                                            percent = overallPercent,
                                            bytesDownloaded = totalBytesDownloaded,
                                            totalBytes = totalExpectedBytes
                                        )
                                    )
                                }
                            }
                        }
                    }
                    activeConn.disconnect()

                    if (tempFile.exists() && tempFile.length() > 0) {
                        if (destinationFile.exists()) destinationFile.delete()
                        tempFile.renameTo(destinationFile)
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    log("Lỗi tải $fileName (lần thử $attempt): ${e.message}")
                    tempFile.delete()
                } finally {
                    conn?.disconnect()
                }
            }

            if (!success) {
                log("Tải model THẤT BẠI tại tệp $fileName")
                return@withContext false
            }
        }

        onProgress(
            DownloadProgress(
                currentFile = files.lastOrNull() ?: "",
                fileIndex = totalFiles,
                totalFiles = totalFiles,
                percent = 100,
                bytesDownloaded = totalExpectedBytes,
                totalBytes = totalExpectedBytes
            )
        )

        log("Tải hoàn tất model: ${modelSpec.name}")
        true
    }

    fun deleteModel(targetDir: File, log: (String) -> Unit = {}): Boolean {
        if (targetDir.exists()) {
            val deleted = targetDir.deleteRecursively()
            if (deleted) {
                log("Đã xoá tệp model tại: ${targetDir.name}")
            }
            return deleted
        }
        return false
    }
}
