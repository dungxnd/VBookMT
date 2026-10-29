package com.vbookmt.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TranslateRequest(
    val text: String,
    val from: String = "auto",
    val to: String = "vi",
    val model: String = "hachimi-60-qt",
    val style: String = "qt"
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
