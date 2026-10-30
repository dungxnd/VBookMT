#include <jni.h>

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

#include "ct2_engine.h"
#include "jni_strings.h"

namespace {

// One engine per loaded library: the Kotlin Ct2Translator facade is a singleton and every native
// entry point it calls is @Synchronized, so the engine needs no locking of its own.
vbookmt::Engine g_engine;

/** nativeTranslate settings: 320 decoding steps, one sequence per batch, Examples batching. */
constexpr int kSingleMaxDecodingLength = 320;
constexpr int kSingleMaxBatchSize = 1;
constexpr int kSingleBatchType = 0;  // 0 => ctranslate2::BatchType::Examples

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir,
    jint numThreads,
    jstring computeTypeArg
) {
    if (modelDir == nullptr) return JNI_FALSE;

    const std::string dir = vbookmt::jstringToUtf8(env, modelDir);
    if (dir.empty()) return JNI_FALSE;

    // An empty name keeps the model's stored compute type.
    const std::string requestedComputeType =
        computeTypeArg != nullptr ? vbookmt::jstringToUtf8(env, computeTypeArg) : std::string();

    return g_engine.load(dir, static_cast<int>(numThreads), requestedComputeType)
               ? JNI_TRUE
               : JNI_FALSE;
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
    jint maxBatchSize,
    jint batchType
) {
    const jsize count = inputs != nullptr ? env->GetArrayLength(inputs) : 0;

    std::vector<std::string> texts(static_cast<std::size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(inputs, i));
        if (item == nullptr) continue;
        texts[static_cast<std::size_t>(i)] = vbookmt::jstringToUtf8(env, item);
        env->DeleteLocalRef(item);
    }

    const std::vector<std::string> translations = g_engine.translate(
        texts,
        static_cast<int>(numBeams),
        static_cast<int>(maxDecodingLength),
        static_cast<int>(maxBatchSize),
        static_cast<int>(batchType));

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;

    jobjectArray out = env->NewObjectArray(count, stringClass, nullptr);
    env->DeleteLocalRef(stringClass);
    if (out == nullptr) return nullptr;

    for (jsize i = 0; i < count; ++i) {
        const auto index = static_cast<std::size_t>(i);
        if (index >= translations.size()) break;  // never read past the engine's result vector
        jstring item = vbookmt::utf8ToJstring(env, translations[index]);
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

    const std::string utf8 = vbookmt::jstringToUtf8(env, text);
    if (utf8.empty()) return 0;

    return static_cast<jint>(g_engine.tokenCount(utf8));
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

    const std::string text = vbookmt::jstringToUtf8(env, inputText);
    if (text.empty()) return env->NewStringUTF("");

    // Same batched path as nativeTranslateBatch, with a single sequence.
    const std::vector<std::string> translations = g_engine.translate(
        std::vector<std::string>{text},
        static_cast<int>(numBeams),
        kSingleMaxDecodingLength,
        kSingleMaxBatchSize,
        kSingleBatchType);

    return vbookmt::utf8ToJstring(
        env,
        translations.empty() ? std::string_view{} : std::string_view{translations.front()});
}
