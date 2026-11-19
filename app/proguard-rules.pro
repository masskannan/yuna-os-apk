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
