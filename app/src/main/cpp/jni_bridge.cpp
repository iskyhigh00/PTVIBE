#include <jni.h>
#include "audio_engine.h"

namespace {
AudioEngine g_engine;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStart(JNIEnv*, jobject) {
    return g_engine.start();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStop(JNIEnv*, jobject) {
    g_engine.stop();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeTrigger(JNIEnv*, jobject, jint pad, jfloat vel,
                                                    jlong eventNs) {
    g_engine.trigger(pad, vel, eventNs);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSetBursts(JNIEnv*, jobject, jint bursts) {
    g_engine.setBursts(bursts);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeAutoTune(JNIEnv*, jobject) {
    g_engine.autoTune();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeResetStats(JNIEnv*, jobject) {
    g_engine.resetStats();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeGetStats(JNIEnv* env, jobject, jdoubleArray arr) {
    double tmp[AudioEngine::kStatCount];
    g_engine.getStats(tmp);
    if (env->GetArrayLength(arr) >= AudioEngine::kStatCount)
        env->SetDoubleArrayRegion(arr, 0, AudioEngine::kStatCount, tmp);
}

}  // extern "C"
