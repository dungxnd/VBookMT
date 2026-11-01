plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "vn.dungxnd.vbookmt"
    compileSdk = 37

    defaultConfig {
        applicationId = "vn.dungxnd.vbookmt"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.1"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    // AndroidX & Lifecycle (Latest)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.0")

    // Jetpack Compose (Latest BOM 2026.09.00)
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Ktor Server 3.6.0 (High-performance Coroutine Embedded HTTP Server)
    val ktorVersion = "3.6.0"
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-cio:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages:$ktorVersion")

    // Latest Kotlin Coroutines & Serialization for Kotlin 2.4.x
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")

    // ONNX Runtime. This AAR ships `libonnxruntime.so` + `libonnxruntime4j_jni.so` for every ABI;
    // `core`'s adapter compiles against the API via `compileOnly`, so this is the artifact that
    // actually provides natives at run time.
    //
    // **The plain build, not `-qnn`.** `onnxruntime-android-qnn` drags in
    // `com.qualcomm.qti:qnn-runtime` transitively — a 61 MB AAR that expands to **180 MB** of `.so`
    // files, which was 83% of the whole 218 MB APK. Most of it is `libQnnHtpPrepare.so` (79.8 MB)
    // plus a `QnnHtpV6x/V7x/V8xSkel.so` for every HTP generation, when a device uses exactly one.
    //
    // Dropping it costs nothing that was being used:
    //  - CPU is the default provider and measured *faster* than QNN GPU on a Snapdragon 8 Gen 3
    //    (755–1992 vs 358–1566 chars/s, unique text, cache off) — see OnnxProvider.
    //  - QNN HTP never worked here: the bundled SDK generation did not match the device DSP
    //    firmware (`QNN_DEVICE_ERROR_INVALID_CONFIG`).
    // To be precise about the trade-off: the Java API still exposes `addQnn` in this build, so the
    // enum entry still compiles, but the native library has no QnnHtp backend wiring, so a QNN
    // request fails as a provider error and `applyProvider` reports the fallback to CPU instead of
    // silently running somewhere else. That is the honest outcome — the NPU path was already dead.
    //
    // Reverting is one line if a future device/firmware pairing makes HTP worth testing: swap this
    // for `onnxruntime-android-qnn:<version>`.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
