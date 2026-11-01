cmake_minimum_required(VERSION 3.22.1)
project("vbookmt_android")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(JNI_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# The tokenizer and its JNI glue live at the repo root in `native/` because the Windows host compiles
# exactly the same files (native/windows/CMakeLists.txt). Keeping one copy is what makes "same ids on
# both hosts" true rather than aspirational: the trailing </s> contract and the special-piece filter
# have already drifted once when there were two copies.
#
# `apps/android/src/main/cpp` -> repo root is five levels, not six. The sixth `..` resolved one
# directory above the repo (to the vBook workspace root), so CMake failed with
# "Cannot find source file: .../vBook/native/jni_bridge.cpp" and then "No SOURCES given to target".
set(VBOOKMT_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../native")

# CTranslate2 is gone: translation runs on ONNX Runtime, which ships its own AAR. What remains native
# is SentencePiece — the only tokenizer, shared with every ONNX graph ever exported for this app.
set(VBOOKMT_NATIVE_SOURCES
    "${VBOOKMT_NATIVE_DIR}/jni_bridge.cpp"
    "${VBOOKMT_NATIVE_DIR}/jni_strings.cpp"
    "${VBOOKMT_NATIVE_DIR}/tokenizer.cpp"
)

if(EXISTS "${JNI_LIBS_DIR}/libsentencepiece.so")
    message(STATUS "Found prebuilt SentencePiece library for ${ANDROID_ABI}")

    add_library(sentencepiece SHARED IMPORTED)
    set_target_properties(sentencepiece PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libsentencepiece.so")

    # Extra roots are harmless when absent and necessary when present: SentencePiece's public header
    # reaches its vendored absl shim through "third_party/absl/...", and that shim in turn expects
    # the real abseil tree to be findable as "absl/...". Keep every spelling on the path.
    foreach(_vbookmt_include_root
            "${CMAKE_CURRENT_SOURCE_DIR}/include"
            "${CMAKE_CURRENT_SOURCE_DIR}/include/third_party"
            "${CMAKE_CURRENT_SOURCE_DIR}/include/third_party/absl")
        if(EXISTS "${_vbookmt_include_root}")
            include_directories("${_vbookmt_include_root}")
        endif()
    endforeach()

    add_library(vbookmt_native SHARED ${VBOOKMT_NATIVE_SOURCES})
    target_include_directories(vbookmt_native PRIVATE "${VBOOKMT_NATIVE_DIR}")

    find_library(log-lib log)
    # libomp was CTranslate2's OpenMP runtime. SentencePiece's prebuilt .so still carries that
    # dependency, so keep linking it when the file is present.
    if(EXISTS "${JNI_LIBS_DIR}/libomp.so")
        add_library(omp SHARED IMPORTED)
        set_target_properties(omp PROPERTIES IMPORTED_LOCATION "${JNI_LIBS_DIR}/libomp.so")
        target_link_libraries(vbookmt_native ${log-lib} sentencepiece omp)
    else()
        target_link_libraries(vbookmt_native ${log-lib} sentencepiece)
    endif()
else()
    # No SentencePiece: the library still builds so the app loads, but encode/decode return empty and
    # the ONNX path reports the tokenizer as unavailable instead of crashing.
    message(STATUS "Prebuilt SentencePiece not found. Building stub tokenizer bridge.")
    add_library(vbookmt_native SHARED ${VBOOKMT_NATIVE_SOURCES})
    target_include_directories(vbookmt_native PRIVATE "${VBOOKMT_NATIVE_DIR}")
    find_library(log-lib log)
    target_link_libraries(vbookmt_native ${log-lib})
endif()
