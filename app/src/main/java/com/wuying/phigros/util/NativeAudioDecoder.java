package com.wuying.phigros.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Fast native audio decoder using minimp3 (MP3) and stb_vorbis (OGG).
 * Falls back to {@link PcmDecoder} for unsupported formats.
 */
public final class NativeAudioDecoder {

    private NativeAudioDecoder() {}

    static {
        try {
            System.loadLibrary("audio_native");
        } catch (UnsatisfiedLinkError e) {
        }
    }

    @Nullable
    private static native float[] nativeDecodeFile(@NonNull String filePath, @NonNull int[] outMeta);

    @Nullable
    public static PcmDecoder.DecodedAudio decodeFile(@NonNull String filePath) {
        try {
            int[] meta = new int[2];
            float[] pcm = nativeDecodeFile(filePath, meta);
            if (pcm == null || pcm.length == 0) return null;
            int sampleRate = meta[0];
            int channels = meta[1];
            if (sampleRate <= 0 || channels <= 0) return null;
            return new PcmDecoder.DecodedAudio(pcm, pcm.length, sampleRate, channels);
        } catch (UnsatisfiedLinkError | Exception e) {
            return null;
        }
    }
}
