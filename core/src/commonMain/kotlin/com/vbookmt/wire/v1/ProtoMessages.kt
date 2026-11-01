package com.vbookmt.wire.v1

/**
 * Proto3 wire models generated/matched with `proto/vbookmt.proto`.
 *
 * Rules:
 * - Only string, bool, int32, double, repeated string.
 * - TranslateConfig uses explicit optional presence (null = leave unchanged).
 */

data object Empty

data class TranslateRequest(
    val text: String = "",
    val from: String = "auto",
    val to: String = "vi",
    val style: String = "qt",
    val model: String = "" // Deprecated, ignored by host
)

data class TranslateResponse(
    val success: Boolean = false,
    val translatedText: String = "",
    val error: String = "",
    val elapsedMs: Int = 0
)

data class TranslateConfig(
    val maxBatch: Int? = null,
    val batchType: String? = null,
    val threads: Int? = null,
    val chunkChars: Int? = null,
    val computeType: String? = null,
    val clearCache: Boolean? = null,
    val cacheEnabled: Boolean? = null
)

data class ConfigResult(
    val appliedJson: String = ""
)

data class HealthReport(
    val status: String = "healthy",
    val host: String = "",
    val engine: String = "ONNX",
    val port: Int = 0,
    val model: String = "",
    val ready: Boolean = false,
    val requests: Int = 0,
    val inflight: Int = 0,
    val threads: Int = 0,
    val maxBatch: Int = 0,
    val batchType: String = "",
    val computeType: String = "",
    val chunkChars: Int = 0,
    val cacheEnabled: Boolean = false,
    val cacheEntries: Int = 0,
    val cacheChars: Int = 0,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val cacheHitPct: Double = 0.0,
    val cacheDiskBytes: Int = 0,
    val cacheDiskRestored: Int = 0,
    val pssMb: Int = 0,
    val jvmHeapMb: Int = 0
)

data class EnergySnapshot(
    val recordsJsonl: List<String> = emptyList()
)

data class ResetResult(
    val reset: Boolean = false
)
