package com.vbookmt.core.model

import kotlinx.serialization.Serializable

/**
 * Core files for an ONNX Runtime model directory.
 *
 * [decoder_with_past_model] is required, not optional: the first decode step uses `decoder_model`
 * (no cache in), and every later step uses the with-past graph. A directory without it can produce
 * exactly one token.
 *
 * [trim_meta] carries `keep_ids`. The decoder emits 5,951-wide logits, so the argmax is an index into
 * the trimmed vocabulary and must be mapped back before the token can be decoded. Without this file
 * the output is fluent Vietnamese built from the wrong words.
 *
 * `tokenizer.json` is deliberately NOT here. The app tokenises with the `.spm` tables through `native/`
 * (see `NativeOnnxTokenizer`), so `source.spm` + `target.spm` is what it actually needs, and requiring a
 * file nothing reads would fail the download on an artifact that ships without one.
 */
val ONNX_CORE_FILES: List<String> = listOf(
    "config.json",
    "encoder_model.onnx",
    "decoder_model.onnx",
    "decoder_with_past_model.onnx",
    // Required, not optional: the decoder graphs name their KV-cache inputs from this file, because
    // Marian's cache is four tensors per layer rather than the usual key/value pair.
    "onnx_meta.json",
    "source.spm",
    "target.spm",
    "trim_meta.json"
)

/** Files for an ONNX variant, which lives in a subfolder of the repo (`fp16/`, `q8/`, `q16/`). */
fun onnxVariantFiles(variant: QuantVariant): List<String> =
    ONNX_CORE_FILES.map { "${variant.folder}/$it" }

/**
 * Numeric precision of an ONNX artifact.
 *
 * Measured on a Snapdragon 8 Gen 3, both 30-QT and 60-QT, against each model's own fp32 export:
 *
 * | variant | size vs fp16 | chrF vs fp32 | CPU vs fp16 |
 * | :--- | ---: | ---: | ---: |
 * | fp16 | 100% | 100.00 | baseline |
 * | q16 | 85% | 98.0 – 100.00 | slower |
 * | q8 | 62% | 96.0 – 96.25 | faster, ~25% less CPU |
 *
 * q8 is the one that matters on a phone: it was the only variant that used *less* memory and CPU than
 * fp16, because decode is bandwidth-bound and int8 weights halve the bytes read. fp16 is the safe
 * default for quality; q16 is the middle option that matched fp16 exactly on one model.
 */
@Serializable
enum class QuantVariant(
    val folder: String,
    val label: String,
    /** Approximate download size, used for the UI only. */
    val approxMb: Map<String, Int>,
    /**
     * One-line trade-off for the picker, in the reader's terms.
     *
     * Kept here rather than in the UI so the numbers shown on the card are the same ones the model
     * card on Hugging Face publishes — both read this enum's measurements.
     */
    val hint: String,
) {
    FP16(
        "fp16", "FP16",
        mapOf("hachimi-60-qt" to 205, "moxhi-30-qt" to 142),
        "Chất lượng cao nhất",
    ),
    Q16(
        "q16", "Q16",
        mapOf("hachimi-60-qt" to 167, "moxhi-30-qt" to 121),
        "Gần bằng FP16, nhẹ hơn",
    ),
    Q8(
        "q8", "Q8",
        mapOf("hachimi-60-qt" to 124, "moxhi-30-qt" to 88),
        "Nhẹ nhất, nhanh nhất, RAM thấp nhất",
    ),
    ;

    fun sizeMb(modelKey: String): Int = approxMb[modelKey] ?: 0

    companion object {
        val DEFAULT: QuantVariant = FP16
        fun parse(value: String): QuantVariant =
            entries.firstOrNull { it.folder.equals(value.trim(), ignoreCase = true) } ?: DEFAULT
    }
}

/** Bare file names a model needs on disk, in download order. */
fun ModelSpec.requiredFileNames(): List<String> = downloadFiles.map { it.substringAfterLast('/') }

/**
 * True when [presentNames] holds what the ONNX path needs to tokenise: both SentencePiece tables.
 *
 * CTranslate2 used to carry two more layouts (`shared_vocabulary.json`, or the split
 * `source_vocabulary.json`/`target_vocabulary.json` pair). Those belonged to the CT2 artifacts and are
 * gone with the engine, so a directory is only usable when it ships the `.spm` pair the native
 * tokenizer actually loads.
 */
fun hasUsableVocabulary(presentNames: Set<String>): Boolean =
    "source.spm" in presentNames && "target.spm" in presentNames

