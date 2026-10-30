package com.vbookmt.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TranslateRequest(
    val text: String,
    val from: String = "auto",
    val to: String = "vi",
    /**
     * Legacy field: the model is chosen inside the app (`TranslationService.activeModel`), so this
     * is ignored. Kept so older installed plugin builds that still send it keep parsing.
     */
    val model: String = "hachimi-60-qt",
    val style: String = "qt"
)

/**
 * Runtime tuning knobs for `POST /config` (localhost only). Every field is optional: 0 / "" means
 * "leave unchanged", so batch and thread settings can be measured on a real device instead of
 * being recompiled per configuration.
 */
@Serializable
data class TranslateConfig(
    val maxBatch: Int = 0,
    val batchType: String = "",
    val threads: Int = 0,
    /**
     * Target chunk length in characters (0 = leave unchanged). The largest measured throughput knob
     * on ARM - the same text ran 1556 chars/s at 80 chars per chunk and 1040 at 280 - and the one
     * setting whose quality cost shows up as dropped sentences above ~250. Session-scoped on
     * purpose: it exists to repeat that sweep on another device, not to be a user preference.
     */
    val chunkChars: Int = 0,
    /**
     * CTranslate2 compute type: one of `int8`, `int8_float32`, `int8_float16`, `int8_bfloat16`,
     * `int16`, `float32`, `float16`, `bfloat16`, `auto`, `default` ("" = leave unchanged).
     *
     * This is genuinely device-dependent and cannot be settled on desktop: on an x86 CPU without
     * VNNI (measured: Zen 3) `int8` and `int8_float32` are indistinguishable, while ARMv8.2 cores
     * with dotprod/i8mm reach a real int8 kernel. Changing it reloads the model.
     */
    val computeType: String = "",
    /** Drop every cached chunk translation (free and safe; only re-reads get slower afterwards). */
    val clearCache: Boolean = false,
    /** false disables the translation cache entirely; omitted/true leaves it as-is. */
    val cacheEnabled: Boolean = true
)

@Serializable
data class TranslateResponse(
    val success: Boolean,
    val translatedText: String,
    val error: String? = null,
    val elapsedMs: Long = 0
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeMb: Int,
    val isInstalled: Boolean = false
)
