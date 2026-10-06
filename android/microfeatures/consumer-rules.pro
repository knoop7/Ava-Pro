# JNI entry points are registered dynamically in JNI_OnLoad (no Java_* exports).
-keep class com.example.microfeatures.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.example.microfeatures.** {
    native <methods>;
}
