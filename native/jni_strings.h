#pragma once

#include <jni.h>

#include <string>
#include <string_view>

namespace vbookmt {

// -------------------------------------------------------------------------------------------
// UTF-16 <-> UTF-8
//
// JNI's GetStringUTFChars/NewStringUTF use *modified* UTF-8 (CESU-8): supplementary-plane
// characters are re-encoded as surrogate pairs taking 6 bytes instead of 4. Rare CJK glyphs
// (U+20000+, common in xianxia names) therefore reached SentencePiece as garbage bytes and
// produced corrupted tokens. Convert explicitly instead.
// -------------------------------------------------------------------------------------------

/** Decodes a UTF-16 jstring into genuine UTF-8. Returns "" for a null/empty string. */
[[nodiscard]] std::string jstringToUtf8(JNIEnv* env, jstring value);

/**
 * Encodes genuine UTF-8 into a UTF-16 jstring (surrogate pairs included).
 * Invalid lead bytes are dropped and a truncated trailing sequence is ignored, so this never
 * fails on malformed input; returns "" when nothing decodable is left.
 */
[[nodiscard]] jstring utf8ToJstring(JNIEnv* env, std::string_view utf8);

}  // namespace vbookmt
