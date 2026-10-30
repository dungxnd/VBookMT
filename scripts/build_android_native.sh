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
# Copy absl headers required by SentencePiece
if [ -d "$WORK_DIR/sentencepiece/third_party/absl" ]; then
    mkdir -p "$DEST_INC/third_party"
    cp -rf "$WORK_DIR/sentencepiece/third_party/absl" "$DEST_INC/third_party/" 2>/dev/null || true
fi
if [ -d "$WORK_DIR/sentencepiece/src/third_party/absl" ]; then
    mkdir -p "$DEST_INC/third_party"
    cp -rf "$WORK_DIR/sentencepiece/src/third_party/absl" "$DEST_INC/third_party/" 2>/dev/null || true
fi

echo "Native libraries and headers successfully installed for Android!"
ls -lh "$DEST_JNI"
