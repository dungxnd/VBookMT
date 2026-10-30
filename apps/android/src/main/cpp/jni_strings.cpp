#include "jni_strings.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace vbookmt {
namespace {

// Unicode surrogate geometry: a supplementary code point is stored as two UTF-16 halves
// carrying 10 bits each, offset by 0x10000 from the plane-1 base.
constexpr std::uint32_t kHighSurrogateFirst = 0xD800;
constexpr std::uint32_t kHighSurrogateLast = 0xDBFF;
constexpr std::uint32_t kLowSurrogateFirst = 0xDC00;
constexpr std::uint32_t kLowSurrogateLast = 0xDFFF;
constexpr std::uint32_t kSupplementaryBase = 0x10000;
constexpr std::uint32_t kSurrogatePayloadBits = 10;
constexpr std::uint32_t kSurrogatePayloadMask = 0x3FF;

// UTF-8 length boundaries, in code points.
constexpr std::uint32_t kOneByteLimit = 0x7F;
constexpr std::uint32_t kTwoByteLimit = 0x7FF;
constexpr std::uint32_t kThreeByteLimit = 0xFFFF;

// A single UTF-16 code unit expands to at most 3 UTF-8 bytes.
constexpr std::size_t kMaxUtf8BytesPerCodeUnit = 3;

// UTF-8 continuation-byte helpers.
constexpr std::uint32_t kContinuationMask = 0x3F;
constexpr std::uint8_t kAsciiLeadLimit = kOneByteLimit;  // same ceiling, typed to match a lead byte
constexpr std::uint8_t kContinuationTag = 0x80;
constexpr std::uint8_t kTwoBytePrefix = 0xC0;
constexpr std::uint8_t kThreeBytePrefix = 0xE0;
constexpr std::uint8_t kFourBytePrefix = 0xF0;
constexpr std::uint8_t kTwoByteMask = 0xE0;
constexpr std::uint8_t kThreeByteMask = 0xF0;
constexpr std::uint8_t kFourByteMask = 0xF8;
constexpr std::uint32_t kTwoBytePayloadMask = 0x1F;
constexpr std::uint32_t kThreeBytePayloadMask = 0x0F;
constexpr std::uint32_t kFourBytePayloadMask = 0x07;

}  // namespace

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};

    const jsize length = env->GetStringLength(value);
    if (length <= 0) return {};

    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string utf8;
    utf8.reserve(static_cast<std::size_t>(length) * kMaxUtf8BytesPerCodeUnit);

    for (jsize i = 0; i < length; ++i) {
        std::uint32_t codePoint = chars[i];

        // Combine a surrogate pair into one supplementary code point.
        if (codePoint >= kHighSurrogateFirst && codePoint <= kHighSurrogateLast && i + 1 < length) {
            const std::uint32_t low = chars[i + 1];
            if (low >= kLowSurrogateFirst && low <= kLowSurrogateLast) {
                codePoint = kSupplementaryBase +
                            ((codePoint - kHighSurrogateFirst) << kSurrogatePayloadBits) +
                            (low - kLowSurrogateFirst);
                ++i;
            }
        }

        if (codePoint < kOneByteLimit) {
            utf8.push_back(static_cast<char>(codePoint));
        } else if (codePoint < kTwoByteLimit) {
            utf8.push_back(static_cast<char>(kTwoBytePrefix | (codePoint >> 6)));
            utf8.push_back(static_cast<char>(kContinuationTag | (codePoint & kContinuationMask)));
        } else if (codePoint < kThreeByteLimit) {
            utf8.push_back(static_cast<char>(kThreeBytePrefix | (codePoint >> 12)));
            utf8.push_back(static_cast<char>(kContinuationTag | ((codePoint >> 6) & kContinuationMask)));
            utf8.push_back(static_cast<char>(kContinuationTag | (codePoint & kContinuationMask)));
        } else {
            utf8.push_back(static_cast<char>(kFourBytePrefix | (codePoint >> 18)));
            utf8.push_back(static_cast<char>(kContinuationTag | ((codePoint >> 12) & kContinuationMask)));
            utf8.push_back(static_cast<char>(kContinuationTag | ((codePoint >> 6) & kContinuationMask)));
            utf8.push_back(static_cast<char>(kContinuationTag | (codePoint & kContinuationMask)));
        }
    }

    env->ReleaseStringChars(value, chars);
    return utf8;
}

jstring utf8ToJstring(JNIEnv* env, std::string_view utf8) {
    if (utf8.empty()) return env->NewStringUTF("");

    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());

    std::size_t cursor = 0;
    while (cursor < utf8.size()) {
        const auto lead = static_cast<std::uint8_t>(utf8[cursor]);
        std::uint32_t codePoint = 0;
        std::size_t tailBytes = 0;

        if (lead <= kAsciiLeadLimit) {
            codePoint = lead;
        } else if ((lead & kTwoByteMask) == kTwoBytePrefix) {
            codePoint = lead & kTwoBytePayloadMask;
            tailBytes = 1;
        } else if ((lead & kThreeByteMask) == kThreeBytePrefix) {
            codePoint = lead & kThreeBytePayloadMask;
            tailBytes = 2;
        } else if ((lead & kFourByteMask) == kFourBytePrefix) {
            codePoint = lead & kFourBytePayloadMask;
            tailBytes = 3;
        } else {
            ++cursor;  // invalid lead byte, drop it
            continue;
        }

        if (cursor + tailBytes >= utf8.size()) break;
        for (std::size_t offset = 1; offset <= tailBytes; ++offset) {
            codePoint = (codePoint << 6) |
                        (static_cast<std::uint8_t>(utf8[cursor + offset]) & kContinuationMask);
        }
        cursor += tailBytes + 1;

        if (codePoint <= kThreeByteLimit) {
            utf16.push_back(static_cast<jchar>(codePoint));
        } else {
            const std::uint32_t supplementary = codePoint - kSupplementaryBase;
            utf16.push_back(static_cast<jchar>(kHighSurrogateFirst +
                                               (supplementary >> kSurrogatePayloadBits)));
            utf16.push_back(static_cast<jchar>(kLowSurrogateFirst +
                                               (supplementary & kSurrogatePayloadMask)));
        }
    }

    if (utf16.empty()) return env->NewStringUTF("");
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

}  // namespace vbookmt
