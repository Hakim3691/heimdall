# JNI entry points are resolved by name from C++; never rename or strip them.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.heimdall.engine.vlm.NativeVlmBridge { *; }
