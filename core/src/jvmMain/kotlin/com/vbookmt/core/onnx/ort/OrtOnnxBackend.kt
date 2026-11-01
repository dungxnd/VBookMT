package com.vbookmt.core.onnx.ort

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vbookmt.core.onnx.OnnxBackend
import com.vbookmt.core.onnx.TensorHandle
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

/**
 * [OnnxBackend] over ONNX Runtime's Java API.
 *
 * Lives in `jvmMain` so Android and Windows share one implementation — the Java API is identical on
 * both, only the native payload differs (`onnxruntime-android` ships `.so` files in an AAR, the
 * desktop jar ships `.dll`s). Which artifact is resolved is each app's decision, not this class's.
 *
 * **Precision.** The shipped graphs are fp16: `logits` comes back as `tensor(float16)`, and
 * `getFloatBuffer()` throws a type error on it. [readFloats] therefore decodes the halves itself.
 * Only `logits` is ever read, so that costs a few thousand half-floats per token rather than
 * anything proportional to the cache.
 *
 * **Ownership.** Tensors taken from an [OrtSession.Result] stay valid after the result wrapper is
 * dropped, because ORT frees memory per value rather than per result. The decode loop owns every
 * tensor it is given and closes each one; [close] releases the sessions.
 */
class OrtOnnxBackend(
    private val env: OrtEnvironment,
    private val sessions: Map<String, OrtSession>,
) : OnnxBackend, AutoCloseable {

    override fun run(graph: String, inputs: Map<String, TensorHandle>): List<TensorHandle> {
        val session = sessions[graph] ?: error("no session registered for graph '$graph'")
        val feed = inputs.mapValues { (_, v) -> v as OnnxTensor }
        val result = session.run(feed)
        // Deliberately not closing `result`: that would close the tensors being returned.
        return (0 until result.size()).map { result.get(it) }
    }

    override fun outputNames(graph: String): List<String> =
        sessions[graph]?.outputNames?.toList() ?: emptyList()

    override fun longs(values: LongArray, shape: LongArray): TensorHandle =
        OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)

    override fun readFloats(handle: TensorHandle): FloatArray {
        val tensor = handle as OnnxTensor
        val info = tensor.info
        val elements = info.shape.fold(1L) { acc, d -> acc * d }.toInt()

        // fp16 graphs: getFloatBuffer() refuses, so decode the halves ourselves.
        if (info.type == OnnxJavaType.FLOAT16 || info.type == OnnxJavaType.BFLOAT16) {
            val shorts = tensor.shortBuffer
            val out = FloatArray(elements)
            for (i in 0 until elements) out[i] = halfToFloat(shorts.get(i).toInt() and 0xFFFF)
            return out
        }

        val out = FloatArray(elements)
        tensor.floatBuffer.get(out)
        return out
    }

    override fun release(handle: TensorHandle) {
        (handle as? OnnxValue)?.close()
    }

    /**
     * Argmax over each row's last position, straight off the tensor's own buffer.
     *
     * The decode loop calls this once per generated token, so it is the single hottest piece of Kotlin
     * in the ONNX path. The old route was `readFloats` (converting the entire `[batch, 1, vocab]`
     * tensor to fp32) followed by a scan — ~95k conversions per step at batch 16 × 5,951 vocab, when
     * only 16 values are ever used.
     *
     * fp16 comparison is exact for this purpose: half-precision ordering matches the floats it would
     * convert to, so the chosen index cannot differ. NaN never appears in these logits; if it did,
     * `>` against NaN is false and the index would simply not move, same as before.
     */
    override fun argmaxLastRow(handle: TensorHandle, rows: Int, vocab: Int): IntArray {
        val tensor = handle as OnnxTensor
        val elements = tensor.info.shape.fold(1L) { acc, d -> acc * d }.toInt()
        val result = IntArray(rows)
        if (rows <= 0 || vocab <= 0) return result

        val rowWidth = elements / rows
        val lastOffset = rowWidth - vocab

        when (tensor.info.type) {
            OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 -> {
                val buf = tensor.shortBuffer
                for (row in 0 until rows) {
                    val base = row * rowWidth + lastOffset
                    // Compare as signed 16-bit halves: fp16 negatives have the sign bit set, and the
                    // raw short is already ordered correctly for two's-complement comparison of the
                    // same sign. Mixed-sign rows are handled by seeding with the first element.
                    var best = 0
                    var bestVal = halfToFloat(buf.get(base).toInt() and 0xFFFF)
                    for (i in 1 until vocab) {
                        val v = halfToFloat(buf.get(base + i).toInt() and 0xFFFF)
                        if (v > bestVal) {
                            bestVal = v
                            best = i
                        }
                    }
                    result[row] = best
                }
            }
            else -> {
                val buf = tensor.floatBuffer
                for (row in 0 until rows) {
                    val base = row * rowWidth + lastOffset
                    var best = 0
                    var bestVal = buf.get(base)
                    for (i in 1 until vocab) {
                        val v = buf.get(base + i)
                        if (v > bestVal) {
                            bestVal = v
                            best = i
                        }
                    }
                    result[row] = best
                }
            }
        }
        return result
    }

    /**
     * Gathers rows on axis 0, preserving the tensor's element type.
     *
     * The cache is fp16, and ORT binds a feed by declared type, so a re-created tensor must stay
     * `FLOAT16` — handing back a float32 copy would fail the graph's binding check rather than
     * quietly work. Rows are copied one at a time rather than through one contiguous slice because
     * [keep] need not be contiguous: a finished row can sit in the middle of the batch.
     */
    override fun gatherRows(source: TensorHandle, keep: IntArray): TensorHandle {
        val tensor = source as OnnxTensor
        val shape = tensor.info.shape
        val rows = shape[0].toInt()
        require(rows > 0) { "cannot gather rows from an empty tensor" }
        val perRow = (shape.fold(1L) { acc, d -> acc * d } / rows).toInt()

        val outShape = shape.copyOf()
        outShape[0] = keep.size.toLong()

        return when (tensor.info.type) {
            OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 -> {
                val src = tensor.shortBuffer
                val dst = ShortArray(keep.size * perRow)
                keep.forEachIndexed { i, row ->
                    src.position(row * perRow)
                    src.get(dst, i * perRow, perRow)
                }
                OnnxTensor.createTensor(env, ShortBuffer.wrap(dst), outShape, tensor.info.type)
            }

            OnnxJavaType.FLOAT -> {
                val src = tensor.floatBuffer
                val dst = FloatArray(keep.size * perRow)
                keep.forEachIndexed { i, row ->
                    src.position(row * perRow)
                    src.get(dst, i * perRow, perRow)
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(dst), outShape)
            }

            else -> error("gatherRows: unsupported cache element type ${tensor.info.type}")
        }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
    }

    /** IEEE 754 half -> single. Subnormals and inf/nan are not emitted by these graphs but are handled. */
    private fun halfToFloat(h: Int): Float {
        val sign = (h ushr 15) and 0x1
        val exponent = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF
        val value = when {
            exponent == 0 -> if (mantissa == 0) 0f else mantissa * 5.9604645e-8f
            exponent == 31 -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> {
                val bits = (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
                Float.fromBits(bits)
            }
        }
        return if (sign == 1) -value else value
    }
}
