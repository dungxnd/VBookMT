plugins {
    // Kotlin 2.4.21 (Latest)
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false

    // Compose Multiplatform
    id("org.jetbrains.compose") version "1.12.2" apply false

    // Android Gradle Plugin 9.5.0 (Latest for Gradle 9.x)
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
}

allprojects {
    group = "com.vbookmt"
    version = "1.2.2"
}
