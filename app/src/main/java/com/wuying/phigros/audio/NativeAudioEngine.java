package com.wuying.phigros.audio;

import com.wuying.phigros.game.GameConstants;

public final class NativeAudioEngine {

    static {
        System.loadLibrary("audio_native");
    }

    private static volatile boolean sCreated = false;

    private NativeAudioEngine() {}

    public static void create() {
        if (sCreated) return;
        nativeCreate();
        sCreated = true;
    }

    public static void delete() {
        if (!sCreated) return;
        nativeDelete();
        sCreated = false;
    }

    public static void setMusicData(float[] pcm, int sampleRate, int channels) {
        if (!sCreated) return;
        if (pcm == null) {
            nativeSetMusicData(null, sampleRate, channels);
            return;
        }
        nativeSetMusicDataRange(pcm, pcm.length, sampleRate, channels);
    }

    public static void setMusicData(float[] pcm, int length, int sampleRate, int channels) {
        if (!sCreated) return;
        nativeSetMusicDataRange(pcm, length, sampleRate, channels);
    }

    public static void setSfxData(int noteType, float[] pcm, int sampleRate, int channels) {
        if (!sCreated) return;
        if (noteType == GameConstants.NOTE_HOLD) noteType = GameConstants.NOTE_TAP;
        nativeSetSfxData(noteType, pcm, sampleRate, channels);
    }

    public static void triggerSfx(int noteType) {
        if (!sCreated) return;
        if (noteType == GameConstants.NOTE_HOLD) noteType = GameConstants.NOTE_TAP;
        nativeTriggerSfx(noteType);
    }

    public static void setPlaybackSpeed(float speed) {
        if (!sCreated) return;
        nativeSetPlaybackSpeed(speed);
    }

    public static void setSfxVolume(float volume) {
        if (!sCreated) return;
        nativeSetSfxVolume(volume);
    }

    public static void setMusicVolume(float volume) {
        if (!sCreated) return;
        nativeSetMusicVolume(volume);
    }

    public static void prepareDefaultSfxIfMissing() {
        if (!sCreated) return;
        nativePrepareDefaultSfxIfMissing();
    }

    public static void start() {
        if (!sCreated) return;
        nativeStart();
    }

    public static void pause(boolean pause) {
        if (!sCreated) return;
        nativePause(pause);
    }

    public static void restart() {
        if (!sCreated) return;
        nativeRestart();
    }

    public static void stop() {
        if (!sCreated) return;
        nativeStop();
    }

    public static double getPlayheadSeconds() {
        if (!sCreated) return 0;
        return nativeGetPlayheadSeconds();
    }

    public static double getMusicDurationSeconds() {
        if (!sCreated) return 0;
        return nativeGetMusicDurationSeconds();
    }

    private static native void nativeCreate();
    private static native void nativeDelete();
    private static native void nativeSetMusicData(float[] pcm, int sampleRate, int channels);
    private static native void nativeSetMusicDataRange(float[] pcm, int length, int sampleRate, int channels);
    private static native void nativeSetSfxData(int noteType, float[] pcm, int sampleRate, int channels);
    private static native void nativePrepareDefaultSfxIfMissing();
    private static native void nativeTriggerSfx(int noteType);
    private static native void nativeSetPlaybackSpeed(float speed);
    private static native void nativeSetSfxVolume(float volume);
    private static native void nativeSetMusicVolume(float volume);
    private static native void nativeStart();
    private static native void nativePause(boolean pause);
    private static native void nativeRestart();
    private static native void nativeStop();
    private static native double nativeGetPlayheadSeconds();
    private static native double nativeGetMusicDurationSeconds();
}
