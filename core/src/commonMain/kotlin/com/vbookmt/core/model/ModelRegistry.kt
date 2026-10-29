package com.vbookmt.core.model

import kotlinx.serialization.Serializable

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
    val downloadFiles: List<String> = listOf(
        "ct2-int8_float32/model.bin",
        "ct2-int8_float32/shared_vocabulary.json",
        "ct2-int8_float32/source.spm",
        "ct2-int8_float32/target.spm"
    )
)

object ModelRegistry {
    val HACHIMI_60_QT = ModelSpec(
        id = "hachimi-60-qt",
        name = "HachimiMT 60 QT",
        author = "ngocdang83",
        license = "CC BY 4.0",
        repoId = "ngocdang83/HachimiMT-60-QT",
        sizeMb = 55,
        isDefault = true,
        description = "Mô hình mặc định — Chất lượng dịch cao nhất, chuẩn phong cách convert kiếm hiệp/tiên hiệp, ổn định xưng hô tuyệt đối."
    )

    val MOXHI_30_QT = ModelSpec(
        id = "moxhi-30-qt",
        name = "MoxhiMT 30 QT",
        author = "DanVP",
        license = "CC BY-NC 4.0",
        repoId = "DanVP/MoxhiMT-30-QT",
        sizeMb = 36,
        isDefault = false,
        description = "Mô hình tải thêm — Bản siêu nhẹ (36MB), tốc độ dịch cực nhanh, tiết kiệm RAM tối đa cho máy cấu hình thấp."
    )

    val HACHIMI_40_QT = ModelSpec(
        id = "hachimi-40-qt",
        name = "HachimiMT 40 QT",
        author = "ngocdang83",
        license = "CC BY 4.0",
        repoId = "ngocdang83/HachimiMT-40-QT",
        sizeMb = 40,
        isDefault = false,
        description = "Mô hình tùy chọn — Kiến trúc weight-sharing, hỗ trợ bảo toàn ngắt dòng đoạn văn <NL>."
    )

    val ALL_MODELS = listOf(HACHIMI_60_QT, MOXHI_30_QT, HACHIMI_40_QT)

    fun getById(id: String): ModelSpec {
        return ALL_MODELS.find { it.id.equals(id, ignoreCase = true) } ?: HACHIMI_60_QT
    }

    fun getDownloadUrl(model: ModelSpec, filename: String): String {
        return "https://huggingface.co/${model.repoId}/resolve/main/$filename"
    }
}
