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
        @Suppress("UNCHECKED_CAST")
        val feed = inputs as? Map<String, OnnxTensor>
            ?: inputs.mapValues { (_, v) -> v as OnnxTensor }
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
        val out = FloatArray(elements)

        when (info.type) {
            OnnxJavaType.FLOAT16 -> {
                val buf = tensor.shortBuffer
                if (buf.hasArray()) {
                    val raw = buf.array()
                    val offset = buf.arrayOffset()
                    for (i in 0 until elements) out[i] = halfToFloat(raw[offset + i].toInt() and 0xFFFF)
                } else {
                    val raw = ShortArray(elements)
                    buf.duplicate().get(raw)
                    for (i in 0 until elements) out[i] = halfToFloat(raw[i].toInt() and 0xFFFF)
                }
            }
            OnnxJavaType.BFLOAT16 -> {
                val buf = tensor.shortBuffer
                if (buf.hasArray()) {
                    val raw = buf.array()
                    val offset = buf.arrayOffset()
                    for (i in 0 until elements) out[i] = bf16ToFloat(raw[offset + i])
                } else {
                    val raw = ShortArray(elements)
                    buf.duplicate().get(raw)
                    for (i in 0 until elements) out[i] = bf16ToFloat(raw[i])
                }
            }
            OnnxJavaType.FLOAT -> {
                tensor.floatBuffer.duplicate().get(out)
            }
            else -> error("readFloats: unsupported type ${info.type}")
        }
        return out
    }

    override fun release(handle: TensorHandle) {
        (handle as? OnnxValue)?.close()
    }

    /**
     * Argmax over each row's last position, straight off the tensor's own buffer.
     *
     * The decode loop calls this once per generated token, so it is the single hottest piece of Kotlin
     * in the ONNX path. If the buffer is heap-backed, reads directly from the array. Otherwise bulk-reads
     * each row into a reusable array via duplicate() and compares FP16/BF16 bits directly via
     * [fp16ToComparable] without converting to float32.
     */
    override fun argmaxLastRow(handle: TensorHandle, rows: Int, vocab: Int): IntArray {
        val tensor = handle as OnnxTensor
        val elements = tensor.info.shape.fold(1L) { acc, d -> acc * d }.toInt()
        val result = IntArray(rows)
        if (rows <= 0 || vocab <= 0) return result

        val rowWidth = elements / rows
        require(vocab <= rowWidth) { "vocab ($vocab) cannot exceed rowWidth ($rowWidth)" }
        val lastOffset = rowWidth - vocab

        when (tensor.info.type) {
            OnnxJavaType.FLOAT16, OnnxJavaType.BFLOAT16 -> {
                val buf = tensor.shortBuffer
                if (buf.hasArray()) {
                    val raw = buf.array()
                    val baseOffset = buf.arrayOffset() + lastOffset
                    for (row in 0 until rows) {
                        val base = baseOffset + row * rowWidth
                        var best = 0
                        var bestVal = fp16ToComparable(raw[base])
                        for (i in 1 until vocab) {
                            val v = fp16ToComparable(raw[base + i])
                            if (v > bestVal) {
                                bestVal = v
                                best = i
                            }
                        }
                        result[row] = best
                    }
                } else {
                    val dup = buf.duplicate()
                    val rowBuf = ShortArray(vocab)
                    for (row in 0 until rows) {
                        val base = row * rowWidth + lastOffset
                        dup.position(base)
                        dup.get(rowBuf, 0, vocab)
                        var best = 0
                        var bestVal = fp16ToComparable(rowBuf[0])
                        for (i in 1 until vocab) {
                            val v = fp16ToComparable(rowBuf[i])
                            if (v > bestVal) {
                                bestVal = v
                                best = i
                            }
                        }
                        result[row] = best
                    }
                }
            }
            else -> {
                val buf = tensor.floatBuffer
                if (buf.hasArray()) {
                    val raw = buf.array()
                    val baseOffset = buf.arrayOffset() + lastOffset
                    for (row in 0 until rows) {
                        val base = baseOffset + row * rowWidth
                        var best = 0
                        var bestVal = raw[base]
                        for (i in 1 until vocab) {
                            val v = raw[base + i]
                            if (v > bestVal) {
                                bestVal = v
                                best = i
                            }
                        }
                        result[row] = best
                    }
                } else {
                    val dup = buf.duplicate()
                    val rowBuf = FloatArray(vocab)
                    for (row in 0 until rows) {
                        val base = row * rowWidth + lastOffset
                        dup.position(base)
                        dup.get(rowBuf, 0, vocab)
                        var best = 0
                        var bestVal = rowBuf[0]
                        for (i in 1 until vocab) {
                            val v = rowBuf[i]
                            if (v > bestVal) {
                                bestVal = v
                                best = i
                            }
                        }
                        result[row] = best
                    }
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
                if (src.hasArray()) {
                    val srcArr = src.array()
                    val srcOffset = src.arrayOffset()
                    keep.forEachIndexed { i, row ->
                        require(row in 0 until rows) { "Row $row out of bounds for tensor with $rows rows" }
                        System.arraycopy(srcArr, srcOffset + row * perRow, dst, i * perRow, perRow)
                    }
                } else {
                    val dup = src.duplicate()
                    keep.forEachIndexed { i, row ->
                        require(row in 0 until rows) { "Row $row out of bounds for tensor with $rows rows" }
                        dup.position(row * perRow)
                        dup.get(dst, i * perRow, perRow)
                    }
                }
                OnnxTensor.createTensor(env, ShortBuffer.wrap(dst), outShape, tensor.info.type)
            }

            OnnxJavaType.FLOAT -> {
                val src = tensor.floatBuffer
                val dst = FloatArray(keep.size * perRow)
                if (src.hasArray()) {
                    val srcArr = src.array()
                    val srcOffset = src.arrayOffset()
                    keep.forEachIndexed { i, row ->
                        require(row in 0 until rows) { "Row $row out of bounds for tensor with $rows rows" }
                        System.arraycopy(srcArr, srcOffset + row * perRow, dst, i * perRow, perRow)
                    }
                } else {
                    val dup = src.duplicate()
                    keep.forEachIndexed { i, row ->
                        require(row in 0 until rows) { "Row $row out of bounds for tensor with $rows rows" }
                        dup.position(row * perRow)
                        dup.get(dst, i * perRow, perRow)
                    }
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(dst), outShape)
            }

            else -> error("gatherRows: unsupported cache element type ${tensor.info.type}")
        }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
    }

    /**
     * Works for any 16-bit sign-magnitude floating point format (IEEE FP16 and BF16).
     * Bit 15 is sign; remaining 15 bits are magnitude.
     */
    private fun fp16ToComparable(raw: Short): Int =
        if (raw >= 0) raw.toInt() else -32768 - raw.toInt()

    private fun bf16ToFloat(raw: Short): Float =
        Float.fromBits((raw.toInt() and 0xFFFF) shl 16)

    /** IEEE 754 half -> single. Subnormals and inf/nan are handled. */
    private fun halfToFloat(h: Int): Float {
        val sign = (h ushr 15) and 0x1
        val exponent = (h ushr 10) and 0x1F
        val mantissa = h and 0x3FF
        return when (exponent) {
            0 -> {
                val mag = mantissa * 5.9604645e-8f // 2^-24
                if (sign == 1) -mag else mag
            }
            31 -> {
                if (mantissa == 0) {
                    if (sign == 1) Float.NEGATIVE_INFINITY else Float.POSITIVE_INFINITY
                } else {
                    Float.NaN
                }
            }
            else -> {
                val bits = (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
                Float.fromBits(bits)
            }
        }
    }
}
