package com.vbookmt.windows.backend

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.vbookmt.core.model.ModelRegistry
import kotlinx.coroutines.runBlocking

class WindowsNativeTokenizerTest {

    @Test
    fun testNativeDirResolutionAndTokenizer() {
        val dir = WindowsNativeTokenizer.nativeDir()
        println("Resolved nativeDir: ${dir.absolutePath}")
        val loaded = WindowsNativeTokenizer.ensureLoaded(dir)
        assertTrue(loaded, "Failed to load vbookmt_native: ${WindowsNativeTokenizer.loadError()}")

        val pathProp = System.getProperty("VBOOKMT_ONNX_MODEL")
        val candidateDirs = listOfNotNull(
            pathProp?.let { File(it) },
            File("../../build/onnx_trimmed/hachimi-60-qt"),
            File("build/onnx_trimmed/hachimi-60-qt")
        )
        val modelDir = candidateDirs.firstOrNull { it.isDirectory && File(it, "source.spm").isFile }?.absoluteFile
        if (modelDir != null) {
            val ok = WindowsNativeTokenizer.load(modelDir.absolutePath)
            assertTrue(ok, "Failed to load tokenizer from $modelDir")
            assertTrue(WindowsNativeTokenizer.isReady())

            val sample = "他抬头看向远处的山门。"
            val count = WindowsNativeTokenizer.tokenCount(sample)
            assertTrue(count > 0, "tokenCount should be > 0, got $count")
            val encoded = WindowsNativeTokenizer.encode(sample)
            assertEquals(count, encoded.size, "encoded size should match tokenCount")

            val decoded = WindowsNativeTokenizer.decode(encoded)
            println("Token count: $count, encoded: ${encoded.toList()}, decoded: $decoded")
        }
    }

    @Test
    fun testWindowsTranslatorEndToEnd() = runBlocking {
        val pathProp = System.getProperty("VBOOKMT_ONNX_MODEL")
        val candidateDirs = listOfNotNull(
            pathProp?.let { File(it) },
            File("../../build/onnx_trimmed/hachimi-60-qt"),
            File("build/onnx_trimmed/hachimi-60-qt")
        )
        val modelSource = candidateDirs.firstOrNull { it.isDirectory && File(it, "source.spm").isFile }?.absoluteFile ?: return@runBlocking

        val targetDir = File(WindowsState.modelsDir, ModelRegistry.HACHIMI_60_QT.id)
        targetDir.mkdirs()
        modelSource.copyRecursively(targetDir, overwrite = true)
        try {
            val ok = WindowsTranslator.init(ModelRegistry.HACHIMI_60_QT.id)
            assertTrue(ok, "WindowsTranslator.init failed")
            assertTrue(WindowsTranslator.isReady(), "WindowsTranslator should be ready")

            val translated = WindowsTranslator.translate("他抬头看向远处的山门。")
            println("Translated: $translated")
            assertTrue(translated.isNotBlank())
            assertTrue(translated.contains("Hắn ngẩng đầu") || translated.contains("sơn môn"))
        } finally {
            targetDir.deleteRecursively()
        }
    }
}