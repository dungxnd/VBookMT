#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include <android/log.h>

#define TAG "VBookMT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef HAS_REAL_CT2
#include <ctranslate2/batch_reader.h>
#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

static std::unique_ptr<ctranslate2::Translator> g_translator;
static sentencepiece::SentencePieceProcessor g_sp_src;
static sentencepiece::SentencePieceProcessor g_sp_tgt;
static std::string g_currentModelDir;

/** MarianMT position tables are 512 deep; never let a sequence reach them. */
static constexpr size_t kModelPositionLimit = 512;
#endif

// -------------------------------------------------------------------------------------------
// UTF-16 <-> UTF-8
//
// JNI's GetStringUTFChars/NewStringUTF use *modified* UTF-8 (CESU-8): supplementary-plane
// characters are re-encoded as surrogate pairs taking 6 bytes instead of 4. Rare CJK glyphs
// (U+20000+, common in xianxia names) therefore reached SentencePiece as garbage bytes and
// produced corrupted tokens. Convert explicitly instead.
// -------------------------------------------------------------------------------------------

static std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};

    const jsize length = env->GetStringLength(value);
    if (length <= 0) return {};

    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string out;
    out.reserve(static_cast<size_t>(length) * 3);

    for (jsize i = 0; i < length; ++i) {
        uint32_t cp = chars[i];

        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }

        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }

    env->ReleaseStringChars(value, chars);
    return out;
}

static jstring utf8ToJstring(JNIEnv* env, const std::string& in) {
    if (in.empty()) return env->NewStringUTF("");

    std::vector<jchar> utf16;
    utf16.reserve(in.size());

    size_t i = 0;
    while (i < in.size()) {
        const unsigned char lead = static_cast<unsigned char>(in[i]);
        uint32_t cp = 0;
        size_t extra = 0;

        if (lead < 0x80) {
            cp = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1Fu;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0Fu;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07u;
            extra = 3;
        } else {
            ++i;  // invalid lead byte, drop it
            continue;
        }

        if (i + extra >= in.size()) break;
        for (size_t k = 1; k <= extra; ++k) {
            cp = (cp << 6) | (static_cast<unsigned char>(in[i + k]) & 0x3Fu);
        }
        i += extra + 1;

        if (cp <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }

    if (utf16.empty()) return env->NewStringUTF("");
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

// -------------------------------------------------------------------------------------------
// Core: translate N sequences in ONE batched inference call.
//
// Returns a vector of exactly `texts.size()` entries; a failed/empty entry is "" so the Kotlin
// layer can fall back per-item instead of losing the whole chapter.
// -------------------------------------------------------------------------------------------

#ifdef HAS_REAL_CT2

static std::vector<std::string> translateInternal(const std::vector<std::string>& texts,
                                                  int numBeams,
                                                  int maxDecodingLength,
                                                  int maxBatchSize) {
    std::vector<std::string> out(texts.size());

    if (!g_translator) {
        LOGE("translateInternal called before a model was loaded");
        return out;
    }

    try {
        std::vector<std::vector<std::string>> batch;
        batch.reserve(texts.size());
        std::vector<size_t> slots;
        slots.reserve(texts.size());

        for (size_t i = 0; i < texts.size(); ++i) {
            if (texts[i].empty()) continue;

            std::vector<std::string> tokens;
            g_sp_src.Encode(texts[i], &tokens);
            if (tokens.empty()) continue;
            // MarianMT tokenizer contract, same as the Python reference implementation.
            tokens.push_back("</s>");

            batch.emplace_back(std::move(tokens));
            slots.push_back(i);
        }

        if (batch.empty()) return out;

        ctranslate2::TranslationOptions options;
        options.beam_size = numBeams > 0 ? static_cast<size_t>(numBeams) : 1;
        options.min_decoding_length = 1;
        options.max_decoding_length = maxDecodingLength > 0
                                          ? static_cast<size_t>(maxDecodingLength)
                                          : 256;
        // Last-resort guard: truncate rather than let CT2 throw out of the position table.
        // Kotlin keeps every sequence under its own token budget, so this never fires in practice.
        options.max_input_length = kModelPositionLimit;
        options.return_scores = false;  // beam_size == 1 => final softmax is skipped entirely

        const size_t batchSize = maxBatchSize > 0 ? static_cast<size_t>(maxBatchSize) : 0;

        // CTranslate2 sorts by length and restores the original order, so results map 1:1.
        auto results = g_translator->translate_batch(
            batch,
            options,
            batchSize,
            ctranslate2::BatchType::Examples);

        if (results.size() != batch.size()) {
            LOGE("Result count mismatch: %zu results for %zu sequences",
                 results.size(), batch.size());
            return out;
        }

        for (size_t i = 0; i < results.size(); ++i) {
            const auto& outputTokens = results[i].output();
            if (outputTokens.empty()) continue;

            std::string decoded;
            g_sp_tgt.Decode(outputTokens, &decoded);
            if (decoded.empty()) continue;

            out[slots[i]] = std::move(decoded);
        }

        LOGI("Translated %zu/%zu sequences in %zu batch(es) of <=%zu",
             batch.size(), texts.size(),
             batchSize > 0 ? (batch.size() + batchSize - 1) / batchSize : 1, batchSize);
    } catch (const std::exception& e) {
        LOGE("CTranslate2 batch translation error: %s", e.what());
    } catch (...) {
        LOGE("CTranslate2 batch translation error: unknown exception");
    }

    return out;
}

#endif  // HAS_REAL_CT2

// -------------------------------------------------------------------------------------------
// JNI surface
// -------------------------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir,
    jint numThreads
) {
    if (modelDir == nullptr) return JNI_FALSE;

    std::string dir = jstringToUtf8(env, modelDir);
    if (dir.empty()) return JNI_FALSE;

#ifdef HAS_REAL_CT2
    try {
        std::string srcSpm = dir + "/source.spm";
        std::string tgtSpm = dir + "/target.spm";

        if (!g_sp_src.Load(srcSpm).ok()) {
            srcSpm = dir + "/ct2-int8_float32/source.spm";
            tgtSpm = dir + "/ct2-int8_float32/target.spm";
            if (!g_sp_src.Load(srcSpm).ok()) {
                LOGE("Could not find source.spm in %s", dir.c_str());
                return JNI_FALSE;
            }
        }

        if (!g_sp_tgt.Load(tgtSpm).ok()) {
            LOGE("Could not find target.spm in %s", dir.c_str());
            return JNI_FALSE;
        }

        std::string ct2ModelDir = dir;
        FILE* fp = fopen((dir + "/model.bin").c_str(), "rb");
        if (fp) {
            fclose(fp);
        } else {
            ct2ModelDir = dir + "/ct2-int8_float32";
        }

        // Honor the caller's thread count instead of silently falling back to CT2's default (4).
        // On big.LITTLE phones intra_threads > big-core count schedules onto little cores and
        // loses throughput while heating up; CT2 docs advise intra*inter <= physical cores.
        ctranslate2::ReplicaPoolConfig poolConfig;
        if (numThreads > 0) {
            poolConfig.num_threads_per_replica = static_cast<size_t>(numThreads);
        }

        LOGI("Initializing CTranslate2 from %s with %d intra-op thread(s)",
             ct2ModelDir.c_str(), numThreads);

        g_translator = std::make_unique<ctranslate2::Translator>(
            ct2ModelDir,
            ctranslate2::Device::CPU,
            ctranslate2::ComputeType::DEFAULT,
            std::vector<int>{0},
            false,
            poolConfig);

        g_currentModelDir = dir;
        LOGI("CTranslate2 model loaded and active (%zu replica(s))",
             g_translator->num_replicas());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Failed to init CTranslate2: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Failed to init CTranslate2: unknown exception");
        return JNI_FALSE;
    }
#else
    LOGI("Verifying model files at: %s", dir.c_str());
    // Only return TRUE if the actual model.bin exists on disk
    FILE* fp = fopen((dir + "/model.bin").c_str(), "rb");
    if (!fp) fp = fopen((dir + "/ct2-int8_float32/model.bin").c_str(), "rb");
    if (fp) {
        fclose(fp);
        return JNI_TRUE;
    }
    return JNI_FALSE;
#endif
}

/**
 * Translate a whole chapter in one batched inference call.
 *
 * This is the hot path. Previously each paragraph was a separate native call, so a 7k-character
 * chapter paid ~300 fixed call costs (~25 ms each on desktop, far more on ARM) and never filled
 * a batch: measured 4.6x slower than handing the same sequences over at once.
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeTranslateBatch(
    JNIEnv* env,
    jobject /* this */,
    jobjectArray inputs,
    jint numBeams,
    jint maxDecodingLength,
    jint maxBatchSize
) {
    const jsize count = inputs != nullptr ? env->GetArrayLength(inputs) : 0;

    std::vector<std::string> texts(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(inputs, i));
        if (item == nullptr) continue;
        texts[static_cast<size_t>(i)] = jstringToUtf8(env, item);
        env->DeleteLocalRef(item);
    }

    std::vector<std::string> translated;
