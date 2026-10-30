// Empty translation unit in the fallback (non-CT2) build: the only caller of parseComputeType is
// the CTranslate2 engine, which is itself compiled out without HAS_REAL_CT2.
#include "compute_type.h"

#ifdef HAS_REAL_CT2

#include <stdexcept>
#include <string>

namespace vbookmt {

ctranslate2::ComputeType parseComputeType(std::string_view name) {
    if (name == "default") return ctranslate2::ComputeType::DEFAULT;
    if (name == "auto") return ctranslate2::ComputeType::AUTO;
    if (name == "float32" || name == "float") return ctranslate2::ComputeType::FLOAT32;
    if (name == "float16") return ctranslate2::ComputeType::FLOAT16;
    if (name == "bfloat16") return ctranslate2::ComputeType::BFLOAT16;
    if (name == "int16") return ctranslate2::ComputeType::INT16;
    if (name == "int8") return ctranslate2::ComputeType::INT8;
    if (name == "int8_float32") return ctranslate2::ComputeType::INT8_FLOAT32;
    if (name == "int8_float16") return ctranslate2::ComputeType::INT8_FLOAT16;
    if (name == "int8_bfloat16") return ctranslate2::ComputeType::INT8_BFLOAT16;
    throw std::invalid_argument("Invalid compute type: " + std::string(name));
}

std::string_view computeTypeName(ctranslate2::ComputeType type) noexcept {
    switch (type) {
        case ctranslate2::ComputeType::DEFAULT: return "default";
        case ctranslate2::ComputeType::AUTO: return "auto";
        case ctranslate2::ComputeType::FLOAT32: return "float32";
        case ctranslate2::ComputeType::FLOAT16: return "float16";
        case ctranslate2::ComputeType::BFLOAT16: return "bfloat16";
        case ctranslate2::ComputeType::INT16: return "int16";
        case ctranslate2::ComputeType::INT8: return "int8";
        case ctranslate2::ComputeType::INT8_FLOAT32: return "int8_float32";
        case ctranslate2::ComputeType::INT8_FLOAT16: return "int8_float16";
        case ctranslate2::ComputeType::INT8_BFLOAT16: return "int8_bfloat16";
    }
    return "unknown";
}

}  // namespace vbookmt

#endif  // HAS_REAL_CT2
