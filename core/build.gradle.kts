plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()
    androidTarget()
    
    // Kotlin/Native for Windows (mingwX64)
    mingwX64 {
        binaries {
            sharedLib {
                baseName = "vbookmt_core"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Latest kotlinx libraries for Kotlin 2.4.x
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
