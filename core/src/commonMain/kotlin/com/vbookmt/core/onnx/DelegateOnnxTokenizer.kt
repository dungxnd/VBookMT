package com.vbookmt.core.onnx

/**
 * An [OnnxTokenizer] implementation that delegates encoding and decoding to provided functions.
 * Useful for bridging host-specific native or JNI tokenizers without duplicate adapter classes.
 */
class DelegateOnnxTokenizer(
    private val encodeFn: (String) -> LongArray,
    private val decodeFn: (LongArray) -> String,
) : OnnxTokenizer {

    override fun encode(text: String): LongArray = encodeFn(text)

    override fun decode(ids: LongArray): String = decodeFn(ids)
}
