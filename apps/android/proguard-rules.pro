# ProGuard & R8 Optimization Rules for VBookMT

# 1. Native JNI Bridge
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class vn.dungxnd.vbookmt.service.Ct2Translator { *; }

# 2. Kotlinx Serialization
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * extends kotlinx.serialization.internal.GeneratedSerializer {
    <fields>;
    <methods>;
}
-keepclasseswithmembers class * {
    public static *** Companion;
}
-keepclassmembers class * {
    public static *** serializer(...);
}

# 3. Ktor Server CIO & Coroutines
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn io.netty.**
-dontwarn org.slf4j.**

# 4. Compose Optimization Rules
-keep class androidx.compose.** { *; }
