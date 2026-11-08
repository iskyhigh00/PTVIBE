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
Java_com_glidelive_audio_NativeEngine_nativeStemLoad(JNIEnv* env, jobject, jint idx, jshortArray data,
                                                     jint channels, jint rate) {
    const jsize n = env->GetArrayLength(data);
    jshort* p = env->GetShortArrayElements(data, nullptr);
    g_engine.stems().load(idx, p, n / (channels > 0 ? channels : 1), channels, rate);
    env->ReleaseShortArrayElements(data, p, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeReplicaSet(JNIEnv* env, jobject, jdoubleArray times, jfloatArray durs,
                                                       jfloatArray vels, jintArray notes, jintArray kinds) {
    const jsize n = env->GetArrayLength(times);
    jdouble* t = env->GetDoubleArrayElements(times, nullptr);
    jfloat* d = env->GetFloatArrayElements(durs, nullptr);
    jfloat* v = env->GetFloatArrayElements(vels, nullptr);
    jint* a = env->GetIntArrayElements(notes, nullptr);
    jint* k = env->GetIntArrayElements(kinds, nullptr);
    std::vector<Replica::Ev> ev(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i)
        ev[i] = {t[i], v[i], d[i], static_cast<int16_t>(a[i]), static_cast<int8_t>(k[i])};
    g_engine.replica().set(std::move(ev));
    env->ReleaseDoubleArrayElements(times, t, JNI_ABORT);
    env->ReleaseFloatArrayElements(durs, d, JNI_ABORT);
    env->ReleaseFloatArrayElements(vels, v, JNI_ABORT);
    env->ReleaseIntArrayElements(notes, a, JNI_ABORT);
    env->ReleaseIntArrayElements(kinds, k, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeReplicaMask(JNIEnv*, jobject, jint bits) { g_engine.replica().setMask(bits); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeReplicaGain(JNIEnv*, jobject, jfloat d, jfloat b, jfloat m) {
    g_engine.replica().setGain(d, b, m);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemClear(JNIEnv*, jobject, jint idx) {
    if (idx < 0) g_engine.stems().clearAll(); else g_engine.stems().clear(idx);
    g_engine.stems().freeRetired();
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemFree(JNIEnv*, jobject) { g_engine.stems().freeRetired(); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemTrack(JNIEnv*, jobject, jint idx, jfloat vol, jfloat pan,
                                                      jboolean mute, jboolean solo) {
    g_engine.stems().setTrack(idx, vol, pan, mute, solo);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemMaster(JNIEnv*, jobject, jfloat master, jfloat speed) {
    g_engine.stems().setMaster(master, speed);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemPlay(JNIEnv*, jobject, jboolean on) { g_engine.stems().play(on); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemSeek(JNIEnv*, jobject, jdouble sec) { g_engine.stems().seek(sec); }

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemLoop(JNIEnv*, jobject, jdouble a, jdouble b) {
    g_engine.stems().setLoop(a, b);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeStemStatus(JNIEnv* env, jobject, jdoubleArray out) {
    double t[3] = {g_engine.stems().position(), g_engine.stems().duration(), g_engine.stems().playing() ? 1.0 : 0.0};
    env->SetDoubleArrayRegion(out, 0, 3, t);
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
Java_com_glidelive_audio_NativeEngine_nativeKitCommit(JNIEnv*, jobject, jint slot) { g_engine.kitCommit(slot); }

JNIEXPORT jboolean JNICALL
Java_com_glidelive_audio_NativeEngine_nativeKitSelect(JNIEnv*, jobject, jint slot) {
    return g_engine.kitSelect(slot) ? JNI_TRUE : JNI_FALSE;
}

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
Java_com_glidelive_audio_NativeEngine_nativeSetQueuedPattern(JNIEnv* env, jobject, jintArray ticks,
                                                             jintArray pads, jfloatArray vels, jint n,
                                                             jint length) {
    jint* t = env->GetIntArrayElements(ticks, nullptr);
    jint* p = env->GetIntArrayElements(pads, nullptr);
    jfloat* v = env->GetFloatArrayElements(vels, nullptr);
    g_engine.setQueuedPattern(t, p, v, n, length);
    env->ReleaseIntArrayElements(ticks, t, JNI_ABORT);
    env->ReleaseIntArrayElements(pads, p, JNI_ABORT);
    env->ReleaseFloatArrayElements(vels, v, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_glidelive_audio_NativeEngine_nativeSeqStatus(JNIEnv* env, jobject, jdoubleArray out) {
    double tmp[11] = {0};
    g_engine.getSeqStatus(tmp);
    const jsize len = env->GetArrayLength(out);
    if (len >= 1) env->SetDoubleArrayRegion(out, 0, len < 11 ? len : 11, tmp);
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
