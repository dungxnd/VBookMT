#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cstdio>
#include <android/log.h>

#define TAG "VBookMT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifdef HAS_REAL_CT2
#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>

static std::unique_ptr<ctranslate2::Translator> g_translator;
static sentencepiece::SentencePieceProcessor g_sp_src;
static sentencepiece::SentencePieceProcessor g_sp_tgt;
static std::string g_currentModelDir;
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir,
    jint numThreads
) {
    if (modelDir == nullptr) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(modelDir, nullptr);
    if (path == nullptr) return JNI_FALSE;

    std::string dir(path);
    env->ReleaseStringUTFChars(modelDir, path);

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

        LOGI("Initializing CTranslate2 native engine from %s with %d threads", ct2ModelDir.c_str(), numThreads);
        g_translator = std::make_unique<ctranslate2::Translator>(
            ct2ModelDir,
            ctranslate2::Device::CPU,
            ctranslate2::ComputeType::INT8,
            std::vector<int>{0},
            nullptr,
            numThreads
        );

        g_currentModelDir = dir;
        LOGI("CTranslate2 model successfully loaded and active!");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Failed to init CTranslate2: %s", e.what());
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

extern "C" JNIEXPORT jstring JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeTranslate(
    JNIEnv* env,
    jobject /* this */,
    jstring inputText,
    jint numBeams
) {
    if (inputText == nullptr) return env->NewStringUTF("");
    const char* srcChars = env->GetStringUTFChars(inputText, nullptr);
    if (srcChars == nullptr) return env->NewStringUTF("");

    std::string text(srcChars);
    env->ReleaseStringUTFChars(inputText, srcChars);

#ifdef HAS_REAL_CT2
    if (!g_translator) {
        return env->NewStringUTF("");
    }

    try {
        std::vector<std::string> tokens;
        g_sp_src.Encode(text, &tokens);
        tokens.push_back("</s>");

        std::vector<std::vector<std::string>> batch = { tokens };
        ctranslate2::TranslationOptions options;
        options.beam_size = numBeams > 0 ? numBeams : 1;
        options.max_decoding_length = 512;

        auto results = g_translator->translate_batch(batch, options);
        if (results.empty() || results[0].hypotheses.empty()) {
            return env->NewStringUTF("");
        }

        std::string translated;
        g_sp_tgt.Decode(results[0].hypotheses[0], &translated);
        return env->NewStringUTF(translated.c_str());
    } catch (const std::exception& e) {
        LOGE("CTranslate2 translation error: %s", e.what());
        return env->NewStringUTF("");
    }
#else
    // When real CT2 native binary is not active, return empty string so Kotlin
    // immediately runs the Sino-Vietnamese / Convert engine.
    // NEVER return Chinese or [CT2 Native] mock string!
    return env->NewStringUTF("");
#endif
}
