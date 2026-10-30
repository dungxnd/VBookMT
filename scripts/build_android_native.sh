#!/usr/bin/env bash
set -eo pipefail

echo "=========================================================="
echo " Building CTranslate2 & SentencePiece for Android ARM64   "
echo "=========================================================="

# 1. Determine Android NDK path
if [ -z "$ANDROID_NDK_HOME" ]; then
    if [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        NDK_DIR=$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)
        export ANDROID_NDK_HOME="$NDK_DIR"
    fi
fi

if [ -z "$ANDROID_NDK_HOME" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "NDK not found, attempting installation via sdkmanager..."
    if [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
        yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;28.2.13676358"
        export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
    else
        echo "ERROR: ANDROID_NDK_HOME could not be determined."
        exit 1
    fi
fi

echo "Using Android NDK: $ANDROID_NDK_HOME"

WORK_DIR="$(pwd)/build/native_build"
mkdir -p "$WORK_DIR"
cd "$WORK_DIR"

ABI="arm64-v8a"
PLATFORM="android-26"
TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"

# Pinned dependencies. Changing any of these is a deliberate act: the prebuilt .so files, the header
# tree installed below, and the JNI code that includes them all have to agree.
#
# SentencePiece is a pre-release here (v0.2.3pre1). What it buys over 0.2.2:
#   - the deprecated API surface is gone, so merely including its header no longer emits ~two dozen
#     -Wdeprecated-declarations warnings no caller can fix (ct2_engine.h stopped suppressing them);
#   - tokenizer hardening from OSS-Fuzz: piece-id bounds checks, lattice bounds, empty-piece guards -
#     failures that scraped novel text can actually trigger;
#   - its public header now includes real "absl/..." paths instead of vendored third_party/absl
#     shims, which deletes the shim-installation dance this script used to need.
# What it costs: protobuf and abseil are no longer vendored inside its tree, so both are fetched and
# built for Android here, plus a host protoc (step 2a). SentencePiece Lite - the dependency-free
# FlatBuffers runtime that would remove protobuf entirely - stays off because it wants .spm.fb
# models and ours are classic .spm. Revisit at 0.2.3 GA.
SPM_TAG="v0.2.3pre1"
# Exactly the protobuf the Android build links: protoc stamps its version into the generated code,
# and a mismatch with the runtime aborts at load time instead of failing the build.
PROTOBUF_TAG="v36.2"
CT2_TAG="v4.8.4"

# 2. Host protoc
#
# 0.2.3 generates its model code from the .proto files during the build; the pre-generated copy is
# gone. A cross build therefore needs a protoc that runs on the build machine, which the NDK
# toolchain cannot provide. Build it from the same protobuf release the Android library links.
if [ ! -d protobuf ]; then
    # --recursive: protobuf's own build needs its utf8_range submodule, and this checkout is handed
    # to the Android configure below, so it has to be complete.
    git clone --depth 1 --recursive --branch "$PROTOBUF_TAG" https://github.com/protocolbuffers/protobuf.git protobuf
fi

echo "Building host protoc..."
cmake -S protobuf -B build_protoc \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_BUILD_TYPE=Release \
    -Dprotobuf_BUILD_TESTS=OFF \
    -Dprotobuf_BUILD_EXAMPLES=OFF \
    -Dprotobuf_BUILD_SHARED_LIBS=OFF
cmake --build build_protoc --target protoc -- -j"$(nproc)"

# Absolute, not relative: SentencePiece runs protoc from inside its own build directory, so a bare
# "build_protoc/..." resolves against the wrong working directory and the build stops at the first
# generated .pb.cc with "not found".
PROTOC_BIN=$(find "$WORK_DIR/build_protoc" -maxdepth 3 -type f -name 'protoc' -perm -u+x | head -n 1 || true)
if [ -z "$PROTOC_BIN" ]; then
    PROTOC_BIN=$(find "$WORK_DIR/build_protoc" -maxdepth 3 -type f -name 'protoc-*' -perm -u+x | head -n 1 || true)
fi
if [ -z "$PROTOC_BIN" ]; then
    echo "ERROR: the host protoc was not built, so SentencePiece cannot generate its model code."
    exit 1
fi
echo "Using host protoc: $PROTOC_BIN"

# 3. Clone & Build SentencePiece
if [ ! -d "sentencepiece" ]; then
    echo "Cloning SentencePiece..."
    git clone --depth 1 --branch "$SPM_TAG" https://github.com/google/sentencepiece.git sentencepiece
fi

echo "Building SentencePiece for $ABI..."
# protobuf and abseil are static and get linked into libsentencepiece.so, so their objects must be
# position independent. Android's toolchain does not default that on for static libraries.
cmake -S sentencepiece -B build_spm \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DSPM_ENABLE_SHARED=ON \
    -DSPM_ENABLE_TCMALLOC=OFF \
    -DSPM_ENABLE_LITE=OFF \
    -DSPM_PROTOC_EXECUTABLE="$PROTOC_BIN" \
    -DFETCHCONTENT_SOURCE_DIR_PROTOBUF="$WORK_DIR/protobuf" \
    -DSPM_BUILD_TEST=OFF \
    -DSPM_BUILD_EXAMPLES=OFF \
    -DSPM_BUILD_COMMANDS=OFF \
    -DCMAKE_INSTALL_PREFIX="$WORK_DIR/install_spm"

cmake --build build_spm --target sentencepiece -- -j$(nproc)
cmake --install build_spm || true
# If install failed due to missing train lib, try installing just what exists
if [ ! -f "$WORK_DIR/install_spm/lib/libsentencepiece.so" ]; then
    mkdir -p "$WORK_DIR/install_spm/lib" "$WORK_DIR/install_spm/include"
    cp -f build_spm/src/libsentencepiece.so "$WORK_DIR/install_spm/lib/" 2>/dev/null || true
    cp -f build_spm/libsentencepiece.so "$WORK_DIR/install_spm/lib/" 2>/dev/null || true
    cp -rf sentencepiece/src/*.h "$WORK_DIR/install_spm/include/" 2>/dev/null || true
fi

# 4. Clone & Build CTranslate2
if [ ! -d "ctranslate2" ]; then
    echo "Cloning CTranslate2..."
    git clone --depth 1 --recursive --branch "$CT2_TAG" https://github.com/OpenNMT/CTranslate2.git ctranslate2
fi

# Patch CMakeLists to be compatible with CMake 4.x if needed
if [ -f "ctranslate2/CMakeLists.txt" ]; then
    sed -i 's/cmake_minimum_required(VERSION [0-9\.]*)/cmake_minimum_required(VERSION 3.5)/' ctranslate2/CMakeLists.txt || true
fi
if [ -f "sentencepiece/CMakeLists.txt" ]; then
    sed -i 's/cmake_minimum_required(VERSION [0-9\.]*)/cmake_minimum_required(VERSION 3.5)/' sentencepiece/CMakeLists.txt || true
fi

echo "Building CTranslate2 for $ABI..."
cmake -S ctranslate2 -B build_ct2 \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_CLI=OFF \
    -DWITH_MKL=OFF \
    -DWITH_DNNL=OFF \
    -DWITH_OPENBLAS=OFF \
    -DWITH_RUY=ON \
    -DENABLE_CPU_DISPATCH=OFF \
    -DOPENMP_RUNTIME=COMP \
    -DCMAKE_INSTALL_PREFIX="$WORK_DIR/install_ct2"

cmake --build build_ct2 --target ctranslate2 -- -j$(nproc)
cmake --install build_ct2

# 5. Copy libraries and headers into Android project
PROJECT_ROOT="$(pwd)/../.."
DEST_JNI="$PROJECT_ROOT/apps/android/src/main/jniLibs/$ABI"
DEST_INC="$PROJECT_ROOT/apps/android/src/main/cpp/include"

mkdir -p "$DEST_JNI"
mkdir -p "$DEST_INC"

echo "Copying shared libraries to $DEST_JNI..."
cp -f "$WORK_DIR/install_ct2/lib/libctranslate2.so" "$DEST_JNI/"
cp -f "$WORK_DIR/install_spm/lib/libsentencepiece.so" "$DEST_JNI/"

# Find and copy libomp.so from NDK
OMP_SO=$(find "$ANDROID_NDK_HOME" -name "libomp.so" | grep "aarch64" | head -n 1)
if [ -n "$OMP_SO" ] && [ -f "$OMP_SO" ]; then
    echo "Found libomp.so at $OMP_SO"
    cp -f "$OMP_SO" "$DEST_JNI/"
else
    echo "Warning: libomp.so not found in NDK!"
fi

echo "Copying headers to $DEST_INC..."
cp -rf "$WORK_DIR/install_ct2/include/"* "$DEST_INC/" 2>/dev/null || true
if [ -d "$WORK_DIR/install_spm/include" ] && [ "$(ls -A $WORK_DIR/install_spm/include 2>/dev/null)" ]; then
    cp -rf "$WORK_DIR/install_spm/include/"* "$DEST_INC/" 2>/dev/null || true
else
    mkdir -p "$DEST_INC"
    # Copy from source
    cp -rf "$WORK_DIR/sentencepiece/src/sentencepiece_processor.h" "$DEST_INC/" 2>/dev/null || true
    cp -rf "$WORK_DIR/sentencepiece/src/sentencepiece_trainer.h" "$DEST_INC/" 2>/dev/null || true
    find "$WORK_DIR/sentencepiece/src" -name "*.h" -not -path "*/third_party/*" -exec cp -f {} "$DEST_INC/" \; 2>/dev/null || true
fi

# ---------------------------------------------------------------------------------------------
# Abseil headers.
#
# SentencePiece 0.2.3's public header includes "absl/status/status.h",
# "absl/strings/string_view.h" and "absl/types/span.h" at their real paths, so the JNI code needs a
# matching absl tree on the include path. abseil is linked statically into libsentencepiece.so and
# our code only uses header-defined types from it, so headers alone are enough - but they have to be
# the exact checkout SentencePiece fetched, or the two disagree about layout. Take that checkout and
# refuse to guess. Search build_spm only: the host protoc build fetches a *different* abseil, and
# picking that one would be the same header/library mismatch this block exists to prevent.
# ---------------------------------------------------------------------------------------------
ABSL_SRC=$(find -L "$WORK_DIR/build_spm" -maxdepth 6 -type d -path '*/abseil-cpp-src/absl' -print -quit 2>/dev/null || true)
if [ -z "$ABSL_SRC" ]; then
    ABSL_SRC=$(find -L "$WORK_DIR/build_spm" -maxdepth 6 -type d -name absl -path '*_deps*' -print -quit 2>/dev/null || true)
fi

if [ -n "$ABSL_SRC" ] && [ -f "$ABSL_SRC/status/status.h" ]; then
    echo "Installing abseil headers from $ABSL_SRC"
    mkdir -p "$DEST_INC/absl"
    cp -rf "$ABSL_SRC/." "$DEST_INC/absl/"
else
    echo "ERROR: could not find the abseil checkout SentencePiece was built with."
    echo "       Expected build_spm/_deps/abseil-cpp-src/absl under $WORK_DIR."
    echo "       Refusing to fall back to a pinned version: those headers must match the"
    echo "       prebuilt libsentencepiece.so, or the build succeeds and the app misbehaves."
    exit 1
fi

# ---------------------------------------------------------------------------------------------
# Prove the tree works before anything depends on it.
#
# A stale CI cache once restored a header set that could not satisfy sentencepiece_processor.h,
# and the failure only surfaced minutes later while building the APK. One syntax-only compile
# turns that into an immediate, obvious failure at the point the tree was created.
# ---------------------------------------------------------------------------------------------
echo "Verifying the installed header tree compiles..."
PREBUILT_DIR=$(ls -d "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/* 2>/dev/null | head -n 1)
cat > verify_headers.cpp <<'EOF'
#include <ctranslate2/translator.h>
#include <sentencepiece_processor.h>
int main() { return 0; }
EOF
"$PREBUILT_DIR/bin/clang++" \
    --target=aarch64-none-linux-android26 \
    --sysroot="$PREBUILT_DIR/sysroot" \
    -std=c++20 \
    -fsyntax-only \
    -I"$DEST_INC" \
    -I"$DEST_INC/third_party" \
    -I"$DEST_INC/third_party/absl" \
    verify_headers.cpp \
    || {
        echo "ERROR: the installed header tree does not compile (see the error above)."
        echo "       SentencePiece needs real abseil headers alongside its own."
        exit 1
    }
echo "Header tree verified."

echo "Native libraries and headers successfully installed for Android!"
ls -lh "$DEST_JNI"