@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val repoId: String,
    val sizeMb: Int,
    val isDefault: Boolean = false,
    val description: String,
    /**
     * Inference engine. Every shipped model is `"ONNX"` now that CTranslate2 is gone; the field stays
     * because a spec loaded from an old config would otherwise have no way to say what it is.
     */
    val engine: String = "ONNX",
    /**
     * Prefixed files this artifact ships, for the default variant.
     *
     * [onnxVariantFiles] builds the variant subfolder paths; [requiredFileNames] turns either form into
     * the bare names the downloader writes next to the model directory.
     */
    val downloadFiles: List<String> = onnxVariantFiles(QuantVariant.DEFAULT),
    /**
     * Quantisation options this artifact offers. Every ONNX artifact publishes three builds in separate
     * folders in the same repo, so the user picks one rather than downloading all of them.
     */
    val variants: List<QuantVariant> = QuantVariant.entries.toList()
) {
    val hasVariants: Boolean get() = variants.isNotEmpty()

    /** Files to fetch for [variant], falling back to the default when this model has no variants. */
    fun filesFor(variant: QuantVariant?): List<String> =
        if (!hasVariants || variant == null) downloadFiles else onnxVariantFiles(variant)

    /** Approximate download size for [variant]. */
    fun sizeMbFor(variant: QuantVariant?): Int =
        if (!hasVariants || variant == null) sizeMb else variant.sizeMb(id.substringBefore("-onnx"))
}

object ModelRegistry {
    /**
     * The 60-QT artifact: the largest, best-quality model, and the default because quality is what a
     * reader notices first and the download happens once.
     *
     * ONNX FP16 measures ~205 MB. q8 and q16 are the same model in smaller, cheaper builds — see
     * [QuantVariant] — so the size here is only the default variant's.
     */
    val HACHIMI_60_QT = ModelSpec(
        id = "hachimi-60-qt-onnx",
        name = "HachimiMT 60 QT",
        author = "ngocdang83 (ONNX: DungxND)",
        license = "CC BY 4.0",
        repoId = "dungxnd/HachimiMT-60-QT-ONNX",
        sizeMb = QuantVariant.FP16.sizeMb("hachimi-60-qt"),
        isDefault = true,
        description = "Chất lượng cao nhất, chuẩn phong cách kiếm hiệp/tiên hiệp. Nặng nhất trong hai bản."
    )

    /**
     * The 30-QT artifact: about half the size and noticeably faster, at some cost in prose quality.
     * The better pick for a low-RAM phone.
     */
    val MOXHI_30_QT = ModelSpec(
        id = "moxhi-30-qt-onnx",
        name = "MoxhiMT 30 QT",
        author = "DanVP (ONNX: DungxND)",
        license = "CC BY-NC 4.0",
        repoId = "dungxnd/MoxhiMT-30-QT-ONNX",
        sizeMb = QuantVariant.FP16.sizeMb("moxhi-30-qt"),
        isDefault = false,
        description = "Nhẹ và nhanh hơn, tiết kiệm RAM. Chất lượng thấp hơn HachimiMT một chút."
    )

    /**
     * Models the app can run.
     *
     * Every one is an ONNX artifact: fp16 weights, a trimmed 5,951-piece output vocabulary and a real
     * KV cache, run by ONNX Runtime on CPU. CTranslate2 used to be the engine here — it is gone, and
     * with it the `model.bin` artifacts, so there is no engine choice left to expose.
     */
    val ALL_MODELS = listOf(
        HACHIMI_60_QT,
        MOXHI_30_QT,
    )

    /** The model a fresh install starts on. */
    val DEFAULT_MODEL: ModelSpec = ALL_MODELS.first { it.isDefault }

    fun getById(id: String): ModelSpec {
        return ALL_MODELS.find { it.id.equals(id, ignoreCase = true) } ?: DEFAULT_MODEL
    }

    /**
     * URL for one file of [model], optionally at a specific [variant].
     *
     * [filename] is the bare name the downloader writes to disk; the variant's subfolder is resolved
     * here so callers never have to know the repo layout.
     */
    fun getDownloadUrl(
        model: ModelSpec,
        filename: String,
        variant: QuantVariant? = null,
    ): String {
        val candidates = model.filesFor(variant)
        val mappedFile = candidates.find { it.substringAfterLast('/') == filename } ?: filename
        val path = mappedFile.trimStart('/')
        return "https://huggingface.co/${model.repoId}/resolve/main/$path"
    }
}
