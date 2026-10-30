#include "ct2_engine.h"

#include <cstddef>
#include <exception>
#include <filesystem>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include <android/log.h>

#ifdef HAS_REAL_CT2
#include <memory>

#include "compute_type.h"
#endif

#define TAG "VBookMT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace vbookmt {

#ifdef HAS_REAL_CT2

namespace {

/** MarianMT position tables are 512 deep; never let a sequence reach them. */
constexpr std::size_t kModelPositionLimit = 512;
/** Decoding budget used when the caller passes a non-positive value. */
constexpr std::size_t kDefaultMaxDecodingLength = 256;
/** Greedy search when the caller asks for no beams. */
constexpr std::size_t kDefaultBeamSize = 1;
constexpr std::size_t kMinDecodingLength = 1;
/** Kotlin-side `batchType` encoding: 1 means the batch size is a token budget. */
constexpr int kTokensBatchType = 1;
/** MarianMT tokenizer contract: the Python reference implementation appends EOS by hand. */
constexpr std::string_view kEndOfSentenceToken = "</s>";
/** Model layout probes: the flat directory first, then the ct2-int8_float32/ subdirectory. */
constexpr std::string_view kSourceModelFile = "/source.spm";
constexpr std::string_view kTargetModelFile = "/target.spm";
constexpr std::string_view kWeightsFile = "/model.bin";
constexpr std::string_view kBundledModelSubdir = "/ct2-int8_float32";

std::string joinPath(std::string_view directory, std::string_view leaf) {
    std::string path;
    path.reserve(directory.size() + leaf.size());
    path.append(directory).append(leaf);
    return path;
}

bool fileExists(const std::string& path) {
    return std::filesystem::exists(path);
}

}  // namespace

Engine::Engine() = default;
Engine::~Engine() = default;

bool Engine::load(const std::string& modelDir, int numThreads, std::string_view computeType) {
    try {
        const std::string bundledDir = modelDir + std::string(kBundledModelSubdir);

        // Tokenizers live either next to model.bin or inside the int8_float32 bundle.
        std::string sourceSpm = joinPath(modelDir, kSourceModelFile);
        std::string targetSpm = joinPath(modelDir, kTargetModelFile);
        if (!sourceProcessor_.Load(sourceSpm).ok()) {
            sourceSpm = joinPath(bundledDir, kSourceModelFile);
            targetSpm = joinPath(bundledDir, kTargetModelFile);
            if (!sourceProcessor_.Load(sourceSpm).ok()) {
                LOGE("Could not find source.spm in %s", modelDir.c_str());
                return false;
            }
        }

        if (!targetProcessor_.Load(targetSpm).ok()) {
            LOGE("Could not find target.spm in %s", modelDir.c_str());
            return false;
        }

        // The weights themselves may sit flat or inside the bundle; probe the flat layout first.
        const std::string ct2ModelDir =
            fileExists(joinPath(modelDir, kWeightsFile)) ? modelDir : bundledDir;

        // Honor the caller's thread count instead of silently falling back to CT2's default (4).
        // On big.LITTLE phones intra_threads > big-core count schedules onto little cores and
        // loses throughput while heating up; CT2 docs advise intra*inter <= physical cores.
        ctranslate2::ReplicaPoolConfig poolConfig;
        if (numThreads > 0) {
            poolConfig.num_threads_per_replica = static_cast<std::size_t>(numThreads);
        }

        // An empty name keeps the model's stored compute type; an unknown name throws
        // std::invalid_argument, which the catch below converts into `false` so Kotlin can retry
        // with the default instead of leaving the caller with a dead engine.
        const ctranslate2::ComputeType resolvedComputeType =
            computeType.empty() ? ctranslate2::ComputeType::DEFAULT : parseComputeType(computeType);
        const std::string computeLabel =
            computeType.empty() ? std::string("model default")
                                : std::string(computeTypeName(resolvedComputeType));

        LOGI("Initializing CTranslate2 from %s with %d intra-op thread(s), compute_type=%s",
             ct2ModelDir.c_str(), numThreads, computeLabel.c_str());

        auto translator = std::make_unique<ctranslate2::Translator>(
            ct2ModelDir,
            ctranslate2::Device::CPU,
            resolvedComputeType,
            std::vector<int>{0},
            false,
            poolConfig);

        translator_ = std::move(translator);
        modelDir_ = modelDir;
        LOGI("CTranslate2 model loaded and active (%zu replica(s))", translator_->num_replicas());
        return true;
    } catch (const std::exception& e) {
        LOGE("Failed to init CTranslate2: %s", e.what());
    } catch (...) {
        LOGE("Failed to init CTranslate2: unknown exception");
    }
    return false;
}

