plugins {
    // Version definitions applied across submodules
    id("org.jetbrains.kotlin.multiplatform") version "2.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}

allprojects {
    group = "com.vbookmt"
    version = "1.0.0"
}
