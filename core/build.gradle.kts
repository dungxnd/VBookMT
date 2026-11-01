plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        // The ONNX Runtime adapter lives here so Android and Windows share one implementation: the
        // Java API is identical on both, only the native payload differs.
        //
        // `compileOnly`, not `implementation`, on purpose. The desktop jar bundles Windows/macOS/Linux
        // natives and the Android AAR bundles `.so` files — resolving one here would give the other
        // platform a library it cannot load. Each app declares the artifact it needs; this source set
        // only compiles against the API.
        jvmMain.dependencies {
            compileOnly("com.microsoft.onnxruntime:onnxruntime:1.31.1")
            val ktorVersion = "3.6.0"
            compileOnly("io.ktor:ktor-server-core:$ktorVersion")
            compileOnly("io.ktor:ktor-server-cio:$ktorVersion")
            compileOnly("io.ktor:ktor-server-cors:$ktorVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // `runTest` only: the gRPC dispatcher is suspend throughout, and testing it with a real
            // dispatcher would make every case depend on wall-clock timing for no benefit.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        jvmTest.dependencies {
            // Tests need a real ORT the JVM can load; `compileOnly` above does not put it on the
            // runtime classpath.
            implementation("com.microsoft.onnxruntime:onnxruntime:1.31.1")
        }
    }
}
