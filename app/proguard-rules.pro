# Proguard rules for iTANTRA
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* *;
}

# Keep line numbers so release crash logs stay readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx: the native library reads config fields and builds result objects by name via JNI,
# so its Kotlin classes must not be renamed or stripped.
-keep class com.k2fsa.sherpa.onnx.** { *; }
