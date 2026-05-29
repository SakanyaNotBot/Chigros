#include <jni.h>

// NOTE:
// Some build variants / toolchains may enable aggressive section garbage collection.
// JNI methods which are only referenced from Java (via symbol lookup) can be stripped,
// resulting in UnsatisfiedLinkError: "No implementation found for ...".
//
// To make the binding robust, we register all methods explicitly in JNI_OnLoad.

extern "C" {
void audioEngine_create();
void audioEngine_destroy();
void audioEngine_setMusicData(const float *pcm, int length, int inRate, int inCh);
void audioEngine_setSfxData(int noteType, const float *pcm, int length, int inRate, int inCh);
void audioEngine_prepareDefaultSfxIfMissing();
void audioEngine_triggerSfx(int noteType);
void audioEngine_setPlaybackSpeed(float speed);
void audioEngine_setSfxVolume(float volume);
void audioEngine_setMusicVolume(float volume);
void audioEngine_setMusicLooping(bool looping);
void audioEngine_start();
void audioEngine_pause(bool pause);
void audioEngine_restart();
void audioEngine_stop();
double audioEngine_getPlayheadSeconds();
double audioEngine_getMusicDurationSeconds();
float *audioDecoder_decodeFile(const char *filePath, int *outNumSamples, int *outSampleRate, int *outChannels);
void audioDecoder_freePcm(float *pcm);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeCreate(JNIEnv *, jclass) {
    audioEngine_create();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeDelete(JNIEnv *, jclass) {
    audioEngine_destroy();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicData(JNIEnv *env, jclass,
                                                                        jfloatArray pcm,
                                                                        jint sampleRate,
                                                                        jint channels) {
    if (pcm == nullptr) {
        audioEngine_setMusicData(nullptr, 0, sampleRate, channels);
        return;
    }
    jsize len = env->GetArrayLength(pcm);
    if (len <= 0) {
        audioEngine_setMusicData(nullptr, 0, sampleRate, channels);
        return;
    }

    jboolean isCopy = JNI_FALSE;
    float *ptr = env->GetFloatArrayElements(pcm, &isCopy);
    audioEngine_setMusicData(ptr, (int) len, (int) sampleRate, (int) channels);
    env->ReleaseFloatArrayElements(pcm, ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicDataRange(JNIEnv *env, jclass,
                                                                            jfloatArray pcm,
                                                                            jint length,
                                                                            jint sampleRate,
                                                                            jint channels) {
    if (pcm == nullptr || length <= 0) {
        audioEngine_setMusicData(nullptr, 0, (int) sampleRate, (int) channels);
        return;
    }

    jsize arrLen = env->GetArrayLength(pcm);
    int useLen = (int) length;
    if (useLen > (int) arrLen) useLen = (int) arrLen;
    if (useLen <= 0) {
        audioEngine_setMusicData(nullptr, 0, (int) sampleRate, (int) channels);
        return;
    }

    jboolean isCopy = JNI_FALSE;
    float *ptr = env->GetFloatArrayElements(pcm, &isCopy);
    audioEngine_setMusicData(ptr, useLen, (int) sampleRate, (int) channels);
    env->ReleaseFloatArrayElements(pcm, ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetSfxData(JNIEnv *env, jclass,
                                                                      jint noteType,
                                                                      jfloatArray pcm,
                                                                      jint sampleRate,
                                                                      jint channels) {
    if (pcm == nullptr) {
        audioEngine_setSfxData((int) noteType, nullptr, 0, (int) sampleRate, (int) channels);
        return;
    }

    jsize len = env->GetArrayLength(pcm);
    if (len <= 0) {
        audioEngine_setSfxData((int) noteType, nullptr, 0, (int) sampleRate, (int) channels);
        return;
    }

    jboolean isCopy = JNI_FALSE;
    float *ptr = env->GetFloatArrayElements(pcm, &isCopy);
    audioEngine_setSfxData((int) noteType, ptr, (int) len, (int) sampleRate, (int) channels);
    env->ReleaseFloatArrayElements(pcm, ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativePrepareDefaultSfxIfMissing(JNIEnv *, jclass) {
    audioEngine_prepareDefaultSfxIfMissing();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeTriggerSfx(JNIEnv *, jclass, jint noteType) {
    audioEngine_triggerSfx((int) noteType);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetPlaybackSpeed(JNIEnv *, jclass, jfloat speed) {
    audioEngine_setPlaybackSpeed((float) speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetSfxVolume(JNIEnv *, jclass, jfloat volume) {
    audioEngine_setSfxVolume((float) volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicVolume(JNIEnv *, jclass, jfloat volume) {
    audioEngine_setMusicVolume((float) volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicLooping(JNIEnv *, jclass, jboolean looping) {
    audioEngine_setMusicLooping(looping == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeStart(JNIEnv *, jclass) {
    audioEngine_start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativePause(JNIEnv *, jclass, jboolean pause) {
    audioEngine_pause(pause == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeRestart(JNIEnv *, jclass) {
    audioEngine_restart();
}

extern "C" JNIEXPORT void JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeStop(JNIEnv *, jclass) {
    audioEngine_stop();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeGetPlayheadSeconds(JNIEnv *, jclass) {
    return (jdouble) audioEngine_getPlayheadSeconds();
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_wuying_phigros_audio_NativeAudioEngine_nativeGetMusicDurationSeconds(JNIEnv *, jclass) {
    return (jdouble) audioEngine_getMusicDurationSeconds();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_wuying_phigros_util_NativeAudioDecoder_nativeDecodeFile(JNIEnv *env, jclass,
                                                                  jstring filePath,
                                                                  jintArray outMeta) {
    if (filePath == nullptr || outMeta == nullptr) return nullptr;

    const char *path = env->GetStringUTFChars(filePath, nullptr);
    if (path == nullptr) return nullptr;

    int numSamples = 0, sampleRate = 0, channels = 0;
    float *pcm = audioDecoder_decodeFile(path, &numSamples, &sampleRate, &channels);
    env->ReleaseStringUTFChars(filePath, path);

    if (pcm == nullptr || numSamples <= 0) return nullptr;

    // Write metadata: outMeta[0] = sampleRate, outMeta[1] = channels
    jint meta[2] = {(jint) sampleRate, (jint) channels};
    env->SetIntArrayRegion(outMeta, 0, 2, meta);

    jfloatArray result = env->NewFloatArray((jsize) numSamples);
    if (result == nullptr) {
        audioDecoder_freePcm(pcm);
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, (jsize) numSamples, pcm);
    audioDecoder_freePcm(pcm);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/wuying/phigros/audio/NativeAudioEngine");
    if (cls == nullptr) {
        // Class not found - should not happen because the library is loaded from NativeAudioEngine
        env->ExceptionClear();
        return JNI_ERR;
    }

    // Register all native methods explicitly to avoid name-based lookup issues.
    static const JNINativeMethod kMethods[] = {
            {"nativeCreate", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeCreate},
            {"nativeDelete", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeDelete},
            {"nativeSetMusicData", "([FII)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicData},
            {"nativeSetMusicDataRange", "([FIII)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicDataRange},
            {"nativeSetSfxData", "(I[FII)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetSfxData},
            {"nativePrepareDefaultSfxIfMissing", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativePrepareDefaultSfxIfMissing},
            {"nativeTriggerSfx", "(I)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeTriggerSfx},
            {"nativeSetPlaybackSpeed", "(F)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetPlaybackSpeed},
            {"nativeSetSfxVolume", "(F)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetSfxVolume},
            {"nativeSetMusicVolume", "(F)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicVolume},
            {"nativeSetMusicLooping", "(Z)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeSetMusicLooping},
            {"nativeStart", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeStart},
            {"nativePause", "(Z)V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativePause},
            {"nativeRestart", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeRestart},
            {"nativeStop", "()V", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeStop},
            {"nativeGetPlayheadSeconds", "()D", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeGetPlayheadSeconds},
            {"nativeGetMusicDurationSeconds", "()D", (void *) Java_com_wuying_phigros_audio_NativeAudioEngine_nativeGetMusicDurationSeconds},
    };

    // Register NativeAudioDecoder methods (best-effort, may not exist in minimal builds).
    jclass decoderCls = env->FindClass("com/wuying/phigros/util/NativeAudioDecoder");
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (decoderCls != nullptr) {
        static const JNINativeMethod kDecoderMethods[] = {
                {"nativeDecodeFile", "(Ljava/lang/String;[I)[F", (void *) Java_com_wuying_phigros_util_NativeAudioDecoder_nativeDecodeFile},
        };
        env->RegisterNatives(decoderCls, kDecoderMethods, (jint) (sizeof(kDecoderMethods) / sizeof(kDecoderMethods[0])));
        env->DeleteLocalRef(decoderCls);
    }

    if (env->RegisterNatives(cls, kMethods, (jint) (sizeof(kMethods) / sizeof(kMethods[0]))) < 0) {
        env->ExceptionClear();
        return JNI_ERR;
    }

    env->DeleteLocalRef(cls);
    return JNI_VERSION_1_6;
}
