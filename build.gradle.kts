plugins {
    // Kotlin 2.4.21 (Latest Stable)
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false

    // Android Gradle Plugin 8.12.x
    id("com.android.application") version "8.12.0" apply false
    id("com.android.library") version "8.12.0" apply false
}

allprojects {
    group = "com.vbookmt"
    version = "1.0.0"
}
