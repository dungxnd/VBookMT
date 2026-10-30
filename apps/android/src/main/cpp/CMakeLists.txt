cmake_minimum_required(VERSION 3.22.1)
project("vbookmt_android")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(VBOOKMT_NATIVE_SOURCES
    jni_bridge.cpp
    jni_strings.cpp
    ct2_engine.cpp
    compute_type.cpp
)

if(EXISTS "${JNI_LIBS_DIR}/libctranslate2.so" AND EXISTS "${JNI_LIBS_DIR}/libsentencepiece.so")
    message(STATUS "Found prebuilt CTranslate2 & SentencePiece libraries for ${ANDROID_ABI}")

    add_library(ctranslate2 SHARED IMPORTED)
    set_target_properties(ctranslate2 PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libctranslate2.so")

    add_library(sentencepiece SHARED IMPORTED)
    set_target_properties(sentencepiece PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libsentencepiece.so")

    # Extra roots are harmless when absent and necessary when present: SentencePiece's public
    # header reaches its vendored absl shim through "third_party/absl/...", and that shim in turn
    # expects the real abseil tree to be findable as "absl/...". Keep every spelling on the path.
    foreach(_vbookmt_include_root
            "${CMAKE_CURRENT_SOURCE_DIR}/include"
            "${CMAKE_CURRENT_SOURCE_DIR}/include/third_party"
            "${CMAKE_CURRENT_SOURCE_DIR}/include/third_party/absl")
        if(EXISTS "${_vbookmt_include_root}")
            include_directories("${_vbookmt_include_root}")
        endif()
    endforeach()

    add_library(vbookmt_native SHARED ${VBOOKMT_NATIVE_SOURCES})
    target_compile_definitions(vbookmt_native PRIVATE HAS_REAL_CT2=1)

    find_library(log-lib log)
    if(EXISTS "${JNI_LIBS_DIR}/libomp.so")
        add_library(omp SHARED IMPORTED)
        set_target_properties(omp PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libomp.so")
        target_link_libraries(vbookmt_native ${log-lib} ctranslate2 sentencepiece omp)
    else()
        target_link_libraries(vbookmt_native ${log-lib} ctranslate2 sentencepiece)
    endif()
else()
    message(STATUS "Prebuilt CTranslate2 not found. Building lightweight native bridge.")
    add_library(vbookmt_native SHARED ${VBOOKMT_NATIVE_SOURCES})
    find_library(log-lib log)
    target_link_libraries(vbookmt_native ${log-lib})
endif()
