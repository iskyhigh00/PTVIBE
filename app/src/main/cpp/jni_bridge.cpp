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
Java_com_glidelive_audio_NativeEngine_nativeChoke(JNIEnv*, jobject, jint pad, jlong eventNs) {
    g_engine.choke(pad, eventNs);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSetParam(JNIEnv*, jobject, jint id, jfloat v) {
    g_engine.setParam(id, v);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSynthCmd(JNIEnv*, jobject, jint type, jint a, jint b,
                                                     jfloat f) {
    g_engine.synthCmd(type, a, b, f);
}

JNIEXPORT jfloat JNICALL
Java_com_glidelive_audio_NativeEngine_nativeGetLevel(JNIEnv*, jobject) {
    return g_engine.level();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSoloSet(JNIEnv*, jobject, jint idx, jfloat pitch,
                                                    jfloat filter, jfloat vib, jfloat vol) {
    g_engine.soloSet(idx, pitch, filter, vib, vol);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeKitBegin(JNIEnv*, jobject) { g_engine.kitBegin(); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeKitSet(JNIEnv* env, jobject, jint pad, jint layer,
                                                   jint variant, jfloatArray data, jint channels,
                                                   jint rate) {
    const jsize n = env->GetArrayLength(data);
    jfloat* p = env->GetFloatArrayElements(data, nullptr);
    g_engine.kitSet(pad, layer, variant, p, n / (channels > 0 ? channels : 1), channels, rate);
    env->ReleaseFloatArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeKitCommit(JNIEnv*, jobject) { g_engine.kitCommit(); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeKitUseSampled(JNIEnv*, jobject, jboolean sampled) {
    g_engine.kitUseSampled(sampled);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSetPattern(JNIEnv* env, jobject, jintArray ticks,
                                                       jintArray pads, jfloatArray vels, jint n,
                                                       jint length, jboolean restart,
                                                       jboolean oneShot) {
    jint* t = env->GetIntArrayElements(ticks, nullptr);
    jint* p = env->GetIntArrayElements(pads, nullptr);
    jfloat* v = env->GetFloatArrayElements(vels, nullptr);
    g_engine.setPattern(t, p, v, n, length, restart, oneShot);
    env->ReleaseIntArrayElements(ticks, t, JNI_ABORT);
    env->ReleaseIntArrayElements(pads, p, JNI_ABORT);
    env->ReleaseFloatArrayElements(vels, v, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSetEndPattern(JNIEnv* env, jobject, jintArray ticks,
                                                          jintArray pads, jfloatArray vels, jint n,
                                                          jint length) {
    jint* t = env->GetIntArrayElements(ticks, nullptr);
    jint* p = env->GetIntArrayElements(pads, nullptr);
    jfloat* v = env->GetFloatArrayElements(vels, nullptr);
    g_engine.setEndPattern(t, p, v, n, length);
    env->ReleaseIntArrayElements(ticks, t, JNI_ABORT);
    env->ReleaseIntArrayElements(pads, p, JNI_ABORT);
    env->ReleaseFloatArrayElements(vels, v, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSeqStatus(JNIEnv* env, jobject, jdoubleArray out) {
    double tmp[10] = {0};
    g_engine.getSeqStatus(tmp);
    if (env->GetArrayLength(out) >= 10) env->SetDoubleArrayRegion(out, 0, 10, tmp);
}

JNIEXPORT jint JNICALL
Java_com_glidelive_audio_NativeEngine_nativePollViz(JNIEnv* env, jobject, jdoubleArray out,
                                                    jint maxEvents) {
    const jsize cap = env->GetArrayLength(out) / 6;
    jdouble* p = env->GetDoubleArrayElements(out, nullptr);
    const int n = g_engine.pollViz(p, std::min<jsize>(cap, maxEvents));
    env->ReleaseDoubleArrayElements(out, p, 0);
    return n;
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSetRecording(JNIEnv*, jobject, jboolean on) {
    g_engine.setRecording(on);
}

JNIEXPORT jint JNICALL
Java_com_glidelive_audio_NativeEngine_nativeReadRecording(JNIEnv* env, jobject, jfloatArray dst) {
    const jsize cap = env->GetArrayLength(dst);
    jfloat* p = env->GetFloatArrayElements(dst, nullptr);
    const int n = g_engine.readRecording(p, cap);
    env->ReleaseFloatArrayElements(dst, p, 0);
    return n;
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
