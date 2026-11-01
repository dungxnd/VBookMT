package com.vbookmt.windows.backend.onnx

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import com.vbookmt.core.onnx.OnnxTokenizer
import java.nio.file.Path

/**
 * Test-only [OnnxTokenizer] backed by DJL's `tokenizer.json` reader.
 *
 * **Why a test-only implementation exists at all.** Production uses [NativeOnnxTokenizer], which
 * calls into `vbookmt_native.dll` — the same SentencePiece tables CTranslate2 loads. That library is
 * built by `native/windows/CMakeLists.txt` and is not present in a source checkout, so a JVM test
 * cannot call it.
 *
 * DJL is deliberately *not* a production dependency: it ships natives for linux/windows/macOS but
 * none for Android, so adopting it would work on desktop and silently fail on the phone. As a test
 * dependency it is useful precisely because it is an *independent* implementation — ids agreeing
 * between DJL and the `.spm` files is real corroboration rather than the same code checking itself.
 *
 * Tokenizer parity with the `.spm` was additionally verified in Python before the artifacts were
 * published, so this test is checking the graph, cache and vocabulary-mapping logic; the tokenizer
 * half is already known good.
 */
class DjlTestTokenizer private constructor(
    private val tokenizer: HuggingFaceTokenizer,
) : OnnxTokenizer, AutoCloseable {

    override fun encode(text: String): LongArray = tokenizer.encode(text).ids

    override fun decode(ids: LongArray): String = tokenizer.decode(ids)

    override fun close() = tokenizer.close()

    companion object {
        fun fromModelDir(modelDir: Path): DjlTestTokenizer {
            val json = modelDir.resolve("tokenizer.json")
            require(json.toFile().isFile) { "missing tokenizer.json in $modelDir" }
            return DjlTestTokenizer(HuggingFaceTokenizer.newInstance(json))
        }
    }
}
