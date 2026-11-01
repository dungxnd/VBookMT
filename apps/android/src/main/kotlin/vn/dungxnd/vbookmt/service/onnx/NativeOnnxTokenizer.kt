package vn.dungxnd.vbookmt.service.onnx

import com.vbookmt.core.onnx.DelegateOnnxTokenizer
import com.vbookmt.core.onnx.OnnxTokenizer

/**
 * [OnnxTokenizer] backed by the SentencePiece tables the native engine loads,
 * delegating to [DelegateOnnxTokenizer] in `core`.
 */
class NativeOnnxTokenizer private constructor(
    onEncode: (String) -> LongArray,
    onDecode: (LongArray) -> String,
) : OnnxTokenizer by DelegateOnnxTokenizer(onEncode, onDecode) {

    companion object {
        fun load(
            modelDir: String,
            encode: (String) -> LongArray,
            decode: (LongArray) -> String,
            loadTables: (String) -> Boolean
        ): NativeOnnxTokenizer? =
            if (loadTables(modelDir)) NativeOnnxTokenizer(encode, decode) else null
    }
}
