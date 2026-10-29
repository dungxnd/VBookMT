#ifndef CT2_C_API_H
#define CT2_C_API_H

#ifdef __cplusplus
extern "C" {
#endif

typedef void* ct2_translator_t;

/**
 * Creates and initializes a CTranslate2 Translator from a model directory.
 */
ct2_translator_t ct2_create(const char* model_dir, int num_threads);

/**
 * Translates a single text string using CTranslate2.
 * Returns a null-terminated UTF-8 string that must be freed with ct2_free_string.
 */
char* ct2_translate(ct2_translator_t handle, const char* text, int num_beams);

/**
 * Frees a string returned by ct2_translate.
 */
void ct2_free_string(char* str);

/**
 * Destroys the CTranslate2 translator instance and releases memory.
 */
void ct2_destroy(ct2_translator_t handle);

#ifdef __cplusplus
}
#endif

#endif // CT2_C_API_H
