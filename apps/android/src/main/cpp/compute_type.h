#pragma once

// Compute types only exist in the CTranslate2 build; the lightweight fallback bridge has no CT2
// headers at all, so this module compiles to an empty translation unit there.
#ifdef HAS_REAL_CT2

#include <ctranslate2/types.h>

#include <string>
#include <string_view>

namespace vbookmt {

/**
 * Maps a compute-type name from the model config to the CTranslate2 enum.
 *
 * Deliberately local instead of calling ctranslate2::str_to_compute_type: that helper lives in
 * types.h without a CT2_API export marker, so it is not guaranteed to be a visible symbol in the
 * prebuilt libctranslate2.so this bridge links against.
 *
 * Accepted names: default, auto, float32|float, float16, bfloat16, int16, int8, int8_float32,
 * int8_float16, int8_bfloat16. Throws std::invalid_argument on anything else.
 */
[[nodiscard]] ctranslate2::ComputeType parseComputeType(std::string_view name);

/** Canonical name of a compute type (the inverse of parseComputeType), "unknown" if unmapped. */
[[nodiscard]] std::string_view computeTypeName(ctranslate2::ComputeType type) noexcept;

}  // namespace vbookmt

#endif  // HAS_REAL_CT2
