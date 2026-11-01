#include "tokenizer.h"

#include <filesystem>
#include <string>

// Logging: Android routes through logcat so the message lands in the app's own log stream; the
// desktop build has no logcat, so it goes to stderr where the host's console captures it.
#ifdef __ANDROID__
#include <android/log.h>
#define TAG "VBookMT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#else
#include <cstdio>
namespace vbookmt::detail {
/**
 * Desktop log sink. `printf` on the format string would be a format-string bug in waiting, so the
 * message is formatted into a buffer and written as one line to stderr.
 */
template <typename... Args>
void logToStderr(const char* level, const char* format, Args... args) {
    char buffer[2048];
    const int written = std::snprintf(buffer, sizeof(buffer), format, args...);
    if (written > 0) {
        std::fprintf(stderr, "[%s] %s\n", level, buffer);
        std::fflush(stderr);
    }
}
}  // namespace vbookmt::detail
#define LOGI(...) ::vbookmt::detail::logToStderr("I", __VA_ARGS__)
#define LOGE(...) ::vbookmt::detail::logToStderr("E", __VA_ARGS__)
#endif

namespace vbookmt {

namespace {

/** MarianMT tokenizer contract: the Python reference implementation appends EOS by hand. */
constexpr std::string_view kEndOfSentenceToken = "</s>";
/** Table names, probed in the model directory first and this subdirectory second. */
constexpr std::string_view kSourceSpm = "/source.spm";
constexpr std::string_view kTargetSpm = "/target.spm";
/** Where older CTranslate2 artifacts kept the tables. */
constexpr std::string_view kLegacySubdir = "/ct2-int8_float32";

std::string joinPath(std::string_view directory, std::string_view leaf) {
    std::string path;
    path.reserve(directory.size() + leaf.size());
    path.append(directory).append(leaf);
    return path;
}

}  // namespace

bool Tokenizer::load(const std::string& modelDir) {
    if (modelDir.empty()) return false;

    const std::string legacyDir = modelDir + std::string(kLegacySubdir);

    // Flat layout first: the ONNX artifacts ship the tables at the top level. The subdirectory is
    // only for model directories exported by the old CT2 pipeline.
    std::string sourceSpm = joinPath(modelDir, kSourceSpm);
    std::string targetSpm = joinPath(modelDir, kTargetSpm);
    if (!sourceProcessor_.Load(sourceSpm).ok()) {
        sourceSpm = joinPath(legacyDir, kSourceSpm);
        targetSpm = joinPath(legacyDir, kTargetSpm);
        if (!sourceProcessor_.Load(sourceSpm).ok()) {
            LOGE("Could not find source.spm in %s", modelDir.c_str());
            ready_ = false;
            return false;
        }
    }

    if (!targetProcessor_.Load(targetSpm).ok()) {
        LOGE("Could not find target.spm in %s", modelDir.c_str());
        ready_ = false;
        return false;
    }

    modelDir_ = modelDir;
    ready_ = true;
    LOGI("Tokenizers loaded from %s", modelDir.c_str());
    return true;
}

int Tokenizer::tokenCount(std::string_view text) const {
    if (!ready_ || text.empty()) return 0;

    std::vector<int> tokens;
    (void)sourceProcessor_.Encode(std::string(text), &tokens);
    // +1 for the </s> that encode() appends: budgeting on the raw piece count would understate the
    // sequence the decoder actually sees by one token per chunk.
    return static_cast<int>(tokens.size()) + 1;
}

std::vector<int32_t> Tokenizer::encode(std::string_view text) const {
    std::vector<int32_t> ids;
    if (!ready_ || text.empty()) return ids;

    (void)sourceProcessor_.Encode(std::string(text), &ids);
    if (ids.empty()) return ids;

    // Marian terminates the source with </s>. The ONNX graphs were exported against exactly this
    // sequence, so producing anything else here would change the first decoder step.
    ids.push_back(sourceProcessor_.PieceToId(kEndOfSentenceToken));
    return ids;
}

std::string Tokenizer::decode(const std::vector<int32_t>& ids) const {
    if (!ready_ || ids.empty()) return {};

    std::vector<std::string> tokens;
    tokens.reserve(ids.size());
    for (const int32_t id : ids) {
        if (id < 0 || id >= targetProcessor_.GetPieceSize()) continue;
        const std::string piece = targetProcessor_.IdToPiece(id);
        // Special pieces are structural (end of sequence, padding, unknown); they are never part of
        // user-visible text, and SentencePiece would emit them literally.
        if (piece == kEndOfSentenceToken || piece == "<pad>" || piece == "<unk>") continue;
        if (piece.empty()) continue;
        tokens.push_back(piece);
    }
    if (tokens.empty()) return {};

    std::string decoded;
    (void)targetProcessor_.Decode(tokens, &decoded);
    return decoded;
}

}  // namespace vbookmt
