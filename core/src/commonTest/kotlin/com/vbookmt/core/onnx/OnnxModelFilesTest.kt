package com.vbookmt.core.onnx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * These run against the exact JSON the exporter writes, so a change in the exporter's output that
 * the app cannot handle fails here rather than on a user's phone.
 */
class OnnxModelFilesTest {

    private val meta = """
        {
          "decoder_layers": 2,
          "decoder_attention_heads": 8,
          "head_dim": 72,
          "past_names": [
            "past_key_values.0.self_k",
            "past_key_values.0.self_v",
            "past_key_values.0.cross_k",
            "past_key_values.0.cross_v",
            "past_key_values.1.self_k",
            "past_key_values.1.self_v",
            "past_key_values.1.cross_k",
            "past_key_values.1.cross_v"
          ],
          "present_names": [
            "logits",
            "present.0.self_k"
          ],
          "vocab_size": 5951,
          "output_vocab_size": 5951,
          "input_vocab_size": 24000,
          "trimmed": true
        }
    """.trimIndent()

    private val trim = """
        {
          "keep_ids": [0, 1, 2, 3, 5, 6],
          "output_vocab_size": 6,
          "input_vocab_size": 24000
        }
    """.trimIndent()

    @Test
    fun parsesTheCacheInputNamesAndIgnoresNonCacheEntries() {
        val parsed = OnnxModelFiles.parseMeta(meta)

        assertEquals(8, parsed.cacheInputNames.size)
        assertTrue(parsed.cacheInputNames.all { it.startsWith("past_key_values") })
        // `logits` sits in present_names and must never be treated as a cache input.
        assertTrue(parsed.cacheInputNames.none { it == "logits" })
    }

    @Test
    fun parsesKeepIdsAsInts() {
        val parsed = OnnxModelFiles.parseTrim(trim)
        assertEquals(listOf(0, 1, 2, 3, 5, 6), parsed.keepIds)
    }

    @Test
    fun refusesATrimmedModelWithoutMapping() {
        val noIds = """{"keep_ids": [], "output_vocab_size": 0}"""
        assertFailsWith<IllegalArgumentException> {
            OnnxModelFiles.buildModel(meta, noIds, StubBackend(), StubTokenizer())
        }
    }

    @Test
    fun refusesAModelWithoutACache() {
        val noCache = """{"past_names": [], "present_names": [], "vocab_size": 6}"""
        assertFailsWith<IllegalArgumentException> {
            OnnxModelFiles.buildModel(noCache, trim, StubBackend(), StubTokenizer())
        }
    }

    @Test
    fun refusesWhenMetaAndTrimDisagreeOnTheVocabularySize() {
        // A 24,000-logit graph paired with a 6-entry mapping would throw on the first argmax lookup;
        // catching it at load time says which file is wrong.
        val mismatched = """{"keep_ids": [0, 1, 2], "output_vocab_size": 3}"""
        val err = assertFailsWith<IllegalArgumentException> {
            OnnxModelFiles.buildModel(meta, mismatched, StubBackend(), StubTokenizer())
        }
        assertTrue(err.message!!.contains("5951"), "message should name the sizes: ${err.message}")
    }

    @Test
    fun buildsAModelWhoseOutputWidthComesFromKeepIds() {
        val small = """{"decoder_layers": 2, "past_names": ["past_key_values.0.self_k"], "present_names": ["logits"]}"""
        val model = OnnxModelFiles.buildModel(small, trim, StubBackend(), StubTokenizer())

        assertEquals(6, model.outputVocabSize)
        assertEquals(listOf("past_key_values.0.self_k"), model.cacheInputNames)
        assertEquals(5, model.keepIds[4])
    }

    private class StubBackend : OnnxBackend {
        override fun run(graph: String, inputs: Map<String, TensorHandle>) = emptyList<TensorHandle>()
        override fun outputNames(graph: String) = emptyList<String>()
        override fun longs(values: LongArray, shape: LongArray): TensorHandle = Any()
        override fun readFloats(handle: TensorHandle) = FloatArray(0)
        override fun argmaxLastRow(handle: TensorHandle, rows: Int, vocab: Int) = IntArray(rows)
        override fun gatherRows(source: TensorHandle, keep: IntArray): TensorHandle = Any()
        override fun release(handle: TensorHandle) {}
    }

    private class StubTokenizer : OnnxTokenizer {
        override fun encode(text: String) = LongArray(0)
        override fun decode(ids: LongArray) = ""
    }
}
