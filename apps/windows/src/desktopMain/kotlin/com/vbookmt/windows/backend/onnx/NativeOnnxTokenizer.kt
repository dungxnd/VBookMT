package com.vbookmt.windows.backend.onnx

import com.vbookmt.core.onnx.DelegateOnnxTokenizer
import com.vbookmt.core.onnx.OnnxTokenizer
import com.vbookmt.windows.backend.WindowsNativeTokenizer

/**
 * [OnnxTokenizer] backed by the SentencePiece tables the native engine already loads,
 * delegating to [DelegateOnnxTokenizer] in `core`.
 */
class NativeOnnxTokenizer(
    encodeFn: (String) -> LongArray,
    decodeFn: (LongArray) -> String,
) : OnnxTokenizer by DelegateOnnxTokenizer(encodeFn, decodeFn) {

    companion object {
        fun create(): NativeOnnxTokenizer = NativeOnnxTokenizer(
            encodeFn = { WindowsNativeTokenizer.encode(it) },
            decodeFn = { WindowsNativeTokenizer.decode(it) },
        )
    }
}
