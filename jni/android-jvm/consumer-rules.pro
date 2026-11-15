# The JNI entry points are resolved by name from libmpv_jni.so; keep the
# bridge object and its external methods reachable in release builds.
-keep class cn.enaium.mpv.Jni { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