std::vector<std::string> Engine::translate(const std::vector<std::string>& texts,
                                           int numBeams,
                                           int maxDecodingLength,
                                           int maxBatchSize,
                                           int batchType) const {
    std::vector<std::string> translations(texts.size());

    if (!translator_) {
        LOGE("translate() called before a model was loaded");
        return translations;
    }

    try {
        // Only non-empty sequences with at least one token go to CT2; `slots` remembers where each
        // one came from so the results can be mapped back onto the caller's order.
        std::vector<std::vector<std::string>> batch;
        batch.reserve(texts.size());
        std::vector<std::size_t> slots;
        slots.reserve(texts.size());

        for (std::size_t index = 0; index < texts.size(); ++index) {
            const auto& text = texts[index];
            if (text.empty()) continue;

            std::vector<std::string> tokens;
            // Encode reports failure through its status ([[nodiscard]]). Swallowing it turned a
            // tokenizer error into "this chunk had no tokens", i.e. a silently skipped paragraph.
            if (!sourceProcessor_.Encode(text, &tokens).ok()) {
                LOGE("SentencePiece failed to encode a %zu-byte chunk; skipping it", text.size());
                continue;
            }
            if (tokens.empty()) continue;
            // MarianMT tokenizer contract, same as the Python reference implementation.
            tokens.emplace_back(kEndOfSentenceToken);

            batch.emplace_back(std::move(tokens));
            slots.emplace_back(index);
        }

        if (batch.empty()) return translations;

        ctranslate2::TranslationOptions options;
        options.beam_size = numBeams > 0 ? static_cast<std::size_t>(numBeams) : kDefaultBeamSize;
        options.min_decoding_length = kMinDecodingLength;
        options.max_decoding_length = maxDecodingLength > 0
                                          ? static_cast<std::size_t>(maxDecodingLength)
                                          : kDefaultMaxDecodingLength;
        // Last-resort guard: truncate rather than let CT2 throw out of the position table.
        // Kotlin keeps every sequence under its own token budget, so this never fires in practice.
        options.max_input_length = kModelPositionLimit;
        options.return_scores = false;  // beam_size == 1 => final softmax is skipped entirely

        // A batch size of 0 means "engine default".
        const std::size_t batchSize = maxBatchSize > 0 ? static_cast<std::size_t>(maxBatchSize) : 0;

        // CTranslate2 sorts by length and restores the original order, so results map 1:1.
        // BatchType::Tokens (batchType == 1) reads maxBatchSize as a token budget instead of a
        // sequence count, which keeps batches more uniform when chunk lengths vary a lot.
        const ctranslate2::BatchType ct2BatchType = batchType == kTokensBatchType
                                                        ? ctranslate2::BatchType::Tokens
                                                        : ctranslate2::BatchType::Examples;

        const auto results = translator_->translate_batch(batch, options, batchSize, ct2BatchType);

        if (results.size() != batch.size()) {
            LOGE("Result count mismatch: %zu results for %zu sequences",
                 results.size(), batch.size());
            return translations;
        }

        for (std::size_t index = 0; index < results.size(); ++index) {
            const auto& outputTokens = results[index].output();
            if (outputTokens.empty()) continue;

            std::string decoded;
            // The std::vector overloads of Decode are deprecated in SentencePiece 0.2.x; the span
            // form is the supported one and is what the deprecated wrappers forward to.
            if (!targetProcessor_.Decode(absl::MakeConstSpan(outputTokens), &decoded).ok()) {
                LOGE("SentencePiece failed to decode sequence %zu; falling back for that item", index);
                continue;
            }
            if (decoded.empty()) continue;

            translations[slots[index]] = std::move(decoded);
        }

        LOGI("Translated %zu/%zu sequences in %zu batch(es) of <=%zu",
             batch.size(), texts.size(),
             batchSize > 0 ? (batch.size() + batchSize - 1) / batchSize : 1, batchSize);
    } catch (const std::exception& e) {
        LOGE("CTranslate2 batch translation error: %s", e.what());
    } catch (...) {
        LOGE("CTranslate2 batch translation error: unknown exception");
    }

    return translations;
}

int Engine::tokenCount(std::string_view text) const {
    if (text.empty()) return 0;

    std::vector<std::string> tokens;
    // 0 already means "count unknown" to the caller (it then skips token-based budgeting), which is
    // the right answer for a tokenizer failure too.
    if (!sourceProcessor_.Encode(std::string(text), &tokens).ok()) return 0;
    return static_cast<int>(tokens.size()) + 1;  // + </s>
}

bool Engine::ready() const noexcept {
    return translator_ != nullptr;
}

const std::string& Engine::modelDir() const noexcept {
    return modelDir_;
}

#else  // !HAS_REAL_CT2

namespace {

/** Fallback layout probe, same order as the real engine: flat first, then the bundle. */
constexpr std::string_view kWeightsFile = "/model.bin";
constexpr std::string_view kBundledModelSubdir = "/ct2-int8_float32";

std::string joinPath(std::string_view directory, std::string_view leaf) {
    std::string path;
    path.reserve(directory.size() + leaf.size());
    path.append(directory).append(leaf);
    return path;
}

bool fileExists(const std::string& path) {
    return std::filesystem::exists(path);
}

}  // namespace

Engine::Engine() = default;
Engine::~Engine() = default;

bool Engine::load(const std::string& modelDir, int /*numThreads*/, std::string_view /*computeType*/) {
    LOGI("Verifying model files at: %s", modelDir.c_str());

    // Only report success when the actual model.bin exists on disk.
    const bool modelFound = fileExists(joinPath(modelDir, kWeightsFile)) ||
                            fileExists(joinPath(modelDir + std::string(kBundledModelSubdir), kWeightsFile));
    if (!modelFound) return false;

    modelFilesPresent_ = true;
    modelDir_ = modelDir;
    return true;
}

std::vector<std::string> Engine::translate(const std::vector<std::string>& texts,
                                           int /*numBeams*/,
                                           int /*maxDecodingLength*/,
                                           int /*maxBatchSize*/,
                                           int /*batchType*/) const {
    // No real engine: return empty strings so Kotlin runs the Sino-Vietnamese fallback per item.
    // NEVER return Chinese or a "[CT2 Native]" mock string.
    return std::vector<std::string>(texts.size());
}

int Engine::tokenCount(std::string_view /*text*/) const {
    return 0;
}

bool Engine::ready() const noexcept {
    return modelFilesPresent_;
}

const std::string& Engine::modelDir() const noexcept {
    return modelDir_;
}

#endif  // HAS_REAL_CT2

}  // namespace vbookmt
