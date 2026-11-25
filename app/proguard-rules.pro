# ---- Heimdall release rules ----

# JNI: the native layer looks these up by exact name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.heimdall.engine.vlm.NativeVlmBridge { *; }

# Services and activities declared in the manifest are referenced reflectively by the platform.
-keep class com.heimdall.service.** { *; }
-keep class com.heimdall.ui.** { *; }
-keep class com.heimdall.HeimdallApplication { *; }

# Kotlin metadata for enums we serialise into diagnostics.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Coroutines internals that are safe to keep (avoids service-loader warnings in R8).
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Strip verbose logging in release; warnings/errors are kept for on-device diagnostics.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
