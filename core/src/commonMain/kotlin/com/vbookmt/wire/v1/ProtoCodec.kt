package com.vbookmt.wire.v1

/**
 * Byte-level Proto3 encoder and decoder for the VBookMT wire schema.
 * Zero external dependencies, pure Kotlin, compatible with Kotlin Multiplatform (JVM & mingwX64).
 */
object ProtoCodec {

    // --- High-level Encoders & Decoders ---

    fun encodeEmpty(empty: Empty = Empty): ByteArray = ByteArray(0)
    fun decodeEmpty(bytes: ByteArray): Empty = Empty

    fun encodeTranslateRequest(req: TranslateRequest): ByteArray {
        val writer = ProtoWriter()
        writer.writeString(1, req.text)
        writer.writeString(2, req.from)
        writer.writeString(3, req.to)
        writer.writeString(4, req.style)
        writer.writeString(5, req.model)
        return writer.toByteArray()
    }

    fun decodeTranslateRequest(bytes: ByteArray): TranslateRequest {
        val reader = ProtoReader(bytes)
        var text = ""
        var from = "auto"
        var to = "vi"
        var style = "qt"
        var model = ""

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()

            when (fieldNumber) {
                1 -> text = reader.readString()
                2 -> from = reader.readString()
                3 -> to = reader.readString()
                4 -> style = reader.readString()
                5 -> model = reader.readString()
                else -> reader.skip(wireType)
            }
        }
        return TranslateRequest(text = text, from = from, to = to, style = style, model = model)
    }

    fun encodeTranslateResponse(res: TranslateResponse): ByteArray {
        val writer = ProtoWriter()
        writer.writeBool(1, res.success)
        writer.writeString(2, res.translatedText)
        writer.writeString(3, res.error)
        writer.writeInt32(4, res.elapsedMs)
        return writer.toByteArray()
    }

    fun decodeTranslateResponse(bytes: ByteArray): TranslateResponse {
        val reader = ProtoReader(bytes)
        var success = false
        var translatedText = ""
        var error = ""
        var elapsedMs = 0

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()

            when (fieldNumber) {
                1 -> success = reader.readBool()
                2 -> translatedText = reader.readString()
                3 -> error = reader.readString()
                4 -> elapsedMs = reader.readInt32()
                else -> reader.skip(wireType)
            }
        }
        return TranslateResponse(
            success = success,
            translatedText = translatedText,
            error = error,
            elapsedMs = elapsedMs
        )
    }

    fun encodeTranslateConfig(cfg: TranslateConfig): ByteArray {
        val writer = ProtoWriter()
        writer.writeOptionalInt32(1, cfg.maxBatch)
        writer.writeOptionalString(2, cfg.batchType)
        writer.writeOptionalInt32(3, cfg.threads)
        writer.writeOptionalInt32(4, cfg.chunkChars)
        writer.writeOptionalString(5, cfg.computeType)
        writer.writeOptionalBool(6, cfg.clearCache)
        writer.writeOptionalBool(7, cfg.cacheEnabled)
        return writer.toByteArray()
    }

    fun decodeTranslateConfig(bytes: ByteArray): TranslateConfig {
        val reader = ProtoReader(bytes)
        var maxBatch: Int? = null
        var batchType: String? = null
        var threads: Int? = null
        var chunkChars: Int? = null
        var computeType: String? = null
        var clearCache: Boolean? = null
        var cacheEnabled: Boolean? = null

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()

            when (fieldNumber) {
                1 -> maxBatch = reader.readInt32()
                2 -> batchType = reader.readString()
                3 -> threads = reader.readInt32()
                4 -> chunkChars = reader.readInt32()
                5 -> computeType = reader.readString()
                6 -> clearCache = reader.readBool()
                7 -> cacheEnabled = reader.readBool()
                else -> reader.skip(wireType)
            }
        }
        return TranslateConfig(
            maxBatch = maxBatch,
            batchType = batchType,
            threads = threads,
            chunkChars = chunkChars,
            computeType = computeType,
            clearCache = clearCache,
            cacheEnabled = cacheEnabled
        )
    }

    fun encodeConfigResult(res: ConfigResult): ByteArray {
        val writer = ProtoWriter()
        writer.writeString(1, res.appliedJson)
        return writer.toByteArray()
    }

    fun decodeConfigResult(bytes: ByteArray): ConfigResult {
        val reader = ProtoReader(bytes)
        var appliedJson = ""
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (fieldNumber) {
                1 -> appliedJson = reader.readString()
                else -> reader.skip(wireType)
            }
        }
        return ConfigResult(appliedJson = appliedJson)
    }

    fun encodeHealthReport(rep: HealthReport): ByteArray {
        val writer = ProtoWriter()
        writer.writeString(1, rep.status)
        writer.writeString(2, rep.host)
        writer.writeString(3, rep.engine)
        writer.writeInt32(4, rep.port)
        writer.writeString(5, rep.model)
        writer.writeBool(6, rep.ready)
        writer.writeInt32(7, rep.requests)
        writer.writeInt32(8, rep.inflight)
        writer.writeInt32(9, rep.threads)
        writer.writeInt32(10, rep.maxBatch)
        writer.writeString(11, rep.batchType)
        writer.writeString(12, rep.computeType)
        writer.writeInt32(13, rep.chunkChars)
        writer.writeBool(14, rep.cacheEnabled)
        writer.writeInt32(15, rep.cacheEntries)
        writer.writeInt32(16, rep.cacheChars)
        writer.writeInt32(17, rep.cacheHits)
        writer.writeInt32(18, rep.cacheMisses)
        writer.writeDouble(19, rep.cacheHitPct)
        writer.writeInt32(20, rep.cacheDiskBytes)
        writer.writeInt32(21, rep.cacheDiskRestored)
        writer.writeInt32(22, rep.pssMb)
        writer.writeInt32(23, rep.jvmHeapMb)
        return writer.toByteArray()
    }

    fun decodeHealthReport(bytes: ByteArray): HealthReport {
        val reader = ProtoReader(bytes)
        var status = ""
        var host = ""
        var engine = ""
        var port = 0
        var model = ""
        var ready = false
        var requests = 0
        var inflight = 0
        var threads = 0
        var maxBatch = 0
        var batchType = ""
        var computeType = ""
        var chunkChars = 0
        var cacheEnabled = false
        var cacheEntries = 0
        var cacheChars = 0
        var cacheHits = 0
        var cacheMisses = 0
        var cacheHitPct = 0.0
        var cacheDiskBytes = 0
        var cacheDiskRestored = 0
        var pssMb = 0
        var jvmHeapMb = 0

        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()

            when (fieldNumber) {
                1 -> status = reader.readString()
                2 -> host = reader.readString()
                3 -> engine = reader.readString()
                4 -> port = reader.readInt32()
                5 -> model = reader.readString()
                6 -> ready = reader.readBool()
                7 -> requests = reader.readInt32()
                8 -> inflight = reader.readInt32()
                9 -> threads = reader.readInt32()
                10 -> maxBatch = reader.readInt32()
                11 -> batchType = reader.readString()
                12 -> computeType = reader.readString()
                13 -> chunkChars = reader.readInt32()
                14 -> cacheEnabled = reader.readBool()
                15 -> cacheEntries = reader.readInt32()
                16 -> cacheChars = reader.readInt32()
                17 -> cacheHits = reader.readInt32()
                18 -> cacheMisses = reader.readInt32()
                19 -> cacheHitPct = reader.readDouble()
                20 -> cacheDiskBytes = reader.readInt32()
                21 -> cacheDiskRestored = reader.readInt32()
                22 -> pssMb = reader.readInt32()
                23 -> jvmHeapMb = reader.readInt32()
                else -> reader.skip(wireType)
            }
        }
        return HealthReport(
            status = status,
            host = host,
            engine = engine,
            port = port,
            model = model,
            ready = ready,
            requests = requests,
            inflight = inflight,
            threads = threads,
            maxBatch = maxBatch,
            batchType = batchType,
            computeType = computeType,
            chunkChars = chunkChars,
            cacheEnabled = cacheEnabled,
            cacheEntries = cacheEntries,
            cacheChars = cacheChars,
            cacheHits = cacheHits,
            cacheMisses = cacheMisses,
            cacheHitPct = cacheHitPct,
            cacheDiskBytes = cacheDiskBytes,
            cacheDiskRestored = cacheDiskRestored,
            pssMb = pssMb,
            jvmHeapMb = jvmHeapMb
        )
    }

    fun encodeEnergySnapshot(snap: EnergySnapshot): ByteArray {
        val writer = ProtoWriter()
        writer.writeRepeatedString(1, snap.recordsJsonl)
        return writer.toByteArray()
    }

    fun decodeEnergySnapshot(bytes: ByteArray): EnergySnapshot {
        val reader = ProtoReader(bytes)
        val records = mutableListOf<String>()
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (fieldNumber) {
                1 -> records.add(reader.readString())
                else -> reader.skip(wireType)
            }
        }
        return EnergySnapshot(recordsJsonl = records)
    }

    fun encodeResetResult(res: ResetResult): ByteArray {
        val writer = ProtoWriter()
        writer.writeBool(1, res.reset)
        return writer.toByteArray()
    }

    fun decodeResetResult(bytes: ByteArray): ResetResult {
        val reader = ProtoReader(bytes)
        var reset = false
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (fieldNumber) {
                1 -> reset = reader.readBool()
                else -> reader.skip(wireType)
            }
        }
        return ResetResult(reset = reset)
    }

    // --- Low-level Writer ---

    internal class ProtoWriter {
        private var buffer = ByteArray(128)
        private var count = 0

        fun toByteArray(): ByteArray = buffer.copyOf(count)

        private fun ensureCapacity(extra: Int) {
            val required = count + extra
            if (required > buffer.size) {
                var newSize = buffer.size * 2
                while (newSize < required) newSize *= 2
                buffer = buffer.copyOf(newSize)
            }
        }

        private fun writeByte(b: Int) {
            ensureCapacity(1)
            buffer[count++] = b.toByte()
        }

        private fun writeBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
            ensureCapacity(length)
            bytes.copyInto(buffer, destinationOffset = count, startIndex = offset, endIndex = offset + length)
            count += length
        }

        fun writeVarint(value: Long) {
            var v = value
            while ((v and 0x7FL.inv()) != 0L) {
                writeByte(((v and 0x7FL).toInt()) or 0x80)
                v = v ushr 7
            }
            writeByte((v and 0x7FL).toInt())
        }

        fun writeTag(fieldNumber: Int, wireType: Int) {
            writeVarint(((fieldNumber.toLong() shl 3) or wireType.toLong()))
        }

        fun writeString(fieldNumber: Int, value: String) {
            if (value.isEmpty()) return
            val bytes = value.encodeToByteArray()
            writeTag(fieldNumber, 2)
            writeVarint(bytes.size.toLong())
            writeBytes(bytes)
        }

        fun writeOptionalString(fieldNumber: Int, value: String?) {
            if (value == null) return
            val bytes = value.encodeToByteArray()
            writeTag(fieldNumber, 2)
            writeVarint(bytes.size.toLong())
            writeBytes(bytes)
        }

        fun writeBool(fieldNumber: Int, value: Boolean) {
            if (!value) return
            writeTag(fieldNumber, 0)
            writeVarint(1L)
        }

        fun writeOptionalBool(fieldNumber: Int, value: Boolean?) {
            if (value == null) return
            writeTag(fieldNumber, 0)
            writeVarint(if (value) 1L else 0L)
        }

        fun writeInt32(fieldNumber: Int, value: Int) {
            if (value == 0) return
            writeTag(fieldNumber, 0)
            writeVarint(value.toLong())
        }

        fun writeOptionalInt32(fieldNumber: Int, value: Int?) {
            if (value == null) return
            writeTag(fieldNumber, 0)
            writeVarint(value.toLong())
        }

        fun writeDouble(fieldNumber: Int, value: Double) {
            if (value == 0.0) return
            writeTag(fieldNumber, 1)
            val bits = value.toBits()
            ensureCapacity(8)
            for (i in 0 until 8) {
                buffer[count++] = ((bits ushr (i * 8)) and 0xFFL).toByte()
            }
        }

        fun writeRepeatedString(fieldNumber: Int, values: List<String>) {
            for (value in values) {
                val bytes = value.encodeToByteArray()
                writeTag(fieldNumber, 2)
                writeVarint(bytes.size.toLong())
                writeBytes(bytes)
            }
        }
    }

    // --- Low-level Reader ---

    internal class ProtoReader(private val bytes: ByteArray) {
        private var offset = 0

        fun hasMore(): Boolean = offset < bytes.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (offset >= bytes.size) {
                    throw IllegalArgumentException("Unexpected EOF while reading varint")
                }
                val b = bytes[offset++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) {
                    return result
                }
                shift += 7
            }
            throw IllegalArgumentException("Malformed varint: exceeds 64 bits")
        }

        fun readString(): String {
            val len = readVarint().toInt()
            if (len < 0 || offset + len > bytes.size) {
                throw IllegalArgumentException("String length $len out of bounds (remaining ${bytes.size - offset})")
            }
            val str = bytes.decodeToString(startIndex = offset, endIndex = offset + len)
            offset += len
            return str
        }

        fun readBool(): Boolean = readVarint() != 0L

        fun readInt32(): Int = readVarint().toInt()

        fun readDouble(): Double {
            if (offset + 8 > bytes.size) {
                throw IllegalArgumentException("Unexpected EOF while reading double")
            }
            var bits = 0L
            for (i in 0 until 8) {
                bits = bits or ((bytes[offset++].toLong() and 0xFFL) shl (i * 8))
            }
            return Double.fromBits(bits)
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> {
                    if (offset + 8 > bytes.size) throw IllegalArgumentException("EOF skipping 64-bit field")
                    offset += 8
                }
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || offset + len > bytes.size) throw IllegalArgumentException("EOF skipping length-delimited field")
                    offset += len
                }
                5 -> {
                    if (offset + 4 > bytes.size) throw IllegalArgumentException("EOF skipping 32-bit field")
                    offset += 4
                }
                else -> throw IllegalArgumentException("Unsupported protobuf wire type: $wireType")
            }
        }
    }
}
