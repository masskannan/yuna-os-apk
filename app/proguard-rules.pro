-keep class com.puyal.os.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes *Annotation*,Signature,InnerClasses
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
