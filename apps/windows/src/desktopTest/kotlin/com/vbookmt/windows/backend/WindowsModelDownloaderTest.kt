package com.vbookmt.windows.backend

import com.vbookmt.core.model.ModelRegistry
import com.vbookmt.core.model.QuantVariant
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsModelDownloaderTest {

    private val model = ModelRegistry.HACHIMI_60_QT
    private val modelDir = File(WindowsState.modelsDir, model.id)
    private var originalVariant: QuantVariant? = null

    @BeforeTest
    fun setUp() {
        originalVariant = WindowsState.installedVariant(model.id)
        if (modelDir.exists()) modelDir.deleteRecursively()
    }

    @AfterTest
    fun tearDown() {
        if (modelDir.exists()) modelDir.deleteRecursively()
        originalVariant?.let { WindowsState.setInstalledVariant(model.id, it) }
    }

    private fun populateFakeModelFiles(dir: File) {
        dir.mkdirs()
        File(dir, "encoder_model.onnx").writeBytes(ByteArray(600_000))
        File(dir, "decoder_model.onnx").writeBytes(ByteArray(600_000))
        File(dir, "decoder_with_past_model.onnx").writeBytes(ByteArray(600_000))
        File(dir, "trim_meta.json").writeBytes(ByteArray(200))
        File(dir, "source.spm").writeBytes(ByteArray(15_000))
        File(dir, "target.spm").writeBytes(ByteArray(15_000))
    }

    @Test
    fun `isModelReady returns false when model dir does not exist`() {
        assertFalse(WindowsModelDownloader.isModelReady(model))
    }

    @Test
    fun `isModelReady returns false when any required file is missing`() {
        populateFakeModelFiles(modelDir)
        File(modelDir, "encoder_model.onnx").delete()
        assertFalse(WindowsModelDownloader.isModelReady(model))
    }

    @Test
    fun `isModelReady without variant checks files only`() {
        populateFakeModelFiles(modelDir)
        assertTrue(WindowsModelDownloader.isModelReady(model, null))
    }

    @Test
    fun `isModelReady checks installed variant correctly`() {
        populateFakeModelFiles(modelDir)
        WindowsState.setInstalledVariant(model.id, QuantVariant.Q8)

        // Matching variant
        assertTrue(WindowsModelDownloader.isModelReady(model, QuantVariant.Q8))

        // Different variant on disk
        assertFalse(WindowsModelDownloader.isModelReady(model, QuantVariant.FP16))
    }
}
