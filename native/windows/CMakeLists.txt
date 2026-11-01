cmake_minimum_required(VERSION 3.22.1)
project("vbookmt_windows_native" LANGUAGES CXX)

# The Windows half of the native library.
#
# It compiles the *same* sources as the Android build (../../native), against the same SentencePiece
# version (v0.2.3pre1), so the two hosts cannot disagree about the trailing </s> contract or about
# which pieces count as special. The only difference is the logging sink, which tokenizer.cpp selects
# on __ANDROID__.
#
# CTranslate2 is no longer linked: translation runs on ONNX Runtime on both hosts, which brings its own
# managed runtime. This library now serves the SentencePiece tokenizer alone — the only tokenizer in
# the app, and the one every ONNX graph was exported against.
#
# SentencePiece, protobuf and abseil are static, which removes the protobuf runtime version skew that
# would otherwise abort at load time.

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(VBOOKMT_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../native")

set(SPM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../build/native-win/sentencepiece" CACHE PATH "SentencePiece source checkout")
set(SPM_BUILD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../build/native-win/build-spm" CACHE PATH "SentencePiece build directory")
set(JNI_ROOT "$ENV{JAVA_HOME}" CACHE PATH "JDK root containing include/jni.h")

if(NOT JNI_ROOT AND DEFINED ENV{JAVA_HOME})
    set(JNI_ROOT "$ENV{JAVA_HOME}")
endif()

if(NOT EXISTS "${JNI_ROOT}/include/jni.h")
    find_package(JNI QUIET)
    if(JNI_FOUND)
        set(JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
    else()
        message(FATAL_ERROR
            "jni.h not found. Ensure JNI_ROOT or JAVA_HOME points to a JDK containing include/jni.h.\n"
            "  JNI_ROOT = '${JNI_ROOT}'\n"
            "  JAVA_HOME = '$ENV{JAVA_HOME}'")
    endif()
else()
    set(JNI_INCLUDE_DIRS "${JNI_ROOT}/include" "${JNI_ROOT}/include/win32")
endif()

# SentencePiece: static library plus the protobuf/abseil objects it was built with.
find_library(SPM_LIBRARY
    NAMES sentencepiece
    PATHS "${SPM_BUILD_DIR}/src/Release" "${SPM_BUILD_DIR}/Release"
    NO_DEFAULT_PATH)
if(NOT SPM_LIBRARY)
    message(FATAL_ERROR "sentencepiece.lib not found under ${SPM_BUILD_DIR}")
endif()

# The static SentencePiece archive carries its protobuf and absl dependencies as objects, so nothing
add_library(vbookmt_native SHARED
    "${VBOOKMT_NATIVE_DIR}/jni_bridge.cpp"
    "${VBOOKMT_NATIVE_DIR}/jni_strings.cpp"
    "${VBOOKMT_NATIVE_DIR}/tokenizer.cpp"
)

target_include_directories(vbookmt_native PRIVATE
    "${VBOOKMT_NATIVE_DIR}"
    "${SPM_ROOT}/src"
    "${SPM_ROOT}"                       # sentencepiece_processor.h includes "sentencepiece_model.pb.h"
    "${SPM_BUILD_DIR}/src"              # generated .pb.h lives in the build tree
    "${SPM_BUILD_DIR}/_deps/abseil-cpp-src"   # 0.2.3's public header includes real "absl/..." paths
    ${JNI_INCLUDE_DIRS}
)

target_compile_definitions(vbookmt_native PRIVATE
    NOMINMAX=1                          # <windows.h> min/max macros break <limits> in MSVC
    WIN32_LEAN_AND_MEAN=1
    _CRT_SECURE_NO_WARNINGS=1
    # JNI binds an `external fun` by its fully-qualified class name, and this host's facade is
    # `com.vbookmt.windows.backend.WindowsNativeTokenizer`, not Android's
    # `vn.dungxnd.vbookmt.service.NativeTokenizer`. Without this, the DLL exported only the Android
    # symbols and every native call here failed with UnsatisfiedLinkError.
    VBOOKMT_HOST_WINDOWS=1
)

# MSVC needs the static runtime to match SentencePiece's own build, or the linker sees two different
# implementations of the C++ standard library in one binary.
set_property(TARGET vbookmt_native PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreadedDLL")

# SentencePiece 0.2.3 links protobuf and abseil as *separate* static archives rather than folding
# their objects into sentencepiece.lib, so every one of them has to be on the link line. Collecting
# them from the build tree instead of naming ~92 libraries by hand keeps the list honest when a
# SentencePiece or protobuf version bump adds or removes one.
file(GLOB_RECURSE SPM_STATIC_DEPS "${SPM_BUILD_DIR}/_deps/abseil-cpp-build/*/*/Release/*.lib")
file(GLOB SPM_PROTOBUF_DEPS "${SPM_BUILD_DIR}/_deps/protobuf-build/Release/*.lib")
list(FILTER SPM_PROTOBUF_DEPS EXCLUDE REGEX "protoc|libprotoc")
# protobuf 36 splits UTF-8 validation into its own archive under third_party/, which is not part of
# the Release/ glob above.
file(GLOB SPM_UTF8_RANGE_DEPS
    "${SPM_BUILD_DIR}/_deps/protobuf-build/third_party/utf8_range/Release/*.lib")
if(NOT SPM_STATIC_DEPS)
    message(FATAL_ERROR "No abseil static libraries found under ${SPM_BUILD_DIR}/_deps/abseil-cpp-build")
endif()
if(NOT SPM_UTF8_RANGE_DEPS)
    message(FATAL_ERROR "libutf8_range.lib not found; protobuf will fail to link")
endif()
list(LENGTH SPM_STATIC_DEPS _spm_dep_count)
message(STATUS "Linking ${_spm_dep_count} abseil libraries + protobuf + utf8_range against SentencePiece")

target_link_libraries(vbookmt_native PRIVATE
    "${SPM_LIBRARY}"
    ${SPM_PROTOBUF_DEPS}
    ${SPM_UTF8_RANGE_DEPS}
    ${SPM_STATIC_DEPS}
)

# abseil's symbolizer and spinlock code reach for Win32 APIs that are not in the default set.
target_link_libraries(vbookmt_native PRIVATE dbghelp psapi advapi32 bcrypt)

# Keep the JNI entry points exported: without a .def file MSVC still exports JNIEXPORT symbols,
# and nothing else in this DLL needs to be visible.
set_target_properties(vbookmt_native PROPERTIES
    OUTPUT_NAME "vbookmt_native"
    PREFIX ""
)
