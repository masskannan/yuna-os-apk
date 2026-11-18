#include <jni.h>
#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PuyalAudioDSP", __VA_ARGS__)

static bool g_is_tts_speaking = false;
static float g_biometric_threshold = 0.85f;

float compute_cosine_similarity(const float* v1, const float* v2, int dim) {
    float dot = 0.0f, norm_a = 0.0f, norm_b = 0.0f;
    for (int i = 0; i < dim; ++i) {
        dot += v1[i] * v2[i];
        norm_a += v1[i] * v1[i];
        norm_b += v2[i] * v2[i];
    }
    if (norm_a <= 0.0f || norm_b <= 0.0f) return 0.0f;
    return dot / (std::sqrt(norm_a) * std::sqrt(norm_b));
}

extern "C" JNIEXPORT void JNICALL
Java_com_puyal_os_audio_PuyalAudioGuardian_nativeSetTtsSpeaking(JNIEnv*, jobject, jboolean speaking) {
    g_is_tts_speaking = (speaking == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_puyal_os_audio_PuyalAudioGuardian_nativeVerifyVoiceprint(
        JNIEnv* env, jobject, jfloatArray live_emb, jfloatArray admin_emb, jstring time_pin_fallback) {
    jsize len = env->GetArrayLength(live_emb);
    if (len != env->GetArrayLength(admin_emb) || len <= 0) return JNI_FALSE;

    jfloat* live_ptr = env->GetFloatArrayElements(live_emb, nullptr);
    jfloat* admin_ptr = env->GetFloatArrayElements(admin_emb, nullptr);

    float similarity = compute_cosine_similarity(live_ptr, admin_ptr, len);

    env->ReleaseFloatArrayElements(live_emb, live_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(admin_emb, admin_ptr, JNI_ABORT);

    if (similarity >= g_biometric_threshold) {
        LOGI("Voice Biometric Verified: %.2f%%", similarity * 100.0f);
        return JNI_TRUE;
    }

    if (time_pin_fallback != nullptr) {
        const char* pin_str = env->GetStringUTFChars(time_pin_fallback, nullptr);
        bool pin_valid = (std::strlen(pin_str) == 4);
        env->ReleaseStringUTFChars(time_pin_fallback, pin_str);
        if (pin_valid) return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_puyal_os_audio_PuyalAudioGuardian_nativeProcessPcmFrame(
        JNIEnv* env, jobject, jshortArray pcm_frame, jint sample_rate) {
    if (g_is_tts_speaking) return env->NewStringUTF("PAUSED_ECHO_GUARD");
    jsize num_samples = env->GetArrayLength(pcm_frame);
    jshort* samples = env->GetShortArrayElements(pcm_frame, nullptr);
    double energy = 0.0;
    for (int i = 0; i < num_samples; ++i) {
        energy += static_cast<double>(samples[i]) * samples[i];
    }
    double rms = (num_samples > 0) ? std::sqrt(energy / num_samples) : 0.0;
    env->ReleaseShortArrayElements(pcm_frame, samples, JNI_ABORT);
    return (rms > 650.0) ? env->NewStringUTF("VAD_SPEECH_DETECTED") : env->NewStringUTF("SILENCE");
}
