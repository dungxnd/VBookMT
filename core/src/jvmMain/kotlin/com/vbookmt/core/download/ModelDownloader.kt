package com.vbookmt.core.download

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.ModelSpec
import com.vbookmt.core.model.QuantVariant
import com.vbookmt.core.model.hasUsableVocabulary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/**
 * Shared model downloading and artifact verification logic for all JVM-based targets (Android & Windows).
 * Supports persistent HTTP Range resumption, atomic verification, and variant state on disk.
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

    private fun isFileValid(file: File, fileName: String): Boolean {
        if (!file.exists() || !file.isFile) return false
        val len = file.length()
        return when {
            fileName.endsWith(".onnx") -> len > 500_000
            fileName.endsWith(".spm") -> len > 10_000
            fileName.endsWith(".json") -> len > 50
            else -> len > 0
        }
    }

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
        val hasTrim = File(modelDir, "trim_meta.json").length() > 50
        val hasSrcSpm = File(modelDir, "source.spm").length() > 10_000
        val hasTgtSpm = File(modelDir, "target.spm").length() > 10_000

        val names = (modelDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()).toSet()
        val filesOk = hasEncoder && hasDecoder && hasPast && hasTrim && hasSrcSpm && hasTgtSpm &&
            hasUsableVocabulary(names)
        val wanted = variant ?: return filesOk

        val diskMarker = File(modelDir, ".variant").takeIf { it.exists() }?.readText()?.trim()
            ?.let { QuantVariant.parse(it) }
        val effectiveVariant = diskMarker ?: currentInstalledVariant

        return filesOk && effectiveVariant == wanted
    }

    suspend fun downloadModel(
        targetDir: File,
        modelSpec: ModelSpec,
        variant: QuantVariant? = QuantVariant.DEFAULT,
        currentInstalledVariant: QuantVariant? = null,
        userAgent: String = "VBookMT",
        log: (String) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit = {},
        /**
         * Maps a bare file name to the URL it is fetched from.
         *
         * Defaults to the real Hugging Face layout; it exists so the download loop (Range resumption,
         * markers, variant switching) can be exercised against a local server. Those are the parts a
         * user notices after a crash, and before this seam the only way to test them was to download
         * hundreds of megabytes from the network.
         */
        resolveUrl: (String) -> String = { ModelRegistry.getDownloadUrl(modelSpec, it, variant) },
    ): Boolean = withContext(Dispatchers.IO) {
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetVariant = variant ?: QuantVariant.DEFAULT
        val variantMarker = File(targetDir, ".variant")
        val downloadingMarker = File(targetDir, ".variant_downloading")

        // Track in-progress variant on disk to support resume across app restarts/crashes
        val inProgressVariant = if (downloadingMarker.exists()) downloadingMarker.readText().trim() else null
        val installedVariantOnDisk = if (variantMarker.exists()) variantMarker.readText().trim() else null

        val isSameVariant = inProgressVariant == targetVariant.folder ||
            (inProgressVariant == null && (installedVariantOnDisk == targetVariant.folder || currentInstalledVariant == targetVariant))

        if (!isSameVariant) {
            // Clean up files and tmp if previous incomplete attempt or installed variant was a different quantization
            targetDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp") || file.name.endsWith(".onnx") ||
                    file.name.endsWith(".json") || file.name.endsWith(".spm") ||
                    file.name == ".variant"
                ) {
                    file.delete()
                }
            }
            downloadingMarker.writeText(targetVariant.folder)
        } else if (!downloadingMarker.exists()) {
            downloadingMarker.writeText(targetVariant.folder)
        }

        val files = modelSpec.filesFor(variant).map { it.substringAfterLast('/') }
        val totalFiles = files.size
        val totalExpectedBytes = (modelSpec.sizeMbFor(variant) * 1024L * 1024L).coerceAtLeast(1L)
        var totalBytesDownloaded = 0L

        // Pre-count bytes of existing fully-downloaded valid files so progress starts accurately
        for (fileName in files) {
            val destinationFile = File(targetDir, fileName)
            if (isSameVariant && isFileValid(destinationFile, fileName)) {
                totalBytesDownloaded += destinationFile.length()
            }
        }

        var lastReportedPercent = -1

        log("Bắt đầu tải model: ${modelSpec.name} (${targetVariant.label}, $totalFiles tệp)...")

        for ((index, fileName) in files.withIndex()) {
            val destinationFile = File(targetDir, fileName)
            if (isSameVariant && isFileValid(destinationFile, fileName)) {
                continue
            }

            val fileUrl = resolveUrl(fileName)
            val tempFile = File(targetDir, "$fileName.tmp")
            val baseBytesBeforeFile = totalBytesDownloaded

            var success = false
            for (attempt in 1..3) {
                var conn: HttpURLConnection? = null
                try {
                    var rangeStart = if (tempFile.exists() && isSameVariant) tempFile.length() else 0L

                    var currentUrl = fileUrl
                    var redirects = 0
                    while (redirects < 5) {
                        val uri = URI.create(currentUrl)
                        val candidateConn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                            connectTimeout = 30_000
                            readTimeout = 60_000
                            instanceFollowRedirects = true
                            setRequestProperty("User-Agent", userAgent)
                            if (rangeStart > 0) {
                                setRequestProperty("Range", "bytes=$rangeStart-")
                            }
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

                    if (responseCode == 416) { // Range Not Satisfiable
                        activeConn.disconnect()
                        tempFile.delete()
                        rangeStart = 0L
                        continue
                    }

                    if (responseCode !in 200..299) {
                        activeConn.disconnect()
                        continue
                    }

                    val isRangeAccepted = (responseCode == 206)
                    val appendMode = isRangeAccepted && rangeStart > 0
                    var currentFileBytes = if (appendMode) rangeStart else 0L
                    if (appendMode) {
                        log("Tiếp tục tải tệp $fileName từ ${rangeStart / 1024} KB...")
                    } else if (tempFile.exists() && rangeStart > 0) {
                        tempFile.delete()
                    }

                    activeConn.inputStream.use { input ->
                        FileOutputStream(tempFile, appendMode).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                currentFileBytes += bytesRead
                                val currentTotalBytes = baseBytesBeforeFile + currentFileBytes

                                val overallPercent = ((currentTotalBytes * 100) / totalExpectedBytes)
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
                                            bytesDownloaded = currentTotalBytes,
                                            totalBytes = totalExpectedBytes
                                        )
                                    )
                                }
                            }
                            output.flush()
                        }
                    }
                    activeConn.disconnect()

                    if (tempFile.exists() && isFileValid(tempFile, fileName)) {
                        if (destinationFile.exists()) destinationFile.delete()
                        val renamed = tempFile.renameTo(destinationFile)
                        if (renamed) {
                            totalBytesDownloaded = baseBytesBeforeFile + destinationFile.length()
                            success = true
                            break
                        }
                    }
                } catch (e: Exception) {
                    log("Lỗi tải $fileName (lần thử $attempt): ${e.message}")
                    if (attempt < 3) {
                        delay(attempt * 1000L)
                    }
                } finally {
                    conn?.disconnect()
                }
            }

            if (!success) {
                log("Tải model THẤT BẠI tại tệp $fileName")
                return@withContext false
            }
        }

        variantMarker.writeText(targetVariant.folder)
        downloadingMarker.delete()

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

        log("Tải hoàn tất model: ${modelSpec.name} (${targetVariant.label})")
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
