cmake_minimum_required(VERSION 3.22.1)
project("vbookmt_android")

# Include CTranslate2 and SentencePiece headers
# In full build: add_subdirectory(${CTRANSLATE2_SRC} ctranslate2_build)
# Here we define the JNI native bridge library
add_library(
    vbookmt_native
    SHARED
    ct2_jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    vbookmt_native
    ${log-lib}
    # ctranslate2
)
