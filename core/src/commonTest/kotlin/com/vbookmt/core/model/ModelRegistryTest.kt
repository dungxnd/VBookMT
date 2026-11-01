package com.vbookmt.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelRegistryTest {

    @Test
    fun acceptsBothSentencePieceTables() {
        assertTrue(hasUsableVocabulary(setOf("source.spm", "target.spm")))
        assertTrue(
            hasUsableVocabulary(
                setOf(
                    "config.json", "encoder_model.onnx", "decoder_model.onnx",
                    "decoder_with_past_model.onnx", "onnx_meta.json",
                    "source.spm", "target.spm", "trim_meta.json",
                )
            )
        )
    }

    @Test
    fun rejectsHalfDownloadedVocabulary() {
        // A half-downloaded artifact must not look ready: the native tokenizer needs both tables, and
        // loading one side would encode text into ids the decoder cannot line up with.
        assertFalse(hasUsableVocabulary(setOf("source.spm")))
        assertFalse(hasUsableVocabulary(setOf("target.spm")))
    }

    @Test
    fun rejectsDirectoriesWithoutAnyVocabulary() {
        assertFalse(hasUsableVocabulary(emptySet()))
        assertFalse(hasUsableVocabulary(setOf("config.json", "model.bin")))
        // CTranslate2's vocabulary layouts are gone with the engine, so they no longer count.
        assertFalse(hasUsableVocabulary(setOf("shared_vocabulary.json")))
        assertFalse(hasUsableVocabulary(setOf("source_vocabulary.json", "target_vocabulary.json")))
    }

    @Test
    fun requiredFileNamesAreBareAndOrdered() {
        assertEquals(
            ONNX_CORE_FILES,
            ModelRegistry.HACHIMI_60_QT.requiredFileNames(),
        )
    }

    @Test
    fun everyShippedModelIsOnnx() {
        // CTranslate2 is gone, so a spec still claiming it would download files nothing can load.
        for (model in ModelRegistry.ALL_MODELS) {
            assertEquals("ONNX", model.engine, "${model.id} should be an ONNX model")
        }
    }

    @Test
    fun modelsUseOnnxCoreFilesPerVariant() {
        for (model in ModelRegistry.ALL_MODELS) {
            // Artifacts are published per precision, so every variant must resolve to that variant's
            // folder — a flat ONNX_CORE_FILES list would silently fetch whichever build sat at the
            // repo root.
            assertTrue(model.hasVariants, "${model.id} should offer quant variants")
            for (variant in model.variants) {
                val files = model.filesFor(variant)
                assertEquals(
                    ONNX_CORE_FILES.map { "${variant.folder}/$it" },
                    files,
                    "unexpected layout for ${model.id} at ${variant.folder}",
                )
                assertTrue(
                    hasUsableVocabulary(files.map { it.substringAfterLast('/') }.toSet()),
                    "no usable vocabulary for ${model.id} at ${variant.folder}",
                )
            }
        }
    }

    @Test
    fun defaultVariantResolvesToTheDefaultFolder() {
        // downloadFiles is what a caller that passes no variant gets, so it has to match the DEFAULT
        // variant rather than being a separate hand-written list that could drift.
        for (model in ModelRegistry.ALL_MODELS) {
            assertEquals(onnxVariantFiles(QuantVariant.DEFAULT), model.downloadFiles)
        }
    }

    @Test
    fun thereIsExactlyOneDefaultModel() {
        assertEquals(1, ModelRegistry.ALL_MODELS.count { it.isDefault })
        assertEquals(ModelRegistry.HACHIMI_60_QT.id, ModelRegistry.DEFAULT_MODEL.id)
    }

    @Test
    fun unknownIdFallsBackToTheDefaultModel() {
        // The active-model file on disk can name a model that no longer ships (e.g. a CT2 id from a
        // build before this change). getById must not return a dead spec.
        assertEquals(ModelRegistry.DEFAULT_MODEL.id, ModelRegistry.getById("hachimi-60-qt").id)
        assertEquals(ModelRegistry.DEFAULT_MODEL.id, ModelRegistry.getById("nonsense").id)
    }

    @Test
    fun modelIdsStayDistinct() {
        val ids = ModelRegistry.ALL_MODELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate model id: $ids")
    }
}
