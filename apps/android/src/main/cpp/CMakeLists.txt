cmake_minimum_required(VERSION 3.22.1)
project("vbookmt_android")

set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

if(EXISTS "${JNI_LIBS_DIR}/libctranslate2.so" AND EXISTS "${JNI_LIBS_DIR}/libsentencepiece.so")
    message(STATUS "Found prebuilt CTranslate2 & SentencePiece libraries for ${ANDROID_ABI}")

    add_library(ctranslate2 SHARED IMPORTED)
    set_target_properties(ctranslate2 PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libctranslate2.so")

    add_library(sentencepiece SHARED IMPORTED)
    set_target_properties(sentencepiece PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libsentencepiece.so")

    include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/include
    )

    add_library(vbookmt_native SHARED ct2_jni.cpp)
    target_compile_definitions(vbookmt_native PRIVATE HAS_REAL_CT2=1)

    find_library(log-lib log)
    target_link_libraries(vbookmt_native ${log-lib} ctranslate2 sentencepiece)
else()
    message(STATUS "Prebuilt CTranslate2 not found. Building lightweight native bridge.")
    add_library(vbookmt_native SHARED ct2_jni.cpp)
    find_library(log-lib log)
    target_link_libraries(vbookmt_native ${log-lib})
endif()
