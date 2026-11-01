package com.vbookmt.wire.v1

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtoCodecTest {

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun testGoldenVectorTranslateRequest() {
        // Spec test vector from docs/GRPC_WEB_MIGRATION.md Section 2:
        // A Translate request with text = "你好" is:
        // 00 00000008 0A06 E4BDA0 E5A5BD - 13 bytes, base64-encoded as HTTP body
        val req = TranslateRequest(text = "你好", from = "", to = "", style = "")
        val protoBytes = ProtoCodec.encodeTranslateRequest(req)
        assertEquals("0a06e4bda0e5a5bd", protoBytes.toHex())

        val framed = GrpcWebEnvelope.frame(protoBytes)
        assertEquals("00000000080a06e4bda0e5a5bd", framed.toHex())

        val base64 = GrpcWebEnvelope.encodeGrpcWebText(protoBytes)
        assertEquals("AAAAAAgKBuS9oOWlvQ==", base64)

        // Decode from standard padded base64
        val decodedProto1 = GrpcWebEnvelope.decodeGrpcWebText(base64)
        val decodedReq1 = ProtoCodec.decodeTranslateRequest(decodedProto1)
        assertEquals("你好", decodedReq1.text)

        // Decode from unpadded base64
        val decodedProto2 = GrpcWebEnvelope.decodeGrpcWebText("AAAAAAgKBuS9oOWlvQ")
        val decodedReq2 = ProtoCodec.decodeTranslateRequest(decodedProto2)
        assertEquals("你好", decodedReq2.text)

        // Decode with embedded whitespace/newlines
        val decodedProto3 = GrpcWebEnvelope.decodeGrpcWebText("  AAAAAAgK\nBuS9oOWlvQ==\r\n ")
        val decodedReq3 = ProtoCodec.decodeTranslateRequest(decodedProto3)
        assertEquals("你好", decodedReq3.text)
    }

    @Test
    fun testTranslateRequestRoundTrip() {
        val original = TranslateRequest(
            text = "Một đoạn văn dài kiểm tra tính đúng đắn của protobuf encoder và decoder.",
            from = "zh",
            to = "vi",
            style = "qt",
            model = "hachimi-60-qt"
        )
        val bytes = ProtoCodec.encodeTranslateRequest(original)
        val decoded = ProtoCodec.decodeTranslateRequest(bytes)
        assertEquals(original, decoded)
    }

    @Test
    fun testTranslateResponseRoundTrip() {
        val successRes = TranslateResponse(
            success = true,
            translatedText = "Văn bản đã được dịch thành công.",
            error = "",
            elapsedMs = 452
        )
        val bytes1 = ProtoCodec.encodeTranslateResponse(successRes)
        val decoded1 = ProtoCodec.decodeTranslateResponse(bytes1)
        assertEquals(successRes, decoded1)

        val errorRes = TranslateResponse(
            success = false,
            translatedText = "",
            error = "Lỗi bộ nhớ hoặc engine chưa sẵn sàng",
            elapsedMs = 12
        )
        val bytes2 = ProtoCodec.encodeTranslateResponse(errorRes)
        val decoded2 = ProtoCodec.decodeTranslateResponse(bytes2)
        assertEquals(errorRes, decoded2)
    }

    @Test
    fun testTranslateConfigOptionalPresence() {
        // When all fields are null (unset patch), encoded bytes must be empty
        val emptyConfig = TranslateConfig()
        val emptyBytes = ProtoCodec.encodeTranslateConfig(emptyConfig)
        assertEquals(0, emptyBytes.size)

        val decodedEmpty = ProtoCodec.decodeTranslateConfig(emptyBytes)
        assertNull(decodedEmpty.maxBatch)
        assertNull(decodedEmpty.batchType)
        assertNull(decodedEmpty.threads)
        assertNull(decodedEmpty.chunkChars)
        assertNull(decodedEmpty.computeType)
        assertNull(decodedEmpty.clearCache)
        assertNull(decodedEmpty.cacheEnabled)

        // When fields are explicitly set, even to 0 / "" / false, they must be preserved
        val patchConfig = TranslateConfig(
            maxBatch = 0,
            batchType = "",
            threads = 4,
            chunkChars = 120,
            computeType = "int8",
            clearCache = false,
            cacheEnabled = true
        )
        val patchBytes = ProtoCodec.encodeTranslateConfig(patchConfig)
        assertTrue(patchBytes.isNotEmpty())

        val decodedPatch = ProtoCodec.decodeTranslateConfig(patchBytes)
        assertEquals(0, decodedPatch.maxBatch)
        assertEquals("", decodedPatch.batchType)
        assertEquals(4, decodedPatch.threads)
        assertEquals(120, decodedPatch.chunkChars)
        assertEquals("int8", decodedPatch.computeType)
        assertEquals(false, decodedPatch.clearCache)
        assertEquals(true, decodedPatch.cacheEnabled)
    }

    @Test
    fun testConfigResultRoundTrip() {
        val res = ConfigResult(appliedJson = """{"threads":4,"max_batch":32,"ready":true}""")
        val bytes = ProtoCodec.encodeConfigResult(res)
        val decoded = ProtoCodec.decodeConfigResult(bytes)
        assertEquals(res, decoded)
    }

    @Test
    fun testHealthReportRoundTrip() {
        val report = HealthReport(
            status = "healthy",
            host = "android",
            engine = "ONNX",
            port = 8765,
            model = "hachimi-60-qt",
            ready = true,
            requests = 124,
            inflight = 1,
            threads = 4,
            maxBatch = 32,
            batchType = "tokens",
            computeType = "int8",
            chunkChars = 140,
            cacheEnabled = true,
            cacheEntries = 1530,
            cacheChars = 61200,
            cacheHits = 432,
            cacheMisses = 1098,
            cacheHitPct = 28.235,
            cacheDiskBytes = 1048576,
            cacheDiskRestored = 1200,
            pssMb = -1,
            jvmHeapMb = 58
        )
        val bytes = ProtoCodec.encodeHealthReport(report)
        val decoded = ProtoCodec.decodeHealthReport(bytes)
        assertEquals(report, decoded)
    }

    @Test
    fun testEnergySnapshotRoundTrip() {
        val snapshot = EnergySnapshot(
            recordsJsonl = listOf(
                """{"kind":"header","device":"Pixel 7","threads":4}""",
                """{"kind":"chapter","index":1,"wall_ms":3200,"mah":12.5}""",
                """{"kind":"chapter","index":2,"wall_ms":3150,"mah":12.2}"""
            )
        )
        val bytes = ProtoCodec.encodeEnergySnapshot(snapshot)
        val decoded = ProtoCodec.decodeEnergySnapshot(bytes)
        assertEquals(snapshot, decoded)
    }

    @Test
    fun testResetResultRoundTrip() {
        val resTrue = ResetResult(reset = true)
        assertEquals(resTrue, ProtoCodec.decodeResetResult(ProtoCodec.encodeResetResult(resTrue)))

        val resFalse = ResetResult(reset = false)
        assertEquals(resFalse, ProtoCodec.decodeResetResult(ProtoCodec.encodeResetResult(resFalse)))
    }

    @Test
    fun testUnknownFieldsIgnored() {
        // Encode a valid TranslateResponse
        val original = TranslateResponse(success = true, translatedText = "Hello", elapsedMs = 10)
        val baseBytes = ProtoCodec.encodeTranslateResponse(original)

        // Append unknown fields:
        // field 99, wireType 0 (varint) -> tag = (99 << 3) | 0 = 792 = 0x98 0x06, value 42
        // field 100, wireType 2 (length-delimited) -> tag = (100 << 3) | 2 = 802 = 0xA2 0x06, len 3, "ABC"
        // field 101, wireType 1 (64-bit) -> tag = (101 << 3) | 1 = 809 = 0xA9 0x06, 8 bytes
        // field 102, wireType 5 (32-bit) -> tag = (102 << 3) | 5 = 821 = 0xB5 0x06, 4 bytes
        val unknownBytes = byteArrayOf(
            0x98.toByte(), 0x06, 42,
            0xA2.toByte(), 0x06, 3, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(),
            0xA9.toByte(), 0x06, 1, 2, 3, 4, 5, 6, 7, 8,
            0xB5.toByte(), 0x06, 1, 2, 3, 4
        )
        val combined = baseBytes + unknownBytes
        val decoded = ProtoCodec.decodeTranslateResponse(combined)
        assertEquals(original, decoded)
    }

    @Test
    fun testSizeCapEnforced() {
        val tooLarge = ByteArray(GrpcWebEnvelope.MAX_MESSAGE_SIZE + 1)
        assertFailsWith<IllegalArgumentException> {
            val framed = ByteArray(5 + tooLarge.size)
            val len = tooLarge.size
            framed[0] = 0
            framed[1] = (len ushr 24).toByte()
            framed[2] = (len ushr 16).toByte()
            framed[3] = (len ushr 8).toByte()
            framed[4] = (len and 0xFF).toByte()
            GrpcWebEnvelope.unframe(framed)
        }
    }
}
