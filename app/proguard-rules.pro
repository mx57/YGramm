# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# The Go core calls these native declarations by exact JVM name. The default
# proguard-android-optimize.txt already keeps native method names, this is an
# explicit guard against a future default-file change.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# JNI callbacks: the Go core resolves these class/method names from C.
-keep class com.example.engine.YggNativeCoreBridge { *; }
-keep class com.example.engine.YggNativeCoreBridge$* { *; }

# Room entities are instantiated by generated code from the compiled schema.
-keep class com.example.data.** { *; }

# Kotlin metadata is needed by kotlinx.coroutines and Compose runtime lookups.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontwarn kotlinx.coroutines.**
