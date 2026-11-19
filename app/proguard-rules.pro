-keep class com.puyal.os.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# YUNA Batch 2: Preserve Rust/Zig JNI & WebView @JavascriptInterface
-keep class com.puyal.os.nativebridge.** { *; }
-keepclassmembers class com.puyal.os.bridge.YunaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.puyal.os.cloud.** { *; }

# YUNA Batch 2: Preserve Rust/Zig JNI & WebView @JavascriptInterface
-keep class com.puyal.os.nativebridge.** { *; }
-keepclassmembers class com.puyal.os.bridge.YunaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.puyal.os.cloud.** { *; }

# YUNA Batch 2: Preserve Rust/Zig JNI & WebView @JavascriptInterface
-keep class com.puyal.os.nativebridge.** { *; }
-keepclassmembers class com.puyal.os.bridge.YunaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.puyal.os.cloud.** { *; }

# YUNA Batch 2: Preserve Rust/Zig JNI & WebView @JavascriptInterface
-keep class com.puyal.os.nativebridge.** { *; }
-keepclassmembers class com.puyal.os.bridge.YunaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.puyal.os.cloud.** { *; }

-keep class com.puyal.os.service.** { *; }

# YUNA 100% R8 Release Safety Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.codehaus.**
-dontwarn javax.annotation.**
