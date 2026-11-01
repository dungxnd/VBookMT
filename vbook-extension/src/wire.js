/**
 * VBookMT Protobuf & gRPC-Web wire codec for vBook extension.
 *
 * Implements protobuf encoding/decoding and gRPC-Web framing per proto/vbookmt.proto
 * and docs/GRPC_WEB_MIGRATION.md.
 *
 * Runs in sandboxed JS:
 * - Zero npm dependencies.
 * - Self-contained Base64 and UTF-8 codec (no mandatory btoa/atob).
 * - Enforces 256 KiB maximum message limit.
 */
(function (root) {
    "use strict";

    var MAX_MESSAGE_SIZE = 256 * 1024; // 256 KiB cap

    // ---------------------------------------------------------------------------
    // Base64 Codec
    // ---------------------------------------------------------------------------
    var B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    var B64_LOOKUP = new Int32Array(256);
    for (var i = 0; i < 256; i++) B64_LOOKUP[i] = -1;
    for (var i = 0; i < B64_CHARS.length; i++) {
        B64_LOOKUP[B64_CHARS.charCodeAt(i)] = i;
    }

    function encodeBase64(bytes) {
        var len = bytes.length;
        var out = "";
        var i = 0;
        while (i < len) {
            var b0 = bytes[i++];
            var has1 = i < len;
            var b1 = has1 ? bytes[i++] : 0;
            var has2 = i < len;
            var b2 = has2 ? bytes[i++] : 0;

            var triple = (b0 << 16) | (b1 << 8) | b2;
            out += B64_CHARS.charAt((triple >> 18) & 0x3f);
            out += B64_CHARS.charAt((triple >> 12) & 0x3f);
            out += has1 ? B64_CHARS.charAt((triple >> 6) & 0x3f) : "=";
            out += has2 ? B64_CHARS.charAt(triple & 0x3f) : "=";
        }
        return out;
    }

    function decodeBase64(str) {
        if (!str) return new Uint8Array(0);
        // Filter out whitespace and newlines
        var clean = "";
        for (var i = 0; i < str.length; i++) {
            var ch = str.charAt(i);
            if (ch > " ") clean += ch;
        }

        var len = clean.length;
        while (len > 0 && clean.charAt(len - 1) === "=") len--;

        var outLen = ((len * 6) / 8) | 0;
        var out = new Uint8Array(outLen);
        var bits = 0;
        var bitCount = 0;
        var byteIdx = 0;

        for (var i = 0; i < len; i++) {
            var code = clean.charCodeAt(i);
            var val = code < 256 ? B64_LOOKUP[code] : -1;
            if (val < 0) continue;
            bits = (bits << 6) | val;
            bitCount += 6;
            if (bitCount >= 8) {
                bitCount -= 8;
                out[byteIdx++] = (bits >> bitCount) & 0xff;
            }
        }
        return byteIdx === outLen ? out : out.subarray(0, byteIdx);
    }

    // ---------------------------------------------------------------------------
    // UTF-8 Codec
    // ---------------------------------------------------------------------------
    var hasNativeText = typeof TextEncoder !== "undefined" && typeof TextDecoder !== "undefined";
    var textEncoder = hasNativeText ? new TextEncoder() : null;
    var textDecoder = hasNativeText ? new TextDecoder("utf-8") : null;

    function encodeUtf8(str) {
        if (!str) return new Uint8Array(0);
        if (textEncoder) return textEncoder.encode(str);

        // Fallback manual UTF-8 encoder
        var bytes = [];
        for (var i = 0; i < str.length; i++) {
            var code = str.charCodeAt(i);
            if (code >= 0xd800 && code <= 0xdbff && i + 1 < str.length) {
                var next = str.charCodeAt(i + 1);
                if (next >= 0xdc00 && next <= 0xdfff) {
                    code = (code - 0xd800) * 0x400 + (next - 0xdc00) + 0x10000;
                    i++;
                }
            }
            if (code < 0x80) {
                bytes.push(code);
            } else if (code < 0x800) {
                bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
            } else if (code < 0x10000) {
                bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
            } else {
                bytes.push(0xf0 | (code >> 18), 0x80 | ((code >> 12) & 0x3f), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
            }
        }
        return new Uint8Array(bytes);
    }

    function decodeUtf8(bytes, start, end) {
        start = start || 0;
        end = end !== undefined ? end : bytes.length;
        if (start >= end) return "";
        if (textDecoder && start === 0 && end === bytes.length) {
            return textDecoder.decode(bytes);
        }
        if (textDecoder) {
            return textDecoder.decode(bytes.subarray(start, end));
        }

        // Fallback manual UTF-8 decoder
        var out = "";
        var i = start;
        while (i < end) {
            var b = bytes[i++];
            if (b < 0x80) {
                out += String.fromCharCode(b);
            } else if ((b & 0xe0) === 0xc0) {
                var b2 = bytes[i++];
                out += String.fromCharCode(((b & 0x1f) << 6) | (b2 & 0x3f));
            } else if ((b & 0xf0) === 0xe0) {
                var b2 = bytes[i++];
                var b3 = bytes[i++];
                out += String.fromCharCode(((b & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f));
            } else if ((b & 0xf8) === 0xf0) {
                var b2 = bytes[i++];
                var b3 = bytes[i++];
                var b4 = bytes[i++];
                var cp = (((b & 0x07) << 18) | ((b2 & 0x3f) << 12) | ((b3 & 0x3f) << 6) | (b4 & 0x3f)) - 0x10000;
                out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------
    // Protobuf Low-level Writer
    // ---------------------------------------------------------------------------
    function ProtoWriter() {
        this.buf = new Uint8Array(128);
        this.len = 0;
    }

    ProtoWriter.prototype.ensureCapacity = function (extra) {
        var needed = this.len + extra;
        if (needed > this.buf.length) {
            var newCap = this.buf.length * 2;
            while (newCap < needed) newCap *= 2;
            var next = new Uint8Array(newCap);
            next.set(this.buf.subarray(0, this.len));
            this.buf = next;
        }
    };

    ProtoWriter.prototype.writeByte = function (b) {
        this.ensureCapacity(1);
        this.buf[this.len++] = b & 0xff;
    };

    ProtoWriter.prototype.writeBytes = function (arr) {
        if (!arr || arr.length === 0) return;
        this.ensureCapacity(arr.length);
        this.buf.set(arr, this.len);
        this.len += arr.length;
    };

    ProtoWriter.prototype.writeVarint = function (val) {
        // Safe 32-bit/53-bit varint encoder
        while (val > 0x7f) {
            this.writeByte((val & 0x7f) | 0x80);
            val = Math.floor(val / 128);
        }
        this.writeByte(val & 0x7f);
    };

    ProtoWriter.prototype.writeTag = function (fieldNumber, wireType) {
        this.writeVarint((fieldNumber << 3) | (wireType & 7));
    };

    ProtoWriter.prototype.writeString = function (fieldNumber, str) {
        if (!str) return;
        var bytes = encodeUtf8(str);
        this.writeTag(fieldNumber, 2);
        this.writeVarint(bytes.length);
        this.writeBytes(bytes);
    };

    ProtoWriter.prototype.writeOptionalString = function (fieldNumber, str) {
        if (str === null || str === undefined) return;
        var bytes = encodeUtf8(str);
        this.writeTag(fieldNumber, 2);
        this.writeVarint(bytes.length);
        this.writeBytes(bytes);
    };

    ProtoWriter.prototype.writeBool = function (fieldNumber, b) {
        if (!b) return;
        this.writeTag(fieldNumber, 0);
        this.writeVarint(1);
    };

    ProtoWriter.prototype.writeOptionalBool = function (fieldNumber, b) {
        if (b === null || b === undefined) return;
        this.writeTag(fieldNumber, 0);
        this.writeVarint(b ? 1 : 0);
    };

    ProtoWriter.prototype.writeInt32 = function (fieldNumber, n) {
        if (!n) return;
        this.writeTag(fieldNumber, 0);
        this.writeVarint(n);
    };

    ProtoWriter.prototype.writeOptionalInt32 = function (fieldNumber, n) {
        if (n === null || n === undefined) return;
        this.writeTag(fieldNumber, 0);
        this.writeVarint(n);
    };

    ProtoWriter.prototype.writeDouble = function (fieldNumber, d) {
        if (!d || d === 0.0) return;
        this.writeTag(fieldNumber, 1);
        this.ensureCapacity(8);
        var view = new DataView(this.buf.buffer, this.buf.byteOffset + this.len, 8);
        view.setFloat64(0, d, true); // little-endian
        this.len += 8;
    };

    ProtoWriter.prototype.writeRepeatedString = function (fieldNumber, list) {
        if (!list) return;
        for (var i = 0; i < list.length; i++) {
            var bytes = encodeUtf8(list[i]);
            this.writeTag(fieldNumber, 2);
            this.writeVarint(bytes.length);
            this.writeBytes(bytes);
        }
    };

    ProtoWriter.prototype.toUint8Array = function () {
        return this.buf.subarray(0, this.len);
    };

    // ---------------------------------------------------------------------------
    // Protobuf Low-level Reader
    // ---------------------------------------------------------------------------
    function ProtoReader(bytes) {
        this.bytes = bytes || new Uint8Array(0);
        this.pos = 0;
    }

    ProtoReader.prototype.hasMore = function () {
        return this.pos < this.bytes.length;
    };

    ProtoReader.prototype.readVarint = function () {
        var res = 0;
        var shift = 0;
        while (shift < 56) {
            if (this.pos >= this.bytes.length) {
                throw new Error("Unexpected EOF reading varint");
            }
            var b = this.bytes[this.pos++];
            res += (b & 0x7f) * Math.pow(2, shift);
            if ((b & 0x80) === 0) return res;
            shift += 7;
        }
        throw new Error("Varint overflow");
    };

    ProtoReader.prototype.readString = function () {
        var len = this.readVarint();
        if (this.pos + len > this.bytes.length) {
            throw new Error("String length " + len + " exceeds buffer");
        }
        var str = decodeUtf8(this.bytes, this.pos, this.pos + len);
        this.pos += len;
        return str;
    };

    ProtoReader.prototype.readBool = function () {
        return this.readVarint() !== 0;
    };

    ProtoReader.prototype.readInt32 = function () {
        return this.readVarint() | 0;
    };

    ProtoReader.prototype.readDouble = function () {
        if (this.pos + 8 > this.bytes.length) {
            throw new Error("Unexpected EOF reading double");
        }
        var view = new DataView(this.bytes.buffer, this.bytes.byteOffset + this.pos, 8);
        var val = view.getFloat64(0, true); // little-endian
        this.pos += 8;
        return val;
    };

    ProtoReader.prototype.skip = function (wireType) {
        if (wireType === 0) {
            this.readVarint();
        } else if (wireType === 1) {
            if (this.pos + 8 > this.bytes.length) throw new Error("EOF skipping 64-bit");
            this.pos += 8;
        } else if (wireType === 2) {
            var len = this.readVarint();
            if (this.pos + len > this.bytes.length) throw new Error("EOF skipping length-delimited");
            this.pos += len;
        } else if (wireType === 5) {
            if (this.pos + 4 > this.bytes.length) throw new Error("EOF skipping 32-bit");
            this.pos += 4;
        } else {
            throw new Error("Unsupported wire type: " + wireType);
        }
    };

    // ---------------------------------------------------------------------------
    // High-Level Message Codecs
    // ---------------------------------------------------------------------------
    function encodeTranslateRequest(req) {
        req = req || {};
        var writer = new ProtoWriter();
        writer.writeString(1, req.text || "");
        writer.writeString(2, req.from || "");
        writer.writeString(3, req.to || "");
        writer.writeString(4, req.style || "");
        writer.writeString(5, req.model || "");
        return writer.toUint8Array();
    }

    function decodeTranslateRequest(bytes) {
        var reader = new ProtoReader(bytes);
        var out = { text: "", from: "auto", to: "vi", style: "qt", model: "" };
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) out.text = reader.readString();
            else if (fieldNum === 2) out.from = reader.readString();
            else if (fieldNum === 3) out.to = reader.readString();
            else if (fieldNum === 4) out.style = reader.readString();
            else if (fieldNum === 5) out.model = reader.readString();
            else reader.skip(wireType);
        }
        return out;
    }

    function encodeTranslateResponse(res) {
        res = res || {};
        var writer = new ProtoWriter();
        writer.writeBool(1, !!res.success);
        writer.writeString(2, res.translated_text || res.translatedText || "");
        writer.writeString(3, res.error || "");
        writer.writeInt32(4, res.elapsed_ms || res.elapsedMs || 0);
        return writer.toUint8Array();
    }

    function decodeTranslateResponse(bytes) {
        var reader = new ProtoReader(bytes);
        var out = { success: false, translated_text: "", error: "", elapsed_ms: 0 };
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) out.success = reader.readBool();
            else if (fieldNum === 2) out.translated_text = reader.readString();
            else if (fieldNum === 3) out.error = reader.readString();
            else if (fieldNum === 4) out.elapsed_ms = reader.readInt32();
            else reader.skip(wireType);
        }
        return out;
    }

    function encodeTranslateConfig(cfg) {
        cfg = cfg || {};
        var writer = new ProtoWriter();
        writer.writeOptionalInt32(1, cfg.max_batch !== undefined ? cfg.max_batch : cfg.maxBatch);
        writer.writeOptionalString(2, cfg.batch_type !== undefined ? cfg.batch_type : cfg.batchType);
        writer.writeOptionalInt32(3, cfg.threads !== undefined ? cfg.threads : cfg.threads);
        writer.writeOptionalInt32(4, cfg.chunk_chars !== undefined ? cfg.chunk_chars : cfg.chunkChars);
        writer.writeOptionalString(5, cfg.compute_type !== undefined ? cfg.compute_type : cfg.computeType);
        writer.writeOptionalBool(6, cfg.clear_cache !== undefined ? cfg.clear_cache : cfg.clearCache);
        writer.writeOptionalBool(7, cfg.cache_enabled !== undefined ? cfg.cache_enabled : cfg.cacheEnabled);
        return writer.toUint8Array();
    }

    function decodeTranslateConfig(bytes) {
        var reader = new ProtoReader(bytes);
        var out = {
            max_batch: null,
            batch_type: null,
            threads: null,
            chunk_chars: null,
            compute_type: null,
            clear_cache: null,
            cache_enabled: null
        };
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) out.max_batch = reader.readInt32();
            else if (fieldNum === 2) out.batch_type = reader.readString();
            else if (fieldNum === 3) out.threads = reader.readInt32();
            else if (fieldNum === 4) out.chunk_chars = reader.readInt32();
            else if (fieldNum === 5) out.compute_type = reader.readString();
            else if (fieldNum === 6) out.clear_cache = reader.readBool();
            else if (fieldNum === 7) out.cache_enabled = reader.readBool();
            else reader.skip(wireType);
        }
        return out;
    }

    function encodeConfigResult(res) {
        res = res || {};
        var writer = new ProtoWriter();
        writer.writeString(1, res.applied_json || res.appliedJson || "");
        return writer.toUint8Array();
    }

    function decodeConfigResult(bytes) {
        var reader = new ProtoReader(bytes);
        var out = { applied_json: "" };
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) out.applied_json = reader.readString();
            else reader.skip(wireType);
        }
        return out;
    }

    function encodeHealthReport(rep) {
        rep = rep || {};
        var writer = new ProtoWriter();
        writer.writeString(1, rep.status || "");
        writer.writeString(2, rep.host || "");
        writer.writeString(3, rep.engine || "");
        writer.writeInt32(4, rep.port || 0);
        writer.writeString(5, rep.model || "");
        writer.writeBool(6, !!rep.ready);
        writer.writeInt32(7, rep.requests || 0);
        writer.writeInt32(8, rep.inflight || 0);
        writer.writeInt32(9, rep.threads || 0);
        writer.writeInt32(10, rep.max_batch || rep.maxBatch || 0);
        writer.writeString(11, rep.batch_type || rep.batchType || "");
        writer.writeString(12, rep.compute_type || rep.computeType || "");
        writer.writeInt32(13, rep.chunk_chars || rep.chunkChars || 0);
        writer.writeBool(14, !!rep.cache_enabled || !!rep.cacheEnabled);
        writer.writeInt32(15, rep.cache_entries || rep.cacheEntries || 0);
        writer.writeInt32(16, rep.cache_chars || rep.cacheChars || 0);
        writer.writeInt32(17, rep.cache_hits || rep.cacheHits || 0);
        writer.writeInt32(18, rep.cache_misses || rep.cacheMisses || 0);
        writer.writeDouble(19, rep.cache_hit_pct || rep.cacheHitPct || 0.0);
        writer.writeInt32(20, rep.cache_disk_bytes || rep.cacheDiskBytes || 0);
        writer.writeInt32(21, rep.cache_disk_restored || rep.cacheDiskRestored || 0);
        writer.writeInt32(22, rep.pss_mb || rep.pssMb || 0);
        writer.writeInt32(23, rep.jvm_heap_mb || rep.jvmHeapMb || 0);
        return writer.toUint8Array();
    }

    function decodeHealthReport(bytes) {
        var reader = new ProtoReader(bytes);
        var out = {
            status: "",
            host: "",
            engine: "",
            port: 0,
            model: "",
            ready: false,
            requests: 0,
            inflight: 0,
            threads: 0,
            max_batch: 0,
            batch_type: "",
            compute_type: "",
            chunk_chars: 0,
            cache_enabled: false,
            cache_entries: 0,
            cache_chars: 0,
            cache_hits: 0,
            cache_misses: 0,
            cache_hit_pct: 0.0,
            cache_disk_bytes: 0,
            cache_disk_restored: 0,
            pss_mb: 0,
            jvm_heap_mb: 0
        };
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) out.status = reader.readString();
            else if (fieldNum === 2) out.host = reader.readString();
            else if (fieldNum === 3) out.engine = reader.readString();
            else if (fieldNum === 4) out.port = reader.readInt32();
            else if (fieldNum === 5) out.model = reader.readString();
            else if (fieldNum === 6) out.ready = reader.readBool();
            else if (fieldNum === 7) out.requests = reader.readInt32();
            else if (fieldNum === 8) out.inflight = reader.readInt32();
            else if (fieldNum === 9) out.threads = reader.readInt32();
            else if (fieldNum === 10) out.max_batch = reader.readInt32();
            else if (fieldNum === 11) out.batch_type = reader.readString();
            else if (fieldNum === 12) out.compute_type = reader.readString();
            else if (fieldNum === 13) out.chunk_chars = reader.readInt32();
            else if (fieldNum === 14) out.cache_enabled = reader.readBool();
            else if (fieldNum === 15) out.cache_entries = reader.readInt32();
            else if (fieldNum === 16) out.cache_chars = reader.readInt32();
            else if (fieldNum === 17) out.cache_hits = reader.readInt32();
            else if (fieldNum === 18) out.cache_misses = reader.readInt32();
            else if (fieldNum === 19) out.cache_hit_pct = reader.readDouble();
            else if (fieldNum === 20) out.cache_disk_bytes = reader.readInt32();
            else if (fieldNum === 21) out.cache_disk_restored = reader.readInt32();
            else if (fieldNum === 22) out.pss_mb = reader.readInt32();
            else if (fieldNum === 23) out.jvm_heap_mb = reader.readInt32();
            else reader.skip(wireType);
        }
        return out;
    }

    function encodeEnergySnapshot(snap) {
        snap = snap || {};
        var writer = new ProtoWriter();
        writer.writeRepeatedString(1, snap.records_jsonl || snap.recordsJsonl || []);
        return writer.toUint8Array();
    }

    function decodeEnergySnapshot(bytes) {
        var reader = new ProtoReader(bytes);
        var records = [];
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) records.push(reader.readString());
            else reader.skip(wireType);
        }
        return { records_jsonl: records };
    }

    function encodeResetResult(res) {
        res = res || {};
        var writer = new ProtoWriter();
        writer.writeBool(1, !!res.reset);
        return writer.toUint8Array();
    }

    function decodeResetResult(bytes) {
        var reader = new ProtoReader(bytes);
        var reset = false;
        while (reader.hasMore()) {
            var tag = reader.readVarint();
            var fieldNum = tag >> 3;
            var wireType = tag & 7;
            if (fieldNum === 1) reset = reader.readBool();
            else reader.skip(wireType);
        }
        return { reset: reset };
    }

    // ---------------------------------------------------------------------------
    // gRPC-Web Framing
    // ---------------------------------------------------------------------------
    function frame(protoBytes) {
        var len = protoBytes ? protoBytes.length : 0;
        var out = new Uint8Array(5 + len);
        out[0] = 0; // Uncompressed
        out[1] = (len >>> 24) & 0xff;
        out[2] = (len >>> 16) & 0xff;
        out[3] = (len >>> 8) & 0xff;
        out[4] = len & 0xff;
        if (len > 0) out.set(protoBytes, 5);
        return out;
    }

    function unframe(framedBytes) {
        if (!framedBytes || framedBytes.length < 5) {
            throw new Error("Framed message too short (minimum 5 bytes)");
        }
        var flag = framedBytes[0];
        if (flag !== 0) {
            throw new Error("Unsupported gRPC compression flag: " + flag);
        }
        var len = ((framedBytes[1] << 24) | (framedBytes[2] << 16) | (framedBytes[3] << 8) | framedBytes[4]) >>> 0;
        if (len > MAX_MESSAGE_SIZE) {
            throw new Error("Message size " + len + " exceeds 256 KiB cap");
        }
        if (framedBytes.length < 5 + len) {
            throw new Error("Framed buffer too short: expected " + (5 + len) + " bytes, got " + framedBytes.length);
        }
        return framedBytes.subarray(5, 5 + len);
    }

    function encodeGrpcWebText(protoBytes) {
        return encodeBase64(frame(protoBytes));
    }

    function decodeGrpcWebText(text) {
        var framed = decodeBase64(text);
        return unframe(framed);
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------
    var VBookWire = {
        MAX_MESSAGE_SIZE: MAX_MESSAGE_SIZE,
        encodeBase64: encodeBase64,
        decodeBase64: decodeBase64,
        encodeUtf8: encodeUtf8,
        decodeUtf8: decodeUtf8,
        frame: frame,
        unframe: unframe,
        encodeGrpcWebText: encodeGrpcWebText,
        decodeGrpcWebText: decodeGrpcWebText,
        encodeTranslateRequest: encodeTranslateRequest,
        decodeTranslateRequest: decodeTranslateRequest,
        encodeTranslateResponse: encodeTranslateResponse,
        decodeTranslateResponse: decodeTranslateResponse,
        encodeTranslateConfig: encodeTranslateConfig,
        decodeTranslateConfig: decodeTranslateConfig,
        encodeConfigResult: encodeConfigResult,
        decodeConfigResult: decodeConfigResult,
        encodeHealthReport: encodeHealthReport,
        decodeHealthReport: decodeHealthReport,
        encodeEnergySnapshot: encodeEnergySnapshot,
        decodeEnergySnapshot: decodeEnergySnapshot,
        encodeResetResult: encodeResetResult,
        decodeResetResult: decodeResetResult
    };

    if (typeof module !== "undefined" && module.exports) {
        module.exports = VBookWire;
    }
    if (typeof globalThis !== "undefined") {
        globalThis.VBookWire = VBookWire;
    }
    if (typeof root !== "undefined") {
        root.VBookWire = VBookWire;
    }
})(typeof globalThis !== "undefined" ? globalThis : this);
