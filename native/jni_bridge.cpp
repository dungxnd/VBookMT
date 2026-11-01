#include <jni.h>

#include <cstddef>
#include <string>
#include <string_view>
#include <vector>

#include "jni_strings.h"
#include "tokenizer.h"

namespace {

// One tokenizer per loaded library. The Kotlin facade is a singleton and every native entry point it
// calls is @Synchronized, so the tokenizer needs no locking of its own.
vbookmt::Tokenizer g_tokenizer;

/**
 * JNI binds an `external fun` by its **fully-qualified class name**, so the exported symbol has to
 * name the Kotlin facade that declares it. Android and the Windows host each have their own facade
 * over this same tokenizer, and exactly one is compiled per host, because
 * `native/windows/CMakeLists.txt` defines `VBOOKMT_HOST_WINDOWS`.
 *
 * This file used to be named after the CTranslate2 translation engine. CTranslate2 is gone —
 * translation runs on ONNX Runtime — but the SentencePiece tokenizer it shipped with is still the
 * only tokenizer, so the bridge now serves that alone.
 */
#if defined(VBOOKMT_HOST_WINDOWS)
#define VBOOKMT_JNI_CLASS com_vbookmt_windows_backend_WindowsNativeTokenizer
#else
#define VBOOKMT_JNI_CLASS vn_dungxnd_vbookmt_service_NativeTokenizer
#endif

/** Defines one JNI entry point under whichever facade this host compiles against. */
/*
 * Two levels on purpose. `Java_##VBOOKMT_JNI_CLASS##_##name` in one step would paste the *literal*
 * token `VBOOKMT_JNI_CLASS` — the result of `##` is never rescanned, so the symbol would come out as
 * `Java_VBOOKMT_JNI_CLASS_nativeLoad`. The outer macro expands its argument first (it is neither
 * stringified nor pasted there), and only then does the inner one paste the real class name.
 */
#define VBOOKMT_JNI_SYMBOL_IMPL(cls, name) Java_##cls##_##name
#define VBOOKMT_JNI_SYMBOL(cls, name) VBOOKMT_JNI_SYMBOL_IMPL(cls, name)
#define VBOOKMT_JNI(ret, name) \
    extern "C" JNIEXPORT ret JNICALL VBOOKMT_JNI_SYMBOL(VBOOKMT_JNI_CLASS, name)

}  // namespace

/**
 * Loads `source.spm` and `target.spm` from `modelDir`.
 *
 * The model directory holds the ONNX graphs and their tables; there are no engine weights to load
 * here, because ONNX Runtime owns the model.
 */
VBOOKMT_JNI(jboolean, nativeLoad)(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir
) {
    if (modelDir == nullptr) return JNI_FALSE;

    const std::string dir = vbookmt::jstringToUtf8(env, modelDir);
    if (dir.empty()) return JNI_FALSE;

    return g_tokenizer.load(dir) ? JNI_TRUE : JNI_FALSE;
}

/**
 * SentencePiece length of `text` plus the trailing </s>. Lets Kotlin enforce a *token* budget
 * instead of guessing from characters: normal Chinese prose costs ~0.62 tokens/char, but rare
 * glyphs (龘靐齉齾...) cost ~2.0, so a 350-char chunk can be 215 or 720 tokens.
 */
VBOOKMT_JNI(jint, nativeTokenCount)(
    JNIEnv* env,
    jobject /* this */,
    jstring text
) {
    if (text == nullptr) return 0;

    const std::string utf8 = vbookmt::jstringToUtf8(env, text);
    if (utf8.empty()) return 0;

    return static_cast<jint>(g_tokenizer.tokenCount(utf8));
}

/**
 * Source text -> model ids, including the trailing `</s>`.
 *
 * The ONNX graphs were exported against exactly this sequence, so the trailing EOS is part of the
 * contract rather than a caller's choice. Returns an empty array on failure; the caller treats that
 * as an empty input rather than an error, because the Sino-Vietnamese fallback still produces output.
 */
VBOOKMT_JNI(jintArray, nativeEncode)(
    JNIEnv* env,
    jobject /* this */,
    jstring text
) {
    if (text == nullptr) return env->NewIntArray(0);

    const std::string utf8 = vbookmt::jstringToUtf8(env, text);
    if (utf8.empty()) return env->NewIntArray(0);

    const std::vector<int32_t> ids = g_tokenizer.encode(utf8);
    jintArray out = env->NewIntArray(static_cast<jsize>(ids.size()));
    if (out == nullptr || ids.empty()) return out;

    env->SetIntArrayRegion(out, 0, static_cast<jsize>(ids.size()),
                           reinterpret_cast<const jint*>(ids.data()));
    return out;
}

/** Model ids -> text, skipping special tokens. */
VBOOKMT_JNI(jstring, nativeDecode)(
    JNIEnv* env,
    jobject /* this */,
    jintArray ids
) {
    if (ids == nullptr) return env->NewStringUTF("");

    const jsize length = env->GetArrayLength(ids);
    if (length <= 0) return env->NewStringUTF("");

    // Heap buffer, not `jint raw[length]`: a variable-length array is a GCC extension that MSVC
    // rejects, and this file is compiled by every host including Windows.
    std::vector<jint> raw(static_cast<std::size_t>(length));
    env->GetIntArrayRegion(ids, 0, length, raw.data());

    std::vector<int32_t> tokens(raw.begin(), raw.end());
    return vbookmt::utf8ToJstring(env, g_tokenizer.decode(tokens));
}
