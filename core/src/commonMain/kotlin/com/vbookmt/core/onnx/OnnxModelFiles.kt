package com.vbookmt.core.onnx

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `onnx_meta.json` — written by the exporter so the client never has to guess tensor names.
 *
 * The cache layout is the reason this file exists. Marian keeps four tensors per layer
 * (`self_k`, `self_v`, `cross_k`, `cross_v`), so a client that assumes the usual
 * `past_key_values.N.key` / `.value` pair feeds the wrong thing and gets garbage — or a crash.
 *
 * The JSON is `snake_case` while Kotlin is `camelCase`, so every field needs an explicit
 * `@SerialName`. Without it kotlinx.serialization silently uses the default for each property, which
 * looks like "the file is empty" rather than "the names do not match".
 */
@Serializable
data class OnnxMeta(
    @SerialName("decoder_layers") val decoderLayers: Int = 2,
    @SerialName("decoder_attention_heads") val decoderAttentionHeads: Int = 8,
    @SerialName("head_dim") val headDim: Int = 72,
    /** Input names of `decoder_with_past`, in the order the graph declares them. */
    @SerialName("past_names") val pastNames: List<String> = emptyList(),
    /** Output names of the decoders, in graph order. `logits` is expected first. */
    @SerialName("present_names") val presentNames: List<String> = emptyList(),
    @SerialName("vocab_size") val vocabSize: Int = 0,
    @SerialName("output_vocab_size") val outputVocabSize: Int = 0,
    @SerialName("input_vocab_size") val inputVocabSize: Int = 0,
    @SerialName("trimmed") val trimmed: Boolean = false,
) {
    /** Cache inputs only — the encoder mask is a separate input and must not be treated as cache. */
    val cacheInputNames: List<String> get() = pastNames.filter { it.startsWith("past_key_values") }
}

/**
 * `trim_meta.json` — the output-vocabulary mapping.
 *
 * `keepIds[i]` is the real tokenizer id that trimmed index `i` stands for. The decoder emits
 * `keepIds.size` logits, so `argmax` is an index into THIS array, not a token id.
 */
@Serializable
data class TrimMeta(
    @SerialName("keep_ids") val keepIds: List<Int> = emptyList(),
    @SerialName("output_vocab_size") val outputVocabSize: Int = 0,
    @SerialName("input_vocab_size") val inputVocabSize: Int = 0,
)

/**
 * Parses the two sidecar files an ONNX model directory carries.
 *
 * Kept separate from [OnnxModel] so the parsing is unit-tested against the exact files the exporter
 * writes, on any host, with no runtime installed.
 */
object OnnxModelFiles {

    private val json = Json { ignoreUnknownKeys = true }

    fun parseMeta(text: String): OnnxMeta = json.decodeFromString<OnnxMeta>(text)

    fun parseTrim(text: String): TrimMeta = json.decodeFromString<TrimMeta>(text)

    /**
     * Builds the runtime description from the two sidecar files.
     *
     * Fails loudly when the mapping does not match the model, because the alternative is silent
     * garbage: an untrimmed graph (24,000 logits) paired with a 5,951-entry mapping would index out
     * of bounds on the first token, and a reversed mismatch would produce plausible Vietnamese from
     * the wrong words.
     */
    fun buildModel(
        metaText: String,
        trimText: String,
        backend: OnnxBackend,
        tokenizer: OnnxTokenizer,
    ): OnnxModel {
        val meta = parseMeta(metaText)
        val trim = parseTrim(trimText)

        require(trim.keepIds.isNotEmpty()) {
            "trim_meta.json has no keep_ids — the trimmed-vocabulary mapping is required"
        }
        require(meta.cacheInputNames.isNotEmpty()) {
            "onnx_meta.json lists no past_key_values inputs — the model has no KV cache"
        }
        if (meta.outputVocabSize != 0) {
            require(meta.outputVocabSize == trim.keepIds.size) {
                "onnx_meta says ${meta.outputVocabSize} output pieces but trim_meta has " +
                    "${trim.keepIds.size} keep_ids"
            }
        }

        return OnnxModel(
            backend = backend,
            tokenizer = tokenizer,
            keepIds = trim.keepIds.toIntArray(),
            cacheInputNames = meta.cacheInputNames,
            withPastOutputNames = meta.presentNames,
        )
    }
}
