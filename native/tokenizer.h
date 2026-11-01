#pragma once

#include <cstdint>
#include <sentencepiece_processor.h>
#include <string>
#include <string_view>
#include <vector>

namespace vbookmt {

/**
 * The two SentencePiece tables the models are exported against.
 *
 * **Why this exists separately from a translation engine.** VBookMT used to ship CTranslate2, and
 * this tokenizer lived inside `ct2_engine.cpp` beside it. CTranslate2 is gone — translation runs on
 * ONNX Runtime — but the tokenizer cannot follow it out: the ONNX graphs were exported against
 * exactly these ids, so the app still needs SentencePiece to turn text into model ids and back.
 * Keeping it in its own translation unit means the tokenizer survives the engine swap, and there is
 * still exactly **one** tokenizer implementation per host, so the two can never disagree about the
 * trailing `</s>` or about which pieces are special.
 *
 * Thread-safety: no locking of its own. The Kotlin facade is @Synchronized on every entry point, so
 * calls are already serialized.
 */
class Tokenizer {
public:
    Tokenizer() = default;

    Tokenizer(const Tokenizer&) = delete;
    Tokenizer& operator=(const Tokenizer&) = delete;

    /**
     * Loads `source.spm` and `target.spm` from `modelDir`.
     *
     * Tries the directory itself first, then the `ct2-int8_float32/` bundle that older CT2 artifacts
     * shipped them under, so a model directory laid out either way still works.
     *
     * @return false (after logging why) when either table is missing or unreadable.
     */
    [[nodiscard]] bool load(const std::string& modelDir);

    /** True once both tables loaded. */
    [[nodiscard]] bool ready() const noexcept { return ready_; }

    /** SentencePiece length of `text` plus the trailing `</s>`; 0 for an empty text. */
    [[nodiscard]] int tokenCount(std::string_view text) const;

    /** Source text to model ids, including the trailing `</s>`. Empty on failure. */
    [[nodiscard]] std::vector<int32_t> encode(std::string_view text) const;

    /** Model ids back to text, skipping special tokens. Empty ids yield "". */
    [[nodiscard]] std::string decode(const std::vector<int32_t>& ids) const;

    /** Directory the current tables were loaded from; "" until the first successful load. */
    [[nodiscard]] const std::string& modelDir() const noexcept { return modelDir_; }

private:
    sentencepiece::SentencePieceProcessor sourceProcessor_;
    sentencepiece::SentencePieceProcessor targetProcessor_;
    bool ready_ = false;
    std::string modelDir_;
};

}  // namespace vbookmt
