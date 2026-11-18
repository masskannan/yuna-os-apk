#include <jni.h>
#include <cstdint>
#include <cstring>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PuyalZeroCopy", __VA_ARGS__)

extern "C" JNIEXPORT jint JNICALL
Java_com_puyal_os_bridge_WasmHostBridge_nativeDispatchZeroCopy(
        JNIEnv* env, jobject, jobject direct_buffer, jint length) {
    if (direct_buffer == nullptr || length <= 0) return -1;
    auto* mem_ptr = static_cast<uint8_t*>(env->GetDirectBufferAddress(direct_buffer));
    if (mem_ptr == nullptr) return -2;
    LOGI("Zero-Copy FFI received %d bytes at %p", length, mem_ptr);
    mem_ptr[0] = 0xAA; // ACK byte written directly to shared memory
    return length;
}
