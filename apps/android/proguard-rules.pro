# ProGuard & R8 Optimization Rules for VBookMT

# 1. Native JNI Bridge
-keepclasseswithmembernames class * {
    native <methods>;
}
# JNI binds an `external fun` by its **fully-qualified class name**, so R8 renaming this class breaks
# every native call at runtime with UnsatisfiedLinkError. The rule named `Ct2Translator` until that
# class was deleted with the CTranslate2 engine; the tokenizer facade took its place and its name is
# part of the same contract (`Java_vn_dungxnd_vbookmt_service_NativeTokenizer_*`).
# `-keepclasseswithmembernames` above should already cover a class with native methods, but this is a
# release-only failure that a debug build cannot reveal, so it stays explicit.
-keep class vn.dungxnd.vbookmt.service.NativeTokenizer { *; }

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

# 5. ONNX Runtime
#
# The native half resolves Java classes **by name** — `libonnxruntime4j_jni.so` contains the literal
# strings `ai/onnxruntime/OrtException`, `ai/onnxruntime/TensorInfo`, `ai/onnxruntime/OnnxTensor`, and
# turns them into `FindClass` calls. R8 does not see across the JNI boundary, so without this rule it
# happily renames them.
#
# That is a release-only failure a debug build can never show, and it is not theoretical: the v1.2.0
# release mapping had
#     ai.onnxruntime.OrtException -> zk1:
#     ai.onnxruntime.OnnxJavaType -> ck1:
# and the released dex contained **zero** occurrences of `ai/onnxruntime/OrtException`.
#
# The symptom matches what a reader hit: ordinary translation works, because a happy path never throws
# — but the benchmark sweep reloads the session for each candidate thread count, and the first one that
# fails makes the native layer raise an `OrtException` that no longer exists. Tuning crashed; nothing
# else did.
#
# `-keep class ai.onnxruntime.** { *; }` is the broad form because the full set of names the JNI uses is
# an implementation detail of each ORT release — enumerating today's twelve would silently break on the
# next bump. ONNX Runtime is 30 MB of the APK and the app's only inference engine; the shrinker gains
# little here and the failure mode is a crash.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

