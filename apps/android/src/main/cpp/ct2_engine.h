#pragma once

#include <memory>
#include <string>
#include <string_view>
#include <vector>

#ifdef HAS_REAL_CT2
#include <ctranslate2/batch_reader.h>
#include <ctranslate2/translator.h>
#include <ctranslate2/types.h>

// SentencePiece 0.2.3 dropped the deprecated wrappers that used to make this header flood every
// compile with -Wdeprecated-declarations warnings, so it can be included plainly.
#include <sentencepiece_processor.h>
#endif

namespace vbookmt {

/**
 * Owns the CTranslate2 translator plus the two SentencePiece tokenizers and remembers where the
 * model was loaded from.
 *
 * Thread-safety: this class does no locking of its own. The only caller (the Kotlin
 * Ct2Translator facade) is @Synchronized, so every native entry point already runs serialized on
 * a single thread; keep it that way or add a mutex here.
 */
class Engine {
public:
    Engine();
    ~Engine();

    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;

    /**
     * Resolves the model layout under `modelDir` and loads it.
     *
     * An empty `computeType` keeps the model's stored compute type. Returns false (after logging
     * the reason) on any failure; never throws, so the caller can keep its previous engine state
     * or retry with defaults.
     */
    [[nodiscard]] bool load(const std::string& modelDir, int numThreads, std::string_view computeType);

    /** True once a model has been loaded successfully. */
    [[nodiscard]] bool ready() const noexcept;

    /**
     * Translates `texts` in ONE batched inference call.
     *
     * Returns exactly `texts.size()` entries; a failed or empty entry is "" so the Kotlin layer
     * can fall back per item instead of losing the whole chapter. `maxBatchSize <= 0` means the
     * engine default; `batchType == 1` reads it as a token budget instead of a sequence count.
     */
    [[nodiscard]] std::vector<std::string> translate(const std::vector<std::string>& texts,
                                                     int numBeams,
                                                     int maxDecodingLength,
                                                     int maxBatchSize,
                                                     int batchType) const;

    /** SentencePiece length of `text` plus the trailing </s>; 0 for an empty text. */
    [[nodiscard]] int tokenCount(std::string_view text) const;

    /** Directory the current model was loaded from; "" until the first successful load. */
    [[nodiscard]] const std::string& modelDir() const noexcept;

private:
#ifdef HAS_REAL_CT2
    std::unique_ptr<ctranslate2::Translator> translator_;
    sentencepiece::SentencePieceProcessor sourceProcessor_;
    sentencepiece::SentencePieceProcessor targetProcessor_;
#else
    // Fallback build: no CT2/SentencePiece headers, so only the on-disk probe result is kept.
    bool modelFilesPresent_ = false;
#endif
    std::string modelDir_;
};

}  // namespace vbookmt
