// GlideLive bridge over the MIT PocketTTS.cpp C API. Lifetime is owned by Kotlin.
#include <jni.h>
#include <string>
#include <vector>
#include <exception>
extern "C" {
void* ptt_create(const char*, const char*, const char*, const char*, float, int, int, int, int);
void ptt_destroy(void*);
void* ptt_stream_start(void*, const char*, const char*);
int ptt_stream_read(void*, float**, int*);
void ptt_stream_end(void*);
void ptt_free_audio(float*);
}
static std::string str(JNIEnv* env, jstring s) {
    const char* p = env->GetStringUTFChars(s, nullptr);
    if (!p) return {};
    std::string out(p); env->ReleaseStringUTFChars(s, p); return out;
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_glidelive_princess_NativePocketTts_nativeCreate(JNIEnv* env, jobject, jstring models, jstring voices) {
    const auto m = str(env, models), v = str(env, voices);
    if (env->ExceptionCheck()) return 0;
    // Absolute tokenizer path is essential in Android's app-private storage.
    return reinterpret_cast<jlong>(ptt_create(m.c_str(), v.c_str(), (m + "/tokenizer.model").c_str(),
                                            "fp32", 0.5f, 4, 2, 100, 50));
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_glidelive_princess_NativePocketTts_nativeSynthesize(JNIEnv* env, jobject, jlong handle,
                                                          jbyteArray text, jstring voice, jobject sink) {
    if (!handle) return JNI_FALSE;
    std::vector<char> utf8(env->GetArrayLength(text) + 1, 0);
    env->GetByteArrayRegion(text, 0, static_cast<jsize>(utf8.size()-1), reinterpret_cast<jbyte*>(utf8.data()));
    const auto v = str(env, voice);
    if (env->ExceptionCheck()) return JNI_FALSE;
    void* stream = nullptr;
    bool success = false;
    try {
        stream = ptt_stream_start(reinterpret_cast<void*>(handle), utf8.data(), v.c_str());
        if (!stream) return JNI_FALSE;
        jclass cls = env->GetObjectClass(sink);
        jmethodID onAudio = env->GetMethodID(cls, "onAudio", "([F)Z");
        env->DeleteLocalRef(cls);
        success = onAudio != nullptr;
        while (success) {
            float* samples = nullptr; int count = 0;
            const int state = ptt_stream_read(stream, &samples, &count);
            if (state == 0) break;
            if (state < 0 || count <= 0) { ptt_free_audio(samples); success = false; break; }
            jfloatArray chunk = env->NewFloatArray(count);
            if (!chunk) { ptt_free_audio(samples); success = false; break; }
            env->SetFloatArrayRegion(chunk, 0, count, samples);
            ptt_free_audio(samples);
            const jboolean accepted = env->CallBooleanMethod(sink, onAudio, chunk);
            env->DeleteLocalRef(chunk);
            if (env->ExceptionCheck() || !accepted) { success = false; break; }
        }
    } catch (const std::exception&) { success = false; }
    // End cancels and joins both native producer threads before freeing state.
    // No concurrent nativeStop pointer race: cancellation is signaled by the sink.
    if (stream) ptt_stream_end(stream);
    return success ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_glidelive_princess_NativePocketTts_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    if (handle) ptt_destroy(reinterpret_cast<void*>(handle));
}
