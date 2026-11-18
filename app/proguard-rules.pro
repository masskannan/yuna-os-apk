-keep class com.puyal.os.** { *; }

# YUNA Native JNI & FlatBuffers Keep Rules (#78)
-keepclasseswithmembers class * {
    native <methods>;
}
-keep class com.puyal.os.audio.** { *; }
-keep class com.puyal.os.guard.** { *; }
