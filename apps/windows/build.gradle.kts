plugins {
    kotlin("multiplatform")
}

kotlin {
    mingwX64("windows") {
        binaries {
            executable {
                entryPoint = "com.vbookmt.windows.main"
                baseName = "vbookmt-server"
            }
        }
    }

    sourceSets {
        val windowsMain by getting {
            dependencies {
                implementation(project(":core"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
    }
}
