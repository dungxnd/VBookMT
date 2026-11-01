package com.vbookmt.wire.v1

/**
 * gRPC-Web framing and base64 codec.
 * Wire specification defined in docs/GRPC_WEB_MIGRATION.md.
 */
object GrpcWebEnvelope {

    const val MAX_MESSAGE_SIZE = 256 * 1024 // 256 KiB size cap

    class MessageTooLargeException(message: String) : IllegalArgumentException(message)

    enum class GrpcStatus(val code: Int) {
        OK(0),
        CANCELLED(1),
        UNKNOWN(2),
        INVALID_ARGUMENT(3),
        DEADLINE_EXCEEDED(4),
        NOT_FOUND(5),
        ALREADY_EXISTS(6),
        PERMISSION_DENIED(7),
        RESOURCE_EXHAUSTED(8),
        FAILED_PRECONDITION(9),
        ABORTED(10),
        OUT_OF_RANGE(11),
        UNIMPLEMENTED(12),
        INTERNAL(13),
        UNAVAILABLE(14),
        DATA_LOSS(15),
        UNAUTHENTICATED(16);

        companion object {
            fun fromCode(code: Int): GrpcStatus = entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    /**
     * Frames a protobuf message with gRPC 5-byte header:
     * [0x00] 1 byte compression flag (0 = uncompressed)
     * [length] 4 bytes big-endian length of the protobuf payload
     * [payload] N bytes
     */
    fun frame(protoBytes: ByteArray): ByteArray {
        val len = protoBytes.size
        val out = ByteArray(5 + len)
        out[0] = 0 // No compression
        out[1] = (len ushr 24).toByte()
        out[2] = (len ushr 16).toByte()
        out[3] = (len ushr 8).toByte()
        out[4] = (len and 0xFF).toByte()
        protoBytes.copyInto(out, destinationOffset = 5)
        return out
    }

    /**
     * Parses a 5-byte gRPC frame and returns the protobuf payload.
     * Enforces the 256 KiB message limit and uncompressed flag.
     */
    fun unframe(framedBytes: ByteArray): ByteArray {
        if (framedBytes.size < 5) {
            throw IllegalArgumentException("Framed message too short: ${framedBytes.size} bytes (minimum 5)")
        }
        val flag = framedBytes[0].toInt() and 0xFF
        if (flag != 0) {
            throw IllegalArgumentException("Unsupported gRPC compression flag: $flag (compression is not used)")
        }
        val len = ((framedBytes[1].toInt() and 0xFF) shl 24) or
            ((framedBytes[2].toInt() and 0xFF) shl 16) or
            ((framedBytes[3].toInt() and 0xFF) shl 8) or
            (framedBytes[4].toInt() and 0xFF)

        if (len < 0 || len > MAX_MESSAGE_SIZE) {
            throw MessageTooLargeException("Message size $len exceeds maximum cap of $MAX_MESSAGE_SIZE bytes")
        }
        if (framedBytes.size < 5 + len) {
            throw IllegalArgumentException("Framed buffer too short: expected ${5 + len} bytes, got ${framedBytes.size}")
        }
        return framedBytes.copyOfRange(5, 5 + len)
    }

    /** Encodes framed protobuf bytes to a base64 string for application/grpc-web-text+proto. */
    fun encodeGrpcWebText(protoBytes: ByteArray): String = encodeBase64(frame(protoBytes))

    /** Decodes application/grpc-web-text+proto base64 string to raw protobuf bytes. */
    fun decodeGrpcWebText(text: String): ByteArray = unframe(decodeBase64(text))

    // --- Pure Kotlin Base64 Codec ---

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(256) { -1 }.apply {
        for (i in BASE64_ALPHABET.indices) {
            this[BASE64_ALPHABET[i].code] = i
        }
    }

    fun encodeBase64(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i++].toInt() and 0xFF
            val has1 = i < bytes.size
            val b1 = if (has1) bytes[i++].toInt() and 0xFF else 0
            val has2 = i < bytes.size
            val b2 = if (has2) bytes[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            out.append(if (has1) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            out.append(if (has2) BASE64_ALPHABET[triple and 0x3F] else '=')
        }
        return out.toString()
    }

    fun decodeBase64(text: String): ByteArray {
        // Strip whitespace and newlines
        val clean = StringBuilder(text.length)
        for (ch in text) {
            if (ch > ' ') clean.append(ch)
        }
        var len = clean.length
        while (len > 0 && clean[len - 1] == '=') len--

        var bits = 0
        var bitCount = 0
        val buffer = ByteArray((len * 6) / 8)
        var byteIdx = 0

        for (i in 0 until len) {
            val ch = clean[i].code
            val value = if (ch in 0..255) DECODE_TABLE[ch] else -1
            if (value < 0) continue // Skip any unexpected characters
            bits = (bits shl 6) or value
            bitCount += 6
            if (bitCount >= 8) {
                bitCount -= 8
                buffer[byteIdx++] = ((bits ushr bitCount) and 0xFF).toByte()
            }
        }
        return if (byteIdx == buffer.size) buffer else buffer.copyOf(byteIdx)
    }
}
