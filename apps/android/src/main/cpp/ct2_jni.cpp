#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#define TAG "VBookMT_Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// In production build with CTranslate2:
// #include <ctranslate2/translator.h>
// static std::unique_ptr<ctranslate2::Translator> g_translator;

extern "C" JNIEXPORT jboolean JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelDir,
    jint numThreads
) {
    const char* path = env->GetStringUTFChars(modelDir, nullptr);
    LOGI("Initializing CTranslate2 model from: %s with %d threads", path, numThreads);
    
    // In production:
    // ctranslate2::ComputeType compute_type = ctranslate2::ComputeType::INT8;
    // g_translator = std::make_unique<ctranslate2::Translator>(path, ctranslate2::Device::CPU, compute_type);
    
    env->ReleaseStringUTFChars(modelDir, path);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_vn_dungxnd_vbookmt_service_Ct2Translator_nativeTranslate(
    JNIEnv* env,
    jobject /* this */,
    jstring inputText,
    jint numBeams
) {
    const char* src = env->GetStringUTFChars(inputText, nullptr);
    LOGI("Translating text (beams=%d)...", numBeams);

    // In production:
    // 1. tokenize src using SentencePiece
    // 2. auto results = g_translator->translate_batch(tokens, ...);
    // 3. detokenize to std::string target

    std::string mockResult = std::string("[CT2 Native] ") + src;

    env->ReleaseStringUTFChars(inputText, src);
    return env->NewStringUTF(mockResult.c_str());
}
