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

# 2. Clone & Build SentencePiece
if [ ! -d "sentencepiece" ]; then
    echo "Cloning SentencePiece..."
    git clone --depth 1 --branch v0.2.2 https://github.com/google/sentencepiece.git sentencepiece
fi

echo "Building SentencePiece for $ABI..."
cmake -S sentencepiece -B build_spm \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="$PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release \
    -DSPM_ENABLE_SHARED=ON \
    -DSPM_BUILD_SHARED_LIBS=ON \
    -DSPM_ENABLE_TCMALLOC=OFF \
    -DSPM_USE_BUILTIN_PROTOBUF=ON \
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
    cp -rf sentencepiece/third_party/absl "$WORK_DIR/install_spm/include/" 2>/dev/null || true
fi

# 3. Clone & Build CTranslate2
if [ ! -d "ctranslate2" ]; then
    echo "Cloning CTranslate2..."
    git clone --depth 1 --recursive --branch v4.8.4 https://github.com/OpenNMT/CTranslate2.git ctranslate2
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

# 4. Copy libraries and headers into Android project
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
# SentencePiece's absl shims.
#
# Its public header includes "third_party/absl/strings/string_view.h", "…/types/span.h" and
# "…/status/status.h". Those files are NOT in its git tree - they are materialised beside the absl
# checkout its own build used, which is why a from-source build works and an installed header tree
# does not. Find the directory wherever that build put it and install it under the spelling the
# header asks for. (The previous version of this script only looked in two hardcoded places, so a
# fresh clone installed no shims at all and the tree could never compile.)
# ---------------------------------------------------------------------------------------------
ABSL_SHIM=$(find -L "$WORK_DIR" -path "*/third_party/absl/strings/string_view.h" -print -quit 2>/dev/null || true)
if [ -n "$ABSL_SHIM" ]; then
    SHIM_ROOT="${ABSL_SHIM%/strings/string_view.h}"
    mkdir -p "$DEST_INC/third_party/absl"
    cp -rf "$SHIM_ROOT/." "$DEST_INC/third_party/absl/"
    echo "Installed SentencePiece's absl shims from $SHIM_ROOT"
else
    echo "WARNING: no third_party/absl shim directory found under $WORK_DIR."
fi
# ---------------------------------------------------------------------------------------------
# Abseil headers.
#
# SentencePiece links against abseil, and the shims installed above forward into the real tree.
# Take that tree from beside the shims - the very checkout that built the prebuilt .so - and refuse
# to guess a version. A pinned clone would be a header/ABI mismatch against a library we did not
# build with it, which is exactly the failure this section exists to prevent.
# ---------------------------------------------------------------------------------------------
ABSL_SRC=""
if [ -n "$ABSL_SHIM" ]; then
    ABSL_PARENT="${SHIM_ROOT%/absl}"
    for candidate in "$ABSL_PARENT/abseil-cpp" "$ABSL_PARENT/absl"; do
        if [ -d "$candidate/absl" ]; then
            ABSL_SRC="$candidate"
            break
        fi
    done
fi

if [ -n "$ABSL_SRC" ]; then
    echo "Installing abseil headers from $ABSL_SRC"
    mkdir -p "$DEST_INC/absl"
    cp -rf "$ABSL_SRC/absl/." "$DEST_INC/absl/"
elif [ -n "$ABSL_SHIM" ] && [ -d "$SHIM_ROOT/base" ]; then
    # Some SentencePiece builds symlink third_party/absl straight at abseil's own absl/ directory;
    # then the "shim" root already is the header root we need.
    echo "Installing abseil headers from $SHIM_ROOT (the shims are abseil itself)"
    mkdir -p "$DEST_INC/absl"
    cp -rf "$SHIM_ROOT/." "$DEST_INC/absl/"
else
    echo "ERROR: could not find the abseil checkout SentencePiece was built with."
    echo "       Expected third_party/abseil-cpp beside its absl shims, under $WORK_DIR."
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