#ifdef HAS_REAL_CT2
    translated = translateInternal(texts, numBeams, maxDecodingLength, maxBatchSize);
#else
    // No real engine: return empty strings so Kotlin runs the Sino-Vietnamese fallback per item.
    // NEVER return Chinese or a "[CT2 Native]" mock string.
    (void)numBeams;
    (void)maxDecodingLength;
    (void)maxBatchSize;
    translated.assign(texts.size(), std::string());
#endif

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;

    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (out == nullptr) return nullptr;

    for (jsize i = 0; i < count; ++i) {
        jstring item = utf8ToJstring(env, translated[static_cast<size_t>(i)]);
        if (item == nullptr) continue;
        env->SetObjectArrayElement(out, i, item);
        env->DeleteLocalRef(item);
    }

    return out;
}

/**
 * SentencePiece length of `text` plus the trailing </s>. Lets Kotlin enforce a *token* budget
 * instead of guessing from characters: normal Chinese prose costs ~0.62 tokens/char, but rare
 * glyphs (龘靐齉齾...) cost ~2.0, so a 350-char chunk can be 215 or 720 tokens.
 */
extern "C" JNIEXPORT jint JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeTokenCount(
    JNIEnv* env,
    jobject /* this */,
    jstring text
) {
    if (text == nullptr) return 0;

#ifdef HAS_REAL_CT2
    const std::string utf8 = jstringToUtf8(env, text);
    if (utf8.empty()) return 0;

    std::vector<std::string> tokens;
    g_sp_src.Encode(utf8, &tokens);
    return static_cast<jint>(tokens.size() + 1);  // + </s>
#else
    (void)env;
    return 0;
#endif
}

/** Single-sequence convenience wrapper; kept so one-off callers stay source-compatible. */
extern "C" JNIEXPORT jstring JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeTranslate(
    JNIEnv* env,
    jobject /* this */,
    jstring inputText,
    jint numBeams
) {
    if (inputText == nullptr) return env->NewStringUTF("");

    const std::string text = jstringToUtf8(env, inputText);
    if (text.empty()) return env->NewStringUTF("");

    std::string translated;
#ifdef HAS_REAL_CT2
    auto results = translateInternal({text}, numBeams, 320, 1);
    if (!results.empty()) translated = std::move(results[0]);
#else
    (void)numBeams;
#endif

    return utf8ToJstring(env, translated);
}
