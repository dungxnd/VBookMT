package com.vbookmt.core.onnx

/**
 * The seam between the ONNX decode loop and whatever runtime executes the graphs.
 *
 * The loop lives in `commonMain` so batching, cache handling and vocabulary mapping are unit-tested
 * on the JVM against a fake backend. Android and Windows only implement this interface, which is
 * where ONNX Runtime's Java API actually exists.
 *
 * One backend serves all three graphs rather than one per graph, because ONNX Runtime creates its
 * environment once and then any number of sessions inside it. Graph names are the constants in
 * [Graphs].
 *
 * Tensors are opaque handles rather than primitive arrays on purpose. The decoder cache is eight
 * fp16 tensors per step (two layers x four), and copying those into Kotlin arrays every token would
 * cost more than the graph does. Handles let the cache flow from one step's outputs straight into the
 * next step's inputs without being read; only the small `logits` tensor is ever materialised.
 */
interface OnnxBackend {

    /** Runs [graph] with [inputs] and returns its outputs in the order the session declares them. */
    fun run(graph: String, inputs: Map<String, TensorHandle>): List<TensorHandle>

    /** Output names of [graph], in the order [run] returns them. */
    fun outputNames(graph: String): List<String>

    fun longs(values: LongArray, shape: LongArray): TensorHandle

    /**
     * Reads a tensor as fp32, converting from fp16 when the graph produces half precision.
     *
     * Only `logits` is ever read, and only its last row per batch entry is used, so the conversion
     * costs a few thousand values per token rather than anything proportional to the cache.
     */
    fun readFloats(handle: TensorHandle): FloatArray

    /**
     * Index of the largest value in each row's **last** position, without materialising fp32.
     *
     * `logits` is `[batch, tgt_len, vocab]` and the decode loop only ever wants the argmax of the final
     * position per row. Going through [readFloats] converts the whole tensor to fp32 first — at batch
     * 16 with a 5,951-wide vocabulary that is ~95k half→float conversions *per decode step*, of which
     * only ~16 values matter. This reads the half buffer directly and compares halves.
     *
     * Correctness note: comparisons happen on fp16 values. Monotonicity is preserved (a larger half is
     * a larger half), so the argmax index is identical to comparing the converted floats — the only
     * thing lost is the exact magnitude, which this never needs.
     *
     * @param rows number of batch rows; each row's payload is `vocab` values wide.
     */
    fun argmaxLastRow(handle: TensorHandle, rows: Int, vocab: Int): IntArray

    /**
     * Returns the same tensor with axis 0 reduced to [keep], in that order.
     *
     * Exists so the decode loop can drop rows that already emitted EOS. Without it, a finished row
     * keeps being decoded and kept in the cache until the whole batch finishes; with a measured
     * ~2.7 ms marginal cost per row at a 448-token cache, eight dead rows in a 16-row batch cost
     * more than the copy that removes them.
     *
     * Only the decoder cache needs this. Output type is preserved, because the cache is fp16 and
     * re-creating it in another dtype would change the graph's binding type, not just its values.
     */
    fun gatherRows(source: TensorHandle, keep: IntArray): TensorHandle

    fun release(handle: TensorHandle)

    fun releaseAll(handles: Iterable<TensorHandle?>) {
        handles.forEach { if (it != null) release(it) }
    }
}

/** Graph names the decode loop addresses. */
object Graphs {
    const val ENCODER = "encoder"
    const val DECODER = "decoder"
    const val DECODER_WITH_PAST = "decoder_with_past"
}

/** An opaque tensor owned by an [OnnxBackend]. */
typealias TensorHandle = Any

/**
 * Text to ids and back.
 *
 * Kept out of the backend because the two hosts reach SentencePiece differently — Android and Windows
 * already link it inside `native/` — while the decode loop only needs text in, ids out.
 */
interface OnnxTokenizer {
    /** Source text to model ids, including the trailing `</s>` the graphs were trained with. */
    fun encode(text: String): LongArray

    /** Model ids back to text, skipping special tokens. */
    fun decode(ids: LongArray): String
}

/**
 * Everything one trimmed Marian ONNX export needs at run time.
 *
 * [keepIds] is the crux. The decoder emits logits of width `keepIds.size` (5,951 in the shipped
 * models), so `argmax` indexes the *trimmed* vocabulary. Feeding that index back as a token id
 * produces fluent Vietnamese from the wrong words, which reads as a quality problem rather than a
 * bug — so the mapping is enforced by [OnnxDecodeEngine] rather than left to callers.
 */
class OnnxModel(
    val backend: OnnxBackend,
    val tokenizer: OnnxTokenizer,
    val keepIds: IntArray,
    /** Input names of the with-past graph that carry the cache, in declaration order. */
    val cacheInputNames: List<String>,
    /** Output names of the with-past graph, in declaration order. `logits` is expected first. */
    val withPastOutputNames: List<String>,
) {
    val outputVocabSize: Int get() = keepIds.size

    fun encode(text: String): LongArray = tokenizer.encode(text)

    fun decode(ids: LongArray): String = tokenizer.decode(ids)
}
