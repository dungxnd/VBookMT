package com.vbookmt.windows.backend.onnx

import com.vbookmt.core.onnx.OnnxDecodeEngine
import com.vbookmt.core.onnx.ort.OrtOnnxModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end proof against the real exported graphs, not a fake backend.
 *
 * Skipped unless `VBOOKMT_ONNX_MODEL` points at a built model directory, because the artifacts are
 * ~200 MB and are not in the repository:
 *
 * ```
 * VBOOKMT_ONNX_MODEL=build/onnx_trimmed/hachimi-60-qt ./gradlew :apps:windows:desktopTest
 * ```
 *
 * The expected strings were produced by the fp32 HuggingFace model, so a pass here means the whole
 * chain — fp16 weights, trimmed vocabulary, KV cache, batching — reproduces the original output.
 */
class OrtOnnxModelTest {

    /**
     * Model directory, from a system property first and the environment second.
     *
     * The build forwards `-PonnxModel=...` as a system property because the Gradle daemon does not
     * reliably see a shell-exported environment variable. Reading only the environment is what made
     * this test report a pass in 0.05 s without loading anything.
     */
    private fun modelDir(): Path? =
        (System.getProperty("VBOOKMT_ONNX_MODEL")
            ?: System.getenv("VBOOKMT_ONNX_MODEL"))
            ?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) }
            ?.takeIf { Files.isDirectory(it) }

    private val expected = mapOf(
        "他抬头看向远处的山门。" to "Hắn ngẩng đầu nhìn về phía sơn môn xa xa.",
        "本王不要看。朕且听你说。" to "Bản vương không muốn nhìn. Trẫm tạm nghe ngươi nói.",
        "第一件事就这么难办。" to "Chuyện thứ nhất khó xử như vậy.",
    )

    @Test
    fun translatesRealGraphsExactly() {
        val dir = modelDir() ?: return
        OrtOnnxModel.load(dir, tokenizer = DjlTestTokenizer.fromModelDir(dir)).use { loaded ->
            val engine = OnnxDecodeEngine(loaded.model)
            for ((source, want) in expected) {
                val got = engine.translate(listOf(source), maxSteps = 48).single()
                assertEquals(want, got, "source: $source")
            }
        }
    }

    @Test
    fun batchingMatchesSingleRowOutput() {
        val dir = modelDir() ?: return
        OrtOnnxModel.load(dir, tokenizer = DjlTestTokenizer.fromModelDir(dir)).use { loaded ->
            val engine = OnnxDecodeEngine(loaded.model)
            val sources = expected.keys.toList()

            val batched = engine.translate(sources, maxSteps = 48, batchSize = sources.size)
            assertEquals(sources.size, batched.size)

            for ((i, source) in sources.withIndex()) {
                val single = engine.translate(listOf(source), maxSteps = 48).single()
                assertEquals(single, batched[i], "batched row $i differed from single-row output")
            }
        }
    }

    @Test
    fun outputVocabularyIsTrimmedNotFull() {
        val dir = modelDir() ?: return
        OrtOnnxModel.load(dir, tokenizer = DjlTestTokenizer.fromModelDir(dir)).use { loaded ->
            // 5,951 for the shipped trim. A 24,000 here means the untrimmed graph was loaded and the
            // keep_ids mapping would index past the end of the logits on the first token.
            assertEquals(5951, loaded.model.outputVocabSize)
            assertTrue(loaded.model.cacheInputNames.size == 8, "expected 2 layers x 4 cache tensors")
        }
    }

    @Test
    fun reportsMissingFilesInsteadOfFailingInsideOrt() {
        val empty = Files.createTempDirectory("onnx-missing")
        try {
            assertFalse(OrtOnnxModel.isLoadable(empty))
            // The tokenizer is irrelevant here: the file check runs before it is ever used, which is
            // the point — a half-downloaded model should fail on the missing file, not inside ORT.
            val err = runCatching {
                OrtOnnxModel.load(empty, tokenizer = DjlTestTokenizer.fromModelDir(empty))
            }.exceptionOrNull()
            assertTrue(err is IllegalArgumentException, "expected a clear error, got $err")
            // `assertTrue` has a contract, so `err` is smart-cast here; no explicit cast needed.
            assertTrue(err.message.orEmpty().contains("missing"), err.message.orEmpty())
        } finally {
            empty.toFile().deleteRecursively()
        }
    }
}
