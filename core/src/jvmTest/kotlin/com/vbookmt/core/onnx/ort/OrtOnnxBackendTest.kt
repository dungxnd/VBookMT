package com.vbookmt.core.onnx.ort

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrtOnnxBackendTest {

    private val env = OrtEnvironment.getEnvironment()
    private val backend = OrtOnnxBackend(env, emptyMap())

    @Test
    fun readFloatsDecodesFp16SignedValuesCorrectly() {
        // FP16 bit representations:
        // +1.0 = 0x3C00
        // -1.0 = 0xBC00 (-17408 as Short)
        // -2.5 = 0xC100 (-16128 as Short)
        // +0.5 = 0x3800
        val fp16Values = shortArrayOf(
            0x3C00.toShort(),
            0xBC00.toShort(),
            0xC100.toShort(),
            0x3800.toShort(),
        )
        val tensor = OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(fp16Values),
            longArrayOf(1, 4),
            OnnxJavaType.FLOAT16
        )

        val floats = backend.readFloats(tensor)
        assertEquals(4, floats.size)
        assertEquals(1.0f, floats[0], 1e-4f)
        assertEquals(-1.0f, floats[1], 1e-4f, "FP16 negative 1.0 must decode to -1.0f, not +1.0f")
        assertEquals(-2.5f, floats[2], 1e-4f, "FP16 negative 2.5 must decode to -2.5f, not +2.5f")
        assertEquals(0.5f, floats[3], 1e-4f)

        backend.release(tensor)
    }

    @Test
    fun readFloatsDecodesBfloat16ValuesCorrectly() {
        // BFLOAT16 is the upper 16 bits of Float32
        // +1.0f in float32 = 0x3F800000 -> BF16 = 0x3F80
        // -1.0f in float32 = 0xBF800000 -> BF16 = 0xBF80
        // -3.5f in float32 = 0xC0600000 -> BF16 = 0xC060
        val bf16Values = shortArrayOf(
            0x3F80.toShort(),
            0xBF80.toShort(),
            0xC060.toShort(),
        )
        val tensor = OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(bf16Values),
            longArrayOf(1, 3),
            OnnxJavaType.BFLOAT16
        )

        val floats = backend.readFloats(tensor)
        assertEquals(3, floats.size)
        assertEquals(1.0f, floats[0], 1e-4f)
        assertEquals(-1.0f, floats[1], 1e-4f, "BF16 negative 1.0 must decode to -1.0f")
        assertEquals(-3.5f, floats[2], 1e-4f, "BF16 negative 3.5 must decode to -3.5f")

        backend.release(tensor)
    }

    @Test
    fun argmaxLastRowWithHeapBuffer() {
        // 2 rows, vocab size = 4
        // Row 0: [-5.0, -1.0, -0.5, -2.0] -> index 2 (-0.5 is maximum)
        // Row 1: [ 1.0,  3.0,  2.0,  0.0] -> index 1 (3.0 is maximum)
        // In FP16:
        // -5.0 = 0xC500
        // -1.0 = 0xBC00
        // -0.5 = 0xB800
        // -2.0 = 0xC000
        //  1.0 = 0x3C00
        //  3.0 = 0x4200
        //  2.0 = 0x4000
        //  0.0 = 0x0000
        val fp16Logits = shortArrayOf(
            0xC500.toShort(), 0xBC00.toShort(), 0xB800.toShort(), 0xC000.toShort(),
            0x3C00.toShort(), 0x4200.toShort(), 0x4000.toShort(), 0x0000.toShort(),
        )
        val tensor = OnnxTensor.createTensor(
            env,
            ShortBuffer.wrap(fp16Logits),
            longArrayOf(2, 4),
            OnnxJavaType.FLOAT16
        )

        val winners = backend.argmaxLastRow(tensor, rows = 2, vocab = 4)
        assertEquals(2, winners.size)
        assertEquals(2, winners[0], "Row 0 max should be index 2 (-0.5)")
        assertEquals(1, winners[1], "Row 1 max should be index 1 (3.0)")

        backend.release(tensor)
    }

    @Test
    fun argmaxLastRowWithDirectBuffer() {
        // Test direct NIO buffer path (!buf.hasArray())
        val fp16Logits = shortArrayOf(
            0xC500.toShort(), 0xBC00.toShort(), 0xB800.toShort(), 0xC000.toShort(),
            0x3C00.toShort(), 0x4200.toShort(), 0x4000.toShort(), 0x0000.toShort(),
        )
        val directByteBuffer = ByteBuffer.allocateDirect(fp16Logits.size * 2).order(ByteOrder.nativeOrder())
        val directShortBuffer = directByteBuffer.asShortBuffer()
        directShortBuffer.put(fp16Logits)
        directShortBuffer.flip()

        val tensor = OnnxTensor.createTensor(
            env,
            directShortBuffer,
            longArrayOf(2, 4),
            OnnxJavaType.FLOAT16
        )

        val winners = backend.argmaxLastRow(tensor, rows = 2, vocab = 4)
        assertEquals(2, winners.size)
        assertEquals(2, winners[0], "Direct buffer row 0 max should be index 2")
        assertEquals(1, winners[1], "Direct buffer row 1 max should be index 1")

        backend.release(tensor)
    }

    @Test
    fun gatherRowsPreservesDataWithoutMutatingBuffer() {
        // 3 rows of width 2
        // Row 0: [10.0, 11.0]
        // Row 1: [20.0, 21.0]
        // Row 2: [30.0, 31.0]
        val floats = floatArrayOf(
            10.0f, 11.0f,
            20.0f, 21.0f,
            30.0f, 31.0f,
        )
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(floats),
            longArrayOf(3, 2)
        )

        // Keep rows 2 and 0
        val gathered = backend.gatherRows(tensor, intArrayOf(2, 0))
        val resultFloats = backend.readFloats(gathered)

        assertEquals(4, resultFloats.size)
        assertEquals(30.0f, resultFloats[0])
        assertEquals(31.0f, resultFloats[1])
        assertEquals(10.0f, resultFloats[2])
        assertEquals(11.0f, resultFloats[3])

        backend.release(tensor)
        backend.release(gathered)
    }
}
